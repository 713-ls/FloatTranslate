package com.sheng.floattranslate.model

import android.graphics.Rect

enum class TranslateDirection(val shortLabel: String, val fromLang: String, val toLang: String) {
    EN_TO_ZH("英→中", "en", "zh-CN"),
    ZH_TO_EN("中→英", "zh-CN", "en");

    fun toggle(): TranslateDirection = if (this == EN_TO_ZH) ZH_TO_EN else EN_TO_ZH
}

enum class CaptureMode(val shortLabel: String) {
    NODE("节点"),
    OCR("识图");

    fun toggle(): CaptureMode = if (this == NODE) OCR else NODE
}

data class ScreenTextBlock(
    val originalText: String,
    val bounds: Rect,
    var translatedText: String = ""
)
