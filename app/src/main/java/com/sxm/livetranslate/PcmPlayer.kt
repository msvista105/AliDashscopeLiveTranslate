package com.sxm.livetranslate

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue

/** 24kHz 单声道 16bit PCM 译文语音播放。写入端可从任意线程调用。 */
class PcmPlayer {

    companion object {
        const val SAMPLE_RATE = 24_000
        private val EOS = ByteArray(0)
    }

    private val queue = LinkedBlockingQueue<ByteArray>()
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (thread != null) return
        queue.clear()
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf * 2, 48_000))
                .build()
            track = t
            t.play()
            try {
                while (true) {
                    val chunk = queue.take()
                    if (chunk.size == 0) break
                    t.write(chunk, 0, chunk.size)
                }
            } catch (_: InterruptedException) {
            } finally {
                t.stop()
                t.release()
                track = null
            }
        }.apply { start() }
    }

    fun write(pcm: ByteArray) {
        if (thread != null && pcm.isNotEmpty()) {
            queue.add(pcm)
        }
    }

    fun stop() {
        queue.clear()
        queue.add(EOS)
        thread?.join(2000)
        thread = null
        queue.clear()
    }
}
