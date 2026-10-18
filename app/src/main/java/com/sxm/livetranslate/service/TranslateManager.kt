package com.sxm.livetranslate.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import com.sxm.livetranslate.AppSettings
import com.sxm.livetranslate.LiveTranslateClient
import com.sxm.livetranslate.MicRecorder
import com.sxm.livetranslate.PcmPlayer
import com.sxm.livetranslate.data.PcmFileWriter
import com.sxm.livetranslate.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单例会话核心：持有 WebSocket 客户端、麦克风、播放器、录音文件和数据库连接。
 * 生命周期独立于 Activity，由前台 TranslateService 保活；UI 只观察 [state]/[segments]。
 */
class TranslateManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: TranslateManager? = null

        private const val MAX_RECONNECT = 3
        private const val BASE_RECONNECT_DELAY_MS = 1000L

        fun get(context: Context): TranslateManager =
            instance ?: synchronized(this) {
                instance ?: TranslateManager(context.applicationContext).also { instance = it }
            }
    }

    sealed class UiState {
        data object Idle : UiState()
        data object Connecting : UiState()
        data class Active(val sessionId: Long, val targetLangName: String) : UiState()
        data class Reconnecting(val attempt: Int) : UiState()
        data object MicPaused : UiState()
        data object Finishing : UiState()
        data class Closed(val sessionId: Long?) : UiState()
        data class Error(val message: String) : UiState()
    }

    class SegmentUi(
        val speakerId: String?,
        val sourceText: String,
        val translationText: String,
    )

    private val appContext = context.applicationContext
    private val settings = AppSettings(appContext)
    val store = SessionStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    private val _segments = MutableStateFlow<List<SegmentUi>>(emptyList())
    val segments: StateFlow<List<SegmentUi>> = _segments

    @Volatile
    var currentSessionId: Long? = null
        private set

    private var client: LiveTranslateClient? = null
    private var mic: MicRecorder? = null
    private var player: PcmPlayer? = null
    private var micWriter: PcmFileWriter? = null
    private var translatedWriter: PcmFileWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var sessionStartMs: Long = 0L
    private var reconnectAttempts = 0

    /** CAS 守卫：初连异常与 Socket 失败回调可能并发触发，只允许一个重连流程。 */
    private val reconnecting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 重连前已产出的字幕；新 WebSocket 会话是独立上下文，需与后续字幕拼接展示。 */
    private val frozenSegments = ArrayList<SegmentUi>()

    /** 会话内新增语句的回调，供外部（如通知栏文案）监听。 */
    var onSegmentCommitted: ((Long) -> Unit)? = null

    val isRunning: Boolean
        get() = _state.value is UiState.Connecting ||
            _state.value is UiState.Active ||
            _state.value is UiState.Reconnecting ||
            _state.value is UiState.Finishing

    fun start() {
        if (isRunning) return
        if (!settings.isReady()) {
            _state.value = UiState.Error("请先在设置中填写 API Key 和业务空间 ID")
            return
        }
        _state.value = UiState.Connecting
        scope.launch { startInternal() }
    }

    fun stop() {
        val c = client
        reconnecting.set(false)
        if (c == null || c.state != LiveTranslateClient.State.ACTIVE) {
            forceClose()
            return
        }
        _state.value = UiState.Finishing
        scope.launch {
            mic?.stop()
            try {
                c.finish()
            } catch (_: Exception) {
                c.close()
            }
            endSession()
        }
    }

    fun forceClose() {
        reconnecting.set(false)
        client?.close()
        endSession()
    }

    private suspend fun startInternal() {
        acquireWakeLock()
        requestAudioFocus()
        val startTime = System.currentTimeMillis()
        sessionStartMs = startTime
        segmentRowIds.clear()
        committedTexts.clear()
        frozenSegments.clear()
        reconnectAttempts = 0
        reconnecting.set(false)

        val dir = File(appContext.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startTime))
        val micFile = File(dir, "${stamp}_mic.wav")
        val translatedFile = File(dir, "${stamp}_translated.wav")
        micWriter = PcmFileWriter.create(micFile, MicRecorder.SAMPLE_RATE)
        translatedWriter = PcmFileWriter.create(translatedFile, PcmPlayer.SAMPLE_RATE)
        currentSessionId = store.insertSession(
            startTime, settings.targetLanguage, micFile.absolutePath, translatedFile.absolutePath
        )

        player = PcmPlayer().also { it.start() }
        // 麦克风回调动态读取 client 字段，重连换客户端后无需重建采集线程
        mic = MicRecorder { chunk ->
            micWriter?.append(chunk)
            client?.sendAudio(chunk)
        }
        openClient(fatalOnFailure = true)
    }

    /** 建立 WebSocket 会话并开始采集；重连时复用同一 session 记录与录音文件。 */
    private suspend fun openClient(fatalOnFailure: Boolean) {
        val sessionId = currentSessionId
        if (sessionId == null) {
            handleError("会话状态异常")
            return
        }
        val c = LiveTranslateClient(
            onSegmentsChanged = { persistAndPublish() },
            onTranslationAudio = { pcm ->
                translatedWriter?.append(pcm)
                player?.write(pcm)
            },
            onError = { msg -> scope.launch { handleError(msg) } },
            onClosed = { },
            onTransportFailure = { msg -> scope.launch { handleTransportFailure(msg, fatalOnFailure) } },
        )
        client = c
        try {
            c.connect(
                apiKey = settings.apiKey,
                workspaceId = settings.workspaceId,
                useSingapore = settings.useSingapore,
                targetLanguage = settings.targetLanguage,
            )
        } catch (e: Exception) {
            if (fatalOnFailure) {
                handleError("连接失败: ${e.message}")
                return
            }
            // 释放守卫，让下一轮失败能重新进入重连流程（次数上限仍然兜底）
            reconnecting.set(false)
            handleTransportFailure(e.message ?: "未知错误", fatalOnFailure = false)
            return
        }
        reconnectAttempts = 0
        reconnecting.set(false)
        if (_state.value is UiState.MicPaused) return
        mic?.start()
        _state.value = UiState.Active(sessionId, settings.languageDisplayName())
    }

    /** 传输层断线：指数退避重连，最多 [MAX_RECONNECT] 次；服务端错误不进入此路径。 */
    private suspend fun handleTransportFailure(message: String, fatalOnFailure: Boolean) {
        if (fatalOnFailure || _state.value is UiState.Finishing || currentSessionId == null) {
            handleError(message)
            return
        }
        if (!reconnecting.compareAndSet(false, true)) return
        if (reconnectAttempts >= MAX_RECONNECT) {
            reconnecting.set(false)
            handleError("连接中断（已重试 $MAX_RECONNECT 次）: $message")
            return
        }
        freezeSegments()
        client?.close()
        client = null
        mic?.stop()
        reconnectAttempts++
        _state.value = UiState.Reconnecting(reconnectAttempts)
        delay(BASE_RECONNECT_DELAY_MS shl (reconnectAttempts - 1))
        if (!reconnecting.get()) return // 等待期间用户已手动停止
        openClient(fatalOnFailure = false)
    }

    private suspend fun handleError(message: String) {
        reconnecting.set(false)
        mic?.stop()
        client?.close()
        client = null
        endSession()
        _state.value = UiState.Error(message)
    }

    private fun freezeSegments() {
        val snapshot = client?.snapshot() ?: return
        for (seg in snapshot) {
            val ui = SegmentUi(seg.speakerId, seg.sourceText, seg.translationText)
            if (frozenSegments.none {
                    it.sourceText == ui.sourceText && it.translationText == ui.translationText
                }
            ) {
                frozenSegments.add(ui)
            }
        }
    }

    private fun endSession() {
        mic?.stop()
        mic = null
        player?.stop()
        player = null
        val sid = currentSessionId
        if (sid != null) {
            store.finishSession(sid, System.currentTimeMillis(), null)
        }
        micWriter?.close()
        translatedWriter?.close()
        micWriter = null
        translatedWriter = null
        segmentRowIds.clear()
        committedTexts.clear()
        frozenSegments.clear()
        abandonAudioFocus()
        releaseWakeLock()
        currentSessionId = null
        reconnectAttempts = 0
        if (_state.value !is UiState.Error) {
            _state.value = UiState.Closed(sid)
        }
    }

    /** 增量落库 + 发布到 UI。在 client 回调线程执行；SQLite 写入很快，直接同步写。 */
    private fun persistAndPublish() {
        val c = client ?: return
        val live = c.snapshot()
        for (seg in live) {
            val rowId = segmentRowIds[seg]
            if (rowId == null) {
                val sid = currentSessionId ?: return
                val id = store.insertSegment(
                    sid, System.currentTimeMillis() - sessionStartMs, seg.speakerId
                )
                segmentRowIds[seg] = id
                store.updateSegmentText(id, seg.sourceText, seg.translationText)
                committedTexts[id] = seg.sourceText to seg.translationText
                onSegmentCommitted?.invoke(id)
            } else {
                val committed = committedTexts[rowId]
                if (committed == null ||
                    committed.first != seg.sourceText || committed.second != seg.translationText
                ) {
                    store.updateSegmentText(rowId, seg.sourceText, seg.translationText)
                    committedTexts[rowId] = seg.sourceText to seg.translationText
                    onSegmentCommitted?.invoke(rowId)
                }
            }
        }
        val published = ArrayList<SegmentUi>(frozenSegments.size + live.size)
        published.addAll(frozenSegments)
        published.addAll(live.map { SegmentUi(it.speakerId, it.sourceText, it.translationText) })
        _segments.value = published
    }

    private val segmentRowIds = java.util.IdentityHashMap<LiveTranslateClient.Segment, Long>()

    // rowId -> (source, translation) 上次落库的文本
    private val committedTexts = HashMap<Long, Pair<String, String>>()

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveTranslate:session")
                .also { it.acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> pauseMic()

            AudioManager.AUDIOFOCUS_GAIN -> resumeMic()
        }
    }

    private fun pauseMic() {
        if (_state.value !is UiState.Active) return
        mic?.stop()
        _state.value = UiState.MicPaused
    }

    private fun resumeMic() {
        if (_state.value !is UiState.MicPaused) return
        val sessionId = currentSessionId ?: return
        if (client?.state != LiveTranslateClient.State.ACTIVE) return
        mic?.start()
        _state.value = UiState.Active(sessionId, settings.languageDisplayName())
    }

    fun shutdown() {
        forceClose()
        scope.cancel()
    }
}
