package com.sheng.floattranslate.util

object TextCleaner {
    private val chineseRegex = Regex("[\\u4e00-\\u9fa5]")
    private val englishRegex = Regex("[a-zA-Z]{2,}")

    fun cleanForTranslation(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val noCodePunctuation = trimmed
            .replace(Regex("[{}()\\[\\];\"'`<>]"), " ")
            .replace("->", " ")
            .replace("::", " ")
            .replace("_", " ")

        val splitCamel = noCodePunctuation
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")

        return splitCamel.replace(Regex("\\s+"), " ").trim()
    }

    fun shouldTranslate(text: String, isEnToZh: Boolean): Boolean {
        val t = text.trim()
        if (t.length < 2) return false
        return if (isEnToZh) {
            englishRegex.containsMatchIn(t)
        } else {
            chineseRegex.containsMatchIn(t)
        }
    }
}
