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

        val engineTag = if (prefs.useCustomApi && prefs.apiKey.isNotEmpty()) "custom" else "free"
        val uncachedIndices = mutableListOf<Int>()
        val cleanedInputs = mutableListOf<String>()

        // 去重映射：相同文本在一屏内只请求一次
        val uniqueInputs = LinkedHashMap<String, MutableList<Int>>()

        blocks.forEachIndexed { idx, block ->
            val cleaned = TextCleaner.cleanForTranslation(block.originalText)
            val key = "${direction.name}:$engineTag:$cleaned"
            val cached = memCache.get(key) ?: db.get(key)?.also { memCache.put(key, it) }
            if (cached != null) {
                block.translatedText = cached
            } else {
                val listForText = uniqueInputs.getOrPut(cleaned) { mutableListOf() }
                if (listForText.isEmpty()) {
                    uncachedIndices.add(idx)
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
                val targetBlockIndices = uniqueInputs[cleaned].orEmpty()
                for (bIdx in targetBlockIndices) {
                    val finalTrans = if (trans.isNotEmpty()) trans else blocks[bIdx].originalText
                    blocks[bIdx].translatedText = finalTrans
                }
                if (trans.isNotEmpty()) {
                    val key = "${direction.name}:$engineTag:$cleaned"
                    memCache.put(key, trans)
                    db.put(key, trans)
                }
            }
        }

        blocks
    }

    private suspend fun batchTranslateFreeFast(
        lines: List<String>,
        direction: TranslateDirection
    ): List<String> = coroutineScope {
        // 1. 若 Google GTX 未处于熔断期，优先用编号锚点一次性批量翻译整屏（约 0.2~0.4s）
        if (System.currentTimeMillis() > gtxBlockedUntil) {
            val gtxResult = tryBatchGoogleGtx(lines, direction)
            if (gtxResult != null) return@coroutineScope gtxResult
        }

        // 2. 若 Google GTX 超时或不可达，立即走全并发 MyMemory 请求（所有词条同时发出，耗时约 0.4~0.7s）
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
            // 使用 [1] [2] 编号锚点拼接，彻底解决返回文本换行符错位导致整批作废的问题
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
            // 触发熔断：5 分钟内跳过 Google GTX，后续点击不再白等 2.5 秒超时
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
