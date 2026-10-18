package com.sxm.livetranslate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sxm.livetranslate.databinding.ActivityMainBinding
import com.sxm.livetranslate.databinding.DialogSettingsBinding
import com.sxm.livetranslate.databinding.RowSubtitleBinding
import com.sxm.livetranslate.history.HistoryActivity
import com.sxm.livetranslate.service.TranslateManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private val viewModel: SessionViewModel by viewModels()

    private val rowViews = ArrayList<RowSubtitleBinding>()
    private var pendingStartAfterPermission = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
            val notifGranted = result[Manifest.permission.POST_NOTIFICATIONS] != false
            if (micGranted) {
                if (pendingStartAfterPermission) {
                    pendingStartAfterPermission = false
                    viewModel.start()
                }
            } else {
                toast(getString(R.string.mic_permission_needed))
            }
            if (!notifGranted) toast(getString(R.string.notification_permission_hint))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        settings = AppSettings(this)
        setContentView(binding.root)

        binding.toolbar.inflateMenu(R.menu.main)
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_settings -> showSettings().let { true }
                R.id.action_history -> openHistory().let { true }
                else -> false
            }
        }

        binding.btnToggle.setOnClickListener {
            if (viewModel.state.value.let {
                    it is TranslateManager.UiState.Active ||
                        it is TranslateManager.UiState.Connecting ||
                        it is TranslateManager.UiState.Reconnecting ||
                        it is TranslateManager.UiState.MicPaused ||
                        it is TranslateManager.UiState.Finishing
                }
            ) viewModel.stop() else tryStart()
        }

        clearRows()
        updateLangPair()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { renderState(it) }
                }
                launch {
                    viewModel.segments.collect { renderSegments(it) }
                }
            }
        }
    }

    private fun tryStart() {
        if (!settings.isReady()) {
            showSettings()
            toast(getString(R.string.settings_incomplete))
            return
        }
        val needMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needMic || needNotif) {
            pendingStartAfterPermission = true
            val perms = buildList {
                if (needMic) add(Manifest.permission.RECORD_AUDIO)
                if (needNotif) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
            return
        }
        viewModel.start()
    }

    private fun renderState(state: TranslateManager.UiState) {
        binding.tvStatus.text = when (state) {
            is TranslateManager.UiState.Idle -> getString(R.string.status_idle)
            is TranslateManager.UiState.Connecting -> getString(R.string.status_connecting)
            is TranslateManager.UiState.Active -> getString(R.string.status_active)
            is TranslateManager.UiState.Reconnecting ->
                getString(R.string.status_reconnecting, state.attempt)
            is TranslateManager.UiState.MicPaused -> getString(R.string.status_mic_paused)
            is TranslateManager.UiState.Finishing -> getString(R.string.status_finishing)
            is TranslateManager.UiState.Closed -> getString(R.string.status_closed)
            is TranslateManager.UiState.Error -> state.message
        }
        val running = state is TranslateManager.UiState.Active ||
            state is TranslateManager.UiState.Connecting ||
            state is TranslateManager.UiState.Reconnecting ||
            state is TranslateManager.UiState.MicPaused ||
            state is TranslateManager.UiState.Finishing
        binding.btnToggle.text =
            getString(if (running) R.string.stop_translate else R.string.start_translate)
        binding.btnToggle.isEnabled = state !is TranslateManager.UiState.Connecting &&
            state !is TranslateManager.UiState.Finishing
        if (state is TranslateManager.UiState.Error && state.message.isNotBlank()) {
            showSessionError(state.message)
        }
    }

    private fun renderSegments(segments: List<TranslateManager.SegmentUi>) {
        if (segments.isEmpty()) return
        binding.tvEmptyHint.visibility = View.GONE
        val atBottom = !binding.scroll.canScrollVertically(1)
        for (index in segments.indices) {
            val row = if (index < rowViews.size) {
                rowViews[index]
            } else {
                RowSubtitleBinding.inflate(layoutInflater, binding.subtitleList, true)
                    .also { rowViews.add(it) }
            }
            val segment = segments[index]
            row.tvSource.text = segment.sourceText
            row.tvTranslation.text = segment.translationText
            val speaker = segment.speakerId
            if (speaker != null && segments.size > 1) {
                row.tvSpeaker.text = getString(R.string.speaker_format, speaker)
                row.tvSpeaker.visibility = View.VISIBLE
            }
        }
        if (atBottom) {
            binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearRows() {
        if (binding.subtitleList.childCount > 1) {
            binding.subtitleList.removeViews(1, binding.subtitleList.childCount - 1)
        }
        rowViews.clear()
        binding.tvEmptyHint.visibility = View.VISIBLE
    }

    private fun updateLangPair() {
        binding.tvLangPair.text =
            "${getString(R.string.source_lang_label)} → ${settings.languageDisplayName()}"
    }

    private fun openHistory() {
        if (viewModel.state.value is TranslateManager.UiState.Active) {
            toast(getString(R.string.session_active_history_hint))
        }
        startActivity(
            android.content.Intent(this, HistoryActivity::class.java)
        )
    }

    private fun showSettings() {
        val dialog = DialogSettingsBinding.inflate(layoutInflater)
        dialog.etApiKey.setText(settings.apiKey)
        dialog.etWorkspaceId.setText(settings.workspaceId)
        dialog.toggleRegion.check(
            if (settings.useSingapore) R.id.btnRegionSingapore else R.id.btnRegionBeijing
        )

        val names = AppSettings.LANGUAGES.map { it.displayName }.toTypedArray()
        dialog.actLanguage.setSimpleItems(names)
        var selectedLang = AppSettings.LANGUAGES.firstOrNull { it.code == settings.targetLanguage }
            ?: AppSettings.LANGUAGES.first()
        dialog.actLanguage.setText(selectedLang.displayName, false)
        dialog.actLanguage.setOnItemClickListener { _, _, position, _ ->
            selectedLang = AppSettings.LANGUAGES[position]
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(dialog.root)
            .setPositiveButton(R.string.save) { _, _ ->
                settings.apiKey = dialog.etApiKey.text.toString().trim()
                settings.workspaceId = dialog.etWorkspaceId.text.toString().trim()
                settings.useSingapore =
                    dialog.toggleRegion.checkedButtonId == R.id.btnRegionSingapore
                settings.targetLanguage = selectedLang.code
                updateLangPair()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSessionError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun toast(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 会话由前台 Service + Manager 单例持有，Activity 销毁不结束会话
    }
}
