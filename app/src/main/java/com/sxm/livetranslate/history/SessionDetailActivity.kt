package com.sxm.livetranslate.history

import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sxm.livetranslate.R
import com.sxm.livetranslate.data.SessionStore
import com.sxm.livetranslate.databinding.ActivitySessionDetailBinding
import com.sxm.livetranslate.databinding.RowSubtitleBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        private const val HIGHLIGHT_TICK_MS = 200L
    }

    private enum class Track { MIC, TRANSLATED }

    private class Row(
        val binding: RowSubtitleBinding,
        val seg: SessionStore.SegmentRow,
        val micEndMs: Long,
    )

    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var store: SessionStore
    private var sessionId: Long = -1
    private var player: MediaPlayer? = null
    private var playingTrack: Track? = null
    private var micFile: String? = null
    private var translatedFile: String? = null
    private val rows = ArrayList<Row>()
    private var activeRow: Row? = null
    private val handler = Handler(Looper.getMainLooper())

    private val highlightColor by lazy {
        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondaryContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SessionStore(this)
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1)
        if (sessionId <= 0) {
            finish()
            return
        }

        val session = store.getSession(sessionId) ?: run { finish(); return }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        binding.toolbar.title = fmt.format(Date(session.startTime))
        binding.toolbar.setNavigationIcon(android.R.drawable.ic_media_previous)
        binding.toolbar.setNavigationOnClickListener { finish() }

        renderSegments()
        setupPlayback(session.micFile, session.translatedFile)
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnExportSrt.setOnClickListener { exportSrt() }
        updatePlayButtons()
    }

    private fun renderSegments() {
        val segments = store.listSegments(sessionId)
        rows.clear()
        binding.segmentList.removeAllViews()
        for ((index, seg) in segments.withIndex()) {
            val rowBinding = RowSubtitleBinding.inflate(
                LayoutInflater.from(this), binding.segmentList, false
            )
            rowBinding.tvSource.text = seg.sourceText
            rowBinding.tvTranslation.text = seg.translationText
            seg.speakerId?.let {
                rowBinding.tvSpeaker.text = getString(R.string.speaker_format, it)
                rowBinding.tvSpeaker.visibility = View.VISIBLE
            }
            val end = seg.endMs
            rowBinding.tvTime.text = if (end != null && end > seg.startMs) {
                "${formatClock(seg.startMs)} – ${formatClock(end)}"
            } else {
                formatClock(seg.startMs)
            }

            val nextStart = segments.getOrNull(index + 1)?.startMs
            val row = Row(
                rowBinding,
                seg,
                seg.endMs ?: nextStart?.minus(1) ?: (seg.startMs + 3000),
            )
            rowBinding.root.setOnClickListener { playRow(row) }
            rows.add(row)
            binding.segmentList.addView(rowBinding.root)
        }
    }

    private fun setupPlayback(micPath: String?, translatedPath: String?) {
        micFile = micPath
        translatedFile = translatedPath
        val micExists = micPath != null && File(micPath).exists()
        val translatedExists = translatedPath != null && File(translatedPath).exists()
        binding.btnPlayMic.visibility = if (micExists) View.VISIBLE else View.GONE
        binding.btnPlayTranslated.visibility = if (translatedExists) View.VISIBLE else View.GONE

        binding.btnPlayMic.setOnClickListener {
            if (playingTrack == Track.MIC) stopPlayback()
            else startTrack(Track.MIC, micPath, 0L, R.string.no_mic_audio)
        }
        binding.btnPlayTranslated.setOnClickListener {
            if (playingTrack == Track.TRANSLATED) stopPlayback()
            else startTrack(Track.TRANSLATED, translatedPath, 0L, R.string.no_translated_audio)
        }
    }

    /** 点某条字幕：优先从译文音频的对应位置开始播，译文缺失时退回原声。 */
    private fun playRow(row: Row) {
        val transStart = row.seg.audioStartMs
        if (transStart != null && translatedFile?.let { File(it).exists() } == true) {
            startTrack(Track.TRANSLATED, translatedFile, transStart, R.string.no_translated_audio)
            return
        }
        if (micFile?.let { File(it).exists() } == true) {
            startTrack(Track.MIC, micFile, row.seg.startMs, R.string.no_mic_audio)
            return
        }
        toast(getString(R.string.no_translated_audio))
    }

    private fun startTrack(track: Track, path: String?, offsetMs: Long, missingHint: Int) {
        if (path == null || !File(path).exists()) {
            toast(getString(missingHint))
            return
        }
        stopPlayback()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.setOnPreparedListener { prepared ->
                if (offsetMs > 0) prepared.seekTo(offsetMs, MediaPlayer.SEEK_CLOSEST)
                prepared.start()
            }
            mp.setOnCompletionListener { stopPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            mp.release()
            toast(getString(missingHint))
            return
        }
        player = mp
        playingTrack = track
        updatePlayButtons()
        handler.post(highlightTick)
    }

    private fun stopPlayback() {
        handler.removeCallbacks(highlightTick)
        player?.let {
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        player = null
        playingTrack = null
        setActiveRow(null)
        updatePlayButtons()
    }

    private fun updatePlayButtons() {
        binding.btnPlayMic.setText(
            if (playingTrack == Track.MIC) R.string.stop_playback else R.string.play_mic
        )
        binding.btnPlayTranslated.setText(
            if (playingTrack == Track.TRANSLATED) R.string.stop_playback else R.string.play_translated
        )
    }

    private val highlightTick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            val track = playingTrack ?: return
            val position = try {
                mp.currentPosition.toLong()
            } catch (_: Exception) {
                0L
            }
            setActiveRow(findRowAt(track, position))
            handler.postDelayed(this, HIGHLIGHT_TICK_MS)
        }
    }

    private fun findRowAt(track: Track, positionMs: Long): Row? {
        var lastBefore: Row? = null
        for (row in rows) {
            val start = if (track == Track.TRANSLATED) {
                row.seg.audioStartMs ?: continue
            } else {
                row.seg.startMs
            }
            val end = if (track == Track.TRANSLATED) {
                row.seg.audioEndMs ?: continue
            } else {
                row.micEndMs
            }
            if (positionMs in start..end) return row
            if (positionMs > end) lastBefore = row
        }
        return lastBefore
    }

    private fun setActiveRow(row: Row?) {
        if (row === activeRow) return
        activeRow?.binding?.root?.setBackgroundColor(Color.TRANSPARENT)
        activeRow = row
        if (row != null) {
            row.binding.root.setBackgroundColor(highlightColor)
            ensureVisible(row.binding.root)
        }
    }

    private fun ensureVisible(view: View) {
        val top = view.top - binding.scroll.scrollY
        if (top < 0 || top + view.height > binding.scroll.height) {
            binding.scroll.smoothScrollTo(0, maxOf(0, view.top - 24))
        }
    }

    private fun formatClock(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = totalSeconds / 60 % 60
        val s = totalSeconds % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }

    private fun exportSrt() {
        val session = store.getSession(sessionId) ?: return
        val dir = File(session.translatedFile ?: session.micFile ?: "").parentFile
            ?: getExternalFilesDir(null)
        val outFile = File(dir, "session_$sessionId.srt")
        outFile.writeText(store.exportSrt(sessionId))
        toast(getString(R.string.srt_exported_to) + ": ${outFile.name}")
        shareFile(outFile)
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    file.name
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                stopPlayback()
                store.deleteSession(sessionId)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }
}
