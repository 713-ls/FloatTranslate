package com.sheng.floattranslate.util

import android.content.Context

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("float_translate_prefs", Context.MODE_PRIVATE)

    var useCustomApi: Boolean
        get() = prefs.getBoolean("use_custom_api", false)
        set(value) = prefs.edit().putBoolean("use_custom_api", value).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1"
        set(value) = prefs.edit().putString("base_url", value.trim()).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var modelName: String
        get() = prefs.getString("model_name", "deepseek-chat") ?: "deepseek-chat"
        set(value) = prefs.edit().putString("model_name", value.trim()).apply()
}
