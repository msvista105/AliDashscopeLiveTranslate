package com.sxm.livetranslate

import android.content.Context
import android.content.SharedPreferences

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
        private const val KEY_API = "api_key"
        private const val KEY_WORKSPACE = "workspace_id"
        private const val KEY_REGION_SG = "region_singapore"
        private const val KEY_LANG = "target_lang"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    fun languageDisplayName(): String =
        LANGUAGES.firstOrNull { it.code == targetLanguage }?.displayName ?: targetLanguage
}
