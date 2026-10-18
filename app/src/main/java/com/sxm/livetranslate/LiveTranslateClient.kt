package com.sxm.livetranslate

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Qwen-LiveTranslate-Realtime WebSocket 客户端。
 * 协议：连接后等 session.created，发 session.update 等 session.updated，
 * 之后持续 input_audio_buffer.append（16k PCM base64），停止时发 session.finish 等 session.finished。
 */
class LiveTranslateClient(
    private val onSegmentsChanged: () -> Unit,
    private val onTranslationAudio: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onClosed: () -> Unit,
    private val onTransportFailure: (String) -> Unit = {},
) {
    enum class State { IDLE, CONNECTING, ACTIVE, FINISHING, CLOSED }

    class Segment {
        var speakerId: String? = null
        var sourceText: String = ""
        var translationText: String = ""
    }

    companion object {
        const val MODEL = "qwen3.8-livetranslate-flash-realtime"
        const val DEFAULT_VOICE = "Tina"
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** WorkspaceId 为空时回退到公共域名，让只有 API Key 的用户也能连。 */
        fun buildUrl(workspaceId: String, useSingapore: Boolean): String {
            val host = if (workspaceId.isBlank()) {
                if (useSingapore) "dashscope-intl.aliyuncs.com" else "dashscope.aliyuncs.com"
            } else {
                val region = if (useSingapore) "ap-southeast-1" else "cn-beijing"
                "$workspaceId.$region.maas.aliyuncs.com"
            }
            return "wss://$host/api-ws/v1/realtime?model=$MODEL"
        }
    }

    @Volatile
    var state: State = State.IDLE
        private set

    val segments = ArrayList<Segment>()

    private val lock = Any()
    private val segmentBySourceId = HashMap<String, Segment>()
    private val segmentByTranslationId = HashMap<String, Segment>()

    private val okHttp = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var sessionCreated = CompletableDeferred<Unit>()
    private var sessionUpdated = CompletableDeferred<Unit>()
    private var sessionFinished = CompletableDeferred<Unit>()
    private var failureMessage: String? = null
    private var closing = false

    suspend fun connect(
        apiKey: String,
        workspaceId: String,
        useSingapore: Boolean,
        targetLanguage: String,
    ) {
        state = State.CONNECTING
        closing = false
        segments.clear()
        segmentBySourceId.clear()
        segmentByTranslationId.clear()
        sessionCreated = CompletableDeferred()
        sessionUpdated = CompletableDeferred()
        sessionFinished = CompletableDeferred()
        failureMessage = null

        val url = buildUrl(workspaceId, useSingapore)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()

        withContext(Dispatchers.IO) {
            ws = okHttp.newWebSocket(request, listener)

            awaitStep(sessionCreated, "等待 session.created 超时")
            send(buildSessionUpdate(targetLanguage))
            awaitStep(sessionUpdated, "等待 session.updated 超时")
        }
        state = State.ACTIVE
    }

    private fun buildSessionUpdate(targetLanguage: String): String {
        val session = JSONObject()
        session.put("translation", JSONObject().put("language", targetLanguage))
        session.put("output_modalities", JSONArray().put("text").put("audio"))
        // 实测：不带 voice 时服务端默认音色 Chelsie 被拒（400）；voice=default 仅在复刻模式下存在，
        // 而复刻模式实测不产出任何转写/译文/音频，故固定用已验证可用的 Tina。
        session.put("voice", DEFAULT_VOICE)
        session.put(
            "audio",
            JSONObject().put(
                "input",
                JSONObject().put(
                    "turn_detection",
                    JSONObject().put("type", "speaker_detection").put("threshold", 0.5)
                )
            )
        )
        session.put("input_audio_format", "pcm")
        session.put("output_audio_format", "pcm")
        session.put("sample_rate", 16000)
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    fun sendAudio(pcm16k: ByteArray) {
        // 断线后麦克风线程仍在回调，静默丢弃而非抛异常
        if (state == State.CLOSED) return
        val payload = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(pcm16k, Base64.NO_WRAP))
        try {
            send(payload.toString())
        } catch (_: IllegalStateException) {
        }
    }

    /** 通知服务端音频输入结束，等待全部剩余结果下发后关闭连接。 */
    suspend fun finish() {
        if (state != State.ACTIVE) return
        state = State.FINISHING
        try {
            send("""{"type":"session.finish"}""")
        } catch (_: IllegalStateException) {
        }
        try {
            withTimeout(30_000) { sessionFinished.await() }
        } catch (e: TimeoutCancellationException) {
            // 结果可能丢失，但不再阻塞用户
        }
        close()
    }

    fun close() {
        closing = true
        try {
            ws?.close(1000, null)
        } catch (_: Exception) {
        }
        ws = null
        state = State.CLOSED
        onClosed()
    }

    /** UI 线程渲染用的线程安全快照。 */
    fun snapshot(): List<Segment> = synchronized(lock) { ArrayList(segments) }

    private fun send(json: String) {
        ws?.send(json) ?: throw IllegalStateException("WebSocket 未连接")
    }

    private suspend fun awaitStep(deferred: CompletableDeferred<Unit>, timeoutMsg: String) {
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw IllegalStateException(timeoutMsg)
        }
        failureMessage?.let {
            close()
            throw IllegalStateException(it)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closing) return
            failureMessage = "连接失败: ${t.message}"
            sessionCreated.complete(Unit)
            sessionUpdated.complete(Unit)
            sessionFinished.complete(Unit)
            state = State.CLOSED
            onTransportFailure(failureMessage!!)
            onClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sessionCreated.complete(Unit)
            sessionUpdated.complete(Unit)
            sessionFinished.complete(Unit)
            if (state != State.CLOSED) {
                state = State.CLOSED
                onClosed()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleEvent(JSONObject(text))
            } catch (e: Exception) {
                onError("解析服务端事件失败: ${e.message}")
            }
        }
    }

    private fun handleEvent(event: JSONObject) {
        val type = event.optString("type")
        if (type == "error" || event.has("code")) {
            val msg = event.optString("message", event.toString())
            failureMessage = "服务端错误: $msg"
            sessionFinished.complete(Unit)
            onError(failureMessage!!)
            return
        }

        when (type) {
            "session.created" -> sessionCreated.complete(Unit)

            "session.updated" -> sessionUpdated.complete(Unit)

            "session.finished" -> sessionFinished.complete(Unit)

            "input_audio_buffer.speech_started" -> {
                val sourceId = event.optString("item_id")
                if (sourceId.isNotEmpty()) {
                    val segment = synchronized(lock) {
                        segmentBySourceId.getOrPut(sourceId) {
                            Segment().also { segments.add(it) }
                        }
                    }
                    event.optString("speaker_id").takeIf { it.isNotEmpty() }?.let {
                        synchronized(lock) { segment.speakerId = it }
                    }
                }
                notifyChanged()
            }

            "conversation.item.input_audio_transcription.delta" -> {
                appendSource(event.optString("item_id"), event.optString("delta"))
            }

            "conversation.item.input_audio_transcription.completed" -> {
                appendSource(event.optString("item_id"), event.optString("transcript"), replace = true)
            }

            "conversation.item.created" -> {
                val item = event.optJSONObject("item") ?: return
                if (item.optString("role") != "assistant") return
                val translationId = item.optString("id")
                val sourceId = event.optString("previous_item_id")
                if (translationId.isEmpty() || sourceId.isEmpty()) return
                synchronized(lock) {
                    val source = segmentBySourceId[sourceId]
                    if (source != null) {
                        segmentByTranslationId[translationId] = source
                    }
                }
                notifyChanged()
            }

            "response.text.delta", "response.audio_transcript.delta" -> {
                val translationId = event.optString("item_id")
                val delta = event.optString("delta")
                if (translationId.isEmpty()) return
                synchronized(lock) {
                    val segment = segmentByTranslationId[translationId]
                        ?: Segment().also {
                            segmentByTranslationId[translationId] = it
                            segments.add(it)
                        }
                    segment.translationText += delta
                }
                notifyChanged()
            }

            "response.audio.delta" -> {
                val b64 = event.optString("delta")
                if (b64.isNotEmpty()) {
                    onTranslationAudio(Base64.decode(b64, Base64.NO_WRAP))
                }
            }

            "response.done" -> {
                val response = event.optJSONObject("response")
                if (response != null && response.optString("status") != "completed") {
                    onError("翻译响应异常: ${response.optString("status")}")
                }
            }
        }
    }

    private fun appendSource(sourceId: String, text: String, replace: Boolean = false) {
        if (sourceId.isEmpty()) return
        synchronized(lock) {
            val segment = segmentBySourceId.getOrPut(sourceId) {
                Segment().also { segments.add(it) }
            }
            segment.sourceText = if (replace) text else segment.sourceText + text
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        onSegmentsChanged()
    }
}
