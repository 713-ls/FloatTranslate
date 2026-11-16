package com.sheng.floattranslate.util

object TextCleaner {
    private val chineseRegex = Regex("[\\u4e00-\\u9fa5]")
    private val englishRegex = Regex("[a-zA-Z]{2,}")
    private val blankDashRegex = Regex("([a-zA-Z]{1,6})\\s*(?:[-_\\s]{2,})")

    fun hasChinese(text: String): Boolean = chineseRegex.containsMatchIn(text)

    fun hasEnglishOrBlankHint(text: String): Boolean {
        val t = text.trim()
        return englishRegex.containsMatchIn(t) || blankDashRegex.containsMatchIn(t)
    }

    /**
     * 提取如 "ext - - - -" 或 "ext____" 中的字母前缀与横线数量（若无横线则返回前缀与 null）
     */
    fun extractPrefixHint(text: String): Pair<String, Int?>? {
        val cleaned = text.trim()
        val m = Regex("^([a-zA-Z]{2,6})\\s*([-_\\s]+)$").find(cleaned)
        if (m != null) {
            val prefix = m.groupValues[1].lowercase()
            val dashes = m.groupValues[2].count { it == '-' || it == '_' }
            return prefix to (if (dashes > 0) prefix.length + dashes else null)
        }
        return null
    }

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
