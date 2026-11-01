package com.sxm.livetranslate

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppSettings(context: Context) {

    data class Language(val code: String, val displayName: String)

    companion object {
        val LANGUAGES = listOf(
            Language("en", "English 英语"),
            Language("zh", "中文"),
            Language("ja", "日本語 日语"),
            Language("ko", "한국어 韩语"),
            Language("fr", "Français 法语"),
            Language("de", "Deutsch 德语"),
            Language("es", "Español 西班牙语"),
            Language("ru", "Русский 俄语"),
            Language("pt", "Português 葡萄牙语"),
            Language("ar", "العربية 阿拉伯语"),
            Language("it", "Italiano 意大利语"),
            Language("th", "ไทย 泰语"),
            Language("vi", "Tiếng Việt 越南语"),
            Language("id", "Bahasa Indonesia 印尼语"),
            Language("hi", "हिन्दी 印地语"),
        )

        private const val PREFS = "live_translate_settings"
        private const val SECURE_PREFS = "live_translate_secure"
        private const val KEY_API = "api_key"
        private const val KEY_WORKSPACE = "workspace_id"
        private const val KEY_REGION_SG = "region_singapore"
        private const val KEY_LANG = "target_lang"
        private const val KEY_PARTNER_LANG = "partner_lang"
        private const val KEY_DIALOG = "dialog_mode"
        private const val KEY_HOTWORDS = "hotwords"

        /** 服务端建议热词不超过 1000 条 */
        private const val MAX_PHRASES = 1000

        private fun securePrefs(context: Context): SharedPreferences {
            val app = context.applicationContext
            try {
                return createEncrypted(app)
            } catch (_: Exception) {
                // 密钥库损坏（换机恢复备份等）时旧密文已无法解开，清掉重试一次
                app.deleteSharedPreferences(SECURE_PREFS)
            }
            return try {
                createEncrypted(app)
            } catch (_: Exception) {
                // 最后兜底：退回旧明文存储，保证 App 至少可用
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
        }

        private fun createEncrypted(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migrateLegacy(context, secure)
            return secure
        }

        /** 把旧版明文设置搬进加密文件，成功后清空明文文件（API Key 不再落盘为明文）。 */
        private fun migrateLegacy(context: Context, secure: SharedPreferences) {
            val legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) return
            val editor = secure.edit()
            for ((key, value) in legacy.all) {
                if (secure.contains(key)) continue
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as Set<String>)
                    }
                }
            }
            if (editor.commit()) {
                legacy.edit().clear().commit()
            }
        }
    }

    private val prefs: SharedPreferences = securePrefs(context)

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value).apply()

    var workspaceId: String
        get() = prefs.getString(KEY_WORKSPACE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WORKSPACE, value).apply()

    var useSingapore: Boolean
        get() = prefs.getBoolean(KEY_REGION_SG, false)
        set(value) = prefs.edit().putBoolean(KEY_REGION_SG, value).apply()

    var targetLanguage: String
        get() = prefs.getString(KEY_LANG, "en").orEmpty()
        set(value) = prefs.edit().putString(KEY_LANG, value).apply()

    fun isReady(): Boolean = apiKey.isNotBlank()

    /** 双语对话：会话中可一键把译文语言换成对方语言，两人各听自己懂的那种。 */
    var dialogMode: Boolean
        get() = prefs.getBoolean(KEY_DIALOG, false)
        set(value) = prefs.edit().putBoolean(KEY_DIALOG, value).apply()

    /** 对话模式的另一头语言，即「对方」的译文语言。 */
    var partnerLanguage: String
        get() = prefs.getString(KEY_PARTNER_LANG, "zh").orEmpty()
        set(value) = prefs.edit().putString(KEY_PARTNER_LANG, value).apply()

    /** 与目标语言不同的对话对象语言；设置里选了同一个语言时兜底换一个。 */
    fun dialogPartner(): String {
        if (partnerLanguage.isNotBlank() && partnerLanguage != targetLanguage) return partnerLanguage
        return LANGUAGES.first { it.code != targetLanguage }.code
    }

    fun swapLanguages() {
        val current = targetLanguage
        targetLanguage = dialogPartner()
        partnerLanguage = current
    }

    var hotwords: String
        get() = prefs.getString(KEY_HOTWORDS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HOTWORDS, value).apply()

    /** 每行一条「原文=译文」；只写原文时视为译文与原文相同，适合专有名词。 */
    fun phrases(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in hotwords.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val source = line.substringBefore('=').trim()
            if (source.isEmpty()) continue
            val fixed = line.substringAfter('=', source).trim().ifEmpty { source }
            out.putIfAbsent(source, fixed)
            if (out.size >= MAX_PHRASES) break
        }
        return out
    }

    fun languageDisplayName(): String = displayNameOf(targetLanguage)

    fun displayNameOf(code: String): String =
        LANGUAGES.firstOrNull { it.code == code }?.displayName ?: code
}
