package me.sgly.translator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression test for the fix to "Invalid schema for function ...: relative URL without a base".
 *
 * kotlinx-schema emits "$id" = fully-qualified class name (a *relative* URI) and, for nested
 * types, "$defs" + "$ref": "#/$defs/...". Strict OpenAI-compatible validators try to parse those
 * as URLs and reject them. buildToolParameters() must strip them and inline all refs.
 */
class ToolSchemaTest {

    private fun collectKeys(element: kotlinx.serialization.json.JsonElement): List<String> =
        buildList {
            when (element) {
                is JsonObject -> element.forEach { (k, v) ->
                    add(k)
                    addAll(collectKeys(v))
                }
                is JsonArray -> element.forEach { addAll(collectKeys(it)) }
                else -> {}
            }
        }

    private fun assertCleanAndSelfContained(params: JsonObject) {
        val keys = collectKeys(params)
        assertFalse("parameters must not contain \$id", keys.contains("\$id"))
        assertFalse("parameters must not contain \$ref", keys.contains("\$ref"))
        assertFalse("parameters must not contain \$defs", keys.contains("\$defs"))
        assertFalse("parameters must not contain \$schema", keys.contains("\$schema"))
        assertEquals("object", (params["type"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun ocrToolSchemaIsSelfContained() {
        assertCleanAndSelfContained(buildToolParameters("提取结果", OcrResult::class))
    }

    @Test
    fun translationToolSchemaInlinesNestedTypes() {
        val params = buildToolParameters("翻译结果", TranslationResult::class)
        assertCleanAndSelfContained(params)
        // Nested types must be inlined, not referenced.
        val keys = collectKeys(params)
        assertFalse("nested object should be inlined", keys.contains("\$ref"))
    }
}
