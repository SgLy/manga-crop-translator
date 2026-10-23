package me.sgly.translator.api

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.sgly.translator.logs.AppLog
import okhttp3.Response
import org.json.JSONObject

/** Usage and TTFT stay readable after a failed read, so the caller can still report what arrived. */
internal class ChatStreamReader(
    private val tag: String,
    private val startedAt: Long,
    private val onOutputDelta: suspend (String) -> Unit,
    private val onReasoningDelta: suspend (String) -> Unit,
) {
    var usage: TokenUsage = TokenUsage()
        private set

    var ttftMillis: Long = 0L
        private set

    suspend fun read(response: Response): String =
        withContext(Dispatchers.IO) {
            val source = response.body.source()
            val contentBuilder = java.lang.StringBuilder()
            val functionArguments = java.lang.StringBuilder()
            val reasoningBuilder = java.lang.StringBuilder()

            fun markFirstToken() {
                if (ttftMillis == 0L) ttftMillis = SystemClock.elapsedRealtime() - startedAt
            }

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
                                    if (arguments.isNotEmpty()) {
                                        markFirstToken()
                                        onOutputDelta(arguments)
                                    }
                                }
                            }
                        }
                    }

                    // With stream_options.include_usage the server reports token usage once, on the
                    // last chunk of the stream. Log the whole object for cost/debug inspection.
                    val reportedUsage = jsonChunk.optJSONObject("usage")
                    if (reportedUsage != null) {
                        AppLog.i("$tag/OpenAIUsage", reportedUsage.toString())
                        usage = parseUsage(reportedUsage)
                    }
                } catch (e: Exception) {
                }
            }

            if (reasoningBuilder.isNotEmpty()) {
                AppLog.i("$tag/OpenAIReasoning", reasoningBuilder.toString())
            }

            val finalJson = if (functionArguments.isNotEmpty()) functionArguments.toString() else contentBuilder.toString()
            AppLog.d("$tag/ResponseBody") { "\u2190 Assembled final JSON:\n$finalJson" }
            if (finalJson.isEmpty()) throw ModelResponseEmptyException("No valid tool calls or content found in $tag response")
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
