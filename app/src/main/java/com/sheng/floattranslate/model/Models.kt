package com.sheng.floattranslate.model

enum class TranslateDirection(val label: String, val fromLang: String, val toLang: String) {
    EN_TO_ZH("EN → ZH", "en", "zh-CN"),
    ZH_TO_EN("ZH → EN", "zh-CN", "en");

    fun toggle(): TranslateDirection = if (this == EN_TO_ZH) ZH_TO_EN else EN_TO_ZH
}

enum class CaptureMode(val label: String) {
    NODE("节点读词"),
    OCR("框选识图");

    fun toggle(): CaptureMode = if (this == NODE) OCR else NODE
}

data class TranslationResult(
    val query: String,
    val cleanedQuery: String,
    val translatedText: String,
    val phonetic: String? = null,
    val sourceLabel: String = "免费接口"
)
