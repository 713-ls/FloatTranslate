package com.sheng.floattranslate.data

import android.content.Context
import com.sheng.floattranslate.model.ScreenTextBlock
import com.sheng.floattranslate.model.TranslateDirection
import com.sheng.floattranslate.util.PrefsManager
import com.sheng.floattranslate.util.TextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 批量翻译整屏文字块：优先命中本地缓存，未命中的合并为一次联网请求，速度极快。
     */
    suspend fun translateBlocks(
        blocks: List<ScreenTextBlock>,
        direction: TranslateDirection
    ): List<ScreenTextBlock> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()

        val engineTag = if (prefs.useCustomApi && prefs.apiKey.isNotEmpty()) "custom" else "free"
        val uncachedIndices = mutableListOf<Int>()
        val cleanedInputs = mutableListOf<String>()

        blocks.forEachIndexed { idx, block ->
            val cleaned = TextCleaner.cleanForTranslation(block.originalText)
            val key = "${direction.name}:$engineTag:$cleaned"
            val cached = db.get(key)
            if (cached != null) {
                block.translatedText = cached
            } else {
                uncachedIndices.add(idx)
                cleanedInputs.add(cleaned)
            }
        }

        if (uncachedIndices.isNotEmpty()) {
            val translatedList = if (engineTag == "custom") {
                batchTranslateCustom(cleanedInputs, direction)
            } else {
                batchTranslateFree(cleanedInputs, direction)
            }

            uncachedIndices.forEachIndexed { i, blockIdx ->
                val trans = translatedList.getOrNull(i)?.trim().orEmpty()
                val finalTrans = if (trans.isNotEmpty()) trans else blocks[blockIdx].originalText
                blocks[blockIdx].translatedText = finalTrans
                val key = "${direction.name}:$engineTag:${cleanedInputs[i]}"
                if (trans.isNotEmpty()) db.put(key, finalTrans)
            }
        }

        blocks
    }

    private fun batchTranslateFree(lines: List<String>, direction: TranslateDirection): List<String> {
        // 将多行用换行符拼接，一次请求完成整屏翻译
        val joined = lines.joinToString("\n")
        return try {
            val encoded = URLEncoder.encode(joined, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${direction.fromLang}&tl=${direction.toLang}&dt=t&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            val bodyStr = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
            }

            if (bodyStr.isNotEmpty()) {
                val outer = JSONArray(bodyStr).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until outer.length()) {
                    val seg = outer.getJSONArray(i).optString(0)
                    sb.append(seg)
                }
                val split = sb.toString().split("\n")
                if (split.size == lines.size) {
                    return split
                }
            }
            // Fallback to MyMemory per-chunk if Google GTX is unreachable or line count mismatched
            lines.map { singleMyMemory(it, direction) }
        } catch (_: Exception) {
            lines.map { singleMyMemory(it, direction) }
        }
    }

    private fun singleMyMemory(text: String, direction: TranslateDirection): String {
        return try {
            val encoded = URLEncoder.encode(text.take(250), "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=${direction.fromLang}|${direction.toLang}"
            val req = Request.Builder().url(url).get().build()
            val bodyStr = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return text
                resp.body?.string().orEmpty()
            }
            JSONObject(bodyStr).optJSONObject("responseData")?.optString("translatedText") ?: text
        } catch (_: Exception) {
            text
        }
    }

    private fun batchTranslateCustom(lines: List<String>, direction: TranslateDirection): List<String> {
        return try {
            val endpoint = prefs.baseUrl.trimEnd('/') + "/chat/completions"
            val numbered = lines.mapIndexed { idx, s -> "[${idx + 1}] $s" }.joinToString("\n")
            val targetLang = if (direction == TranslateDirection.EN_TO_ZH) "简体中文" else "英文"
            val prompt = "请将以下按行编号的内容逐行准确翻译为$targetLang，保持相同的行号格式 [n] 输出，不要输出多余解释：\n$numbered"

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

            val bodyStr = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return batchTranslateFree(lines, direction)
                resp.body?.string().orEmpty()
            }
            val content = JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val resultMap = mutableMapOf<Int, String>()
            content.lines().forEach { line ->
                val m = Regex("^\\[(\\d+)\\]\\s*(.*)$").find(line.trim())
                if (m != null) {
                    val idx = m.groupValues[1].toIntOrNull()
                    if (idx != null) resultMap[idx - 1] = m.groupValues[2]
                }
            }
            lines.indices.map { i -> resultMap[i] ?: lines[i] }
        } catch (_: Exception) {
            batchTranslateFree(lines, direction)
        }
    }
}
