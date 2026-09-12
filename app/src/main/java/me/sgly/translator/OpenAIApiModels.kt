package me.sgly.translator

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

@Serializable
data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIChatMessage>,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    val tools: List<OpenAITool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val stream: Boolean? = null,
    val thinking: OpenAIThinkingConfig? = null
)

@Serializable
data class OpenAIChatMessage(
    val role: String,
    val content: JsonElement // String or JsonArray for image
)

@Serializable
data class OpenAIThinkingConfig(val type: String)

@Serializable
data class OpenAITool(
    val type: String,
    val function: OpenAIFunction,
    val strict: Boolean = true
)

@Serializable
data class OpenAIFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null,
    val strict: Boolean? = null
)
