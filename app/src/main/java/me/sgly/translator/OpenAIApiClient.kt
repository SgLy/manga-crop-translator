package me.sgly.translator

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class OpenAIApiClient(
    private val settingsManager: SettingsManager,
) {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val jsonDecoder = Json { ignoreUnknownKeys = true }

    suspend fun extractTextFromImage(
        bitmap: Bitmap,
        thinkingEffort: String,
        onOutputDelta: suspend (String) -> Unit = {},
        onReasoningDelta: suspend (String) -> Unit = {},
        onStats: (RequestStats) -> Unit = {},
    ): OcrResult =
        withContext(Dispatchers.IO) {
            val tag = "MangaVisualModel"
            val base64Image = bitmapToBase64(bitmap)
            AppLog.d("$tag/ImagePayload", "Base64 image length: ${base64Image.length} characters")

            val connection =
                modelConnection(
                    apiKey = settingsManager.visualModelApiKey,
                    apiBase = settingsManager.visualModelApiBase,
                    model = settingsManager.visualModel,
                )
            if (connection.apiKey.isEmpty()) {
                throw Exception("VisualModel API Key is missing. Please configure it in settings.")
            }

            val userContent =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put(
                                "image_url",
                                buildJsonObject {
                                    put("url", "data:image/png;base64,$base64Image")
                                },
                            )
                        },
                    )
                }
            val tool =
                OpenAITool(
                    type = "function",
                    function =
                        OpenAIFunction(
                            name = "submit_ocr_result",
                            description = "提交提取结果",
                            parameters = buildToolParameters("提取结果", OcrResult::class),
                        ),
                )

            AppLog.d(
                "$tag/Request",
                "Requesting VisualModel OCR: URL=${connection.endpoint}, Model=${connection.model}",
            )

            val finalJson =
                chatWithTool(
                    tag = tag,
                    connection = connection,
                    thinkingEffort = thinkingEffort,
                    systemPrompt = Prompts.VISUAL,
                    userContent = userContent,
                    tool = tool,
                    onOutputDelta = onOutputDelta,
                    onReasoningDelta = onReasoningDelta,
                    onStats = onStats,
                )
            decodeToolResult(tag, finalJson, OcrResult.serializer())
        }

    suspend fun translateText(
        japaneseText: String,
        thinkingEffort: String,
        onOutputDelta: suspend (String) -> Unit = {},
        onReasoningDelta: suspend (String) -> Unit = {},
        onStats: (RequestStats) -> Unit = {},
    ): TranslationResult =
        withContext(Dispatchers.IO) {
            val tag = "MangaTranslateModel"
            val useVisualModel = settingsManager.useVisualModelForTranslation
            val connection =
                if (useVisualModel) {
                    modelConnection(
                        apiKey = settingsManager.visualModelApiKey,
                        apiBase = settingsManager.visualModelApiBase,
                        model = settingsManager.visualModel,
                    )
                } else {
                    modelConnection(
                        apiKey = settingsManager.translateModelApiKey,
                        apiBase = settingsManager.translateModelApiBase,
                        model = settingsManager.translateModel,
                    )
                }
            if (connection.apiKey.isEmpty()) {
                throw Exception(
                    "${if (useVisualModel) "VisualModel" else "TranslateModel"} API Key is missing. Please configure it in settings.",
                )
            }

            val tool =
                OpenAITool(
                    type = "function",
                    function =
                        OpenAIFunction(
                            name = "submit_translation_result",
                            description = "提交翻译和词汇解析结果",
                            parameters = buildToolParameters("翻译结果", TranslationResult::class),
                        ),
                )

            AppLog.d(
                "$tag/Request",
                "Requesting TranslateModel Translation: URL=${connection.endpoint}, Model=${connection.model}",
            )

            val finalJson =
                chatWithTool(
                    tag = tag,
                    connection = connection,
                    thinkingEffort = thinkingEffort,
                    systemPrompt = Prompts.TRANSLATE,
                    userContent = JsonPrimitive(japaneseText),
                    tool = tool,
                    onOutputDelta = onOutputDelta,
                    onReasoningDelta = onReasoningDelta,
                    onStats = onStats,
                )
            decodeToolResult(tag, finalJson, TranslationResult.serializer())
        }

    private data class ModelConnection(
        val apiKey: String,
        val endpoint: String,
        val model: String,
    )

    private fun modelConnection(
        apiKey: String,
        apiBase: String,
        model: String,
    ): ModelConnection {
        val base = apiBase.trimEnd('/')
        return ModelConnection(apiKey, if (base.endsWith("/chat/completions")) base else "$base/chat/completions", model)
    }

    /**
     * Sends one streaming tool-call chat request and returns the assembled tool arguments. [tag]
     * names the endpoint in logs and error messages.
     */
    private suspend fun chatWithTool(
        tag: String,
        connection: ModelConnection,
        thinkingEffort: String,
        systemPrompt: String,
        userContent: JsonElement,
        tool: OpenAITool,
        onOutputDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit,
        onStats: (RequestStats) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            val (thinking, reasoningEffort) = thinkingParams(thinkingEffort)
            val requestObj =
                OpenAIChatRequest(
                    model = connection.model,
                    stream = true,
                    streamOptions = OpenAIStreamOptions(includeUsage = true),
                    thinking = thinking,
                    reasoningEffort = reasoningEffort,
                    tools = listOf(tool),
                    toolChoice = "auto",
                    messages =
                        listOf(
                            OpenAIChatMessage(role = "system", content = JsonPrimitive(systemPrompt)),
                            OpenAIChatMessage(role = "user", content = userContent),
                        ),
                )

            val requestJson = jsonDecoder.encodeToString(requestObj)
            AppLog.d("$tag/OpenAIRequestBody") { "\u2192 Request body:\n" + sanitizeForLog(requestJson) }

            val request =
                Request
                    .Builder()
                    .url(connection.endpoint)
                    .header("Authorization", "Bearer ${connection.apiKey}")
                    .post(requestJson.toRequestBody(jsonMediaType))
                    .build()

            val startedAt = SystemClock.elapsedRealtime()
            var ttftMillis = 0L
            var reportedUsage = TokenUsage()

            fun markFirstToken() {
                if (ttftMillis == 0L) ttftMillis = SystemClock.elapsedRealtime() - startedAt
            }

            val call = client.newCall(request)
            // The blocking read below ignores coroutine cancellation, so abort the call when the job dies.
            coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    AppLog.e("$tag/RequestError", "API Error Response: ${response.code} - $errorBody")
                    throw Exception("$tag request failed: ${response.code}\n$errorBody")
                }

                val source = response.body.source()
                val contentBuilder = java.lang.StringBuilder()
                val functionArguments = java.lang.StringBuilder()
                val reasoningBuilder = java.lang.StringBuilder()

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
                                    markFirstToken()
                                    onReasoningDelta(reasoning)
                                    reasoningBuilder.append(reasoning)
                                }

                                val content = delta.optString("content", "")
                                if (content.isNotEmpty()) {
                                    markFirstToken()
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
                                        markFirstToken()
                                        onOutputDelta(arguments)
                                    }
                                }
                            }
                        }

                        // With stream_options.include_usage the server reports token usage once, on the
                        // last chunk of the stream. Log the whole object for cost/debug inspection.
                        val usage = jsonChunk.optJSONObject("usage")
                        if (usage != null) {
                            AppLog.i("$tag/OpenAIUsage", usage.toString())
                            reportedUsage = parseUsage(usage)
                        }
                    } catch (e: Exception) {
                        // Ignore JSON parse errors for incomplete chunks
                    }
                }

                if (reasoningBuilder.isNotEmpty()) {
                    AppLog.i("$tag/OpenAIReasoning", reasoningBuilder.toString())
                }

                val finalJson = if (functionArguments.isNotEmpty()) functionArguments.toString() else contentBuilder.toString()
                AppLog.d("$tag/ResponseBody") { "\u2190 Assembled final JSON:\n$finalJson" }
                if (finalJson.isEmpty()) throw Exception("No valid tool calls or content found in $tag response")
                onStats(
                    RequestStats(
                        usage = reportedUsage,
                        ttfts = listOf(ttftMillis),
                        elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                    ),
                )
                finalJson
            }
        }

    private fun parseUsage(usage: JSONObject): TokenUsage =
        TokenUsage(
            input = usage.optInt("prompt_tokens"),
            output = usage.optInt("completion_tokens"),
            cached = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: 0,
            reasoning = usage.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens") ?: 0,
            total = usage.optInt("total_tokens"),
        )

    private fun <T> decodeToolResult(
        tag: String,
        finalJson: String,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val cleanJson =
            finalJson
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()
        return try {
            jsonDecoder.decodeFromString(deserializer, cleanJson)
        } catch (e: Exception) {
            AppLog.e("$tag/ParseError", "Failed to parse JSON: $finalJson", e)
            throw Exception("Failed to parse JSON: ${e.message}\n$finalJson")
        }
    }

    /** Replaces the base64 image payload with a placeholder so logcat stays readable. */
    private fun sanitizeForLog(json: String): String =
        json.replace(
            Regex("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+"),
            "data:image/png;base64,<BASE64_OMITTED>",
        )

    /**
     * Thinking is always on; `reasoning_effort` carries the user's effort token verbatim
     * (e.g. "low" / "high" / "max", as configured in the thinking-strength sequence).
     */
    private fun thinkingParams(effort: String): Pair<OpenAIThinkingConfig, String> = OpenAIThinkingConfig("enabled") to effort

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
internal fun buildToolParameters(
    description: String,
    schemaClass: kotlin.reflect.KClass<*>,
): JsonObject {
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
private fun inlineRefs(
    element: JsonElement,
    defs: JsonObject?,
): JsonElement =
    when (element) {
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

        is JsonArray -> {
            buildJsonArray {
                element.forEach { add(inlineRefs(it, defs)) }
            }
        }

        else -> {
            element
        }
    }
