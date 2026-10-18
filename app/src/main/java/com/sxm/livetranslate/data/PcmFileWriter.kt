package com.sxm.livetranslate.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * 线程安全的 WAV 追加写入器。流式写 PCM 数据，close 时回填 RIFF 长度头。
 * 用单线程 executor 串行化磁盘写入，避免阻塞音频/网络线程。
 */
class PcmFileWriter private constructor(
    private val file: File,
    private val sampleRate: Int,
) {

    companion object {
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val HEADER_SIZE = 44L
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pcm-file-writer").apply { isDaemon = true }
        }

        fun create(file: File, sampleRate: Int): PcmFileWriter = PcmFileWriter(file, sampleRate)
    }

    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var totalBytes: Long = 0
    private var closed = false

    init {
        out.write(buildHeader(0))
        out.flush()
    }

    fun append(pcm: ByteArray) {
        if (closed || pcm.isEmpty()) return
        totalBytes += pcm.size
        try {
            executor.execute {
                try {
                    out.write(pcm)
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val finalBytes = totalBytes
        try {
            executor.execute {
                try {
                    out.flush()
                    out.close()
                    patchHeader(finalBytes)
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** executor 排队任务的兜底 flush，用于进程可能退出的场景。 */
    fun flushNow() {
        try {
            executor.submit { out.flush() }.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    private fun patchHeader(dataLen: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intToBytes(36 + dataLen))
            raf.seek(40)
            raf.write(intToBytes(dataLen))
        }
    }

    private fun buildHeader(dataLen: Long): ByteArray {
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        return ByteArray(HEADER_SIZE.toInt()).also { h ->
            fun ascii(pos: Int, s: String) {
                for (i in s.indices) h[pos + i] = s[i].code.toByte()
            }
            fun leInt(pos: Int, v: Long) {
                for (i in 0 until 4) h[pos + i] = (v shr (8 * i) and 0xff).toByte()
            }
            fun leShort(pos: Int, v: Int) {
                for (i in 0 until 2) h[pos + i] = (v shr (8 * i) and 0xff).toByte()
            }
            ascii(0, "RIFF")
            leInt(4, 36 + dataLen)
            ascii(8, "WAVE")
            ascii(12, "fmt ")
            leInt(16, 16)
            leShort(20, 1)
            leShort(22, CHANNELS)
            leInt(24, sampleRate.toLong())
            leInt(28, byteRate.toLong())
            leShort(32, blockAlign)
            leShort(34, BITS_PER_SAMPLE)
            ascii(36, "data")
            leInt(40, dataLen)
        }
    }

    private fun intToBytes(v: Long): ByteArray = ByteArray(4) { i ->
        (v shr (8 * i) and 0xff).toByte()
    }
}
