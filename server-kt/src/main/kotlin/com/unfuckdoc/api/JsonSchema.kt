package com.unfuckdoc.api

import com.unfuckdoc.domain.Unified
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Convert an OpenSearch index mapping into a JSON Schema (Draft 2020-12) definition. Array cardinality
 * comes from the consolidation metadata, so a field OpenSearch stores as scalar-or-array becomes a
 * proper JSON Schema array (of a scalar, or of {type,value} objects for semantic arrays).
 */
object JsonSchema {

    private val osToJson = mapOf(
        "text" to "string", "keyword" to "string",
        "double" to "number", "float" to "number",
        "long" to "integer", "integer" to "integer",
        "boolean" to "boolean",
    )

    private fun fieldSchema(defn: JsonObject): JsonObject {
        val type = defn["type"]?.jsonPrimitive?.contentOrNull
        val props = defn["properties"]?.jsonObject
        if (props != null && type == null) return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { props.forEach { (k, v) -> put(k, fieldSchema(v.jsonObject)) } }
        }
        if (type == "knn_vector") {
            val d = defn["dimension"]?.jsonPrimitive?.intOrNull
            return buildJsonObject {
                put("type", "array"); putJsonObject("items") { put("type", "number") }
                if (d != null) { put("minItems", d); put("maxItems", d); put("description", "$d-dim embedding vector") }
            }
        }
        if (type == "date") return buildJsonObject { put("type", "string"); put("format", "date") }
        return buildJsonObject { put("type", osToJson[type] ?: "string") }
    }

    fun convert(mapping: JsonObject, unified: List<Unified>, title: String): JsonObject {
        val props = mapping["mappings"]?.jsonObject?.get("properties")?.jsonObject ?: JsonObject(emptyMap())
        val card = unified.associateBy { it.canonical }
        return buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("title", title)
            put("type", "object")
            putJsonObject("properties") {
                props.forEach { (name, defn) ->
                    var base = fieldSchema(defn.jsonObject)
                    if (card[name]?.cardinality == "array") base = buildJsonObject {
                        put("type", "array"); put("items", base)
                    }
                    put(name, base)
                }
            }
            put("additionalProperties", false)
        }
    }
}
