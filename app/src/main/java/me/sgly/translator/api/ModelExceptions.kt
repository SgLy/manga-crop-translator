package me.sgly.translator.api

enum class ModelRole { Visual, Translate }

class MissingApiKeyException(
    val role: ModelRole,
) : RuntimeException("${role.name}Model API Key is missing. Please configure it in settings.")

class ModelRequestFailedException(
    val statusCode: Int,
    val body: String,
) : RuntimeException("Request failed: HTTP $statusCode\n$body")

class ModelResponseParseException(
    val payload: String,
    cause: Throwable?,
) : RuntimeException("Failed to parse JSON: ${cause?.message}\n$payload", cause)

class ModelResponseEmptyException(
    val detail: String,
) : RuntimeException(detail)
