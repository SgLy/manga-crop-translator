package me.sgly.translator

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val jsonDecoder = Json { ignoreUnknownKeys = true }

    suspend fun extractTextFromImage(bitmap: Bitmap, onThinkingUpdate: suspend (String) -> Unit = {}): OcrResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)
        Log.d("MangaVLM", "Base64 image length: ${base64Image.length} characters")

        val apiKey = settingsManager.vlmApiKey
        val apiBase = settingsManager.vlmApiBase.trimEnd('/')
        val endpoint = if (apiBase.endsWith("/chat/completions")) apiBase else "$apiBase/chat/completions"
        val model = settingsManager.vlmModel

        if (apiKey.isEmpty()) throw Exception("VLM API Key is missing. Please configure it in settings.")

        // Generate Schema from our Data Class
        val generator = kotlinx.schema.generator.json.ReflectionClassJsonSchemaGenerator.Default
        val schemaJsonString = generator.generateSchemaString(OcrResult::class)
        val baseSchema = jsonDecoder.parseToJsonElement(schemaJsonString).jsonObject
        val parameters = buildJsonObject {
            put("description", "提取结果")
            baseSchema.forEach { k, v -> put(k, v) }
        }

        val requestObj = OpenAIChatRequest(
            model = model,
            stream = true,
            thinking = OpenAIThinkingConfig("disabled"),
            tools = listOf(
                OpenAITool(
                    type = "function",
                    function = OpenAIFunction(
                        name = "submit_ocr_result",
                        description = "提交提取结果",
                        parameters = parameters
                    )
                )
            ),
            toolChoice = "auto",
            messages = listOf(
                OpenAIChatMessage(
                    role = "system",
                    content = JsonPrimitive(
                        "你是一个专门用于读取日文漫画文本块的 OCR 专家系统。请按阅读顺序精准提取图片中的日文。\n" +
                        "任务要求：\n" +
                        "1. 按照两级结构来输出结果，第一级为一个漫画气泡（コマ），第二级为气泡内的一列或一行（取决于竖排或横排）\n" + 
                        "  - 如果输入中只有一个气泡或者分辨不出气泡，就处理为一个气泡；\n" +
                        "  - 列内或行内的截断输出为空格即可；\n" +
                        "2. 忽略注音假名（Ruby），只保留主文本。\n" + 
                        "3. 牢记日本漫画阅读顺序为从右到左、从上到下。\n\n" +
                        "调用工具 `submit_ocr_result` 来提交你的读取结果，无需返回任何内容作为文本回复。"
                    )
                ),
                OpenAIChatMessage(
                    role = "user",
                    content = buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/png;base64,$base64Image")
                            })
                        })
                    }
                )
            )
        )

        Log.d("MangaVLM", "Requesting VLM OCR: URL=$endpoint, Model=$model")

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(jsonDecoder.encodeToString(requestObj).toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e("MangaVLM", "API Error Response: ${response.code} - $errorBody")
                throw Exception("VLM Request Failed: ${response.code}\n$errorBody")
            }

            val source = response.body?.source() ?: throw Exception("Empty response body")
            val contentBuilder = java.lang.StringBuilder()
            val functionArguments = java.lang.StringBuilder()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val dataStr = line.substringAfter("data:").trim()
                if (dataStr == "[DONE]") break

                try {
                    val jsonChunk = JSONObject(dataStr)
                    val choices = jsonChunk.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        if (delta != null) {
                            val reasoning = delta.optString("reasoning_content", "")
                            if (reasoning.isNotEmpty()) {
                                onThinkingUpdate(reasoning)
                            }
                            
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                onThinkingUpdate(content)
                                contentBuilder.append(content)
                            }
                            
                            val toolCalls = delta.optJSONArray("tool_calls")
                            if (toolCalls != null && toolCalls.length() > 0) {
                                val tc = toolCalls.getJSONObject(0)
                                val func = tc.optJSONObject("function")
                                if (func != null && func.has("arguments")) {
                                    functionArguments.append(func.getString("arguments"))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    /* Ignore JSON parse errors for incomplete chunks */
                }
            }

            val finalJson = if (functionArguments.isNotEmpty()) functionArguments.toString() else contentBuilder.toString()
            if (finalJson.isNotEmpty()) {
                try {
                    val cleanJson = finalJson.trim().removePrefix("```json").removeSuffix("```").trim()
                    return@withContext jsonDecoder.decodeFromString<OcrResult>(cleanJson)
                } catch (e: Exception) {
                    Log.e("MangaVLM", "Failed to parse JSON: $finalJson", e)
                    throw Exception("Failed to parse JSON: ${e.message}\n$finalJson")
                }
            }

            throw Exception("No valid tool calls or content found in VLM response")
        }
    }

    suspend fun translateText(japaneseText: String, onThinkingUpdate: suspend (String) -> Unit = {}): TranslationResult = withContext(Dispatchers.IO) {
        val apiKey = settingsManager.llmApiKey
        val apiBase = settingsManager.llmApiBase.trimEnd('/')
        val endpoint = if (apiBase.endsWith("/chat/completions")) apiBase else "$apiBase/chat/completions"
        val model = settingsManager.llmModel

        if (apiKey.isEmpty()) throw Exception("LLM API Key is missing. Please configure it in settings.")

        // Generate Schema from our Data Class
        val generator = kotlinx.schema.generator.json.ReflectionClassJsonSchemaGenerator.Default
        val schemaJsonString = generator.generateSchemaString(TranslationResult::class)
        val baseSchema = jsonDecoder.parseToJsonElement(schemaJsonString).jsonObject
        val parameters = buildJsonObject {
            put("description", "翻译结果")
            baseSchema.forEach { k, v -> put(k, v) }
        }

        val requestObj = OpenAIChatRequest(
            model = model,
            stream = true,
            thinking = OpenAIThinkingConfig("disabled"),
            tools = listOf(
                OpenAITool(
                    type = "function",
                    function = OpenAIFunction(
                        name = "submit_translation_result",
                        description = "提交翻译和词汇解析结果",
                        parameters = parameters
                    )
                )
            ),
            toolChoice = "auto",
            messages = listOf(
                OpenAIChatMessage(
                    role = "system",
                    content = JsonPrimitive("你是专业的日语漫画汉化系统，熟知 ACG 文化。你将收到由上一级 OCR 处理后按块提取的分段日文。\n" +
                        "请根据用户输入的日文（按气泡（コマ）/行结构化提供）完成两项核心任务：\n" +
                        "1. 词解析：解释每一块每一列中的每个词：\n" + 
                        "  i. 去除过于基础的词（わたし、くん、こと、今）、连接词（の、が、に）、语气助词（よ、ね）和人名地名（新一、すばる、風舞高校、池袋駅）。这些词无需在结果中出现。\n" +
                        "  ii. 同时给出原词、词典中出现的“原型”（去除时态、变体）、词语的中文对应含义（心強い->安心）及外来语来源（パティシエ->西点师 [法] pâtissier）。中文含义尽量只用一个词语，最好是在本句中的含义，避免多个释义出现。\n" +
                        "  iii. 给每个词赋予在当前输入的所有词语中的 **相对** 难度评分，在 0.0~1.0 之间。难度不需要是词典或语言级别的绝对难度，只要能用来比较当前输入的词语之间的难度即可。\n" +
                        "  iv. 输出时，依然保持原有的块/行结构化顺序输出。\n" +
                        "2. 整句翻译：结合全部上下文，将每一块的所有行或列拼接后，翻译为完整的一句话。\n\n" +
                        "必须调用工具 `submit_translation_result` 来提交你的处理结果。")
                ),
                OpenAIChatMessage(
                    role = "user",
                    content = JsonPrimitive(japaneseText)
                )
            )
        )

        Log.d("MangaLLM", "Requesting LLM Translation: URL=$endpoint, Model=$model")

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(jsonDecoder.encodeToString(requestObj).toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e("MangaLLM", "API Error Response: ${response.code} - $errorBody")
                throw Exception("LLM Translation Failed: ${response.code}\n$errorBody")
            }

            val source = response.body?.source() ?: throw Exception("Empty response body")
            val contentBuilder = java.lang.StringBuilder()
            val functionArguments = java.lang.StringBuilder()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val dataStr = line.substringAfter("data:").trim()
                if (dataStr == "[DONE]") break

                try {
                    val jsonChunk = JSONObject(dataStr)
                    val choices = jsonChunk.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        if (delta != null) {
                            val reasoning = delta.optString("reasoning_content", "")
                            if (reasoning.isNotEmpty()) {
                                onThinkingUpdate(reasoning)
                            }
                            
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                onThinkingUpdate(content)
                                contentBuilder.append(content)
                            }
                            
                            val toolCalls = delta.optJSONArray("tool_calls")
                            if (toolCalls != null && toolCalls.length() > 0) {
                                val tc = toolCalls.getJSONObject(0)
                                val func = tc.optJSONObject("function")
                                if (func != null && func.has("arguments")) {
                                    functionArguments.append(func.getString("arguments"))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    /* Ignore JSON parse errors for incomplete chunks */
                }
            }

            val finalJson = if (functionArguments.isNotEmpty()) functionArguments.toString() else contentBuilder.toString()
            if (finalJson.isNotEmpty()) {
                try {
                    val cleanJson = finalJson.trim().removePrefix("```json").removeSuffix("```").trim()
                    return@withContext jsonDecoder.decodeFromString<TranslationResult>(cleanJson)
                } catch (e: Exception) {
                    Log.e("MangaLLM", "Failed to parse JSON: $finalJson", e)
                    throw Exception("Failed to parse JSON: ${e.message}\n$finalJson")
                }
            }

            throw Exception("No valid tool calls or content found in LLM response")
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 使用 PNG 无损格式，最高识别精度
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
