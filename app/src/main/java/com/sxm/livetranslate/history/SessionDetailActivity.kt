package com.sxm.livetranslate.history

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
    }

    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var store: SessionStore
    private var sessionId: Long = -1
    private var player: MediaPlayer? = null

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
    }

    private fun renderSegments() {
        val segments = store.listSegments(sessionId)
        for (seg in segments) {
            val row = RowSubtitleBinding.inflate(
                LayoutInflater.from(this), binding.segmentList, false
            )
            row.tvSource.text = seg.sourceText
            row.tvTranslation.text = seg.translationText
            seg.speakerId?.let {
                row.tvSpeaker.text = getString(R.string.speaker_format, it)
                row.tvSpeaker.visibility = View.VISIBLE
            }
            binding.segmentList.addView(row.root)
        }
    }

    private fun setupPlayback(micFile: String?, translatedFile: String?) {
        binding.btnPlayMic.visibility =
            if (micFile != null && File(micFile).exists()) View.VISIBLE else View.GONE
        binding.btnPlayTranslated.visibility =
            if (translatedFile != null && File(translatedFile).exists()) View.VISIBLE else View.GONE

        binding.btnPlayMic.setOnClickListener { playOrStop(micFile, R.string.no_mic_audio) }
        binding.btnPlayTranslated.setOnClickListener {
            playOrStop(translatedFile, R.string.no_translated_audio)
        }
    }

    private fun playOrStop(path: String?, missingHint: Int) {
        player?.let {
            it.release()
            player = null
            binding.btnPlayTranslated.setText(R.string.play_translated)
            return
        }
        if (path == null || !File(path).exists()) {
            toast(getString(missingHint))
            return
        }
        player = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener {
                it.release()
                player = null
                binding.btnPlayTranslated.setText(R.string.play_translated)
            }
            prepare()
            start()
        }
        binding.btnPlayTranslated.setText(R.string.stop_playback)
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

    private fun stopPlayback() {
        player?.release()
        player = null
    }

    private fun toast(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }
}
