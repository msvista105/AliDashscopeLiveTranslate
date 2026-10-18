package com.sxm.livetranslate

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/** 16kHz 单声道 16bit PCM 麦克风采集，每 100ms 回调一帧。 */
class MicRecorder(private val onChunk: (ByteArray) -> Unit) {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 3200 // 1600 samples = 100ms
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK_BYTES * 4),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                running = false
                record.release()
                return@Thread
            }
            record.startRecording()
            val buf = ByteArray(CHUNK_BYTES)
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    onChunk(if (n == buf.size) buf else buf.copyOf(n))
                }
            }
            record.stop()
            record.release()
        }.apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }
}
