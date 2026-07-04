package com.unfuckdoc.domain

import jakarta.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A canonical-keyed doc set + the OpenSearch mapping for it. */
data class IndexBundle(
    val properties: Map<String, String>,   // canonical -> os type
    val mappingJson: String,
    val docs: List<Map<String, Any?>>,
)

/**
 * Consolidates a classified table into canonical-keyed, cleaned documents (scalar survivorship
 * coalesce) plus the OpenSearch mapping — the input to indexing. Arrays/{type,value} objects from
 * the Python consolidation are out of this slice; scalar coalesce covers the common case.
 */
class IndexBuilder @Inject constructor() {
    private val money = Regex("[,\\s$€£¥₹]")
    private val dateFormats = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd", "MM/dd/yyyy", "M/d/yyyy", "d/M/yyyy",
        "MMM d, yyyy", "MMM d yyyy", "dd-MM-yyyy",
    ).map { DateTimeFormatter.ofPattern(it) }

    fun build(rows: List<Map<String, String?>>, infos: List<ColumnInfo>): IndexBundle {
        val groups = LinkedHashMap<String, MutableList<ColumnInfo>>()
        infos.filter { it.searchable }.forEach { groups.getOrPut(it.canonical) { mutableListOf() }.add(it) }

        val docs = rows.map { row ->
            val doc = LinkedHashMap<String, Any?>()
            for ((canon, cols) in groups) {
                val kind = cols.first().kind
                val value = cols.firstNotNullOfOrNull { info ->
                    row[info.name]?.takeIf { it.isNotBlank() }?.let { cleanCell(it, kind) }
                }
                if (value != null) doc[canon] = value
            }
            doc
        }

        val props = groups.mapValues { (_, cols) -> cols.first().osType ?: "keyword" }
        return IndexBundle(props, buildMapping(props), docs)
    }

    private fun cleanCell(raw: String, kind: String): Any? = when (kind) {
        "numeric" -> money.replace(raw, "").toDoubleOrNull()
        "boolean" -> raw.lowercase() in setOf("true", "yes")
        "date" -> normalizeDate(raw)
        else -> raw
    }

    private fun normalizeDate(raw: String): String {
        val s = raw.trim().removeSuffix("Z").substringBefore("T")
        for (f in dateFormats) {
            try { return LocalDate.parse(s, f).format(DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: Exception) {}
        }
        return s
    }

    /** The TypeMapping body ({"properties": {...}}) — parsed into a typed TypeMapping when indexing. */
    private fun buildMapping(props: Map<String, String>): String {
        val obj = buildJsonObject {
            putJsonObject("properties") {
                props.forEach { (field, type) -> putJsonObject(field) { put("type", type) } }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }
}
