package com.sheng.floattranslate.util

object TextCleaner {
    private val chineseRegex = Regex("[\\u4e00-\\u9fa5]")
    private val englishRegex = Regex("[a-zA-Z]{2,}")

    private val uiNoiseKeywords = listOf(
        "返回", "练习", "提交", "下一题", "确定", "完成", "跳过", "提示", "查看解析", "vocabgo", "Back"
    )

    fun isUiNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val stripped = t.replace(Regex("[<>＜＞\\s\\d%/.,:：-]"), "")
        if (stripped.isEmpty()) return true
        return uiNoiseKeywords.any { stripped.equals(it, ignoreCase = true) || t.contains("返回") || t.contains("vocabgo", ignoreCase = true) }
    }

    fun hasChinese(text: String): Boolean {
        if (isUiNoise(text)) return false
        return chineseRegex.containsMatchIn(text)
    }

    fun hasEnglishOrBlankHint(text: String): Boolean {
        if (isUiNoise(text)) return false
        val t = text.trim()
        return englishRegex.containsMatchIn(t)
    }

    /**
     * 提取填空框中的首字母前缀（2~5个字母）：
     * 兼容正常横线 "obs - - - -"、"ar - - -" 以及 OCR 将灰色虚线误识别为 "obs iil"、"obs lll"、"ar ---" 的情况。
     */
    fun extractPrefixHint(text: String): Pair<String, Int?>? {
        val cleaned = text.trim()
        // 情况 1: "obs - - - -" 或 "ar - - -"
        val mDash = Regex("^([a-zA-Z]{2,5})\\s+([-_\\s·.]+)$").find(cleaned)
        if (mDash != null) {
            val prefix = mDash.groupValues[1].lowercase()
            val dashCount = mDash.groupValues[2].count { it == '-' || it == '_' || it == '·' || it == '.' }
            return prefix to (if (dashCount > 0) prefix.length + dashCount else null)
        }
        // 情况 2: OCR 把灰色虚线 "- - - -" 误识别成了 "iil"、"lll"、"111"、"iii" 等 (如第2张图中的 "obs iil")
        val mOcrGarble = Regex("^([a-zA-Z]{2,5})\\s+([ilI1|-]{2,10})$").find(cleaned)
        if (mOcrGarble != null) {
            val prefix = mOcrGarble.groupValues[1].lowercase()
            return prefix to null
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
        if (isUiNoise(text)) return false
        val t = text.trim()
        if (t.length < 2) return false
        return if (isEnToZh) {
            englishRegex.containsMatchIn(t)
        } else {
            chineseRegex.containsMatchIn(t)
        }
    }
}
