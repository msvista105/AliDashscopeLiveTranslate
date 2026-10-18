package com.sxm.livetranslate.history

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.sxm.livetranslate.R
import com.sxm.livetranslate.data.SessionStore
import com.sxm.livetranslate.databinding.ActivityHistoryBinding
import com.sxm.livetranslate.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: SessionStore
    private val adapter = SessionAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SessionStore(this)
        binding.toolbar.setNavigationIcon(android.R.drawable.ic_media_previous)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val sessions = store.listSessions()
        binding.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(sessions)
    }

    private inner class SessionAdapter : androidx.recyclerview.widget.RecyclerView
    .Adapter<SessionAdapter.Holder>() {

        private val items = ArrayList<SessionStore.SessionRow>()

        fun submit(list: List<SessionStore.SessionRow>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemHistoryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            holder.bind(row)
        }

        inner class Holder(private val b: ItemHistoryBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root) {

            fun bind(row: SessionStore.SessionRow) {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                b.tvTitle.text = fmt.format(Date(row.startTime))
                val durationMin = if (row.endTime != null) {
                    ((row.endTime - row.startTime) / 60_000).coerceAtLeast(1)
                } else 1
                val segCount = store.listSegments(row.id).size
                b.tvSubtitle.text =
                    "${row.sourceLang ?: getString(R.string.source_lang_label)} → ${row.targetLang.uppercase()}" +
                        " · ${getString(R.string.session_segment_count, segCount)}" +
                        " · ${getString(R.string.session_duration_format, durationMin)}"
                b.root.setOnClickListener {
                    startActivity(
                        Intent(b.root.context, SessionDetailActivity::class.java)
                            .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, row.id)
                    )
                }
            }
        }
    }
}
