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

    suspend fun extractTextFromImage(
        bitmap: Bitmap,
        thinkingEffort: String,
        onOutputDelta: suspend (String) -> Unit = {}
    ): OcrResult = withContext(Dispatchers.IO) {
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
            streamOptions = OpenAIStreamOptions(includeUsage = true),
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
                    content = JsonPrimitive(Prompts.VISUAL)
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
                val errorBody = response.body.string()
                AppLog.e("MangaVisualModel", "API Error Response: ${response.code} - $errorBody")
                throw Exception("VisualModel Request Failed: ${response.code}\n$errorBody")
            }

            val source = response.body.source()
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
                                onOutputDelta(reasoning)
                            }
                            
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                onOutputDelta(content)
                                contentBuilder.append(content)
                            }
                            
                            val toolCalls = delta.optJSONArray("tool_calls")
                            if (toolCalls != null && toolCalls.length() > 0) {
                                val tc = toolCalls.getJSONObject(0)
                                val func = tc.optJSONObject("function")
                                if (func != null && func.has("arguments")) {
                                    val arguments = func.getString("arguments")
                                    functionArguments.append(arguments)
                                    // Tool arguments stream in as their own chunks; count them too so
                                    // the counter keeps moving while the model writes the JSON.
                                    onOutputDelta(arguments)
                                }
                            }
                        }
                    }

                    // With stream_options.include_usage the server reports token usage once, on the
                    // last chunk of the stream. Log the whole object for cost/debug inspection.
                    val usage = jsonChunk.optJSONObject("usage")
                    if (usage != null) {
                        AppLog.i("OpenAIUsage", usage.toString())
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

    suspend fun translateText(
        japaneseText: String,
        thinkingEffort: String,
        onOutputDelta: suspend (String) -> Unit = {}
    ): TranslationResult = withContext(Dispatchers.IO) {
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
            streamOptions = OpenAIStreamOptions(includeUsage = true),
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
                    content = JsonPrimitive(Prompts.TRANSLATE)
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
                val errorBody = response.body.string()
                AppLog.e("MangaTranslateModel", "API Error Response: ${response.code} - $errorBody")
                throw Exception("TranslateModel Translation Failed: ${response.code}\n$errorBody")
            }

            val source = response.body.source()
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
                                onOutputDelta(reasoning)
                            }
                            
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                onOutputDelta(content)
                                contentBuilder.append(content)
                            }
                            
                            val toolCalls = delta.optJSONArray("tool_calls")
                            if (toolCalls != null && toolCalls.length() > 0) {
                                val tc = toolCalls.getJSONObject(0)
                                val func = tc.optJSONObject("function")
                                if (func != null && func.has("arguments")) {
                                    val arguments = func.getString("arguments")
                                    functionArguments.append(arguments)
                                    // Tool arguments stream in as their own chunks; count them too so
                                    // the counter keeps moving while the model writes the JSON.
                                    onOutputDelta(arguments)
                                }
                            }
                        }
                    }

                    // With stream_options.include_usage the server reports token usage once, on the
                    // last chunk of the stream. Log the whole object for cost/debug inspection.
                    val usage = jsonChunk.optJSONObject("usage")
                    if (usage != null) {
                        AppLog.i("OpenAIUsage", usage.toString())
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

