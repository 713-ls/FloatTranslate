package com.sheng.floattranslate.data

import android.content.Context
import android.util.LruCache
import com.sheng.floattranslate.model.ScreenTextBlock
import com.sheng.floattranslate.model.TranslateDirection
import com.sheng.floattranslate.util.PrefsManager
import com.sheng.floattranslate.util.TextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TranslationRepository(context: Context) {
    private val db = TranslationDbHelper(context)
    private val prefs = PrefsManager(context)

    companion object {
        private val memCache = LruCache<String, String>(500)
        @Volatile
        private var gtxBlockedUntil: Long = 0L

        private val fastClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            })
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .build()

        private val customApiClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val concurrencyGate = Semaphore(12)

    suspend fun translateBlocks(
        blocks: List<ScreenTextBlock>,
        direction: TranslateDirection
    ): List<ScreenTextBlock> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()

        // 在「中→英」模式下，先检测当前页面是否是“选词组合短语”或“首字母提示补全单词”题型
        if (direction == TranslateDirection.ZH_TO_EN) {
            val solvedQuiz = trySolveZhToEnExercise(blocks)
            if (solvedQuiz != null) return@withContext solvedQuiz
        }

        // 常规全屏批量翻译（仅翻译对应方向的目标块）
        val targetBlocks = if (direction == TranslateDirection.ZH_TO_EN) {
            blocks.filter { TextCleaner.hasChinese(it.originalText) }
        } else {
            blocks
        }
        if (targetBlocks.isEmpty()) return@withContext emptyList()

        val engineTag = if (prefs.useCustomApi && prefs.apiKey.isNotEmpty()) "custom" else "free"
        val cleanedInputs = mutableListOf<String>()
        val uniqueInputs = LinkedHashMap<String, MutableList<Int>>()

        targetBlocks.forEachIndexed { idx, block ->
            val cleaned = TextCleaner.cleanForTranslation(block.originalText)
            val key = "${direction.name}:$engineTag:$cleaned"
            val cached = memCache.get(key) ?: db.get(key)?.also { memCache.put(key, it) }
            if (cached != null) {
                block.translatedText = cached
            } else {
                val listForText = uniqueInputs.getOrPut(cleaned) { mutableListOf() }
                if (listForText.isEmpty()) {
                    cleanedInputs.add(cleaned)
                }
                listForText.add(idx)
            }
        }

        if (cleanedInputs.isNotEmpty()) {
            val translatedUnique = if (engineTag == "custom") {
                batchTranslateCustom(cleanedInputs, direction)
            } else {
                batchTranslateFreeFast(cleanedInputs, direction)
            }

            cleanedInputs.forEachIndexed { i, cleaned ->
                val trans = translatedUnique.getOrNull(i)?.trim().orEmpty()
                val indices = uniqueInputs[cleaned].orEmpty()
                for (bIdx in indices) {
                    val finalTrans = if (trans.isNotEmpty()) trans else targetBlocks[bIdx].originalText
                    targetBlocks[bIdx].translatedText = finalTrans
                }
                if (trans.isNotEmpty()) {
                    val key = "${direction.name}:$engineTag:$cleaned"
                    memCache.put(key, trans)
                    db.put(key, trans)
                }
            }
        }

        targetBlocks
    }

    /**
     * 智能识别并解答「中→英」短语填空与选词组合题：
     * 1. 首字母补全题（如：an [ext - - - -] species + 已灭绝的物种 -> 补全 extinct，显示完整短语 an extinct species）
     * 2. 候选词组合题（如：在观察中 + [observation, observe, specimen, under] -> 组合为 under observation，并在对应词块标出点击顺序 ① ②）
     */
    private suspend fun trySolveZhToEnExercise(blocks: List<ScreenTextBlock>): List<ScreenTextBlock>? {
        val zhBlocks = blocks.filter { TextCleaner.hasChinese(it.originalText) }
        val enBlocks = blocks.filter { !TextCleaner.hasChinese(it.originalText) && TextCleaner.hasEnglishOrBlankHint(it.originalText) }

        if (zhBlocks.isEmpty() || enBlocks.isEmpty()) return null

        val mainZhBlock = zhBlocks.minByOrNull { it.bounds.top } ?: return null
        val chinesePrompt = mainZhBlock.originalText.trim()

        // 如果开启了自定义大模型 API，优先直接让大模型结合页面给出的英文线索精准解答
        if (prefs.useCustomApi && prefs.apiKey.isNotEmpty()) {
            val llmSolved = solveExerciseWithCustomApi(chinesePrompt, mainZhBlock, enBlocks)
            if (llmSolved != null) return llmSolved
        }

        // 判断题型 1：是否存在首字母填空提示（例如 "ext - - - -" 或同一水平线上有前缀短词）
        val sameRowEn = enBlocks.filter { kotlin.math.abs(it.bounds.centerY() - mainZhBlock.bounds.top) < 350 && it.bounds.top < mainZhBlock.bounds.bottom }
            .sortedBy { it.bounds.left }

        val explicitHintBlock = enBlocks.firstOrNull { TextCleaner.extractPrefixHint(it.originalText) != null }
        if (explicitHintBlock != null || (sameRowEn.isNotEmpty() && enBlocks.size <= 3)) {
            return solvePrefixCompletionFree(chinesePrompt, mainZhBlock, enBlocks, explicitHintBlock)
        }

        // 判断题型 2：下方存在多个英文候选词按钮（如 observation / observe / specimen / under）
        val candidateBlocks = enBlocks.filter { it.bounds.top > mainZhBlock.bounds.top }
        if (candidateBlocks.size >= 2) {
            return solveWordBankCombinationFree(chinesePrompt, mainZhBlock, candidateBlocks)
        }

        return null
    }

    /**
     * 题型 1（首字母补全单词）：例如「an  ext - - - -  species」+「已灭绝的物种」
     */
    private suspend fun solvePrefixCompletionFree(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        enBlocks: List<ScreenTextBlock>,
        explicitHintBlock: ScreenTextBlock?
    ): List<ScreenTextBlock> {
        val sortedEn = enBlocks.sortedBy { it.bounds.left }

        // 找出哪个块是待补全的 2~5 字母前缀
        val targetHintBlock = explicitHintBlock ?: sortedEn.firstOrNull { b ->
            val clean = b.originalText.replace(Regex("[^a-zA-Z]"), "").lowercase()
            clean.length in 2..5 && clean !in setOf("an", "the", "to", "in", "on", "of", "at", "by", "for", "with", "from", "into")
        }

        val prefixPair = targetHintBlock?.let {
            TextCleaner.extractPrefixHint(it.originalText)
                ?: (it.originalText.replace(Regex("[^a-zA-Z]"), "").lowercase() to null)
        }

        // 1. 先把中文短语翻译为英文（免费接口）
        val rawTranslatedList = batchTranslateFreeFast(listOf(chinesePrompt), TranslateDirection.ZH_TO_EN)
        val rawEnPhrase = rawTranslatedList.firstOrNull()?.lowercase().orEmpty()
        val translatedWords = Regex("[a-zA-Z]+").findAll(rawEnPhrase).map { it.value.lowercase() }.toList()

        var completedWord: String? = null
        if (prefixPair != null && prefixPair.first.isNotEmpty()) {
            val prefix = prefixPair.first
            val expectedLen = prefixPair.second

            // A. 先看整句英译结果里是否直接包含以 prefix 开头的单词（如 "extinct species" -> "extinct"）
            completedWord = translatedWords.firstOrNull { w ->
                w.startsWith(prefix) && w.length > prefix.length && (expectedLen == null || w.length == expectedLen)
            } ?: translatedWords.firstOrNull { w ->
                w.startsWith(prefix) && w.length > prefix.length
            }

            // B. 若直接翻译用了同义词，调用 Datamuse 词汇联想接口 (`sp=ext*&ml=英文语境`) 精准补全以 prefix 开头的单词
            if (completedWord == null) {
                val contextWords = (translatedWords + sortedEn.mapNotNull {
                    if (it == targetHintBlock) null else it.originalText.replace(Regex("[^a-zA-Z]"), "").lowercase()
                }).filter { it.length > 2 && it != prefix }.distinct().joinToString(" ")

                completedWord = queryDatamusePrefix(prefix, expectedLen, contextWords)
            }
        }

        // 组装完整短语
        val fullPhrase = if (completedWord != null && targetHintBlock != null) {
            sortedEn.joinToString(" ") { b ->
                if (b == targetHintBlock) completedWord else b.originalText.trim()
            }
        } else {
            rawEnPhrase.ifEmpty { chinesePrompt }
        }

        val out = mutableListOf<ScreenTextBlock>()
        if (completedWord != null && targetHintBlock != null) {
            out.add(
                ScreenTextBlock(
                    originalText = targetHintBlock.originalText,
                    bounds = targetHintBlock.bounds,
                    translatedText = "补全: $completedWord"
                )
            )
        }
        out.add(
            ScreenTextBlock(
                originalText = zhBlock.originalText,
                bounds = zhBlock.bounds,
                translatedText = if (completedWord != null) {
                    "$chinesePrompt  ➔  $fullPhrase （填入: $completedWord）"
                } else {
                    "$chinesePrompt  ➔  $fullPhrase"
                }
            )
        )
        return out
    }

    /**
     * 调用公开免费的 Datamuse 词库 API，根据首字母前缀 (`ext*`) 与上下文含义补全完整英文单词
     */
    private fun queryDatamusePrefix(prefix: String, expectedLen: Int?, contextMeaning: String): String? {
        return try {
            val pattern = if (expectedLen != null && expectedLen > prefix.length) {
                prefix + "?".repeat(expectedLen - prefix.length)
            } else {
                "$prefix*"
            }
            val mlParam = if (contextMeaning.isNotEmpty()) {
                "&ml=${URLEncoder.encode(contextMeaning, "UTF-8")}"
            } else ""
            val url = "https://api.datamuse.com/words?sp=$pattern$mlParam&max=8"
            val req = Request.Builder().url(url).get().build()
            val bodyStr = fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }
            val arr = JSONArray(bodyStr)
            if (arr.length() > 0) {
                return arr.getJSONObject(0).optString("word").takeIf { it.isNotEmpty() }
            }
            // 若严格字数未命中，放宽为 prefix* 再查一次
            if (expectedLen != null) {
                return queryDatamusePrefix(prefix, null, contextMeaning)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 题型 2（用页面给定的英文单词组合成对应中文短语）：
     * 例如中文「在观察中」，候选词 [observation, observe, specimen, under] -> 自动组合出 "under observation"，
     * 并在候选词按钮原位标出 ① under、② observation 的点击顺序！
     */
    private suspend fun solveWordBankCombinationFree(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        candidateBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock> = coroutineScope {
        val candidates = candidateBlocks.map { it.originalText.trim() }

        // 并发请求：1. 中文短语的直接英译；2. 所有两词组合（或候选词本身）的中文含义校对
        val directEnDeferred = async(Dispatchers.IO) {
            batchTranslateFreeFast(listOf(chinesePrompt), TranslateDirection.ZH_TO_EN)
                .firstOrNull()?.lowercase().orEmpty()
        }

        // 生成由候选词构成的合理短语组合（2词组合优先，如 "under observation"）
        val pairPhrases = mutableListOf<List<Int>>()
        for (i in candidates.indices) {
            for (j in candidates.indices) {
                if (i != j) pairPhrases.add(listOf(i, j))
            }
        }

        val directEn = directEnDeferred.await()
        val directTokens = Regex("[a-zA-Z]+").findAll(directEn).map { it.value.lowercase() }.toList()

        // 策略 A：如果直接英译出来的词恰好都在候选池里（如 directEn = "under observation"），直接精确匹配
        val exactIndices = mutableListOf<Int>()
        for (tok in directTokens) {
            val matchIdx = candidates.indexOfFirst { it.equals(tok, ignoreCase = true) }
            if (matchIdx != -1 && matchIdx !in exactIndices) {
                exactIndices.add(matchIdx)
            }
        }

        val chosenIndices: List<Int> = if (exactIndices.size >= 2) {
            exactIndices
        } else {
            // 策略 B：把候选词构成的组合并发翻译回中文，挑选与题目中文重合度最高的一组
            val comboStrings = pairPhrases.map { idxList -> idxList.joinToString(" ") { candidates[it] } }
            val comboZhList = batchTranslateFreeFast(comboStrings, TranslateDirection.EN_TO_ZH)

            var bestScore = -1
            var bestPair: List<Int> = exactIndices.ifEmpty { listOf(0) }

            pairPhrases.forEachIndexed { pairIdx, idxList ->
                val zhTrans = comboZhList.getOrNull(pairIdx).orEmpty()
                val comboEn = comboStrings[pairIdx].lowercase()
                var score = 0
                // 字符重合分
                for (ch in chinesePrompt) {
                    if (ch in zhTrans) score += 3
                }
                if (zhTrans == chinesePrompt) score += 10
                // 英文词根重合分
                for (tok in directTokens) {
                    if (tok.length >= 3 && comboEn.contains(tok.take(4))) score += 4
                }
                // 介词开头加分（英语短语常见 under / in / on / at / by / with + 名词）
                val firstWord = candidates[idxList[0]].lowercase()
                if (firstWord in setOf("under", "in", "on", "at", "by", "for", "with", "Beyond", "without", "beyond", "within", "out", "from", "to")) {
                    score += 2
                }
                if (score > bestScore) {
                    bestScore = score
                    bestPair = idxList
                }
            }
            bestPair
        }

        val finalPhrase = chosenIndices.joinToString(" ") { candidates[it] }
        val orderBadges = arrayOf("①", "②", "③", "④", "⑤", "⑥")

        val outputBlocks = mutableListOf<ScreenTextBlock>()
        // 在中文题目位置直接显示组合好的英文短语答案
        outputBlocks.add(
            ScreenTextBlock(
                originalText = zhBlock.originalText,
                bounds = zhBlock.bounds,
                translatedText = "$chinesePrompt  ➔  $finalPhrase"
            )
        )

        // 在被选中的英文单词按钮上直接标出点击顺序 ①、②
        chosenIndices.forEachIndexed { orderIdx, candIdx ->
            val b = candidateBlocks[candIdx]
            val badge = orderBadges.getOrElse(orderIdx) { "${orderIdx + 1}." }
            outputBlocks.add(
                ScreenTextBlock(
                    originalText = b.originalText,
                    bounds = b.bounds,
                    translatedText = "$badge ${b.originalText}"
                )
            )
        }

        outputBlocks
    }

    /**
     * 若用户开启了自定义大模型 API，一步到位同时支持「选词组合」与「首字母补全单词」
     */
    private fun solveExerciseWithCustomApi(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        enBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock>? {
        return try {
            val endpoint = prefs.baseUrl.trimEnd('/') + "/chat/completions"
            val enClues = enBlocks.sortedBy { it.bounds.top * 1000 + it.bounds.left }
                .joinToString(" | ") { it.originalText.trim() }

            val prompt = "这是一个英语短语练习页面，中文题目：「$chinesePrompt」，页面上的英文线索或候选词：「$enClues」。1. 若英文线索含首字母前缀（如 an | ext - - - - | species），请根据中文补全该单词并输出完整短语；2. 若英文线索是多个候选单词（如 observation | observe | specimen | under），请严格用候选词组合出对应中文题目的英文短语。只输出一行最终英文短语，不要多余解释。" 

            val payload = JSONObject().apply {
                put("model", prefs.modelName)
                put("temperature", 0.0)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${prefs.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val bodyStr = customApiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }
            val answer = JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .lines()
                .firstOrNull()
                ?.trim()
                .orEmpty()

            if (answer.isEmpty()) return null

            val out = mutableListOf<ScreenTextBlock>()
            out.add(
                ScreenTextBlock(
                    originalText = zhBlock.originalText,
                    bounds = zhBlock.bounds,
                    translatedText = "$chinesePrompt  ➔  $answer"
                )
            )

            // 如果页面下方有候选词按钮，顺便把命中的词按顺序标上 ① ②
            val answerWords = Regex("[a-zA-Z]+").findAll(answer).map { it.value.lowercase() }.toList()
            val orderBadges = arrayOf("①", "②", "③", "④", "⑤")
            var step = 0
            for (w in answerWords) {
                val matchedBlock = enBlocks.firstOrNull { it.originalText.trim().equals(w, ignoreCase = true) }
                if (matchedBlock != null && step < orderBadges.size) {
                    out.add(
                        ScreenTextBlock(
                            originalText = matchedBlock.originalText,
                            bounds = matchedBlock.bounds,
                            translatedText = "${orderBadges[step]} ${matchedBlock.originalText}"
                        )
                    )
                    step++
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun batchTranslateFreeFast(
        lines: List<String>,
        direction: TranslateDirection
    ): List<String> = coroutineScope {
        if (System.currentTimeMillis() > gtxBlockedUntil) {
            val gtxResult = tryBatchGoogleGtx(lines, direction)
            if (gtxResult != null) return@coroutineScope gtxResult
        }

        lines.map { line ->
            async(Dispatchers.IO) {
                concurrencyGate.withPermit {
                    singleMyMemory(line, direction)
                }
            }
        }.awaitAll()
    }

    private fun tryBatchGoogleGtx(lines: List<String>, direction: TranslateDirection): List<String>? {
        return try {
            val payloadText = lines.mapIndexed { idx, s -> "[${idx + 1}] ${s.replace("\n", " ")}" }
                .joinToString("\n")
            val encoded = URLEncoder.encode(payloadText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${direction.fromLang}&tl=${direction.toLang}&dt=t&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            val bodyStr = fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }
            if (bodyStr.isEmpty()) return null

            val outer = JSONArray(bodyStr).getJSONArray(0)
            val combined = StringBuilder()
            for (i in 0 until outer.length()) {
                combined.append(outer.getJSONArray(i).optString(0))
            }

            parseNumberedOutput(combined.toString(), lines)
        } catch (_: Exception) {
            gtxBlockedUntil = System.currentTimeMillis() + 5 * 60 * 1000L
            null
        }
    }

    private fun parseNumberedOutput(rawOutput: String, originalLines: List<String>): List<String>? {
        val resultMap = HashMap<Int, String>()
        val regex = Regex("\\[(\\d+)\\]\\s*([^\\[]*)")
        regex.findAll(rawOutput).forEach { match ->
            val idx = match.groupValues[1].toIntOrNull()
            val text = match.groupValues[2].trim()
            if (idx != null && text.isNotEmpty()) {
                resultMap[idx - 1] = text
            }
        }
        if (resultMap.isEmpty()) return null
        return originalLines.indices.map { i -> resultMap[i] ?: originalLines[i] }
    }

    private fun singleMyMemory(text: String, direction: TranslateDirection): String {
        return try {
            val encoded = URLEncoder.encode(text.take(220), "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=${direction.fromLang}|${direction.toLang}"
            val req = Request.Builder().url(url).get().build()
            val bodyStr = fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return text
                resp.body?.string().orEmpty()
            }
            val translated = JSONObject(bodyStr)
                .optJSONObject("responseData")
                ?.optString("translatedText")
                ?.trim()
                .orEmpty()
            if (translated.isNotEmpty()) translated else text
        } catch (_: Exception) {
            text
        }
    }

    private suspend fun batchTranslateCustom(
        lines: List<String>,
        direction: TranslateDirection
    ): List<String> {
        return try {
            val endpoint = prefs.baseUrl.trimEnd('/') + "/chat/completions"
            val numbered = lines.mapIndexed { idx, s -> "[${idx + 1}] ${s.replace("\n", " ")}" }
                .joinToString("\n")
            val targetLang = if (direction == TranslateDirection.EN_TO_ZH) "简体中文" else "英文"
            val prompt = "请将以下编号内容逐条翻译为$targetLang，严格保持 [编号] 译文 格式，不要多余解释：\n$numbered"

            val payload = JSONObject().apply {
                put("model", prefs.modelName)
                put("temperature", 0.1)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${prefs.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val bodyStr = customApiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return batchTranslateFreeFast(lines, direction)
                resp.body?.string().orEmpty()
            }
            val content = JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            parseNumberedOutput(content, lines) ?: batchTranslateFreeFast(lines, direction)
        } catch (_: Exception) {
            batchTranslateFreeFast(lines, direction)
        }
    }
}
