package com.unfuckdoc.api

import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.Dsl
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.opensearch.OpenSearchService
import jakarta.inject.Inject

/**
 * Collections of BUCKETS. A collection has a user-definable unique key (default `email`) that acts
 * as the dedup key: adding a file merges each record into the bucket with the same key value
 * (survivorship union of fields); records with no key value stay their own bucket. Buckets can
 * span multiple source files. Everything lands in one per-collection OpenSearch index.
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
    private class Collection(val name: String, val index: String, val keyField: String) {
        val schema = LinkedHashMap<String, Agg>()
        val files = mutableListOf<CollectionFileDto>()
        val entities = mutableListOf<MutableMap<String, Any?>>()   // deduped buckets
        val keyIndex = HashMap<String, Int>()                       // normalized key value -> bucket index
        val segments = mutableListOf<Pair<String, List<FieldFilter>>>()   // named filtered views
        var rawRecords = 0
        var merged = 0
        var opensearch = OsStatus("unknown")
    }

    private val collections = LinkedHashMap<String, Collection>()
    private val collOrder = listOf("company", "email", "phone", "country", "city", "job_title", "amount", "date", "lead_source")

    private fun slug(name: String) = "col_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
    private fun short(name: String?) = name?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
    private fun sources(e: Map<String, Any?>): List<String> =
        (e["_source_file"] as? List<*>)?.map { it.toString() } ?: listOfNotNull(e["_source_file"] as? String)

    fun list(): List<CollectionSummary> = collections.values.map {
        CollectionSummary(it.name, it.index, it.files.size, it.entities.size, it.schema.size, it.keyField)
    }

    fun create(name: String, key: String): CollectionDetail? {
        if (name.isBlank() || collections.containsKey(name)) return null
        collections[name] = Collection(name, slug(name), key.ifBlank { "email" })
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

        // accumulate schema metadata (types/sources) from this file
        for (u in cons.unified) {
            val agg = c.schema.getOrPut(u.canonical) { Agg(u.osType, u.kind, u.cardinality) }
            if (filename !in agg.sources) agg.sources.add(filename)
            agg.types.add(u.osType)
        }

        // dedup-merge each record into a bucket keyed by the collection's key field
        for (doc in cons.docs) {
            c.rawRecords++
            val kv = Docs.normKey(doc[c.keyField], c.keyField)
            val idx = if (kv.isNotEmpty()) c.keyIndex[kv] else null
            if (idx != null) {
                val e = c.entities[idx]
                for ((k, v) in doc) if (v != null && e[k] == null) e[k] = v      // survivorship union
                val src = (e["_source_file"] as MutableList<String>)
                if (filename !in src) src.add(filename)
                c.merged++
            } else {
                val e = LinkedHashMap<String, Any?>()
                for ((k, v) in doc) if (v != null) e[k] = v
                e["_source_file"] = mutableListOf(filename)
                c.entities.add(e)
                if (kv.isNotEmpty()) c.keyIndex[kv] = c.entities.size - 1
            }
        }
        // recompute per-field coverage over the deduped buckets
        c.schema.forEach { (canon, agg) -> agg.count = c.entities.count { it[canon] != null } }

        c.files.add(CollectionFileDto(filename, result.nRows, mapping))
        c.opensearch = reindex(c)
        return CollectionAddResponse(added = short(filename), mapping = mapping, detail = detail(c))
    }

    /** Create or replace a named segment (saved filtered view) on the collection. */
    fun putSegment(name: String, segName: String, filters: List<FieldFilter>): CollectionDetail? {
        val c = collections[name] ?: return null
        if (segName.isBlank()) return detail(c)
        c.segments.removeAll { it.first == segName }
        c.segments.add(segName to filters)
        return detail(c)
    }

    fun deleteSegment(name: String, segName: String): CollectionDetail? {
        val c = collections[name] ?: return null
        c.segments.removeAll { it.first == segName }
        return detail(c)
    }

    fun search(name: String, q: String, filters: List<FieldFilter>, size: Int): CollectionSearchResponse? {
        val c = collections[name] ?: return null
        val display = collDisplay(c)
        val dtypes = c.schema.mapValues { Docs.dtypeOf(it.value.osType) }
        val ql = q.lowercase()
        val out = ArrayList<Map<String, String>>()
        for (d in c.entities) {
            if (filters.any { f -> !Docs.filterMatch(Docs.fieldValues(d[f.field]), f.value, dtypes[f.field]) }) continue
            if (ql.isNotEmpty() && ql !in Docs.blob(d)) continue
            out.add(display.associateWith { col ->
                when (col) {
                    "_source_file" -> sources(d).joinToString(", ") { short(it) }
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

    private fun segmentCount(c: Collection, filters: List<FieldFilter>): Int {
        val dtypes = c.schema.mapValues { Docs.dtypeOf(it.value.osType) }
        return c.entities.count { d -> filters.all { f -> Docs.filterMatch(Docs.fieldValues(d[f.field]), f.value, dtypes[f.field]) } }
    }

    private fun detail(c: Collection): CollectionDetail {
        val schema = c.schema.entries
            .sortedWith(compareBy({ -it.value.sources.size }, { it.key }))
            .map { (field, a) ->
                SchemaFieldDto(field, a.osType, a.kind, a.cardinality, a.sources.map { short(it) },
                    a.sources.size, a.count, a.types.size > 1)
            }
        val segments = c.segments.map { (n, f) -> Segment(n, f, segmentCount(c, f)) }
        return CollectionDetail(c.name, c.index, c.entities.size, c.keyField, c.rawRecords, c.merged,
            schema, c.files.map { CollectionFileDto(short(it.name), it.rows, it.mapping) }, segments, c.opensearch)
    }

    /** Flatten arrays/objects/multi-source to strings and dynamic-map into one per-collection index. */
    private fun reindex(c: Collection): OsStatus = runCatching {
        if (!opensearch.available()) return OsStatus("unavailable")
        val flat = c.entities.map { d ->
            d.entries.associate { (k, v) -> k to (if (v is List<*> || v is Map<*, *>) Docs.flattenText(v) else v) }
        }
        OsStatus("indexed", c.index, opensearch.indexDocs(c.index, "{\"properties\":{}}", flat))
    }.getOrElse { OsStatus("error", detail = it.message?.take(160)) }
}
