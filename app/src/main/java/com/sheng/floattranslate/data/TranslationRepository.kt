package com.sheng.floattranslate.data

import android.content.Context
import android.util.LruCache
import com.sheng.floattranslate.model.ScreenTextBlock
import com.sheng.floattranslate.model.TranslateDirection
import com.sheng.floattranslate.ocr.ScreenOcrManager
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
import kotlin.math.abs

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
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        private val COMMON_COMPLETE_WORDS = setOf(
            "rare", "government", "stars", "star", "problem", "problems", "species", "fittest",
            "animal", "animals", "world", "people", "time", "year", "years", "work", "life",
            "water", "food", "money", "system", "group", "number", "part", "place", "case",
            "point", "company", "child", "children", "student", "students", "country", "state",
            "family", "school", "member", "members", "power", "change", "level", "service"
        )
    }

    private val concurrencyGate = Semaphore(12)

    suspend fun translateBlocks(
        blocks: List<ScreenTextBlock>,
        direction: TranslateDirection
    ): List<ScreenTextBlock> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()

        // 1. 优先检测是否是「Choose the words commonly used together with ...」单词搭配题
        val collocationSolved = trySolveCollocationQuestion(blocks)
        if (collocationSolved != null && collocationSolved.isNotEmpty()) {
            return@withContext collocationSolved
        }

        // 2. 在「中→英」模式下，检测是否是词汇练习题（首字母补全单词 / 候选词按横线数量组合短语）
        if (direction == TranslateDirection.ZH_TO_EN) {
            val solvedQuiz = trySolveZhToEnExercise(blocks)
            if (solvedQuiz != null && solvedQuiz.isNotEmpty()) {
                return@withContext solvedQuiz
            }
        }

        // 3. 常规全屏翻译（英→中模式过滤掉纯中文选项按钮，只翻英文句子/单词，避免遮挡下方中文选项）
        val targetBlocks = if (direction == TranslateDirection.ZH_TO_EN) {
            blocks.filter { TextCleaner.hasChinese(it.originalText) }
        } else {
            blocks.filter { TextCleaner.shouldTranslate(it.originalText, true) && !TextCleaner.hasChinese(it.originalText) }
        }
        if (targetBlocks.isEmpty()) return@withContext emptyList()

        val engineTag = if (prefs.useCustomApi && prefs.apiKey.isNotEmpty()) "custom" else "free"
        val cleanedInputs = mutableListOf<String>()
        val uniqueInputs = LinkedHashMap<String, MutableList<Int>>()

        targetBlocks.forEachIndexed { idx, block ->
            val cleaned = TextCleaner.cleanForTranslation(block.originalText)
            val key = "${direction.name}:$engineTag:$cleaned"
            val cached = memCache.get(key) ?: db.get(key)?.also { memCache.put(key, it) }
            // 如果缓存里存的是未翻译成功的纯英文（在英→中模式下），丢弃该脏缓存重新翻译！
            val isValidCache = cached != null && !(direction == TranslateDirection.EN_TO_ZH && !TextCleaner.hasChinese(cached))
            if (isValidCache && cached != null) {
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
                var trans = translatedUnique.getOrNull(i)?.trim().orEmpty()
                // 若英译中时某一句仍未翻出中文（例如原本只翻了一半），立即调用有道整句翻译兜底！
                if (direction == TranslateDirection.EN_TO_ZH && !TextCleaner.hasChinese(trans)) {
                    val yd = singleYoudaoTranslate(cleaned)
                    if (TextCleaner.hasChinese(yd)) trans = yd
                }

                val indices = uniqueInputs[cleaned].orEmpty()
                for (bIdx in indices) {
                    val finalTrans = if (trans.isNotEmpty()) trans else targetBlocks[bIdx].originalText
                    targetBlocks[bIdx].translatedText = finalTrans
                }
                if (trans.isNotEmpty() && !(direction == TranslateDirection.EN_TO_ZH && !TextCleaner.hasChinese(trans))) {
                    val key = "${direction.name}:$engineTag:$cleaned"
                    memCache.put(key, trans)
                    db.put(key, trans)
                }
            }
        }

        targetBlocks
    }

    /**
     * 题型：单词固定搭配题（如 "Choose the words commonly used together with survival"）
     */
    private suspend fun trySolveCollocationQuestion(blocks: List<ScreenTextBlock>): List<ScreenTextBlock>? = coroutineScope {
        val fullScreenText = blocks.joinToString(" ") { it.originalText }
        val mColloc = Regex("together\\s+with\\s+([a-zA-Z]{2,20})", RegexOption.IGNORE_CASE).find(fullScreenText)
            ?: return@coroutineScope null

        val targetWord = mColloc.groupValues[1].lowercase()
        val mNeedCount = Regex("(?:有|选中)\\s*(\\d+)\\s*个").find(fullScreenText)
        val requiredCount = mNeedCount?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 2

        val questionBlock = blocks.firstOrNull {
            it.originalText.contains("together", ignoreCase = true) ||
                it.originalText.contains("Choose", ignoreCase = true)
        } ?: blocks.first()

        val candidateBlocks = blocks.filter { b ->
            b != questionBlock &&
                !TextCleaner.hasChinese(b.originalText) &&
                !b.originalText.contains("Choose", ignoreCase = true) &&
                !b.originalText.contains("together", ignoreCase = true) &&
                Regex("^[a-zA-Z.-]{2,20}$").matches(b.originalText.trim())
        }
        if (candidateBlocks.isEmpty()) return@coroutineScope null

        if (prefs.useCustomApi && prefs.apiKey.isNotEmpty()) {
            val llmColloc = solveCollocationWithCustomApi(targetWord, requiredCount, questionBlock, candidateBlocks)
            if (llmColloc != null) return@coroutineScope llmColloc
        }

        val youdaoPhrasesDeferred = async(Dispatchers.IO) {
            queryYoudaoCollocationText(targetWord)
        }
        val datamuseCollocDeferred = async(Dispatchers.IO) {
            queryDatamuseCollocations(targetWord)
        }
        val candZhDeferred = async(Dispatchers.IO) {
            batchTranslateFreeFast(
                candidateBlocks.map { it.originalText.trim() } + listOf(targetWord),
                TranslateDirection.EN_TO_ZH
            )
        }

        val youdaoCollocBlob = youdaoPhrasesDeferred.await().lowercase()
        val datamuseMap = datamuseCollocDeferred.await()
        val zhTranslations = candZhDeferred.await()
        val targetWordZh = zhTranslations.lastOrNull().orEmpty()

        val scoredCandidates = candidateBlocks.mapIndexed { idx, b ->
            val cand = b.originalText.trim().lowercase()
            var score = 0
            if (Regex("\\b$cand\\b").containsMatchIn(youdaoCollocBlob)) {
                score += 60
            }
            score += (datamuseMap[cand] ?: 0)
            val reverseBlob = queryYoudaoCollocationText(cand).lowercase()
            if (Regex("\\b$targetWord\\b").containsMatchIn(reverseBlob)) {
                score += 50
            }
            Triple(idx, cand, score)
        }.sortedByDescending { it.third }

        val chosenSet = scoredCandidates.take(requiredCount).map { it.first }.toSet()
        val chosenWordsStr = scoredCandidates.take(requiredCount).joinToString(" + ") { candidateBlocks[it.first].originalText.trim() }

        val out = mutableListOf<ScreenTextBlock>()
        out.add(
            ScreenTextBlock(
                originalText = questionBlock.originalText,
                bounds = questionBlock.bounds,
                translatedText = "与 $targetWord ($targetWordZh) 搭配的 $requiredCount 个词  ➔  【 $chosenWordsStr 】"
            )
        )

        var badgeOrder = 1
        candidateBlocks.forEachIndexed { idx, b ->
            val zh = zhTranslations.getOrNull(idx).orEmpty()
            val label = if (idx in chosenSet) {
                "★必选${badgeOrder++}: ${b.originalText} ($zh)"
            } else {
                "${b.originalText} ($zh)"
            }
            out.add(
                ScreenTextBlock(
                    originalText = b.originalText,
                    bounds = b.bounds,
                    translatedText = label
                )
            )
        }
        out
    }

    private fun queryYoudaoCollocationText(word: String): String {
        val sb = StringBuilder()
        try {
            val encoded = URLEncoder.encode(word, "UTF-8")
            val url = "https://dict.youdao.com/jsonapi?q=$encoded&dicts=%7B%22count%22%3A99%2C%22dicts%22%3A%5B%5B%22ec%22%2C%22phrs%22%2C%22web_trans%22%2C%22blng_sents_part%22%5D%5D%7D"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
            val bodyStr = fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
            }
            sb.append(bodyStr)
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun queryDatamuseCollocations(word: String): Map<String, Int> {
        val map = HashMap<String, Int>()
        val queries = listOf(
            "https://api.datamuse.com/words?rel_bga=$word&max=40",
            "https://api.datamuse.com/words?rel_bgb=$word&max=40",
            "https://api.datamuse.com/words?rel_trg=$word&max=60",
            "https://api.datamuse.com/words?lc=$word&max=40",
            "https://api.datamuse.com/words?rc=$word&max=40"
        )
        for (url in queries) {
            try {
                val req = Request.Builder().url(url).get().build()
                val bodyStr = fastClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
                }
                if (bodyStr.isNotEmpty()) {
                    val arr = JSONArray(bodyStr)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val w = obj.optString("word").lowercase()
                        if (w.isNotEmpty()) {
                            map[w] = (map[w] ?: 0) + (45 - i.coerceAtMost(35))
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return map
    }

    private fun solveCollocationWithCustomApi(
        targetWord: String,
        requiredCount: Int,
        questionBlock: ScreenTextBlock,
        candidateBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock>? {
        return try {
            val endpoint = prefs.baseUrl.trimEnd('/') + "/chat/completions"
            val options = candidateBlocks.joinToString(", ") { it.originalText.trim() }
            val prompt = "英语固定搭配题：请从候选词 [$options] 中选出最常与 \"$targetWord\" 搭配使用的 $requiredCount 个单词。只输出选中的单词（用英文逗号分隔），不要多余解释。"

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
            val content = JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .lowercase()

            val out = mutableListOf<ScreenTextBlock>()
            out.add(
                ScreenTextBlock(
                    originalText = questionBlock.originalText,
                    bounds = questionBlock.bounds,
                    translatedText = "与 $targetWord 搭配答案  ➔  【 $content 】"
                )
            )
            var idxBadge = 1
            for (b in candidateBlocks) {
                val w = b.originalText.trim().lowercase()
                if (Regex("\\b$w\\b").containsMatchIn(content)) {
                    out.add(ScreenTextBlock(b.originalText, b.bounds, "★必选${idxBadge++}: ${b.originalText}"))
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun trySolveZhToEnExercise(blocks: List<ScreenTextBlock>): List<ScreenTextBlock>? {
        val zhBlocks = blocks.filter { TextCleaner.hasChinese(it.originalText) }
        val enBlocks = blocks.filter { !TextCleaner.hasChinese(it.originalText) && TextCleaner.hasEnglishOrBlankHint(it.originalText) }

        if (zhBlocks.isEmpty()) return null

        val mainZhBlock = zhBlocks.firstOrNull { it.bounds.top > 360 } ?: zhBlocks.first()
        val chinesePrompt = mainZhBlock.originalText.trim()

        if (prefs.useCustomApi && prefs.apiKey.isNotEmpty() && enBlocks.isNotEmpty()) {
            val llmSolved = solveExerciseWithCustomApi(chinesePrompt, mainZhBlock, enBlocks)
            if (llmSolved != null) return llmSolved
        }

        val upperOrSameRowEn = enBlocks.filter { it.bounds.top <= mainZhBlock.bounds.bottom + 60 }
            .sortedBy { it.bounds.left }
        val candidateButtonsBelow = enBlocks.filter { it.bounds.top > mainZhBlock.bounds.bottom + 60 }

        if (candidateButtonsBelow.size >= 2) {
            return solveWordBankCombinationFree(chinesePrompt, mainZhBlock, candidateButtonsBelow)
        }

        if (upperOrSameRowEn.isNotEmpty()) {
            return solvePrefixCompletionFree(chinesePrompt, mainZhBlock, upperOrSameRowEn)
        }

        return null
    }

    private suspend fun solvePrefixCompletionFree(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        enClueBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock> = coroutineScope {
        var hintBlock: ScreenTextBlock? = null
        var prefix = ""
        var expectedLen: Int? = null

        for (b in enClueBlocks) {
            val extracted = TextCleaner.extractPrefixHint(b.originalText)
            if (extracted != null) {
                if (extracted.second == null && extracted.first in COMMON_COMPLETE_WORDS && enClueBlocks.size > 1) {
                    continue
                }
                hintBlock = b
                prefix = extracted.first
                expectedLen = extracted.second
                break
            }
        }

        if (prefix.isEmpty()) {
            for (b in enClueBlocks) {
                val tokens = Regex("[a-zA-Z]{2,12}").findAll(b.originalText).map { it.value }.toList()
                val picked = TextCleaner.findLikelyPrefixToken(tokens, COMMON_COMPLETE_WORDS)
                if (picked != null) {
                    hintBlock = b
                    prefix = picked
                    break
                }
            }
        }

        val contextEnWords = mutableListOf<String>()
        for (b in enClueBlocks) {
            Regex("[a-zA-Z]{2,15}").findAll(b.originalText).forEach { m ->
                val w = m.value.lowercase()
                if (w != prefix && !w.matches(Regex("^[il1]+$"))) {
                    contextEnWords.add(w)
                }
            }
        }

        val contextEnZhList = if (contextEnWords.isNotEmpty()) {
            batchTranslateFreeFast(contextEnWords, TranslateDirection.EN_TO_ZH)
        } else {
            emptyList()
        }

        var targetSlotZh = chinesePrompt
            .replace("某人", "")
            .replace("某事", "")
            .replace("正在", "")
            .replace("做", "")
            .replace(Regex("[.…。，、！？\\s]"), "")

        for (ctxZh in contextEnZhList) {
            val cleanCtx = ctxZh.replace("的", "").trim()
            if (cleanCtx.isNotEmpty() && targetSlotZh.length > cleanCtx.length) {
                targetSlotZh = targetSlotZh.replace(cleanCtx, "")
            }
        }
        if ("government" in contextEnWords) targetSlotZh = targetSlotZh.replace("政府", "")
        if ("stars" in contextEnWords || "star" in contextEnWords) targetSlotZh = targetSlotZh.replace("星星", "").replace("星", "")
        if ("rare" in contextEnWords) targetSlotZh = targetSlotZh.replace("稀有", "").replace("罕见", "")
        if ("problem" in contextEnWords || "problems" in contextEnWords) targetSlotZh = targetSlotZh.replace("问题", "")
        if ("species" in contextEnWords) targetSlotZh = targetSlotZh.replace("物种", "")
        if ("under" in contextEnWords) targetSlotZh = targetSlotZh.replace("在", "").replace("中", "")
        targetSlotZh = targetSlotZh.replace("的", "").trim()
        if (targetSlotZh.isEmpty()) {
            targetSlotZh = chinesePrompt.replace(Regex("[.…。，、！？\\s]"), "")
        }

        val youdaoPrefixDeferred = async(Dispatchers.IO) {
            if (prefix.isNotEmpty()) queryYoudaoPrefixCandidates(prefix) else emptyList()
        }
        val youdaoZhReverseDeferred = async(Dispatchers.IO) {
            queryYoudaoEnglishWordsForChinese(chinesePrompt, targetSlotZh)
        }
        val mtDeferred = async(Dispatchers.IO) {
            batchTranslateFreeFast(listOf(chinesePrompt, targetSlotZh), TranslateDirection.ZH_TO_EN)
        }
        val datamuseDeferred = async(Dispatchers.IO) {
            if (prefix.isNotEmpty()) queryDatamuseContextualPrefix(prefix, expectedLen, contextEnWords) else emptyList()
        }

        val youdaoPrefixList = youdaoPrefixDeferred.await()
        val youdaoZhWords = youdaoZhReverseDeferred.await()
        val mtResults = mtDeferred.await()
        val datamuseWords = datamuseDeferred.await()

        val mtTokens = Regex("[a-zA-Z]{3,}").findAll(mtResults.joinToString(" "))
            .map { it.value.lowercase() }.toSet()

        var bestWord: String? = null

        if (prefix.isNotEmpty()) {
            val minWordLen = (prefix.length + 2).coerceAtLeast(4)
            val directDictMatches = (youdaoZhWords + mtTokens).filter { w ->
                w.startsWith(prefix) && w.length >= minWordLen
            }.distinct()

            val allClueStr = enClueBlocks.joinToString(" ") { it.originalText.lowercase() }

            if (directDictMatches.size == 1) {
                bestWord = directDictMatches.first()
            } else if (directDictMatches.size > 1) {
                bestWord = directDictMatches.maxByOrNull { w ->
                    var s = 0
                    if (allClueStr.contains("under") || allClueStr.contains("the ") || allClueStr.contains("government")) {
                        if (w.endsWith("tion") || w.endsWith("ing") || w.endsWith("val") || w.endsWith("ment")) s += 25
                    }
                    if (allClueStr.contains("doing") || allClueStr.contains("a problem") || allClueStr.contains("sb")) {
                        if (!w.endsWith("tion") && !w.endsWith("ment") && !w.endsWith("ing")) s += 25
                    }
                    if (allClueStr.contains("stars")) {
                        if (w.endsWith("less") || w.endsWith("ous") || w.endsWith("ive")) s += 25
                    }
                    if (expectedLen != null) {
                        s -= abs(w.length - expectedLen!!) * 8
                    }
                    s
                }
            }

            if (bestWord == null) {
                var bestScore = -1
                for ((word, explain) in youdaoPrefixList) {
                    if (!word.startsWith(prefix) || word.length < minWordLen || word.contains(" ") || word.contains("-")) continue
                    var score = 0
                    if (targetSlotZh.length >= 2 && explain.contains(targetSlotZh)) {
                        score += 80
                    } else if (targetSlotZh.length >= 2) {
                        val explainSegments = explain.split(Regex("[；;，,、/（）()\\s]+"))
                        for (seg in explainSegments) {
                            if (seg == targetSlotZh) score += 90
                            else if (seg.length >= 2 && (targetSlotZh.contains(seg) || seg.contains(targetSlotZh))) score += 55
                        }
                    }
                    if (word in datamuseWords) {
                        score += 35
                    }
                    if (allClueStr.contains("under") || (allClueStr.contains("the ") && allClueStr.contains("of"))) {
                        if (explain.contains("n.")) score += 20
                        if (explain.contains("vt.") && !explain.contains("n.")) score -= 15
                    }
                    if (allClueStr.contains("doing") || allClueStr.contains("a problem")) {
                        if (explain.contains("v.") || explain.contains("vt.")) score += 20
                        if (explain.contains("n.") && !explain.contains("v")) score -= 20
                    }
                    if (allClueStr.contains("stars")) {
                        if (explain.contains("adj.")) score += 25
                        if (explain.contains("n.") && !explain.contains("adj.")) score -= 25
                    }
                    if (expectedLen != null) {
                        val diff = abs(word.length - expectedLen!!)
                        if (diff == 0) score += 20
                        else if (diff == 1) score += 8
                        else score -= diff * 6
                    }
                    if (score > bestScore && score >= 25) {
                        bestScore = score
                        bestWord = word
                    }
                }
            }

            if (bestWord == null && datamuseWords.isNotEmpty()) {
                val cands = datamuseWords.filter { it.length >= minWordLen }.take(8)
                if (cands.isNotEmpty()) {
                    val candZh = batchTranslateFreeFast(cands, TranslateDirection.EN_TO_ZH)
                    var bestDmScore = -1
                    cands.forEachIndexed { idx, w ->
                        val zh = candZh.getOrNull(idx).orEmpty()
                        var s = 0
                        if (targetSlotZh.isNotEmpty() && zh.contains(targetSlotZh)) s += 60
                        for (ch in targetSlotZh) {
                            if (ch in zh) s += 12
                        }
                        if (s > bestDmScore) {
                            bestDmScore = s
                            bestWord = w
                        }
                    }
                }
            }
        }

        val out = mutableListOf<ScreenTextBlock>()
        val finalBestWord = bestWord
        val finalHintBlock = hintBlock
        if (finalBestWord != null) {
            val fullPhrase = buildCompletedPhrase(enClueBlocks, finalHintBlock, prefix, finalBestWord)
            if (finalHintBlock != null) {
                out.add(
                    ScreenTextBlock(
                        originalText = finalHintBlock.originalText,
                        bounds = finalHintBlock.bounds,
                        translatedText = "填入: $finalBestWord"
                    )
                )
            }
            out.add(
                ScreenTextBlock(
                    originalText = zhBlock.originalText,
                    bounds = zhBlock.bounds,
                    translatedText = "$chinesePrompt  ➔  $fullPhrase 【补全单词: $finalBestWord】"
                )
            )
        } else {
            val fallbackEn = youdaoZhWords.firstOrNull() ?: mtResults.firstOrNull().orEmpty()
            out.add(
                ScreenTextBlock(
                    originalText = zhBlock.originalText,
                    bounds = zhBlock.bounds,
                    translatedText = "$chinesePrompt  ➔  $fallbackEn"
                )
            )
        }

        out
    }

    private fun buildCompletedPhrase(
        enClueBlocks: List<ScreenTextBlock>,
        hintBlock: ScreenTextBlock?,
        prefix: String,
        completedWord: String
    ): String {
        if (enClueBlocks.isEmpty()) return completedWord
        return enClueBlocks.joinToString(" ") { b ->
            if (b == hintBlock) {
                val replaced = b.originalText.replace(
                    Regex("\\b$prefix\\s*[-_iIl1·.\\s]*", RegexOption.IGNORE_CASE),
                    "$completedWord "
                ).trim()
                if (replaced.contains(completedWord, ignoreCase = true)) replaced else completedWord
            } else {
                b.originalText.trim()
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun queryYoudaoEnglishWordsForChinese(fullZh: String, coreSlotZh: String): List<String> {
        val words = LinkedHashSet<String>()
        val queries = listOf(fullZh, coreSlotZh).filter { it.isNotEmpty() }.distinct()

        for (q in queries) {
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")
                val suggestUrl = "https://dict.youdao.com/suggest?num=15&ver=3.0&doctype=json&cache=false&le=en&q=$encoded"
                val req1 = Request.Builder().url(suggestUrl).header("User-Agent", "Mozilla/5.0").get().build()
                fastClient.newCall(req1).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val entries = JSONObject(body).optJSONObject("data")?.optJSONArray("entries")
                        if (entries != null) {
                            for (i in 0 until entries.length()) {
                                val item = entries.getJSONObject(i)
                                val entry = item.optString("entry")
                                val explain = item.optString("explain")
                                Regex("[a-zA-Z]{3,}").findAll("$entry $explain").forEach { m ->
                                    words.add(m.value.lowercase())
                                }
                            }
                        }
                    }
                }

                val dictUrl = "https://dict.youdao.com/jsonapi?q=$encoded&dicts=%7B%22count%22%3A99%2C%22dicts%22%3A%5B%5B%22ce%22%2C%22web_trans%22%2C%22fanyi%22%5D%5D%7D"
                val req2 = Request.Builder().url(dictUrl).header("User-Agent", "Mozilla/5.0").get().build()
                fastClient.newCall(req2).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        Regex("[a-zA-Z]{3,}").findAll(body).forEach { m ->
                            val w = m.value.lowercase()
                            if (w !in setOf("ce", "web", "trans", "fanyi", "word", "value", "key", "summary", "line", "url", "type")) {
                                words.add(w)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return words.toList()
    }

    /**
     * 获取有道词典针对中文短语返回的完整英文短语列表（保留原始词序，用于给候选词排序！）
     */
    private fun queryYoudaoOrderedPhrasesForChinese(chinesePrompt: String): List<String> {
        val phrases = mutableListOf<String>()
        try {
            val encoded = URLEncoder.encode(chinesePrompt, "UTF-8")
            val dictUrl = "https://dict.youdao.com/jsonapi?q=$encoded&dicts=%7B%22count%22%3A99%2C%22dicts%22%3A%5B%5B%22ce%22%2C%22web_trans%22%2C%22fanyi%22%5D%5D%7D"
            val req = Request.Builder().url(dictUrl).header("User-Agent", "Mozilla/5.0").get().build()
            fastClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    // 提取所有包含至少2个英文单词的连续短语
                    Regex("[a-zA-Z.]+(?:\\s+[a-zA-Z.]+){1,6}").findAll(body).forEach { m ->
                        phrases.add(m.value.lowercase())
                    }
                }
            }
        } catch (_: Exception) {}
        return phrases
    }

    private fun queryYoudaoPrefixCandidates(prefix: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        try {
            val encoded = URLEncoder.encode(prefix, "UTF-8")
            val url = "https://dict.youdao.com/suggest?num=35&ver=3.0&doctype=json&cache=false&le=en&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()
            val bodyStr = fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string().orEmpty()
            }
            val entries = JSONObject(bodyStr).optJSONObject("data")?.optJSONArray("entries") ?: return emptyList()
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val entry = item.optString("entry").trim().lowercase()
                val explain = item.optString("explain").trim()
                if (entry.isNotEmpty() && explain.isNotEmpty()) {
                    results.add(entry to explain)
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun queryDatamuseContextualPrefix(
        prefix: String,
        expectedLen: Int?,
        contextWords: List<String>
    ): List<String> {
        val list = LinkedHashSet<String>()
        val pattern = if (expectedLen != null && expectedLen > prefix.length) {
            prefix + "?".repeat(expectedLen - prefix.length)
        } else {
            "$prefix*"
        }
        val urls = mutableListOf<String>()
        val meaningfulCtx = contextWords.filter { it !in TextCleaner.GRAMMAR_STOP_WORDS }
        for (ctx in meaningfulCtx) {
            urls.add("https://api.datamuse.com/words?sp=$pattern&lc=$ctx&max=10")
            urls.add("https://api.datamuse.com/words?sp=$pattern&rc=$ctx&max=10")
        }
        urls.add("https://api.datamuse.com/words?sp=$prefix*&max=15")

        for (url in urls) {
            try {
                val req = Request.Builder().url(url).get().build()
                val bodyStr = fastClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
                }
                if (bodyStr.isNotEmpty()) {
                    val arr = JSONArray(bodyStr)
                    for (i in 0 until arr.length()) {
                        val w = arr.getJSONObject(i).optString("word").lowercase()
                        if (w.startsWith(prefix)) list.add(w)
                    }
                }
            } catch (_: Exception) {}
        }
        return list.toList()
    }

    /**
     * 解决「选词组短语时前后顺序错乱、位置不对」：
     * 核心改进：
     * 1. 先选出正确的 K 个词（通过中文含义+有道词典反查剔除干扰词）
     * 2. 对这 K 个词的排列顺序，直接用【参考英译短语中的真实先后索引】+【Datamuse 真实英语二元语法紧邻概率 (`lc=prevWord`)】+【英语词性固定句式规则】三重锁定，
     *    彻底杜绝单词前后颠倒！
     */
    private suspend fun solveWordBankCombinationFree(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        candidateBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock> = coroutineScope {
        val candidates = candidateBlocks.map { it.originalText.trim() }
        val n = candidates.size

        val hasSbSthPattern = candidates.any { it.equals("sb.", true) || it.equals("sth.", true) || it.equals("sb", true) || it.equals("sth", true) }
        val hasArticleOrBe = candidates.any { it.lowercase() in setOf("the", "a", "an", "be", "is", "are", "was", "were", "to") }

        val detectedSlots = ScreenOcrManager.lastDetectedBlankSlotCount
        val targetWordCount = when {
            hasSbSthPattern && chinesePrompt.contains("某人") && chinesePrompt.contains("某事") -> n.coerceAtMost(4)
            detectedSlots in 2..n -> detectedSlots
            hasArticleOrBe && n == 4 -> 3
            else -> 2
        }

        // 并发获取：
        // 1. 每个候选词的中文释义
        // 2. 整句中文的机器翻译（保留完整英文语序！）
        // 3. 有道词典关于该中文的所有英文短语（保留完整英文语序！）
        // 4. 每个候选词后面紧跟什么词的 Datamuse 语料库二元语法表 (`lc=cand`)
        val candZhDeferred = async(Dispatchers.IO) {
            batchTranslateFreeFast(candidates, TranslateDirection.EN_TO_ZH)
        }
        val directEnDeferred = async(Dispatchers.IO) {
            batchTranslateFreeFast(listOf(chinesePrompt), TranslateDirection.ZH_TO_EN).firstOrNull()?.lowercase().orEmpty()
        }
        val youdaoPhrasesDeferred = async(Dispatchers.IO) {
            queryYoudaoOrderedPhrasesForChinese(chinesePrompt)
        }
        val bigramMapDeferred = async(Dispatchers.IO) {
            queryBigramFollowerScores(candidates)
        }

        val candZhList = candZhDeferred.await()
        val directEn = directEnDeferred.await()
        val youdaoPhrases = youdaoPhrasesDeferred.await()
        val bigramScores = bigramMapDeferred.await() // Pair(prevWordLower, nextWordLower) -> score

        val referenceCorpus = (listOf(directEn) + youdaoPhrases).joinToString(" | ")
        val permutations = generatePermutations(n, targetWordCount)

        var bestPerm: List<Int> = permutations.firstOrNull() ?: candidates.indices.toList()
        var bestScore = Int.MIN_VALUE

        for (perm in permutations) {
            val words = perm.map { candidates[it] }
            val lowerWords = words.map { it.lowercase().removeSuffix(".") }
            val joinedPerm = lowerWords.joinToString(" ")
            var score = 0

            // 一、整句语序直接命中：如果该排列完整出现在参考英译或有道短语库中，直接加 300 分！
            if (referenceCorpus.contains(joinedPerm)) {
                score += 300
            }

            // 二、成对先后顺序比对：检查任意两个位置 (p < q) 的词在参考英译中的先后顺序是否一致！
            for (p in 0 until lowerWords.size) {
                for (q in (p + 1) until lowerWords.size) {
                    val w1 = lowerWords[p]
                    val w2 = lowerWords[q]
                    for (ref in (listOf(directEn) + youdaoPhrases)) {
                        val idx1 = Regex("\\b${Regex.escape(w1)}\\b").find(ref)?.range?.first ?: -1
                        val idx2 = Regex("\\b${Regex.escape(w2)}\\b").find(ref)?.range?.first ?: -1
                        if (idx1 != -1 && idx2 != -1) {
                            if (idx1 < idx2) score += 45 // 顺序与标准英译一致
                            else score -= 55             // 顺序与标准英译颠倒，重罚！
                        }
                    }
                }
            }

            // 三、Datamuse 真实英语二元语法紧邻得分（判断 word[k] 后面紧接 word[k+1] 是否符合自然英语习惯）
            for (k in 0 until lowerWords.size - 1) {
                val w1 = lowerWords[k]
                val w2 = lowerWords[k + 1]
                score += (bigramScores[w1 to w2] ?: 0)
                // 如果颠倒过来 (w2 -> w1) 的二元概率远高于 (w1 -> w2)，扣分！
                val reverseBigram = bigramScores[w2 to w1] ?: 0
                if (reverseBigram > (bigramScores[w1 to w2] ?: 0) + 15) {
                    score -= 35
                }
            }

            // 四、选词准确性打分（剔除与中文无关的干扰词，如 amount）
            for (idx in perm) {
                val wLower = candidates[idx].lowercase().removeSuffix(".")
                val wZh = candZhList.getOrNull(idx).orEmpty()

                if (wLower == "sb" && chinesePrompt.contains("某人")) score += 50
                if (wLower == "sth" && (chinesePrompt.contains("某事") || chinesePrompt.contains("某物"))) score += 50
                if (wLower == "doing" && (chinesePrompt.contains("正在") || chinesePrompt.contains("做"))) score += 45

                var matchedChar = 0
                for (ch in chinesePrompt) {
                    if (ch != '的' && ch != '在' && ch in wZh) matchedChar++
                }
                if (matchedChar > 0) {
                    score += matchedChar * 24
                } else if (wLower !in TextCleaner.GRAMMAR_STOP_WORDS) {
                    if (!referenceCorpus.contains(wLower.take(4))) {
                        score -= 50
                    }
                }
                if (Regex("\\b${Regex.escape(wLower)}\\b").containsMatchIn(referenceCorpus)) {
                    score += 30
                }
            }

            // 五、铁律语法位置约束
            val firstW = lowerWords.first()
            val lastW = lowerWords.last()

            // 1. 冠词 (the / a / an) 只能放在开头或介词 (of/in/on/for/to/at/with/from) 后面，绝不能放在名词后面或句尾！
            for (pos in lowerWords.indices) {
                if (lowerWords[pos] in setOf("the", "a", "an", "one's", "my", "his", "her", "their", "our", "its")) {
                    if (pos == 0) {
                        score += 45
                    } else if (lowerWords[pos - 1] in setOf("of", "in", "on", "at", "for", "to", "with", "from", "by", "into", "under", "over")) {
                        score += 35
                    } else {
                        score -= 100
                    }
                }
            }
            if (lastW in setOf("the", "a", "an", "of", "to", "for", "with", "under", "in", "on", "at", "by")) {
                score -= 120
            }

            // 2. be / 助动词 / 动词原形位置约束：
            // 若短语以“动词+宾语”（如 observe sb. doing sth. / make a decision）构成，动词排在首位
            val sbPos = lowerWords.indexOf("sb")
            val doingPos = lowerWords.indexOf("doing")
            val sthPos = lowerWords.indexOf("sth")
            if (sbPos != -1 && doingPos != -1 && sthPos != -1) {
                if (sbPos == 1 && doingPos == 2 && sthPos == 3) score += 150
                else score -= 90
            } else if (sbPos != -1 && sthPos != -1) {
                if (sbPos < sthPos && sbPos > 0) score += 60 else score -= 60
            }

            // 3. 当没有介词 of 时，中文“A B”（如“完工日期”，A=完工 completion，B=日期 date），英文也是修饰词 A 在前、中心词 B 在后 (`the completion date`)！
            if ("of" !in lowerWords && lowerWords.size >= 2) {
                val lastCandZh = candZhList.getOrNull(perm.last()).orEmpty()
                val prevCandZh = candZhList.getOrNull(perm[perm.size - 2]).orEmpty()
                val zhTail = chinesePrompt.takeLast(2)
                val zhHead = chinesePrompt.take(2)
                if (zhTail.length == 2 && lastCandZh.contains(zhTail)) score += 55
                if (zhHead.length == 2 && prevCandZh.contains(zhHead)) score += 45
                // 如果把中心词（日期 date）放到了修饰词（完工 completion）前面且中间没有 of，重罚！
                if (zhTail.length == 2 && prevCandZh.contains(zhTail) && !lastCandZh.contains(zhTail)) {
                    score -= 70
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestPerm = perm
            }
        }

        val finalPhrase = bestPerm.joinToString(" ") { candidates[it] }
        val orderBadges = arrayOf("①", "②", "③", "④", "⑤", "⑥")

        val outputBlocks = mutableListOf<ScreenTextBlock>()
        outputBlocks.add(
            ScreenTextBlock(
                originalText = zhBlock.originalText,
                bounds = zhBlock.bounds,
                translatedText = "$chinesePrompt  ➔  $finalPhrase"
            )
        )

        bestPerm.forEachIndexed { orderIdx, candIdx ->
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
     * 查询候选词两两之间的真实英语相邻概率 (`lc=prevWord`)
     */
    private suspend fun queryBigramFollowerScores(candidates: List<String>): Map<Pair<String, String>, Int> = coroutineScope {
        val cleanCands = candidates.map { it.lowercase().removeSuffix(".") }.toSet()
        val map = HashMap<Pair<String, String>, Int>()
        val jobs = cleanCands.filter { it !in setOf("sb", "sth") }.map { w1 ->
            async(Dispatchers.IO) {
                try {
                    val url = "https://api.datamuse.com/words?lc=${URLEncoder.encode(w1, "UTF-8")}&max=50"
                    val req = Request.Builder().url(url).get().build()
                    val body = fastClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
                    }
                    if (body.isNotEmpty()) {
                        val arr = JSONArray(body)
                        for (i in 0 until arr.length()) {
                            val w2 = arr.getJSONObject(i).optString("word").lowercase()
                            if (w2 in cleanCands) {
                                synchronized(map) {
                                    map[w1 to w2] = 45 - (i / 2)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        jobs.awaitAll()
        map
    }

    private fun generatePermutations(n: Int, k: Int): List<List<Int>> {
        val res = mutableListOf<List<Int>>()
        val used = BooleanArray(n)
        val current = IntArray(k)
        fun backtrack(depth: Int) {
            if (depth == k) {
                res.add(current.toList())
                return
            }
            for (i in 0 until n) {
                if (!used[i]) {
                    used[i] = true
                    current[depth] = i
                    backtrack(depth + 1)
                    used[i] = false
                }
            }
        }
        backtrack(0)
        return res
    }

    private fun solveExerciseWithCustomApi(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        enBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock>? {
        return try {
            val endpoint = prefs.baseUrl.trimEnd('/') + "/chat/completions"
            val enClues = enBlocks.sortedBy { it.bounds.top * 1000 + it.bounds.left }
                .joinToString(" | ") { it.originalText.trim() }
            val slotCount = ScreenOcrManager.lastDetectedBlankSlotCount

            val prompt = "这是一个英语词汇练习页面，中文题目：「$chinesePrompt」，页面上的英文线索或候选词：「$enClues」，题目横线槽数参考：$slotCount。1. 若英文线索含首字母前缀，请根据中文和固定搭配补全该单词并输出完整英文短语；2. 若英文线索是多个候选单词按钮，请严格按横线数量和正确英语语法顺序用候选词组合出对应中文题目的英文短语。只输出一行结果，不要多余解释。"

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

            val answerWords = Regex("[a-zA-Z.]+").findAll(answer).map { it.value.lowercase() }.toList()
            val orderBadges = arrayOf("①", "②", "③", "④", "⑤", "⑥")
            var step = 0
            for (w in answerWords) {
                val matchedBlock = enBlocks.firstOrNull {
                    it.originalText.trim().equals(w, ignoreCase = true) ||
                        it.originalText.trim().removeSuffix(".").equals(w.removeSuffix("."), ignoreCase = true)
                }
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
                    if (direction == TranslateDirection.EN_TO_ZH) {
                        val yd = singleYoudaoTranslate(line)
                        if (TextCleaner.hasChinese(yd)) yd else singleMyMemory(line, direction)
                    } else {
                        singleMyMemory(line, direction)
                    }
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

    /**
     * 兼容半角 [1] 与中文全角 【1】 编号解析，防止 Google 翻译把 [1] 转成 【1】 后解析失败导致句子只翻一半！
     */
    private fun parseNumberedOutput(rawOutput: String, originalLines: List<String>): List<String>? {
        val resultMap = HashMap<Int, String>()
        val regex = Regex("[\\[【]\\s*(\\d+)\\s*[\\]】]\\s*([^\\[【]*)")
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

    /**
     * 有道词典整句翻译接口（国内直连毫秒级响应，专门用于长句完整翻译与兜底）
     */
    private fun singleYoudaoTranslate(text: String): String {
        return try {
            val encoded = URLEncoder.encode(text.take(300), "UTF-8")
            val url = "https://dict.youdao.com/jsonapi?q=$encoded&dicts=%7B%22count%22%3A99%2C%22dicts%22%3A%5B%5B%22fanyi%22%2C%22ec%22%5D%5D%7D"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
            val bodyStr = fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                resp.body?.string().orEmpty()
            }
            val json = JSONObject(bodyStr)
            val fanyiTran = json.optJSONObject("fanyi")?.optString("tran")?.trim().orEmpty()
            if (fanyiTran.isNotEmpty()) return fanyiTran

            val ecWord = json.optJSONObject("ec")?.optJSONArray("word")?.optJSONObject(0)
            val trs = ecWord?.optJSONArray("trs")
            if (trs != null && trs.length() > 0) {
                return trs.getJSONObject(0).optJSONArray("tr")
                    ?.optJSONObject(0)?.optJSONObject("l")?.optJSONArray("i")
                    ?.optString(0).orEmpty()
            }
            ""
        } catch (_: Exception) {
            ""
        }
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
