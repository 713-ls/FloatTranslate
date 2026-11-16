package com.sheng.floattranslate.util

object TextCleaner {
    private val chineseRegex = Regex("[\\u4e00-\\u9fa5]")
    private val englishRegex = Regex("[a-zA-Z]{2,}")

    private val uiNoiseKeywords = listOf(
        "返回", "练习", "提交", "下一题", "确定", "完成", "跳过", "查看解析", "vocabgo", "Back"
    )

    val GRAMMAR_STOP_WORDS = setOf(
        "an", "a", "the", "of", "to", "in", "on", "at", "by", "for", "with", "from", "into",
        "under", "over", "above", "below", "between", "among", "through", "across", "against",
        "during", "without", "within", "about", "around", "before", "after", "since", "until",
        "upon", "onto", "off", "out", "up", "down", "as", "than", "like", "near",
        "is", "am", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "doing", "done",
        "sb", "sth", "one", "ones", "one's", "someone", "something", "somebody"
    )

    fun isUiNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val stripped = t.replace(Regex("[<>＜＞\\s\\d%/.,:：-]"), "")
        if (stripped.isEmpty()) return true
        return uiNoiseKeywords.any {
            stripped.equals(it, ignoreCase = true) ||
                t.contains("返回") ||
                t.contains("vocabgo", ignoreCase = true)
        }
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
     * 提取填空框中的首字母前缀（2~5个字母）及总长度（若横线清晰）：
     * 1. 支持独立块 "obs - - - -"、"cou - - - - - -"、"fun - - - -"、"obs iil"
     * 2. 支持同行混合块 "under obs - - - -"、"a rare spe - - - -"、"the sur - - - - of the fittest"、"government fun - - - -"
     */
    fun extractPrefixHint(text: String): Pair<String, Int?>? {
        val cleaned = text.trim()

        // 匹配任意位置的 "前缀 + 虚线/横线/OCR乱码竖线"
        val pattern = Regex("(?:^|\\s)([a-zA-Z]{2,5})\\s*(?:([-_·.]+(?:\\s*[-_·.]+)+)|([ilI1|-]{2,10}))(?:\\s|$)")
        val m = pattern.find(cleaned)
        if (m != null) {
            val prefix = m.groupValues[1].lowercase()
            if (prefix !in GRAMMAR_STOP_WORDS) {
                val dashesGroup = m.groupValues[2]
                val dashCount = dashesGroup.count { it == '-' || it == '_' || it == '·' || it == '.' }
                val totalLen = if (dashCount >= 2) prefix.length + dashCount else null
                return prefix to totalLen
            }
        }

        // 若该块本身就是一个 2~4 字母的残缺词前缀（不在常见介词/冠词表中）
        if (Regex("^[a-zA-Z]{2,4}$").matches(cleaned)) {
            val lower = cleaned.lowercase()
            if (lower !in GRAMMAR_STOP_WORDS) {
                return lower to null
            }
        }
        return null
    }

    /**
     * 当整行文本没有拍出横线（例如 OCR 只识别出 "a rare spe"、"under obs"、"ove a problem"、"government fun"）时，
     * 找出其中不属于常见完整词或位于填空位置的 2~4 字母前缀。
     */
    fun findLikelyPrefixToken(tokens: List<String>, commonCompleteWords: Set<String>): String? {
        // 先找 2~4 字母、既不在语法虚词表也不在常见完整词表中的词（如 spe, obs, ove, cou, sur, ext）
        val incomplete = tokens.firstOrNull { tok ->
            val l = tok.lowercase()
            l.length in 2..4 && l !in GRAMMAR_STOP_WORDS && l !in commonCompleteWords
        }
        if (incomplete != null) return incomplete.lowercase()

        // 再找 3 字母词（如 "government fun" 中的 "fun"）
        val threeLetter = tokens.lastOrNull { tok ->
            val l = tok.lowercase()
            l.length in 2..4 && l !in GRAMMAR_STOP_WORDS
        }
        return threeLetter?.lowercase()
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
