package me.sgly.translator

import android.graphics.Bitmap
import android.util.Base64
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

    suspend fun extractTextFromImage(bitmap: Bitmap, thinkingEffort: String, onThinkingUpdate: suspend (String) -> Unit = {}): OcrResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)
        AppLog.d("MangaVisualModel", "Base64 image length: ${base64Image.length} characters")

        val apiKey = settingsManager.visualModelApiKey
        val apiBase = settingsManager.visualModelApiBase.trimEnd('/')
        val endpoint = if (apiBase.endsWith("/chat/completions")) apiBase else "$apiBase/chat/completions"
        val model = settingsManager.visualModel

        if (apiKey.isEmpty()) throw Exception("VisualModel API Key is missing. Please configure it in settings.")

        // Generate Schema from our Data Class
        val parameters = buildToolParameters("提取结果", OcrResult::class)

        val (thinking, reasoningEffort) = thinkingParams(thinkingEffort)
        val requestObj = OpenAIChatRequest(
            model = model,
            stream = true,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
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
                        "你是一个专门用于读取日文漫画中的文本块的 OCR 专家系统。下面输入的图片是一页漫画中的一小部分，请严格按照以下的要求，结构化地提取文本：\n" +
                        "- 按照两级结构来输出结果，第一级为一个气泡（一个文字块），第二级为气泡内的一列或一行（取决于竖排或横排）；\n" +
                        "- 如果图片中能看到气泡的边框或轮廓，就根据气泡的边框来判断气泡。如果看不到边框，就把紧贴在一起的几列或者几行视为一个文字块。有时候，两个气泡可能重叠，此时也应按照文字块来划分；\n" +
                        "- 日本漫画阅读顺序为先从右到左，再从上到下。如果出现多块文字，综合文字块之间的位置关系和语义关系来确定它们的顺序；\n" +
                        "- 忽略注音假名（Ruby），只保留主文本。忽略拟声词之类的画面中的特效文本；\n" +
                        "确保最终输出的内容的顺序和内容都和原始图片完全一致。\n" +
                        "调用工具 `submit_ocr_result` 来提交你的读取结果，无需返回任何内容作为回复。"
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

        AppLog.d("MangaVisualModel", "Requesting VisualModel OCR: URL=$endpoint, Model=$model")

        val requestJson = jsonDecoder.encodeToString(requestObj)
        AppLog.d("MangaVisualModel") { "\u2192 Request body:\n" + sanitizeForLog(requestJson) }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(requestJson.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                AppLog.e("MangaVisualModel", "API Error Response: ${response.code} - $errorBody")
                throw Exception("VisualModel Request Failed: ${response.code}\n$errorBody")
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
            AppLog.d("MangaVisualModel") { "\u2190 Assembled final JSON:\n$finalJson" }
            if (finalJson.isNotEmpty()) {
                try {
                    val cleanJson = finalJson.trim().removePrefix("```json").removeSuffix("```").trim()
                    return@withContext jsonDecoder.decodeFromString<OcrResult>(cleanJson)
                } catch (e: Exception) {
                    AppLog.e("MangaVisualModel", "Failed to parse JSON: $finalJson", e)
                    throw Exception("Failed to parse JSON: ${e.message}\n$finalJson")
                }
            }

            throw Exception("No valid tool calls or content found in VisualModel response")
        }
    }

    suspend fun translateText(japaneseText: String, thinkingEffort: String, onThinkingUpdate: suspend (String) -> Unit = {}): TranslationResult = withContext(Dispatchers.IO) {
        val useVisualModel = settingsManager.useVisualModelForTranslation
        val apiKey = if (useVisualModel) settingsManager.visualModelApiKey else settingsManager.translateModelApiKey
        val apiBase = (if (useVisualModel) settingsManager.visualModelApiBase else settingsManager.translateModelApiBase).trimEnd('/')
        val endpoint = if (apiBase.endsWith("/chat/completions")) apiBase else "$apiBase/chat/completions"
        val model = if (useVisualModel) settingsManager.visualModel else settingsManager.translateModel

        if (apiKey.isEmpty()) throw Exception("${if (useVisualModel) "VisualModel" else "TranslateModel"} API Key is missing. Please configure it in settings.")

        // Generate Schema from our Data Class
        val parameters = buildToolParameters("翻译结果", TranslationResult::class)

        val (thinking, reasoningEffort) = thinkingParams(thinkingEffort)
        val requestObj = OpenAIChatRequest(
            model = model,
            stream = true,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
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
                    content = JsonPrimitive(
                        "你是一个专业的日语语言学专家，同时非常了解 ACG 文化，阅读过大量日本漫画。接下来，你需要分析结构化的日语对话文本，对其进行词级解析和整句翻译。依次完成以下两个任务，并将结果放入工具调用中：\n" +
                        "\n" +
                        "一. 重点词汇解析：\n" +
                        "   1. 对于输入的每块中的每列，把它们切分为单独的词；\n" +
                        "   2. 跳过其中的常见词。常见词包括助词、介词、感叹词、语气词、数词等等没有太确切含义的词，以及意义非常简单的词，例如人称代词，简单的动词等，以及人名地名，这些要跳过；\n" +
                        "   3. 选中其中带有实际含义的词汇，特别是名词、动词、形容词、副词、专有名词、外来语等；\n" +
                        "   4. 对于没有被跳过的，也就是被选中的每个词，输出：\n" +
                        "      i. 原型：去除时态、变体的，在词典中出现的原型；\n" +
                        "      ii. 含义：词语的中文对应含义，尽量只用一个词，最好是在本句中的含义，不要列出超过一个近义词，也不要枚举所有可能的释义。另外，如果它是外来语（片假名），给出外来语来源及其原始语言，例如 病历,karte[德]。\n" +
                        "      iii. 难度评分：当前词语在所有块所有列的所有词语中的相对难度评分，在 0.0~1.0 之间。难度不需要是词典或语言级别的绝对难度，只要能用来比较当前输入的词语之间的难度即可。\n" +
                        "   5. 输出时，依然保持原有的顺序输出。\n" +
                        "二. 整句翻译：\n" +
                        "   - 对于输入的每块，把它所有的列连接在一起，给出一个完整的、信达雅、符合原文场景和语气的翻译。不需要再按照每列隔开或者单独翻译，而是要给出能够完整表达原文意思的翻译。\n" +
                        "\n" +
                        "必须调用工具 `submit_translation_result` 来提交你的处理结果，无需返回任何内容作为回复。"
                    )
                ),
                OpenAIChatMessage(
                    role = "user",
                    content = JsonPrimitive(japaneseText)
                )
            )
        )

        AppLog.d("MangaTranslateModel", "Requesting TranslateModel Translation: URL=$endpoint, Model=$model")

        val requestJson = jsonDecoder.encodeToString(requestObj)
        AppLog.d("MangaTranslateModel") { "\u2192 Request body:\n" + sanitizeForLog(requestJson) }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(requestJson.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                AppLog.e("MangaTranslateModel", "API Error Response: ${response.code} - $errorBody")
                throw Exception("TranslateModel Translation Failed: ${response.code}\n$errorBody")
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
            AppLog.d("MangaTranslateModel") { "\u2190 Assembled final JSON:\n$finalJson" }
            if (finalJson.isNotEmpty()) {
                try {
                    val cleanJson = finalJson.trim().removePrefix("```json").removeSuffix("```").trim()
                    return@withContext jsonDecoder.decodeFromString<TranslationResult>(cleanJson)
                } catch (e: Exception) {
                    AppLog.e("MangaTranslateModel", "Failed to parse JSON: $finalJson", e)
                    throw Exception("Failed to parse JSON: ${e.message}\n$finalJson")
                }
            }

            throw Exception("No valid tool calls or content found in TranslateModel response")
        }
    }

    /** Replaces the base64 image payload with a placeholder so logcat stays readable. */
    private fun sanitizeForLog(json: String): String =
        json.replace(
            Regex("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+"),
            "data:image/png;base64,<BASE64_OMITTED>"
        )

    /**
     * Thinking is always on; `reasoning_effort` carries the user's effort token verbatim
     * (e.g. "low" / "high" / "max", as configured in the thinking-strength ladder).
     */
    private fun thinkingParams(effort: String): Pair<OpenAIThinkingConfig, String> =
        OpenAIThinkingConfig("enabled") to effort

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 使用 PNG 无损格式，最高识别精度
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}

private val schemaJsonDecoder = Json { ignoreUnknownKeys = true }

/**
 * Builds an OpenAI-compatible tool `parameters` object from a Kotlin data class.
 *
 * kotlinx-schema emits a data-class schema containing:
 *   - "$id": "me.sgly.translator.OcrResult"  → a *relative* URI, which OpenAI's validator
 *     rejects with "relative URL without a base";
 *   - nested types as "$defs" + "$ref": "#/$defs/..." (also relative references).
 *
 * We therefore drop $id/$schema/$defs and inline every $ref so the final schema is a plain,
 * self-contained JSON Schema that OpenAI accepts.
 */
internal fun buildToolParameters(description: String, schemaClass: kotlin.reflect.KClass<*>): JsonObject {
    val generator = kotlinx.schema.generator.json.ReflectionClassJsonSchemaGenerator.Default
    val schemaJsonString = generator.generateSchemaString(schemaClass)
    val root = schemaJsonDecoder.parseToJsonElement(schemaJsonString).jsonObject
    val defs = root["\$defs"] as? JsonObject
    return buildJsonObject {
        put("description", description)
        root.forEach { (key, value) ->
            if (key == "\$id" || key == "\$schema" || key == "\$defs") return@forEach
            put(key, inlineRefs(value, defs))
        }
    }
}

/** Recursively replaces `{"$ref": "#/$defs/X"}` with the referenced definition. */
private fun inlineRefs(element: JsonElement, defs: JsonObject?): JsonElement = when (element) {
    is JsonObject -> {
        val ref = element["\$ref"]?.let { it as? JsonPrimitive }?.contentOrNull
        if (ref != null && ref.startsWith("#/\$defs/") && defs != null) {
            val target = defs[ref.removePrefix("#/\$defs/")]
            if (target != null) inlineRefs(target, defs) else element
        } else {
            buildJsonObject {
                element.forEach { (k, v) -> put(k, inlineRefs(v, defs)) }
            }
        }
    }
    is JsonArray -> buildJsonArray {
        element.forEach { add(inlineRefs(it, defs)) }
    }
    else -> element
}

