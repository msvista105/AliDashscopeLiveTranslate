# LiveTranslate

Android 实时语音翻译 App。手机麦克风拾音，通过阿里云百炼的 `qwen3.8-livetranslate-flash-realtime` 实时模型做端到端语音翻译，屏幕上滚动原文与译文双语字幕，并用模型合成的音色朗读译文。会话与字幕落 SQLite，音频双轨存 WAV，事后可回放和导出 SRT。

包名 `com.sxm.livetranslate`，Kotlin，无后端服务器 —— WebSocket 直连模型服务。

## 功能

- **实时双语字幕**：原文 + 译文逐条流式增长，自动贴底滚动
- **说话人分离**：按 `speaker_detection` 切分语句并标注「说话人 N」
- **译文语音**：24kHz PCM 下行即时播放，音色固定 `Tina`
- **15 种目标语言**：英/中/日/韩/法/德/西/俄/葡/阿/意/泰/越/印尼/印地
- **热词 / 术语表**：每行 `原文=译文`，随 `session.update` 下发给 `translation.corpus.phrases`，专有名词可固定译法
- **双语对话模式**：设定两种语言，翻译中点一下语言对即互换方向，录音与会话记录连续不断裂
- **双地域**：华北2（北京）与新加坡可切换
- **会话历史**：SQLite 存会话与语句，列表可查、可删（连带删音频）
- **回放与导出**：详情页播放原声/译音，播放时按句高亮，点任意字幕从该句位置开始播；导出 SRT 经 FileProvider 分享
- **双轨录音**：`<时间戳>_mic.wav`（16kHz）与 `<时间戳>_translated.wav`（24kHz）写在 `getExternalFilesDir()/recordings/`
- **时间轴**：`speech_started` / `speech_stopped` 事件带输入音频轨内的 `audio_start_ms` / `audio_end_ms`，直接用作原声轨起止；事件缺失时退回客户端到达时刻。译音按已写入字节数换算 24kHz 轨内偏移，因此字幕与两条 WAV 都能对齐
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
| 百炼 API Key | 必填。存 EncryptedSharedPreferences（Android Keystore 加密），不上传、不入仓库 |
| 业务空间 ID | 选填。留空则走公共域名 `dashscope.aliyuncs.com` |
| 地域 | 北京 / 新加坡，决定 WebSocket 域名 |
| 目标语言 | 译文语言 |
| 热词 / 术语表 | 选填。每行一条 `原文=译文`，只写原文表示按原样输出，`#` 开头为注释，最多 1000 条；在开始翻译前下发才生效 |
| 双语对话模式 | 开关 + 「对方语言」。开启后主界面语言对可点，一键把两种语言互换，两人各听自己懂的那种 |

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

数据表：`sessions(_id, start_time, end_time, source_lang, target_lang, mic_file, translated_file)` 与 `segments(_id, session_id, start_ms, end_ms, audio_start_ms, audio_end_ms, speaker_id, source_text, translation_text)`，外键级联删除。`start_ms/end_ms` 是原声轨时间，优先取服务端 `audio_start_ms` / `audio_end_ms`，事件缺失时退回客户端到达时刻；`audio_*` 是译音轨偏移，由下行 PCM 字节数换算。DB v1 → v2 用 `ALTER TABLE` 原地迁移，不丢历史数据。

## 协议要点

连接 `wss://{host}/api-ws/v1/realtime?model=qwen3.8-livetranslate-flash-realtime`，`Authorization: Bearer <key>`。时序是硬性的：等 `session.created` → 发 `session.update` → 等 `session.updated` → 才能开始灌音频；结束时**必须**发 `session.finish` 并等到 `session.finished` 再关连接，否则服务端会截断未完成的译文。

实测踩到的坑，改协议层前务必读 [LiveTranslateClient.kt](app/src/main/java/com/sxm/livetranslate/LiveTranslateClient.kt) 里的注释：

1. 带音频输出时不能省 `voice` 字段 —— 服务端默认音色 `Chelsie` 会被 `<400> InternalError.Algo.InvalidParameter` 拒绝
2. `enable_voice_clone` + `voice=default` 实测不产出任何转写、译文或音频，所以声音复刻功能已从 UI、设置项和协议层彻底移除，音色固定 `Tina`（目前唯一实测可用的）
3. 开始灌音频之后**不能再发第二次 `session.update`** —— 服务端直接以 `1007 Session update error: session already started or finished or failed.` 断开连接。因此双语对话换方向不能原地改语言，只能 `session.finish` 收尾后重开一条连接（`TranslateManager.switchDirection()`）
4. 收尾重开期间**不能再向旧连接灌音频**。`sendAudio` 现在只在 `state == ACTIVE` 时发送；早先的写法（只挡 CLOSED）让麦克风线程在 `session.finish` 之后继续上报，冲刷窗口里旧连接的回调和重连互相打架，整段会话会被误判成异常中断拆掉 —— 模拟器上点一次语言对就把会话打断，修好后同一条会话里连切三次不再掉线

2026-11-01 用本机 TTS 生成的 5.5 秒英文语音跑了一次真实联网会话（脚本在临时目录，不进仓库），实测结论：

- `translation.corpus.phrases` 被 `session.update` 接受且**生效**：喂入 "Mercury will ship the new SDK..."，热词表写 `Mercury=默丘里`，译文返回「默丘里,将在周四的合作伙伴评审会上发布新的SDK。」——默认译法是「水星」，说明术语表确实覆盖了模型选择
- `input_audio_buffer.speech_started` / `speech_stopped` **带服务端时间戳** `audio_start_ms: 0` / `audio_end_ms: 5440`（相对输入音频流，与麦克风 WAV 位置等价）。官方文档没写这两个字段，之前按「事件不带时间戳」实现的客户端墙钟估算已降级为兜底
- `response.audio.delta` 仍不带 `item_id`，译文音频与语句的关联继续依赖响应顺序；本次 16 个 delta 共 238080 字节 = 4960ms @24kHz，与整句朗读时长吻合，`TRANSLATED_BYTES_PER_MS = 48` 的假设成立

同日在模拟器上跑了双语对话换方向（模拟器麦克风是虚拟静音，只能验连接与存储）：一次会话内连点三次语言对，每次都回到「正在翻译，请说话」，`sessions` 里始终一条记录、`target_lang` 跟着最后一次方向变成 `zh`，原声 WAV 只有一个文件且贯穿整段（约 50s / 1606444 字节），停止后 `end_time` 正常写入。已显示字幕在切换后是否还在只能真机验，因为无声喂不出语句。

## 已知限制

- **时间轴的两个残留误差**。重叠说话时译音归属仍按响应顺序近似；断线重连后服务端的输入轨从 0 重新计数，用重连时麦克风已写字节数做基准接续，断线期间只落盘未上报的帧会让后续语句偏移出现小幅漂移。v1 老会话没有 `end_ms`，回退到按文本长度估算
- **音色不可选**。只有 `Tina` 经过实测，UI 不提供音色切换，避免塞进未验证的 ID
- **会话记录只存最后一个方向**。双语对话中换方向会改写该会话的 `target_lang`，语句表没有逐句语言字段，历史里看不出哪句译成了哪种语言
- **换方向时有音频空档**。冲刷上一句译文的那一小段（`session.finish` 到新连接就绪之间）麦克风仍在采集并写入原声 WAV，但不再上报给服务端，所以这一段没有字幕；录音时长不受影响，代价只是几个字的译文缺口
- **断线重连与音频焦点未经现场验证**。代码已实现且构建通过，但真机测试里没有实际触发过这两条路径
- 模拟器麦克风是虚拟静音，只能验 UI / Service / 存储 / 协议握手，语音效果必须真机

## 路线图

- **P0 结构化存储与历史** —— 完成，真机端到端验收通过（识别、翻译、字幕、落库、回放、导出 SRT 全链路）
- **P1 韧性与观感** —— 断线重连、音频焦点、WakeLock 已完成；字幕打字机效果与渲染节流、贴底滚动已完成，真机视觉验证待做
- **P2 特色功能** —— 热词/术语表（`translation.corpus.phrases`）已完成并联网验证生效；字幕与音频时间轴对齐回放已完成（模拟器用本地 WAV + SQLite 验证双轨高亮、按句跳转、SRT 真实时长），原声起止改用服务端 `audio_start_ms` / `audio_end_ms` 后真机验收通过；双语对话模式已完成（切方向走收尾重开，模拟器一次会话内连切三次验证通过：连接不断、会话仍一条、`target_lang` 跟随最后一次方向、原声录音连续；已显示字幕的连续性待真机验证）；待做：图片辅助翻译（`input_image_buffer.append`）
- **P3 发布准备** —— API Key 迁移 EncryptedSharedPreferences 已完成（旧明文设置首次启动自动迁移并清空）；待做：Release 签名 + ProGuard、MockWebServer 协议单测

## 安全

API Key 由用户在本机输入，存 `EncryptedSharedPreferences`（AES256_GCM，密钥在 Android Keystore），不写入仓库、不进日志。旧版明文设置会在首次启动时自动迁移并清空原文件。极端情况下 Keystore 不可用时回退到明文存储以保证 App 可用，此时 API 安全等级降级，需用户自行判断环境是否可信。若把 Key 贴到过聊天或截图里，请到百炼控制台轮换。
