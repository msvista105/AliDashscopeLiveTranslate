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

    /** 单行字幕：target 是服务端最新文本，shown 是已经打出来的部分。 */
    private class SubtitleRow(val binding: RowSubtitleBinding) {
        var targetSource = ""
        var targetTranslation = ""
        var shownSource = ""
        var shownTranslation = ""
        var speaker: String? = null
    }

    private val rows = ArrayList<SubtitleRow>()
    private var pendingSegments: List<TranslateManager.SegmentUi>? = null
    private var renderScheduled = false
    private var revealing = false
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
        // 一帧内的多次 delta 只渲染一次：服务端高频推字时避免重复重排造成抖动
        pendingSegments = segments
        if (renderScheduled) return
        renderScheduled = true
        binding.root.postOnAnimation {
            renderScheduled = false
            val latest = pendingSegments ?: return@postOnAnimation
            pendingSegments = null
            applySegments(latest)
        }
    }

    private fun applySegments(segments: List<TranslateManager.SegmentUi>) {
        if (segments.isEmpty()) {
            clearRows()
            return
        }
        binding.tvEmptyHint.visibility = View.GONE
        val liveIndex = segments.lastIndex
        for (index in segments.indices) {
            val segment = segments[index]
            val row = if (index < rows.size) {
                rows[index]
            } else {
                // 新建行（含旋转后重建）直接显示全文，不从空串重打一遍
                RowSubtitleBinding.inflate(layoutInflater, binding.subtitleList, true).let {
                    SubtitleRow(it).also { created ->
                        created.shownSource = segment.sourceText
                        created.shownTranslation = segment.translationText
                        rows.add(created)
                    }
                }
            }
            row.targetSource = segment.sourceText
            row.targetTranslation = segment.translationText
            if (index < liveIndex) {
                row.shownSource = segment.sourceText
                row.shownTranslation = segment.translationText
            }
            bindText(row)
            bindSpeaker(row, segment.speakerId, segments.size)
        }
        startReveal()
    }

    private fun bindText(row: SubtitleRow) {
        if (row.binding.tvSource.text.toString() != row.shownSource) {
            row.binding.tvSource.text = row.shownSource
        }
        if (row.binding.tvTranslation.text.toString() != row.shownTranslation) {
            row.binding.tvTranslation.text = row.shownTranslation
        }
    }

    private fun bindSpeaker(row: SubtitleRow, speaker: String?, segmentCount: Int) {
        if (speaker == row.speaker) return
        row.speaker = speaker
        if (speaker != null && segmentCount > 1) {
            row.binding.tvSpeaker.text = getString(R.string.speaker_format, speaker)
            row.binding.tvSpeaker.visibility = View.VISIBLE
        } else {
            row.binding.tvSpeaker.visibility = View.GONE
        }
    }

    private fun startReveal() {
        if (revealing) return
        revealing = true
        binding.root.postOnAnimation(revealTick)
    }

    private val revealTick = object : Runnable {
        override fun run() {
            val live = rows.lastOrNull()
            val behind = live != null && revealMore(live)
            // 只在用户本来就贴着底部时跟随滚动，手动上翻时不打断
            if (!binding.scroll.canScrollVertically(1)) {
                binding.scroll.fullScroll(View.FOCUS_DOWN)
            }
            revealing = behind
            if (behind) binding.root.postOnAnimation(this)
        }
    }

    /** @return 是否仍有未揭示的文本 */
    private fun revealMore(row: SubtitleRow): Boolean {
        row.shownSource = revealMore(row.binding.tvSource, row.shownSource, row.targetSource)
        row.shownTranslation =
            revealMore(row.binding.tvTranslation, row.shownTranslation, row.targetTranslation)
        return row.shownSource.length < row.targetSource.length ||
            row.shownTranslation.length < row.targetTranslation.length
    }

    private fun revealMore(view: android.widget.TextView, shown: String, target: String): String {
        val next = chase(shown, target)
        if (next != shown) view.text = next
        return next
    }

    /** 按积压量自适应追赶：攒得越多每帧吐得越快，尾部零头直接补齐。 */
    private fun chase(shown: String, target: String): String {
        if (shown == target) return shown
        if (!target.startsWith(shown)) return target
        val backlog = target.length - shown.length
        if (backlog <= REVEAL_TAIL_SNAP) return target
        return target.substring(0, shown.length + (backlog + 5) / 6)
    }

    private fun clearRows() {
        binding.root.removeCallbacks(revealTick)
        revealing = false
        pendingSegments = null
        if (binding.subtitleList.childCount > 1) {
            binding.subtitleList.removeViews(1, binding.subtitleList.childCount - 1)
        }
        rows.clear()
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

    private companion object {
        /** 剩余零头不超过这个字数就一次补齐，避免结尾拖尾 */
        const val REVEAL_TAIL_SNAP = 2
    }

    override fun onDestroy() {
        super.onDestroy()
        // 会话由前台 Service + Manager 单例持有，Activity 销毁不结束会话
    }
}
