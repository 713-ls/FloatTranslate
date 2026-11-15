package com.sheng.floattranslate.util

object TextCleaner {
    /**
     * 清洗代码符号并自动拆分驼峰 (camelCase) 与下划线 (snake_case) 标识符，
     * 例如 getUserProfile -> get User Profile；motion_planning -> motion planning。
     */
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
}
