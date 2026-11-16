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

        // 常见已完整高频词（避免把句子里已有的完整词如 rare, government, stars, problem 当成残缺前缀）
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

        // 优先检测是否是「Choose the words commonly used together with ...」单词搭配题（无论当前是英→中还是中→英模式都自动解答！）
        val collocationSolved = trySolveCollocationQuestion(blocks)
        if (collocationSolved != null && collocationSolved.isNotEmpty()) {
            return@withContext collocationSolved
        }

        // 在「中→英」模式下，检测是否是词汇练习题（首字母补全单词 / 候选词按横线数量组合短语）
        if (direction == TranslateDirection.ZH_TO_EN) {
            val solvedQuiz = trySolveZhToEnExercise(blocks)
            if (solvedQuiz != null && solvedQuiz.isNotEmpty()) {
                return@withContext solvedQuiz
            }
        }

        val targetBlocks = if (direction == TranslateDirection.ZH_TO_EN) {
            blocks.filter { TextCleaner.hasChinese(it.originalText) }
        } else {
            blocks.filter { TextCleaner.shouldTranslate(it.originalText, true) }
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
     * 题型：单词固定搭配题（如第3张图："Choose the words commonly used together with survival"，提示：本题有2个答案）
     * 同时给出每个选项的中文释义，并用「★必选」直接标出与目标词搭配最紧密的 N 个答案！
     */
    private suspend fun trySolveCollocationQuestion(blocks: List<ScreenTextBlock>): List<ScreenTextBlock>? = coroutineScope {
        val fullScreenText = blocks.joinToString(" ") { it.originalText }
        val mColloc = Regex("together\\s+with\\s+([a-zA-Z]{2,20})", RegexOption.IGNORE_CASE).find(fullScreenText)
            ?: return@coroutineScope null

        val targetWord = mColloc.groupValues[1].lowercase()
        val mNeedCount = Regex("(?:有|选中)\\s*(\\d+)\\s*个").find(fullScreenText)
        val requiredCount = mNeedCount?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 2

        // 找出下方所有的英文候选单词按钮
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

        // 若开启了自定义大模型 API，优先直接询问大模型该搭配题的答案
        if (prefs.useCustomApi && prefs.apiKey.isNotEmpty()) {
            val llmColloc = solveCollocationWithCustomApi(targetWord, requiredCount, questionBlock, candidateBlocks)
            if (llmColloc != null) return@coroutineScope llmColloc
        }

        // 免费模式：并发查询
        // 1. 有道词典 web_trans / 短语库中关于 targetWord 的固定搭配词组
        // 2. Datamuse 语料库搭配接口 (前置词 rel_bgb、后置词 rel_bga、触发关联词 rel_trg)
        // 3. 候选词与 targetWord 的中文释义
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

        // 为每个候选按钮计算固定搭配得分
        val scoredCandidates = candidateBlocks.mapIndexed { idx, b ->
            val cand = b.originalText.trim().lowercase()
            var score = 0

            // A. 有道词典固定短语库中同时出现 cand 与 targetWord（如 "struggle for survival", "survival of the fittest"）
            if (Regex("\\b$cand\\b").containsMatchIn(youdaoCollocBlob)) {
                score += 60
            }
            // B. Datamuse 语料库共现分
            score += (datamuseMap[cand] ?: 0)

            // C. 反向查该候选词自身的有道搭配中是否包含 targetWord
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

    /**
     * 从有道词典 JSON 接口拉取指定单词的所有网络短语、双语例句与固定搭配文本
     */
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

    /**
     * 智能识别并解答「中→英」短语填空与选词组合题
     */
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

        // 题型 B 优先判断：如果中文题目下方有 >= 2 个候选按钮（选词填横线题）
        if (candidateButtonsBelow.size >= 2) {
            return solveWordBankCombinationFree(chinesePrompt, mainZhBlock, candidateButtonsBelow)
        }

        // 题型 A：中文题目上方有英文线索或首字母填空提示
        if (upperOrSameRowEn.isNotEmpty()) {
            return solvePrefixCompletionFree(chinesePrompt, mainZhBlock, upperOrSameRowEn)
        }

        return null
    }

    /**
     * 深度优化的「3字母提示补齐单词」算法：
     * 针对全部 6 类真实错例逐一攻克：
     * 1. `government fun - - - -` (政府拨款) -> 避免漏识别浅灰 `fun`，并结合有道词组库匹配 `government funding`
     * 2. `cou - - - - - - stars` (无数的星星) -> 必须要求完整词义命中“无数”(`countless`)，绝不因为单字“数”误选名词 `counter`(计数器)
     * 3. `a rare spe - - - -` (稀有物种) -> 排除已知完整词 `a`、`rare`，锁定残缺前缀 `spe`，补全 `species`
     * 4. `the sur - - - - of the fittest` (适者生存) -> 要求补全词长度严格 > 前缀长度 (`len > 4`) 且匹配 `survival of the fittest`，绝不选 4 字母的 `sure`
     * 5. `ove - - - - a problem` (忽视问题) -> 完整匹配“忽视/忽略”(`overlook`)，绝不因为单字误选 `overlap`(重叠)
     * 6. `under obs - - - - - - - -` (在观察中) -> `under` 已加入介词停用表，锁定 `obs` + 介词后接名词规则 -> `observation`
     */
    private suspend fun solvePrefixCompletionFree(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        enClueBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock> = coroutineScope {
        var hintBlock: ScreenTextBlock? = null
        var prefix = ""
        var expectedLen: Int? = null

        // 第一步：先找带明显横线/虚线特征的块 (如 "fun - - - -", "cou - - - - - -", "spe - - - -")
        for (b in enClueBlocks) {
            val extracted = TextCleaner.extractPrefixHint(b.originalText)
            if (extracted != null) {
                // 如果这个词本身是句子里已经完整的常见词（如 "rare"、"stars"），跳过它继续找真正的残缺前缀
                if (extracted.second == null && extracted.first in COMMON_COMPLETE_WORDS && enClueBlocks.size > 1) {
                    continue
                }
                hintBlock = b
                prefix = extracted.first
                expectedLen = extracted.second
                break
            }
        }

        // 第二步：如果 OCR 把整行合并成了一个块（如 "a rare spe"、"under obs"、"government fun"），从中挑出残缺的 2~4 字母前缀
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

        // 提取页面上除残缺前缀外的其它已知英文上下文词（如 "government", "stars", "rare", "fittest", "problem", "under"）
        val contextEnWords = mutableListOf<String>()
        for (b in enClueBlocks) {
            Regex("[a-zA-Z]{2,15}").findAll(b.originalText).forEach { m ->
                val w = m.value.lowercase()
                if (w != prefix && !w.matches(Regex("^[il1]+$"))) {
                    contextEnWords.add(w)
                }
            }
        }

        // 提取中文里尚未被已知英文上下文覆盖的“待填词核心中文意思”！
        // 例如：
        // - 题目「政府拨款」，已知上下文有 "government"(政府) -> 待填词核心意思是「拨款」
        // - 题目「无数的星星」，已知上下文有 "stars"(星星) -> 待填词核心意思是「无数」
        // - 题目「稀有物种」，已知上下文有 "rare"(稀有) -> 待填词核心意思是「物种」
        // - 题目「忽视问题」，已知上下文有 "problem"(问题) -> 待填词核心意思是「忽视」
        // - 题目「在观察中」，已知上下文有 "under"(在…中) -> 待填词核心意思是「观察」
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
        // 常见固定上下文中文剥离
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

        // 并发发起 4 路高精度词库查询：
        // 1. 有道前缀联想 (`q=prefix`, num=40)
        // 2. 有道中文反查英文词条 (`q=targetSlotZh` 和 `q=chinesePrompt`) —— 直接拿到中文「拨款 / 无数 / 物种 / 适者生存 / 忽视 / 观察」对应的所有地道英文单词和短语！
        // 3. 机器翻译整句中文
        // 4. Datamuse 以 `prefix*` 开头且与上下文词搭配的词表 (`sp=prefix*&lc=...&rc=...`)
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
        val youdaoZhWords = youdaoZhReverseDeferred.await() // 从中文词典反查出的所有英文词/短语词元
        val mtResults = mtDeferred.await()
        val datamuseWords = datamuseDeferred.await()

        val mtTokens = Regex("[a-zA-Z]{3,}").findAll(mtResults.joinToString(" "))
            .map { it.value.lowercase() }.toSet()

        var bestWord: String? = null

        if (prefix.isNotEmpty()) {
            // 候选词汇总：要求单词必须以 prefix 开头，且长度必须至少比 prefix 多 2 个字母（彻底杜绝 sur -> sure 这种只多1个字母的假匹配）
            val minWordLen = (prefix.length + 2).coerceAtLeast(4)

            // 通道 A（最准）：有道中文词典反查出的英文单词 / 机器翻译结果中，直接存在以 prefix 开头的单词！
            // 例如：
            // - 查「政府拨款 / 拨款」-> 有道返回 "government funding / funding / fund" -> 直接命中以 "fun" 开头的 "funding"！
            // - 查「无数的星星 / 无数」-> 有道返回 "countless / innumerable" -> 直接命中以 "cou" 开头的 "countless"！
            // - 查「稀有物种 / 物种」-> 有道返回 "species" -> 直接命中以 "spe" 开头的 "species"！
            // - 查「适者生存」-> 有道返回 "survival of the fittest" -> 直接命中以 "sur" 开头的 "survival"！
            // - 查「忽视问题 / 忽视」-> 有道返回 "overlook / ignore / neglect" -> 直接命中以 "ove" 开头的 "overlook"！
            // - 查「在观察中 / 观察」-> 有道返回 "under observation / observe" -> 直接命中以 "obs" 开头的 "observation"！
            val directDictMatches = (youdaoZhWords + mtTokens).filter { w ->
                w.startsWith(prefix) && w.length >= minWordLen
            }.distinct()

            val allClueStr = enClueBlocks.joinToString(" ") { it.originalText.lowercase() }

            if (directDictMatches.size == 1) {
                bestWord = directDictMatches.first()
            } else if (directDictMatches.size > 1) {
                // 如果有同根变形（如 observe vs observation，fund vs funding），结合上下文语法与横线长度精挑：
                bestWord = directDictMatches.maxByOrNull { w ->
                    var s = 0
                    // 介词（under/in/on/of/for）或形容词（government/rare）后面优先接名词（observation, funding, survival）
                    if (allClueStr.contains("under") || allClueStr.contains("the ") || allClueStr.contains("government")) {
                        if (w.endsWith("tion") || w.endsWith("ing") || w.endsWith("val") || w.endsWith("ment")) s += 25
                    }
                    // 后面接 "sb. doing" 或 "a problem"（冠词+名词宾语）时，前面优先接及物动词原形（observe, overlook）
                    if (allClueStr.contains("doing") || allClueStr.contains("a problem") || allClueStr.contains("sb")) {
                        if (!w.endsWith("tion") && !w.endsWith("ment") && !w.endsWith("ing")) s += 25
                    }
                    // 名词复数 stars 前面优先接形容词（countless）
                    if (allClueStr.contains("stars")) {
                        if (w.endsWith("less") || w.endsWith("ous") || w.endsWith("ive")) s += 25
                    }
                    if (expectedLen != null) {
                        s -= abs(w.length - expectedLen!!) * 8
                    }
                    s
                }
            }

            // 通道 B：如果中文反查未直接包含该词，在有道前缀词表 (`youdaoPrefixList`) 中做【整词级】语义匹配（绝不再按单个汉字瞎凑分！）
            if (bestWord == null) {
                var bestScore = -1
                for ((word, explain) in youdaoPrefixList) {
                    if (!word.startsWith(prefix) || word.length < minWordLen || word.contains(" ") || word.contains("-")) continue
                    var score = 0

                    // 1. 必须优先匹配完整的待填核心中文词 `targetSlotZh`（例如必须完整包含“无数”、“忽视”、“拨款”、“物种”、“生存”、“观察”）
                    if (targetSlotZh.length >= 2 && explain.contains(targetSlotZh)) {
                        score += 80
                    } else if (targetSlotZh.length >= 2) {
                        // 将词典释义按标点拆成独立义项词，看是否有义项与 targetSlotZh 高度重合（防止“数”匹配到“计数器”）
                        val explainSegments = explain.split(Regex("[；;，,、/（）()\\s]+"))
                        for (seg in explainSegments) {
                            if (seg == targetSlotZh) score += 90
                            else if (seg.length >= 2 && (targetSlotZh.contains(seg) || seg.contains(targetSlotZh))) score += 55
                        }
                    }

                    // 2. 如果单词同时出现在 Datamuse 上下文共现表里，额外加分
                    if (word in datamuseWords) {
                        score += 35
                    }

                    // 3. 词性与语法位置校验
                    if (allClueStr.contains("under") || allClueStr.contains("the ") && allClueStr.contains("of")) {
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

                    // 4. 横线长度校验
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

            // 通道 C：将 Datamuse 结合上下文查出的前 8 个候选词翻译成中文比对
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

    /**
     * 调用有道词典 Suggest + WebTrans 接口，用中文（如“政府拨款”、“无数的星星”、“稀有物种”、“适者生存”、“忽视”、“拨款”）
     * 直接反查出所有对应的英文单词与短语词元！
     */
    private fun queryYoudaoEnglishWordsForChinese(fullZh: String, coreSlotZh: String): List<String> {
        val words = LinkedHashSet<String>()
        val queries = listOf(fullZh, coreSlotZh).filter { it.isNotEmpty() }.distinct()

        for (q in queries) {
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")
                // 1. Suggest 接口
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

                // 2. JSONAPI 词典接口（包含完整中译英网络释义与专业短语，如 government funding, countless, survival of the fittest, overlook）
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
        // 如果前后有上下文词（如 government ___ 或 ___ stars），利用 Datamuse lc/rc 上下文搭配直接搜！
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
     * 深度优化的「用候选词组成对应中文短语（严格按横线槽数选词，绝不漏掉冠词 the/a/an、be 动词或 sb./sth.）」：
     * - 针对第 4 张图：`___ ___ ___` (3条横线) + `完工日期` + `[the, date, amount, completion]` -> 严格选满 3 个词：`the completion date`
     * - 针对第 5/6 张图：`___ ___ ___ / ___` (4条横线) + `看到某人正在做某事` + `[observe, sth., doing, sb.]` -> 识别中文里的“某人→sb.”、“某事→sth.”、“正在做→doing”，严格选满 4 个词：`observe sb. doing sth.`
     */
    private suspend fun solveWordBankCombinationFree(
        chinesePrompt: String,
        zhBlock: ScreenTextBlock,
        candidateBlocks: List<ScreenTextBlock>
    ): List<ScreenTextBlock> = coroutineScope {
        val candidates = candidateBlocks.map { it.originalText.trim() }
        val n = candidates.size

        // 1. 确定需要填入的单词数量（横线数量）：
        // - 如果候选池本身包含 sb. / sth. / doing / ones / one's 等固定搭配组件，往往全部 4 个词都要用上
        // - 否则优先读取 ScreenOcrManager 扫描到的题目上方横线数量 `lastDetectedBlankSlotCount`
        val hasSbSthPattern = candidates.any { it.equals("sb.", true) || it.equals("sth.", true) || it.equals("sb", true) || it.equals("sth", true) }
        val hasArticleOrBe = candidates.any { it.lowercase() in setOf("the", "a", "an", "be", "is", "are", "was", "were", "to") }

        val detectedSlots = ScreenOcrManager.lastDetectedBlankSlotCount
        val targetWordCount = when {
            hasSbSthPattern && chinesePrompt.contains("某人") && chinesePrompt.contains("某事") -> n.coerceAtMost(4)
            detectedSlots in 2..n -> detectedSlots
            // 像 [the, date, amount, completion] 这种 4 选 3（其中 amount 与 date 是二选一干扰项，且含冠词 the）
            hasArticleOrBe && n == 4 -> 3
            else -> 2
        }

        // 2. 并发获取每个候选词的中文含义 + 整句中文的英译 + 有道短语反查
        val candZhDeferred = async(Dispatchers.IO) {
            batchTranslateFreeFast(candidates, TranslateDirection.EN_TO_ZH)
        }
        val directEnDeferred = async(Dispatchers.IO) {
            batchTranslateFreeFast(listOf(chinesePrompt), TranslateDirection.ZH_TO_EN).firstOrNull()?.lowercase().orEmpty()
        }
        val youdaoWordsDeferred = async(Dispatchers.IO) {
            queryYoudaoEnglishWordsForChinese(chinesePrompt, chinesePrompt)
        }

        val candZhList = candZhDeferred.await()
        val directEn = directEnDeferred.await()
        val youdaoEnWords = youdaoWordsDeferred.await()

        // 3. 生成所有长度为 targetWordCount 的候选词排列（n <= 6 时排列数很小，可在内存里毫秒级精准打分）
        val permutations = generatePermutations(n, targetWordCount)

        var bestPerm: List<Int> = permutations.firstOrNull() ?: candidates.indices.toList()
        var bestScore = Int.MIN_VALUE

        for (perm in permutations) {
            val words = perm.map { candidates[it] }
            val lowerWords = words.map { it.lowercase().removeSuffix(".") }
            var score = 0

            // 规则 1：检查每个选中词的含义是否命中中文题目
            for (idx in perm) {
                val wLower = candidates[idx].lowercase().removeSuffix(".")
                val wZh = candZhList.getOrNull(idx).orEmpty()

                // 固定代词精确映射
                if (wLower == "sb" && chinesePrompt.contains("某人")) score += 45
                if (wLower == "sth" && (chinesePrompt.contains("某事") || chinesePrompt.contains("某物"))) score += 45
                if (wLower == "doing" && (chinesePrompt.contains("正在") || chinesePrompt.contains("做"))) score += 40
                if (wLower in setOf("be", "is", "are", "was", "were") && (chinesePrompt.contains("是") || chinesePrompt.contains("处于") || chinesePrompt.contains("被"))) score += 35

                // 实词含义命中中文题目（例如 completion -> 完工/完成，date -> 日期，排除干扰项 amount -> 数量/金额）
                var matchedChar = 0
                for (ch in chinesePrompt) {
                    if (ch != '的' && ch != '在' && ch in wZh) matchedChar++
                }
                if (matchedChar > 0) {
                    score += matchedChar * 22
                } else if (wLower !in TextCleaner.GRAMMAR_STOP_WORDS) {
                    // 如果某个实词既不在有道词典反查结果里、中文释义也跟题目毫无关系（如 amount 在“完工日期”中），重罚！
                    if (wLower !in youdaoEnWords && !directEn.contains(wLower.take(4))) {
                        score -= 40
                    }
                }

                // 如果该词出现在有道中译英短语结果或机器翻译结果中，加分
                if (wLower in youdaoEnWords || directEn.contains(wLower)) {
                    score += 25
                }
            }

            // 规则 2：英语语法语序打分（确保选出的词排成正确的英语语序！）
            // (a) 冠词 (the / a / an) 必须排在第一位或介词后面，绝不能排在末尾
            val firstW = lowerWords.first()
            val lastW = lowerWords.last()
            if (firstW in setOf("the", "a", "an")) score += 35
            if (lastW in setOf("the", "a", "an")) score -= 80

            // (b) 动词+sb.+doing+sth. 经典语序（如 observe sb. doing sth.）
            val sbPos = lowerWords.indexOf("sb")
            val doingPos = lowerWords.indexOf("doing")
            val sthPos = lowerWords.indexOf("sth")
            if (sbPos != -1 && doingPos != -1 && sthPos != -1) {
                // 动词必须在第 0 位，接着 sb. (第 1 位)，接着 doing (第 2 位)，最后 sth. (第 3 位)
                if (sbPos == 1 && doingPos == 2 && sthPos == 3) score += 120
                else score -= 60
            }

            // (c) 名词修饰名词：中心词在最后（例如中文“完工日期”以“日期 date”结尾，英文 `the completion date` 必须以 `date` 结尾！）
            val lastCandIdx = perm.last()
            val lastCandZh = candZhList.getOrNull(lastCandIdx).orEmpty()
            val zhTail = chinesePrompt.takeLast(2)
            if (zhTail.length == 2 && lastCandZh.contains(zhTail)) {
                score += 40
            }

            // (d) 介词短语开头（如 under observation）：介词在前，名词在后
            if (firstW in setOf("under", "in", "on", "at", "by", "with", "without", "beyond", "within", "for", "from", "to")) {
                score += 25
            }
            if (lastW in setOf("under", "in", "on", "at", "by", "with", "without", "for", "from")) {
                score -= 50
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
                translatedText = "$chinesePrompt  ➔  $finalPhrase （共 ${bestPerm.size} 词）"
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

            val prompt = "这是一个英语词汇练习页面，中文题目：「$chinesePrompt」，页面上的英文线索或候选词：「$enClues」，题目横线槽数参考：$slotCount。1. 若英文线索含首字母前缀（如 government | fun - - - - 或 cou - - - - | stars 或 a rare | spe - - - - 或 the | sur - - - - | of the fittest 或 ove - - - - | a problem 或 under | obs - - - -），请根据中文和固定搭配补全该单词并输出完整英文短语（在括号内注明填入的完整单词）；2. 若英文线索是多个候选单词按钮，请严格按横线数量（绝不漏掉 the/a/an/be/sb./sth.）用候选词组合出对应中文题目的英文短语。只输出一行结果，不要多余解释。"

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
