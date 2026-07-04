package com.unfuckdoc.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Translate simple search inputs into an OpenSearch bool query (term/range + multi_match). */
object Dsl {
    private val opRe = Regex("^(>=|<=|>|<)\\s*(.+)$")
    private val rangeRe = Regex("^(.+?)\\s*(?:\\.\\.|to)\\s*(.+)$")

    private fun num(s: String): JsonPrimitive =
        s.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(s)

    private fun filterClause(field: String, value: String): JsonObject = buildJsonObject {
        opRe.matchEntire(value.trim())?.let { m ->
            val key = mapOf(">" to "gt", ">=" to "gte", "<" to "lt", "<=" to "lte")[m.groupValues[1]]!!
            putJsonObject("range") { putJsonObject(field) { put(key, num(m.groupValues[2])) } }
            return@buildJsonObject
        }
        rangeRe.matchEntire(value.trim())?.let { m ->
            putJsonObject("range") { putJsonObject(field) { put("gte", num(m.groupValues[1])); put("lte", num(m.groupValues[2])) } }
            return@buildJsonObject
        }
        putJsonObject("term") { put(field, value) }
    }

    /** The query body ({"bool":{...}} or {"match_all":{}}) — parsed into a typed Query at query time. */
    fun query(q: String?, filters: Map<String, String>, textFields: List<String>): JsonObject {
        val hasMust = !q.isNullOrBlank()
        val hasFilter = filters.isNotEmpty()
        if (!hasMust && !hasFilter) return buildJsonObject { putJsonObject("match_all") {} }
        return buildJsonObject {
            putJsonObject("bool") {
                if (hasMust) putJsonArray("must") {
                    add(buildJsonObject {
                        putJsonObject("multi_match") {
                            put("query", q)
                            putJsonArray("fields") { textFields.forEach { add(it) } }
                        }
                    })
                }
                if (hasFilter) putJsonArray("filter") {
                    filters.forEach { (f, v) -> add(filterClause(f, v)) }
                }
            }
        }
    }

    /** Full DSL for display: {"size": N, "query": {...}}. */
    fun display(query: JsonObject, size: Int): JsonObject = buildJsonObject {
        put("size", size)
        put("query", query)
    }

    fun toJson(obj: JsonObject): String = Json.encodeToString(JsonObject.serializer(), obj)

    /** Convert an OpenSearch source map (Jackson types) into a kotlinx JsonElement for responses. */
    fun anyToJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Map<*, *> -> buildJsonObject { v.forEach { (k, vv) -> put(k.toString(), anyToJson(vv)) } }
        is Iterable<*> -> buildJsonArray { v.forEach { add(anyToJson(it)) } }
        else -> JsonPrimitive(v.toString())
    }
}
