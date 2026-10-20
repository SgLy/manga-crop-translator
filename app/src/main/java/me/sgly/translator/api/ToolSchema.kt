package me.sgly.translator.api

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

private val schemaJsonDecoder = Json { ignoreUnknownKeys = true }

/**
 * Builds an OpenAI-compatible tool `parameters` object from a Kotlin data class.
 *
 * kotlinx-schema emits a data-class schema containing:
 *   - "$id": "me.sgly.translator.api.OcrResult"  → a *relative* URI, which OpenAI's validator
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
