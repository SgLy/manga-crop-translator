package me.sgly.translator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIChatMessage>,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    val tools: List<OpenAITool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val stream: Boolean? = null,
    @SerialName("stream_options") val streamOptions: OpenAIStreamOptions? = null,
    val thinking: OpenAIThinkingConfig? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class OpenAIChatMessage(
    val role: String,
    val content: JsonElement, // String or JsonArray for image
)

@Serializable
data class OpenAIThinkingConfig(
    val type: String,
)

/** Asks an OpenAI-compatible server to append a final SSE chunk carrying the token usage. */
@Serializable
data class OpenAIStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean,
)

data class TokenUsage(
    val input: Int = 0,
    val output: Int = 0,
    val cached: Int = 0,
    val reasoning: Int = 0,
    val total: Int = 0,
) {
    val freshInput: Int get() = input - cached

    val freshOutput: Int get() = output - reasoning

    operator fun plus(other: TokenUsage): TokenUsage =
        TokenUsage(
            input = input + other.input,
            output = output + other.output,
            cached = cached + other.cached,
            reasoning = reasoning + other.reasoning,
            total = total + other.total,
        )
}

enum class RequestKind {
    Vision,
    Translate,
}

enum class RequestState {
    Success,
    Error,
    Cancel,
}

data class RequestStats(
    val kind: RequestKind,
    val state: RequestState,
    val model: String,
    val usage: TokenUsage,
    val startedAtMillis: Long,
    val ttftMillis: Long,
    val elapsedMillis: Long,
)

@Serializable
data class OpenAITool(
    val type: String,
    val function: OpenAIFunction,
)

@Serializable
data class OpenAIFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null,
)
