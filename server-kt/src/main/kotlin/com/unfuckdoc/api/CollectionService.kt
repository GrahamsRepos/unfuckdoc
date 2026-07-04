package com.unfuckdoc.api

import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.Dsl
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.opensearch.OpenSearchService
import jakarta.inject.Inject

/**
 * Collections: a durable target schema built from files of any shape. Adding a file infers its
 * columns, maps them onto the collection's canonical fields, appends the records, and reindexes one
 * per-collection OpenSearch index. In-memory (single-process) — Python parity minus disk persistence.
 */
class CollectionService @Inject constructor(
    private val pipeline: Pipeline,
    private val consolidator: Consolidator,
    private val opensearch: OpenSearchService,
) {
    private class Agg(var osType: String?, var kind: String, var cardinality: String) {
        val sources = mutableListOf<String>()
        var count = 0
        val types = mutableSetOf<String?>()
    }
    private class Collection(val name: String, val index: String) {
        val schema = LinkedHashMap<String, Agg>()
        val files = mutableListOf<CollectionFileDto>()
        val docs = mutableListOf<Map<String, Any?>>()
        var opensearch = OsStatus("unknown")
    }

    private val collections = LinkedHashMap<String, Collection>()
    private val collOrder = listOf("company", "email", "phone", "country", "city", "job_title", "amount", "date", "lead_source")

    private fun slug(name: String) = "col_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
    private fun short(name: String?) = name?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""

    fun list(): List<CollectionSummary> = collections.values.map {
        CollectionSummary(it.name, it.index, it.files.size, it.docs.size, it.schema.size)
    }

    fun create(name: String): CollectionDetail? {
        if (name.isBlank() || collections.containsKey(name)) return null
        collections[name] = Collection(name, slug(name))
        return detail(collections[name]!!)
    }

    fun detail(name: String): CollectionDetail? = collections[name]?.let { detail(it) }

    fun delete(name: String) {
        val c = collections.remove(name) ?: return
        runCatching { opensearch.deleteIndex(c.index) }
    }

    fun add(name: String, filename: String, headers: List<String>, rows: List<Map<String, String?>>): CollectionAddResponse {
        val c = collections[name] ?: return CollectionAddResponse(error = "unknown collection")
        if (c.files.any { short(it.name) == short(filename) }) return CollectionAddResponse(error = "${short(filename)} already in collection")

        val result = pipeline.process(filename, headers, rows)
        val cons = consolidator.consolidate(rows, result.columns)
        val mapping = result.columns.filter { it.searchable }
            .map { FileMappingEntry(it.name, it.canonical, it.canonicalMethod) }

        for (u in cons.unified) {
            val agg = c.schema.getOrPut(u.canonical) { Agg(u.osType, u.kind, u.cardinality) }
            if (filename !in agg.sources) agg.sources.add(filename)
            agg.types.add(u.osType)
            agg.count += cons.docs.count { it[u.canonical] != null }
        }
        cons.docs.forEach { d -> c.docs.add(d + ("_source_file" to filename)) }
        c.files.add(CollectionFileDto(filename, result.nRows, mapping))
        c.opensearch = reindex(c)

        return CollectionAddResponse(added = short(filename), mapping = mapping, detail = detail(c))
    }

    fun search(name: String, q: String, filters: List<FieldFilter>, size: Int): CollectionSearchResponse? {
        val c = collections[name] ?: return null
        val display = collDisplay(c)
        val dtypes = c.schema.mapValues { Docs.dtypeOf(it.value.osType) }
        val ql = q.lowercase()
        val out = ArrayList<Map<String, String>>()
        for (d in c.docs) {
            if (filters.any { f -> !Docs.filterMatch(Docs.fieldValues(d[f.field]), f.value, dtypes[f.field]) }) continue
            if (ql.isNotEmpty() && ql !in Docs.blob(d)) continue
            out.add(display.associateWith { col ->
                when (col) {
                    "_source_file" -> short(d["_source_file"] as? String)
                    "name" -> Docs.rowName(d)
                    else -> Docs.flattenText(d[col])
                }
            })
            if (out.size >= size) break
        }
        val query = Dsl.query(q.ifBlank { null }, filters.associate { it.field to it.value }, listOf("*"))
        return CollectionSearchResponse(display, out.size, out, Dsl.display(query, size), c.index)
    }

    // ---- internals ----
    private fun collDisplay(c: Collection): List<String> {
        val fields = c.schema.keys
        val cols = mutableListOf("_source_file")
        if (fields.any { it in setOf("full_name", "first_name", "last_name") }) cols.add("name")
        collOrder.filter { it in fields }.forEach { cols.add(it) }
        return cols.take(8)
    }

    private fun detail(c: Collection): CollectionDetail {
        val schema = c.schema.entries
            .sortedWith(compareBy({ -it.value.sources.size }, { it.key }))
            .map { (field, a) ->
                SchemaFieldDto(field, a.osType, a.kind, a.cardinality, a.sources.map { short(it) },
                    a.sources.size, a.count, a.types.size > 1)
            }
        return CollectionDetail(c.name, c.index, c.docs.size, schema, c.files.map {
            CollectionFileDto(short(it.name), it.rows, it.mapping)
        }, c.opensearch)
    }

    /** Flatten arrays/objects to strings and dynamic-map into one per-collection index. */
    private fun reindex(c: Collection): OsStatus = runCatching {
        if (!opensearch.available()) return OsStatus("unavailable")
        val flat = c.docs.map { d ->
            d.entries.associate { (k, v) -> k to (if (v is List<*> || v is Map<*, *>) Docs.flattenText(v) else v) }
        }
        OsStatus("indexed", c.index, opensearch.indexDocs(c.index, "{\"properties\":{}}", flat))
    }.getOrElse { OsStatus("error", detail = it.message?.take(160)) }
}
