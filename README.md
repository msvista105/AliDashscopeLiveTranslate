# LiveTranslate

Android 实时语音翻译 App。手机麦克风拾音，通过阿里云百炼的 `qwen3.8-livetranslate-flash-realtime` 实时模型做端到端语音翻译，屏幕上滚动原文与译文双语字幕，并用模型合成的音色朗读译文。会话与字幕落 SQLite，音频双轨存 WAV，事后可回放和导出 SRT。

包名 `com.sxm.livetranslate`，Kotlin，无后端服务器 —— WebSocket 直连模型服务。

## 功能

- **实时双语字幕**：原文 + 译文逐条流式增长，自动贴底滚动
- **说话人分离**：按 `speaker_detection` 切分语句并标注「说话人 N」
- **译文语音**：24kHz PCM 下行即时播放，音色固定 `Tina`
- **15 种目标语言**：英/中/日/韩/法/德/西/俄/葡/阿/意/泰/越/印尼/印地
- **双地域**：华北2（北京）与新加坡可切换
- **会话历史**：SQLite 存会话与语句，列表可查、可删（连带删音频）
- **回放与导出**：详情页播放译文音频，导出 SRT 经 FileProvider 分享
- **双轨录音**：`<时间戳>_mic.wav`（16kHz）与 `<时间戳>_translated.wav`（24kHz）写在 `getExternalFilesDir()/recordings/`
- **抗抖动**：断线指数退避重连（1s/2s/4s，最多 3 次），重连复用同一会话与同一对 WAV
- **音频焦点**：失去焦点即真停采集，恢复后自动续采；`PARTIAL_WAKE_LOCK` 保证息屏不断流

## 构建

需要 JDK 17+ 与 Android SDK（platform 36）。Gradle 版本由 wrapper 钉死在 **8.13**，无需预装 Gradle。

```bash
./gradlew assembleDebug          # Windows / Git Bash: sh ./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`，装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

两处本机相关的配置，换环境时要改：

- `gradle.properties` 的 `org.gradle.java.home` 指向 Android Studio 自带 JBR 21；系统默认 JDK 18 编不过 AGP 8.11
- `local.properties` 的 `sdk.dir`（不入库，各机器自建）

`gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指向腾讯镜像并带 `distributionSha256Sum`，因为直连 `services.gradle.org` 在国内不稳定。依赖仓库同样以阿里云镜像优先，见 `settings.gradle.kts`。

工具链：AGP 8.11.1、Kotlin 2.1.20、compileSdk/targetSdk 36、minSdk 26、ViewBinding + XML 布局（未用 Compose）。

## 使用

首次打开点右上角齿轮进设置：

| 项 | 说明 |
|---|---|
| 百炼 API Key | 必填。只存本机 SharedPreferences，不上传、不入仓库 |
| 业务空间 ID | 选填。留空则走公共域名 `dashscope.aliyuncs.com` |
| 地域 | 北京 / 新加坡，决定 WebSocket 域名 |
| 目标语言 | 译文语言 |

填好后点「开始翻译」，前台服务通知会出现，息屏后仍在跑。再点一次结束会话。

## 架构

```
UI       MainActivity · history.HistoryActivity · history.SessionDetailActivity
状态     SessionViewModel（薄代理，不持有会话）
核心     service.TranslateManager  ← 进程内单例，会话真正的拥有者
服务     service.TranslateService  ← 前台 Service，仅生命周期容器
能力     LiveTranslateClient（协议） · MicRecorder · PcmPlayer
数据     data.SessionStore（SQLite） · data.PcmFileWriter（WAV） · AppSettings
```

分层的关键约定：

- **`LiveTranslateClient` 只做编解码**。拼 URL、发 `session.update` / `input_audio_buffer.append` / `session.finish`、把服务端事件解析成回调。它不知道数据库和 UI，也不参与重连决策，所以这层最稳定。
- **会话资源归 `TranslateManager`**。UI 只是 `StateFlow<UiState>` 的订阅者，Activity 旋转或关闭都不会掐断翻译；`SessionViewModel.stop()` 刻意不走 `viewModelScope`。
- **线程边界**：麦克风采集线程只调 `sendAudio`（断线后静默丢帧，不抛异常）；WebSocket 回调切主线程更新状态；磁盘写走单线程 executor。

数据表：`sessions(_id, start_time, end_time, source_lang, target_lang, mic_file, translated_file)` 与 `segments(_id, session_id, start_ms, speaker_id, source_text, translation_text)`，外键级联删除。

## 协议要点

连接 `wss://{host}/api-ws/v1/realtime?model=qwen3.8-livetranslate-flash-realtime`，`Authorization: Bearer <key>`。时序是硬性的：等 `session.created` → 发 `session.update` → 等 `session.updated` → 才能开始灌音频；结束时**必须**发 `session.finish` 并等到 `session.finished` 再关连接，否则服务端会截断未完成的译文。

两个实测踩到的坑，改协议层前务必读 [LiveTranslateClient.kt](app/src/main/java/com/sxm/livetranslate/LiveTranslateClient.kt) 里的注释：

1. 带音频输出时不能省 `voice` 字段 —— 服务端默认音色 `Chelsie` 会被 `<400> InternalError.Algo.InvalidParameter` 拒绝
2. `enable_voice_clone` + `voice=default` 实测不产出任何转写、译文或音频，所以声音复刻功能已从 UI、设置项和协议层彻底移除，音色固定 `Tina`（目前唯一实测可用的）

## 已知限制

- **字幕时间轴是估算的**。SRT 的结束时间由 `estimateDurationMs` 按文本长度推算，不是真实音频长度，拿去配视频会有累积偏差
- **音色不可选**。只有 `Tina` 经过实测，UI 不提供音色切换，避免塞进未验证的 ID
- **断线重连与音频焦点未经现场验证**。代码已实现且构建通过，但真机测试里没有实际触发过这两条路径
- 模拟器麦克风是虚拟静音，只能验 UI / Service / 存储 / 协议握手，语音效果必须真机

## 路线图

- **P0 结构化存储与历史** —— 完成，真机端到端验收通过（识别、翻译、字幕、落库、回放、导出 SRT 全链路）
- **P1 韧性与观感** —— 断线重连、音频焦点、WakeLock 已完成；字幕打字机效果与滚动防抖待做
- **P2 特色功能** —— 双语对话模式（切换翻译方向）、热词/术语表（`translation.corpus.phrases`）、图片辅助翻译（`input_image_buffer.append`）、字幕与音频时间轴对齐回放
- **P3 发布准备** —— Release 签名 + ProGuard、API Key 迁移 EncryptedSharedPreferences、MockWebServer 协议单测

## 安全

API Key 由用户在本机输入，仅存 SharedPreferences，不写入仓库、不进日志。当前明文存储是已知取舍，P3 会迁到 EncryptedSharedPreferences。若把 Key 贴到过聊天或截图里，请到百炼控制台轮换。
