package com.sxm.livetranslate.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "live_translate.db"
        private const val DB_VERSION = 1
    }

    class SessionRow(
        val id: Long,
        val startTime: Long,
        val endTime: Long?,
        val sourceLang: String?,
        val targetLang: String,
        val micFile: String?,
        val translatedFile: String?,
    )

    class SegmentRow(
        val id: Long,
        val sessionId: Long,
        val startMs: Long,
        val speakerId: String?,
        val sourceText: String,
        val translationText: String,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions(
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            start_time INTEGER NOT NULL,
            end_time INTEGER,
            source_lang TEXT,
            target_lang TEXT NOT NULL,
            mic_file TEXT,
            translated_file TEXT
        )"""
        )
        db.execSQL(
            """CREATE TABLE segments(
            _id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            start_ms INTEGER NOT NULL,
            speaker_id TEXT,
            source_text TEXT NOT NULL DEFAULT '',
            translation_text TEXT NOT NULL DEFAULT '',
            FOREIGN KEY(session_id) REFERENCES sessions(_id) ON DELETE CASCADE
        )"""
        )
        db.execSQL("CREATE INDEX idx_segments_session ON segments(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS segments")
        db.execSQL("DROP TABLE IF EXISTS sessions")
        onCreate(db)
    }

    fun insertSession(startTime: Long, targetLang: String, micFile: String?, translatedFile: String?): Long =
        writableDatabase.use { db ->
            db.insert(
                "sessions", null, ContentValues().apply {
                    put("start_time", startTime)
                    put("target_lang", targetLang)
                    put("mic_file", micFile)
                    put("translated_file", translatedFile)
                }
            )
        }

    fun finishSession(id: Long, endTime: Long, sourceLang: String?) {
        writableDatabase.use { db ->
            db.execSQL(
                "UPDATE sessions SET end_time=?, source_lang=COALESCE(?, source_lang) WHERE _id=?",
                arrayOf(endTime, sourceLang, id)
            )
        }
    }

    fun insertSegment(sessionId: Long, startMs: Long, speakerId: String?): Long =
        writableDatabase.use { db ->
            db.insert(
                "segments", null, ContentValues().apply {
                    put("session_id", sessionId)
                    put("start_ms", startMs)
                    put("speaker_id", speakerId)
                }
            )
        }

    fun updateSegmentText(id: Long, source: String?, translation: String?) {
        writableDatabase.use { db ->
            db.execSQL(
                """UPDATE segments SET
                source_text=COALESCE(?, source_text),
                translation_text=COALESCE(?, translation_text)
                WHERE _id=?""",
                // 服务端 delta 拼接后常带尾随空格，不清洗会一路带进 SRT 导出
                arrayOf(source?.trim(), translation?.trim(), id)
            )
        }
    }

    fun listSessions(): List<SessionRow> {
        val out = ArrayList<SessionRow>()
        readableDatabase.use { db ->
            db.rawQuery("SELECT * FROM sessions ORDER BY start_time DESC", null).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        SessionRow(
                            c.getLong(0), c.getLong(1),
                            if (c.isNull(2)) null else c.getLong(2),
                            if (c.isNull(3)) null else c.getString(3),
                            c.getString(4),
                            if (c.isNull(5)) null else c.getString(5),
                            if (c.isNull(6)) null else c.getString(6),
                        )
                    )
                }
            }
        }
        return out
    }

    fun getSession(id: Long): SessionRow? {
        readableDatabase.use { db ->
            db.rawQuery("SELECT * FROM sessions WHERE _id=?", arrayOf(id.toString())).use { c ->
                if (c.moveToFirst()) {
                    return SessionRow(
                        c.getLong(0), c.getLong(1),
                        if (c.isNull(2)) null else c.getLong(2),
                        if (c.isNull(3)) null else c.getString(3),
                        c.getString(4),
                        if (c.isNull(5)) null else c.getString(5),
                        if (c.isNull(6)) null else c.getString(6),
                    )
                }
            }
        }
        return null
    }

    fun listSegments(sessionId: Long): List<SegmentRow> {
        val out = ArrayList<SegmentRow>()
        readableDatabase.use { db ->
            db.rawQuery(
                "SELECT * FROM segments WHERE session_id=? ORDER BY start_ms",
                arrayOf(sessionId.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        SegmentRow(
                            c.getLong(0), c.getLong(1), c.getLong(2),
                            if (c.isNull(3)) null else c.getString(3),
                            c.getString(4), c.getString(5),
                        )
                    )
                }
            }
        }
        return out
    }

    fun deleteSession(id: Long) {
        getSession(id)?.let { row ->
            row.micFile?.let { File(it).delete() }
            row.translatedFile?.let { File(it).delete() }
        }
        writableDatabase.use { db ->
            db.execSQL("DELETE FROM segments WHERE session_id=?", arrayOf(id.toString()))
            db.execSQL("DELETE FROM sessions WHERE _id=?", arrayOf(id.toString()))
        }
    }

    /** 导出会话字幕为 SRT 格式（时间戳为会话内相对时间）。 */
    fun exportSrt(sessionId: Long): String {
        val sb = StringBuilder()
        var index = 1
        for (seg in listSegments(sessionId)) {
            if (seg.translationText.isBlank()) continue
            sb.append(index++).append('\n')
            sb.append(formatSrtTime(seg.startMs)).append(" --> ")
            sb.append(formatSrtTime(seg.startMs + estimateDurationMs(seg))).append('\n')
            if (!seg.sourceText.isBlank()) {
                sb.append(seg.sourceText).append('\n')
            }
            sb.append(seg.translationText).append('\n').append('\n')
        }
        return sb.toString()
    }

    private fun estimateDurationMs(seg: SegmentRow): Long {
        val chars = maxOf(seg.sourceText.length, seg.translationText.length)
        return maxOf(1200L, chars * 120L)
    }

    private fun formatSrtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }
}
