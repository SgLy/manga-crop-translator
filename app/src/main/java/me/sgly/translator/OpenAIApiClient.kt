package me.sgly.translator

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class OpenAIApiClient(private val settingsManager: SettingsManager) {

    // 增加了长达 3 分钟的超时时间，充分应对跨国流式 API 的读写
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun extractTextFromImageStream(bitmap: Bitmap): Flow<String> = flow {
        try {
            val base64Image = bitmapToBase64(bitmap)
            Log.d("MangaVLM", "Base64 image length: ${base64Image.length} characters")

            val apiKey = settingsManager.vlmApiKey
            val apiBase = settingsManager.vlmApiBase.trimEnd('/')
            val endpoint = if (apiBase.endsWith("/chat/completions")) apiBase else "$apiBase/chat/completions"
            val model = settingsManager.vlmModel

            if (apiKey.isEmpty()) throw Exception("VLM API Key is missing. Please configure it in settings.")

            val requestBody = JSONObject().apply {
                put("model", model)
                put("stream", true) // 开启流式输出
                put("stream_options", JSONObject().apply { put("include_usage", true) })
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are an expert OCR system specializing in reading Japanese manga panels.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "Extract all the Japanese text from this manga crop. Output ONLY the raw Japanese text without any translation, quotes, or markdown blocks. If there are any ruby annotations, drop rubies. REMEMBER that Japanese text can be vertical, and should be read in the order from right to left, top to bottom.")
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    // 使用 PNG 无损格式，最大化 OCR 识别精度
                                    put("url", "data:image/png;base64,$base64Image")
                                })
                            })
                        })
                    })
                })
            }

            Log.d("MangaVLM", "Requesting VLM OCR Stream: URL=$endpoint, Model=$model")

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: ""
                    throw Exception("VLM Request Failed: ${response.code}\n$error")
                }

                // 逐行解析 Server-Sent Events (SSE) 流
                response.body?.source()?.let { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        if (line.startsWith("{") && line.contains("\"error\"")) {
                            throw Exception("VLM API returned error object: $line")
                        }
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6)
                            Log.d("MangaVLM", "VLM Stream Chunk: $data")
                            if (data == "[DONE]") break
                            try {
                                val json = JSONObject(data)
                                val usage = json.optJSONObject("usage")
                                if (usage != null) {
                                    val promptTokens = usage.optInt("prompt_tokens", 0)
                                    val completionTokens = usage.optInt("completion_tokens", 0)
                                    val totalTokens = usage.optInt("total_tokens", 0)
                                    Log.d("MangaVLM", "VLM Tokens - Prompt: $promptTokens, Completion: $completionTokens, Total: $totalTokens")
                                    emit("\n\n[Token Usage - Prompt: $promptTokens, Completion: $completionTokens, Total: $totalTokens]")
                                }

                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    if (delta != null) {
                                        if (delta.has("reasoning_content")) {
                                            val rContent = delta.getString("reasoning_content")
                                            if (rContent.isNotEmpty() && rContent != "null") emit(rContent)
                                        }
                                        if (delta.has("content")) {
                                            val content = delta.getString("content")
                                            if (content.isNotEmpty() && content != "null") emit(content)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MangaVLM", "Error parsing chunk: $data", e)
                            }
                        } else {
                            Log.w("MangaVLM", "Unexpected VLM line: $line")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MangaVLM", "Error in VLM OCR streaming flow", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    fun translateTextStream(japaneseText: String): Flow<String> = flow {
        try {
            val apiKey = settingsManager.llmApiKey
            val apiBase = settingsManager.llmApiBase.trimEnd('/')
            val endpoint = if (apiBase.endsWith("/chat/completions")) apiBase else "$apiBase/chat/completions"
            val model = settingsManager.llmModel

            if (apiKey.isEmpty()) throw Exception("LLM API Key is missing. Please configure it in settings.")

            val requestBody = JSONObject().apply {
                put("model", model)
                put("stream", true) // 开启流式输出
                put("stream_options", JSONObject().apply { put("include_usage", true) })
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are an expert manga translator. Translate the given Japanese text to natural and fluent Simplified Chinese (简体中文). Output ONLY the translated text, nothing else.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", japaneseText)
                    })
                })
                put("temperature", 0.3)
            }

            Log.d("MangaLLM", "Requesting LLM Translation Stream: URL=$endpoint, Model=$model")

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: ""
                    throw Exception("LLM Translation Failed: ${response.code}\n$error")
                }

                response.body?.source()?.let { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        if (line.startsWith("{") && line.contains("\"error\"")) {
                            throw Exception("LLM API returned error object: $line")
                        }
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6)
                            if (data == "[DONE]") break
                            try {
                                val json = JSONObject(data)
                                val usage = json.optJSONObject("usage")
                                if (usage != null) {
                                    val promptTokens = usage.optInt("prompt_tokens", 0)
                                    val completionTokens = usage.optInt("completion_tokens", 0)
                                    val totalTokens = usage.optInt("total_tokens", 0)
                                    Log.d("MangaLLM", "LLM Tokens - Prompt: $promptTokens, Completion: $completionTokens, Total: $totalTokens")
                                    emit("\n\n[Token Usage - Prompt: $promptTokens, Completion: $completionTokens, Total: $totalTokens]")
                                }

                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    if (delta != null) {
                                        if (delta.has("reasoning_content")) {
                                            val rContent = delta.getString("reasoning_content")
                                            if (rContent.isNotEmpty() && rContent != "null") emit(rContent)
                                        }
                                        if (delta.has("content")) {
                                            val content = delta.getString("content")
                                            if (content.isNotEmpty() && content != "null") emit(content)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MangaLLM", "Error parsing chunk: $data", e)
                            }
                        } else {
                            Log.w("MangaLLM", "Unexpected line: $line")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MangaLLM", "Error in LLM translation streaming flow", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 使用 PNG 无损压缩，完美保留漫画中的注音假名细节（PNG 压缩会忽略 quality 参数）
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}