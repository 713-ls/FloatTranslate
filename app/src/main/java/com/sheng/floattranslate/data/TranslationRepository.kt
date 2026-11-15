package com.sheng.floattranslate.data

import android.content.Context
import com.sheng.floattranslate.model.TranslateDirection
import com.sheng.floattranslate.model.TranslationResult
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun translate(rawText: String, direction: TranslateDirection): TranslationResult =
        withContext(Dispatchers.IO) {
            val cleaned = TextCleaner.cleanForTranslation(rawText)
            if (cleaned.isEmpty()) {
                return@withContext TranslationResult(rawText, "", "请输入或截取需要翻译的内容", null, "提示")
            }

            val engineTag = if (prefs.useCustomApi && prefs.apiKey.isNotEmpty()) "custom" else "free"
            val cacheKey = "${direction.name}:$engineTag:$cleaned"
            db.getCached(cacheKey)?.let { return@withContext it }

            val result = if (engineTag == "custom") {
                translateWithCustomApi(cleaned, direction)
            } else {
                translateWithFreeApi(cleaned, direction)
            }

            if (!result.translatedText.startsWith("请求失败")) {
                db.save(cacheKey, result)
            }
            result
        }

    private fun translateWithFreeApi(text: String, direction: TranslateDirection): TranslationResult {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=${direction.fromLang}|${direction.toLang}"
            val req = Request.Builder().url(url).get().build()
            val bodyStr = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return TranslationResult(text, text, "请求失败: HTTP ${resp.code}", null, "免费接口")
                resp.body?.string().orEmpty()
            }
            val json = JSONObject(bodyStr)
            val translated = json.optJSONObject("responseData")?.optString("translatedText") ?: "未获取到翻译结果"

            var phonetic: String? = null
            var dictDetail = ""
            if (direction == TranslateDirection.EN_TO_ZH && !text.contains(" ") && text.length <= 30) {
                val dictInfo = fetchEnglishDictionary(text.lowercase())
                phonetic = dictInfo.first
                if (dictInfo.second.isNotEmpty()) {
                    dictDetail = "\n\n【词典释义】\n${dictInfo.second}"
                }
            }

            TranslationResult(
                query = text,
                cleanedQuery = text,
                translatedText = translated + dictDetail,
                phonetic = phonetic,
                sourceLabel = "免费接口 (MyMemory)"
            )
        } catch (e: Exception) {
            TranslationResult(text, text, "请求失败: ${e.message}", null, "免费接口")
        }
    }

    private fun fetchEnglishDictionary(word: String): Pair<String?, String> {
        return try {
            val req = Request.Builder()
                .url("https://api.dictionaryapi.dev/api/v2/entries/en/${URLEncoder.encode(word, "UTF-8")}")
                .get()
                .build()
            val bodyStr = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null to ""
                resp.body?.string().orEmpty()
            }
            val arr = JSONArray(bodyStr)
            val first = arr.getJSONObject(0)
            val phonetic = first.optString("phonetic").takeIf { it.isNotEmpty() }
            val meanings = first.optJSONArray("meanings")
            val sb = StringBuilder()
            if (meanings != null) {
                for (i in 0 until minOf(2, meanings.length())) {
                    val m = meanings.getJSONObject(i)
                    val pos = m.optString("partOfSpeech")
                    val defs = m.optJSONArray("definitions")
                    val defText = defs?.optJSONObject(0)?.optString("definition").orEmpty()
                    if (defText.isNotEmpty()) {
                        sb.append("• [$pos] $defText\n")
                    }
                }
            }
            phonetic to sb.toString().trim()
        } catch (_: Exception) {
            null to ""
        }
    }

    private fun translateWithCustomApi(text: String, direction: TranslateDirection): TranslationResult {
        return try {
            val endpoint = prefs.baseUrl.trimEnd('/') + "/chat/completions"
            val prompt = if (direction == TranslateDirection.EN_TO_ZH) {
                "请将以下英文内容准确翻译为中文（若包含编程/机器人/工程术语请保留专业准确度，并简要标注关键术语）：\n$text"
            } else {
                "请将以下中文内容准确翻译为地道英文（若为技术/学术表达请给出最贴切的英文术语或句式）：\n$text"
            }

            val payload = JSONObject().apply {
                put("model", prefs.modelName)
                put("temperature", 0.2)
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
                if (!resp.isSuccessful) return TranslationResult(text, text, "请求失败: HTTP ${resp.code}", null, "自定义 API")
                resp.body?.string().orEmpty()
            }

            val json = JSONObject(bodyStr)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            TranslationResult(
                query = text,
                cleanedQuery = text,
                translatedText = content,
                phonetic = null,
                sourceLabel = "自定义 API (${prefs.modelName})"
            )
        } catch (e: Exception) {
            TranslationResult(text, text, "请求失败: ${e.message}", null, "自定义 API")
        }
    }
}
