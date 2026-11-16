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

        private val GRAMMAR_STOP_WORDS = setOf(
            "an", "a", "the", "of", "to", "in", "on", "at", "by", "for", "with", "from", "into",
            "sb", "sth", "doing", "do", "one", "ones", "one's", "someone", "something"
        )
    }

    private val concurrencyGate = Semaphore(12)

    suspend fun translateBlocks(
        blocks: List<ScreenTextBlock>,
        direction: TranslateDirection
    ): List<ScreenTextBlock> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()

        // 在「中→英」模式下，优先检测是否是词汇练习题（首字母补全单词 / 候选词组合短语）
        if (direction == TranslateDirection.ZH_TO_EN) {
            val solvedQuiz = trySolveZhToEnExercise(blocks)
            if (solvedQuiz != null && solvedQuiz.isNotEmpty()) {
                return@withContext solvedQuiz
            }
        }

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
     * 智能识别并解答「中→英」短语填空与选词组合题
     */
    private suspend fun trySolveZhToEnExercise(blocks: List<ScreenTextBlock>): List<ScreenTextBlock>? {
        val zhBlocks = blocks.filter { TextCleaner.hasChinese(it.originalText) }
        val enBlocks = blocks.filter { !TextCleaner.hasChinese(it.originalText) && TextCleaner.hasEnglishOrBlankHint(it.originalText) }

        if (zhBlocks.isEmpty()) return null

        // 选择正文中真正的中文题目（跳过顶部残留的短导航词，优先选 y > 380 的中文块）
        val mainZhBlock = zhBlocks.firstOrNull { it.bounds.top > 380 } ?: zhBlocks.first()
        val chinesePrompt = mainZhBlock.originalText.trim()

        // 1. 若用户配置了自定义大模型 API，优先调用大模型一次性精准推理
        if (prefs.useCustomApi && prefs.apiKey.isNotEmpty() && enBlocks.isNotEmpty()) {
            val llmSolved = solveExerciseWithCustomApi(chinesePrompt, mainZhBlock, enBlocks)
            if (llmSolved != null) return llmSolved
        }

        // 2. 题型 A：中文题目上方（或同一水平高度）有首字母填空提示，例如：
        // - "an  ext - - - -  species" (已灭绝的物种)
        // - "obs - - - -  sb. doing sth." (看到某人正在做某事)
        // - "obs - - - - - - - -" (观察)
        // - "an  ar - - -  of ..." (大量......)
        val upperOrSameRowEn = enBlocks.filter { it.bounds.top <= mainZhBlock.bounds.bottom + 60 }
            .sortedBy { it.bounds.left }

        val candidateButtonsBelow = enBlocks.filter { it.bounds.top > mainZhBlock.bounds.bottom + 60 }

        if (upperOrSameRowEn.isNotEmpty() && candidateButtonsBelow.size < 2) {
            return solvePrefixCompletionFree(chinesePrompt, mainZhBlock, upperOrSameRowEn)
        }

        // 3. 题型 B：中文题目下方有多个英文候选按钮（如 observation / observe / specimen / under）
        if (candidateButtonsBelow.size >= 2) {
            return solveWordBankCombinationFree(chinesePrompt, mainZhBlock, candidateButtonsBelow)
        }

        return null
    }

    /**
     * 免费模式下精准解答「首字母提示补全单词」：
     * 结合 有道词典联想接口 (`dict.youdao.com/suggest`, 自带中英释义对照) + Datamuse 词库 + 整句机器翻译，
     * 彻底解决机器翻译选词不一致（如把 observe 译成 see、把 array 译成 a lot of）和 OCR 漏识别提示字母的问题。
     */
    private suspend fun solvePrefixCompletionFree(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        enClueBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock> = coroutineScope {
        // 1. 提取页面上的首字母前缀块（如 "obs - - - -"、"obs iil"、"ar - - -"、"ext"）
        var hintBlock: ScreenTextBlock? = null
        var prefix = ""
        var expectedLen: Int? = null

        for (b in enClueBlocks) {
            val extracted = TextCleaner.extractPrefixHint(b.originalText)
            if (extracted != null) {
                hintBlock = b
                prefix = extracted.first
                expectedLen = extracted.second
                break
            }
        }

        // 如果 OCR 只识别出了单个孤立前缀词（或把多个词拼在了一行，如 "an ar --- of ..." 或 "obs sb. doing sth."）
        if (prefix.isEmpty()) {
            for (b in enClueBlocks) {
                val tokens = Regex("[a-zA-Z]{2,6}").findAll(b.originalText).map { it.value.lowercase() }.toList()
                val candidatePrefix = tokens.firstOrNull { it !in GRAMMAR_STOP_WORDS }
                if (candidatePrefix != null && candidatePrefix.length in 2..5) {
                    hintBlock = b
                    prefix = candidatePrefix
                    break
                }
            }
        }

        // 提取中文核心词（去掉“某人”、“正在”、“做某事”、“的”、“……”等虚词，用于和有道词典中文释义比对）
        val cleanZhCore = chinesePrompt
            .replace("某人", "")
            .replace("某事", "")
            .replace("正在", "")
            .replace("做", "")
            .replace(Regex("[.…。，、！？\\s]"), "")
            .trim()

        // 并发请求：
        // (1) 有道词典前缀联想（返回以 prefix 开头的高频词及其精准中文释义）
        // (2) 中文短语直接机器翻译
        // (3) Datamuse 按长度/前缀查询候选词
        val youdaoDeferred = async(Dispatchers.IO) {
            if (prefix.isNotEmpty()) queryYoudaoPrefixCandidates(prefix) else emptyList()
        }
        val mtDeferred = async(Dispatchers.IO) {
            batchTranslateFreeFast(listOf(chinesePrompt), TranslateDirection.ZH_TO_EN)
                .firstOrNull()?.lowercase().orEmpty()
        }
        val datamuseDeferred = async(Dispatchers.IO) {
            if (prefix.isNotEmpty()) queryDatamusePrefixList(prefix, expectedLen) else emptyList()
        }

        val youdaoList = youdaoDeferred.await() // List<Pair<word, chineseExplain>>
        val mtPhrase = mtDeferred.await()
        val datamuseWords = datamuseDeferred.await()

        var bestWord: String? = null

        if (prefix.isNotEmpty()) {
            // 优先级 1：从有道词典前缀联想结果中，匹配中文释义与题目中文重合度最高且满足长度的单词！
            // 例如：
            // - prefix="obs", 题目="看到某人正在做某事"(含"看"/"观察") -> 有道返回 observe ("vt. 观察；看到；注意到") 满分命中！
            // - prefix="obs", 题目="观察"(名词/动词，且横线长) -> observation ("n. 观察；观测") / observe 命中！
            // - prefix="ar", 题目="大量……" + 上下文 "an ___ of" -> array ("n. 大量；一系列；数组") 满分命中！
            // - prefix="ext", 题目="已灭绝的物种" -> extinct ("adj. 灭绝的；绝种的") 满分命中！
            var bestScore = -1
            val allClueText = enClueBlocks.joinToString(" ") { it.originalText.lowercase() }

            for ((word, explain) in youdaoList) {
                if (!word.startsWith(prefix) || word.contains(" ") || word.contains("-")) continue
                var score = 0

                // 检查中文题目字词是否出现在词典释义中
                val checkTarget = if (cleanZhCore.isNotEmpty()) cleanZhCore else chinesePrompt
                for (ch in checkTarget) {
                    if (ch in explain) score += 10
                }
                // 常见同义释义映射（如题目写“看到”，词典写“观察/注意到”；题目写“大量”，词典写“一系列/大批/数组”）
                if (chinesePrompt.contains("看") && (explain.contains("观察") || explain.contains("注意") || explain.contains("看"))) {
                    score += 25
                }
                if (chinesePrompt.contains("大量") && (explain.contains("大量") || explain.contains("一系列") || explain.contains("大批") || word == "array")) {
                    score += 30
                }

                // 结合页面语法线索加分：
                // 如 "sb. doing" 需要动词原形/及物动词 (vt./v.)，排除名词 observation
                if (allClueText.contains("doing") || allClueText.contains("sb")) {
                    if (explain.contains("vt.") || explain.contains("vi.") || explain.contains("v.")) score += 15
                    if (word.endsWith("tion") || word.endsWith("ment")) score -= 20
                }
                // 如 "an ___ of" 需要可数名词单数 (n.)
                if (allClueText.contains("an ") && allClueText.contains("of")) {
                    if (explain.contains("n.")) score += 15
                }

                // 如果横线长度明确，优先匹配长度接近的词
                if (expectedLen != null) {
                    val diff = abs(word.length - expectedLen)
                    if (diff == 0) score += 18
                    else if (diff == 1) score += 8
                    else score -= diff * 4
                }

                if (score > bestScore) {
                    bestScore = score
                    bestWord = word
                }
            }

            // 优先级 2：检查整句机器翻译里是否直接有以 prefix 开头的词
            if (bestWord == null || bestScore <= 0) {
                val mtWords = Regex("[a-zA-Z]+").findAll(mtPhrase).map { it.value.lowercase() }.toList()
                val mtMatch = mtWords.firstOrNull { it.startsWith(prefix) && it.length > prefix.length }
                if (mtMatch != null) bestWord = mtMatch
            }

            // 优先级 3：如果仍未命中，将 Datamuse 候选词并发翻译成中文做二次比对
            if (bestWord == null && datamuseWords.isNotEmpty()) {
                val topCandidates = datamuseWords.take(6)
                val candZhList = batchTranslateFreeFast(topCandidates, TranslateDirection.EN_TO_ZH)
                var dmBestScore = -1
                topCandidates.forEachIndexed { idx, w ->
                    val zh = candZhList.getOrNull(idx).orEmpty()
                    var s = 0
                    for (ch in chinesePrompt) {
                        if (ch in zh) s += 10
                    }
                    if (s > dmBestScore) {
                        dmBestScore = s
                        bestWord = w
                    }
                }
            }
        }

        // 组装最终显示的完整短语与补全提示
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
            // 即使 OCR 完全没拍到浅灰色前缀（比如只拍到了 "sb. doing sth." 和 "看到某人正在做某事"），
            // 也利用有道词典查询中文短语给出地道英文搭配，绝不空白无输出！
            val youdaoPhrase = queryYoudaoChineseToEnglishPhrase(chinesePrompt, cleanZhCore)
            val displayEn = youdaoPhrase.ifEmpty { mtPhrase.ifEmpty { "请双击悬浮球切换节点/识图重试" } }
            out.add(
                ScreenTextBlock(
                    originalText = zhBlock.originalText,
                    bounds = zhBlock.bounds,
                    translatedText = "$chinesePrompt  ➔  $displayEn"
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
                // 如果该块里除了前缀还有别的词（如 "an ar - - - of ..."），只替换前缀部分
                val replaced = b.originalText.replace(
                    Regex("\\b$prefix\\s*[-_iIl1\\s.]*", RegexOption.IGNORE_CASE),
                    "$completedWord "
                ).trim()
                if (replaced.contains(completedWord, ignoreCase = true)) replaced else completedWord
            } else {
                b.originalText.trim()
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 调用有道词典公开免费的 Suggest 接口，获取以 prefix 开头的英文单词列表及其真实中文释义
     */
    private fun queryYoudaoPrefixCandidates(prefix: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        try {
            val encoded = URLEncoder.encode(prefix, "UTF-8")
            val url = "https://dict.youdao.com/suggest?num=25&ver=3.0&doctype=json&cache=false&le=en&q=$encoded"
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

    /**
     * 当 OCR 漏掉浅灰色首字母时，直接查有道词典中文词条获取常见英文短语搭配
     */
    private fun queryYoudaoChineseToEnglishPhrase(fullZh: String, coreZh: String): String {
        val queries = listOf(fullZh, coreZh).filter { it.isNotEmpty() }.distinct()
        val found = mutableListOf<String>()
        for (q in queries) {
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")
                val url = "https://dict.youdao.com/suggest?num=6&ver=3.0&doctype=json&cache=false&le=en&q=$encoded"
                val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
                val bodyStr = fastClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
                }
                if (bodyStr.isNotEmpty()) {
                    val entries = JSONObject(bodyStr).optJSONObject("data")?.optJSONArray("entries")
                    if (entries != null) {
                        for (i in 0 until minOf(3, entries.length())) {
                            val explain = entries.getJSONObject(i).optString("explain")
                            if (explain.isNotEmpty()) found.add(explain)
                        }
                    }
                }
                if (found.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        return found.joinToString(" / ")
    }

    private fun queryDatamusePrefixList(prefix: String, expectedLen: Int?): List<String> {
        return try {
            val pattern = if (expectedLen != null && expectedLen > prefix.length) {
                prefix + "?".repeat(expectedLen - prefix.length)
            } else {
                "$prefix*"
            }
            val url = "https://api.datamuse.com/words?sp=$pattern&max=12"
            val req = Request.Builder().url(url).get().build()
            val bodyStr = fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string().orEmpty()
            }
            val arr = JSONArray(bodyStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i).optString("word")
                if (w.isNotEmpty()) list.add(w)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 题型 2：用页面给定的英文候选单词组合成对应中文短语（并标出 ① ② 顺序）
     */
    private suspend fun solveWordBankCombinationFree(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        candidateBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock> = coroutineScope {
        val candidates = candidateBlocks.map { it.originalText.trim() }

        val directEnDeferred = async(Dispatchers.IO) {
            batchTranslateFreeFast(listOf(chinesePrompt), TranslateDirection.ZH_TO_EN)
                .firstOrNull()?.lowercase().orEmpty()
        }

        val pairPhrases = mutableListOf<List<Int>>()
        for (i in candidates.indices) {
            for (j in candidates.indices) {
                if (i != j) pairPhrases.add(listOf(i, j))
            }
        }

        val directEn = directEnDeferred.await()
        val directTokens = Regex("[a-zA-Z]+").findAll(directEn).map { it.value.lowercase() }.toList()

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
            val comboStrings = pairPhrases.map { idxList -> idxList.joinToString(" ") { candidates[it] } }
            val comboZhList = batchTranslateFreeFast(comboStrings, TranslateDirection.EN_TO_ZH)

            var bestScore = -1
            var bestPair: List<Int> = exactIndices.ifEmpty { listOf(0) }

            pairPhrases.forEachIndexed { pairIdx, idxList ->
                val zhTrans = comboZhList.getOrNull(pairIdx).orEmpty()
                val comboEn = comboStrings[pairIdx].lowercase()
                var score = 0
                for (ch in chinesePrompt) {
                    if (ch in zhTrans) score += 3
                }
                if (zhTrans == chinesePrompt) score += 10
                for (tok in directTokens) {
                    if (tok.length >= 3 && comboEn.contains(tok.take(4))) score += 4
                }
                val firstWord = candidates[idxList[0]].lowercase()
                if (firstWord in setOf("under", "in", "on", "at", "by", "for", "with", "without", "beyond", "within", "out", "from", "to")) {
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
        outputBlocks.add(
            ScreenTextBlock(
                originalText = zhBlock.originalText,
                bounds = zhBlock.bounds,
                translatedText = "$chinesePrompt  ➔  $finalPhrase"
            )
        )

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

    private fun solveExerciseWithCustomApi(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        enBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock>? {
        return try {
            val endpoint = prefs.baseUrl.trimEnd('/') + "/chat/completions"
            val enClues = enBlocks.sortedBy { it.bounds.top * 1000 + it.bounds.left }
                .joinToString(" | ") { it.originalText.trim() }

            val prompt = "这是一个英语词汇练习页面，中文题目：「$chinesePrompt」，页面上的英文线索或候选词：「$enClues」。1. 若英文线索含首字母前缀（如 an | ext - - - - | species 或 obs - - - - | sb. doing sth. 或 an | ar - - - | of ...），请根据中文和固定搭配补全该单词并输出完整英文短语（并在括号内注明填入的完整单词）；2. 若英文线索是多个候选单词，请严格用候选词组合出对应中文题目的英文短语。只输出一行结果，不要多余解释。"

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
