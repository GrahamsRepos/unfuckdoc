package com.unfuckdoc.api

import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.Dsl
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.opensearch.OpenSearchService
import jakarta.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
    private class RawFile(val name: String, val headers: List<String>, val rows: List<Map<String, String?>>)
    private class Collection(val name: String, val index: String, var keyField: String) {
        val rawFiles = mutableListOf<RawFile>()                     // retained so a re-map can rebuild
        val overrides = LinkedHashMap<String, String>()             // raw column name -> forced canonical
        val schema = LinkedHashMap<String, Agg>()
        val files = mutableListOf<CollectionFileDto>()
        val entities = mutableListOf<MutableMap<String, Any?>>()   // deduped buckets
        val keyIndex = HashMap<String, Int>()                       // normalized key value -> bucket index
        val segments = mutableListOf<Pair<String, List<FieldFilter>>>()   // named filtered views
        val customCanonicals = LinkedHashMap<String, String>()            // user-defined canonical -> osType
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
        if (c.rawFiles.any { short(it.name) == short(filename) }) return CollectionAddResponse(error = "${short(filename)} already in collection")

        c.rawFiles.add(RawFile(filename, headers, rows))
        val mapping = ingest(c, filename, headers, rows)
        c.opensearch = reindex(c)
        return CollectionAddResponse(added = short(filename), mapping = mapping, detail = detail(c))
    }

    /** Manually override how a raw column maps to a canonical field, then rebuild the merge. Blank
     *  canonical clears the override (back to inferred). */
    fun setMapping(name: String, column: String, canonical: String): CollectionDetail? {
        val c = collections[name] ?: return null
        if (canonical.isBlank()) c.overrides.remove(column) else c.overrides[column] = canonical.trim()
        rebuild(c)
        c.opensearch = reindex(c)
        return detail(c)
    }

    /** Change the association key used to bucket records across files, then rebuild all entities. */
    fun setKey(name: String, key: String): CollectionDetail? {
        val c = collections[name] ?: return null
        val canonical = key.trim().ifBlank { return detail(c) }
        c.keyField = canonical
        rebuild(c)
        c.opensearch = reindex(c)
        return detail(c)
    }

    /** Re-run classify -> (override-aware) canonicalize -> merge over every retained file. */
    private fun rebuild(c: Collection) {
        c.schema.clear(); c.files.clear(); c.entities.clear(); c.keyIndex.clear()
        c.rawRecords = 0; c.merged = 0
        for (rf in c.rawFiles) ingest(c, rf.name, rf.headers, rf.rows)
    }

    /** Process one file (honouring overrides) and dedup-merge its records into the collection. */
    private fun ingest(c: Collection, filename: String, headers: List<String>, rows: List<Map<String, String?>>): List<FileMappingEntry> {
        val result = pipeline.process(filename, headers, rows, c.overrides)
        val cons = consolidator.consolidate(rows, result.columns)
        val mapping = result.columns.filter { it.searchable }
            .map { FileMappingEntry(it.name, it.canonical, it.canonicalMethod) }

        for (u in cons.unified) {
            val agg = c.schema.getOrPut(u.canonical) { Agg(u.osType, u.kind, u.cardinality) }
            if (filename !in agg.sources) agg.sources.add(filename)
            agg.types.add(u.osType)
            if (u.cardinality == "array") agg.cardinality = "array"
        }
        for (doc in cons.docs) {
            c.rawRecords++
            val kv = Docs.normKey(doc[c.keyField], c.keyField)
            val idx = if (kv.isNotEmpty()) c.keyIndex[kv] else null
            if (idx != null) {
                val e = c.entities[idx]
                for ((k, v) in doc) if (v != null) e[k] = mergeValue(c, k, e[k], v)
                val src = sources(e).toMutableList()
                if (filename !in src) {
                    src.add(filename)
                    e["_source_file"] = src
                }
                c.merged++
            } else {
                val e = LinkedHashMap<String, Any?>()
                for ((k, v) in doc) if (v != null) e[k] = v
                e["_source_file"] = mutableListOf(filename)
                c.entities.add(e)
                if (kv.isNotEmpty()) c.keyIndex[kv] = c.entities.size - 1
            }
        }
        c.schema.forEach { (canon, agg) ->
            agg.count = c.entities.count { it[canon] != null }
            // a user-defined canonical's declared type governs the field everywhere (search dtypes,
            // facets, display) — not just the detail view.
            c.customCanonicals[canon]?.let { agg.osType = it }
        }
        c.files.add(CollectionFileDto(filename, result.nRows, mapping))
        return mapping
    }

    /** Create or replace a named segment (saved filtered view) on the collection. */
    fun putSegment(name: String, segName: String, filters: List<FieldFilter>): CollectionDetail? {
        val c = collections[name] ?: return null
        if (segName.isBlank()) return detail(c)
        c.segments.removeAll { it.first == segName }
        c.segments.add(segName to filters)
        return detail(c)
    }

    private val allowedTypes = setOf("keyword", "text", "long", "double", "date", "boolean")

    /** Define (or update) a user-defined canonical target with a declared type. */
    fun putCanonical(name: String, canon: String, osType: String): CollectionDetail? {
        val c = collections[name] ?: return null
        val cn = canon.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (cn.isEmpty()) return detail(c)
        c.customCanonicals[cn] = osType.takeIf { it in allowedTypes } ?: "keyword"
        rebuild(c); c.opensearch = reindex(c)
        return detail(c)
    }

    /** Remove a user-defined canonical; any columns mapped to it fall back to inference. */
    fun deleteCanonical(name: String, canon: String): CollectionDetail? {
        val c = collections[name] ?: return null
        c.customCanonicals.remove(canon)
        c.overrides.entries.removeAll { it.value == canon }   // drop overrides pointing at it
        rebuild(c); c.opensearch = reindex(c)
        return detail(c)
    }

    fun deleteSegment(name: String, segName: String): CollectionDetail? {
        val c = collections[name] ?: return null
        c.segments.removeAll { it.first == segName }
        return detail(c)
    }

    fun search(
        name: String,
        q: String,
        tag: String,
        sourceFiles: List<String>,
        filters: List<FieldFilter>,
        size: Int,
        page: Int,
    ): CollectionSearchResponse? {
        val c = collections[name] ?: return null
        val display = collDisplay(c)
        val dtypes = c.schema.mapValues { Docs.dtypeOf(it.value.osType) }
        val ql = q.lowercase()
        val tagValue = tag.trim()
        val sourceValues = sourceFiles.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val safeSize = size.coerceAtLeast(1)
        val safePage = page.coerceAtLeast(1)
        val offset = ((safePage - 1).toLong() * safeSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val out = ArrayList<Map<String, String>>()
        var total = 0
        for (d in c.entities) {
            if (tagValue.isNotEmpty() && !entityTags(c, d).contains(tagValue)) continue
            if (sourceValues.isNotEmpty() && sourceFiles(d).none { it in sourceValues }) continue
            if (filters.any { f -> !Docs.filterMatch(Docs.fieldValues(d[f.field]), f.value, dtypes[f.field]) }) continue
            if (ql.isNotEmpty() && ql !in Docs.blob(d)) continue
            if (total >= offset && out.size < safeSize) {
                out.add(display.associateWith { col ->
                    when (col) {
                        "_source_file" -> sourceFiles(d).joinToString(", ")
                        "name" -> Docs.rowName(d)
                        else -> Docs.flattenText(d[col])
                    }
                })
            }
            total++
        }
        val query = Dsl.query(
            q.ifBlank { null },
            filters.associate { it.field to it.value },
            listOf("*"),
            tagValue.ifBlank { null },
            "_tags",
            sourceValues.firstOrNull(),
            "_source_file",
        )
        return CollectionSearchResponse(display, out.size, total, safePage, safeSize, out, Dsl.display(query, safeSize), c.index)
    }

    // ---- internals ----
    private fun collDisplay(c: Collection): List<String> {
        val fields = c.schema.keys
        val cols = mutableListOf("_source_file")
        if (fields.any { it in setOf("full_name", "first_name", "last_name") }) cols.add("name")
        collOrder.filter { it in fields }.forEach { cols.add(it) }
        fields.filterNot { it in cols || it in setOf("full_name", "first_name", "last_name") }
            .sorted()
            .forEach { cols.add(it) }
        return cols
    }

    private fun sourceFiles(entity: Map<String, Any?>): List<String> =
        sources(entity).map { short(it) }.distinct().sorted()

    private fun segmentCount(c: Collection, filters: List<FieldFilter>): Int {
        val dtypes = c.schema.mapValues { Docs.dtypeOf(it.value.osType) }
        return c.entities.count { d -> filters.all { f -> Docs.filterMatch(Docs.fieldValues(d[f.field]), f.value, dtypes[f.field]) } }
    }

    /** Distinct [value, count] pairs for a low-cardinality keyword field, so the UI can offer a picker. */
    private fun facetValues(c: Collection, field: String, osType: String?) =
        if (osType != "keyword") null else {
            val counts = LinkedHashMap<String, Int>()
            c.entities.forEach { d -> Docs.fieldValues(d[field]).forEach { v -> counts.merge(v.toString(), 1, Int::plus) } }
            if (counts.isEmpty() || counts.size > 40) null
            else counts.entries.sortedByDescending { it.value }.map { Dsl.anyToJson(listOf(it.key, it.value)) }
        }

    private fun detail(c: Collection): CollectionDetail {
        val schema = c.schema.entries
            .sortedWith(compareBy({ -it.value.sources.size }, { it.key }))
            .map { (field, a) ->
                // a.osType already carries a custom canonical's declared type (applied at ingest)
                SchemaFieldDto(field, a.osType, a.kind, a.cardinality, a.sources.map { short(it) },
                    a.sources.size, a.count, a.types.size > 1, facetValues(c, field, a.osType))
            }
        val segments = c.segments.map { (n, f) -> Segment(n, f, segmentCount(c, f)) }
        val custom = c.customCanonicals.entries.map { (n, t) -> CustomCanonical(n, t, c.schema.containsKey(n)) }
        return CollectionDetail(c.name, c.index, c.entities.size, c.keyField, c.rawRecords, c.merged,
            schema, c.files.map { CollectionFileDto(short(it.name), it.rows, it.mapping) }, segments, c.opensearch,
            collectionTags(c), custom)
    }

    private val wordRe = Regex("[A-Za-z][A-Za-z0-9'-]{2,}")
    private val stop = setOf(
        "the", "and", "for", "with", "from", "that", "this", "they", "them", "their", "will", "would",
        "about", "into", "onto", "than", "then", "have", "has", "had", "needs", "wants", "asked",
        "prefers", "main", "more", "less", "very", "also", "but", "not", "are", "was", "were",
    )

    private fun collectionTags(c: Collection): List<CollectionTag> {
        val textFields = c.schema.filterValues { it.osType == "text" }.keys
        if (textFields.isEmpty()) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        c.entities.forEach { e ->
            entityTags(c, e).forEach { counts.merge(it, 1, Int::plus) }
        }
        return counts.entries.sortedByDescending { it.value }.take(40).map { CollectionTag(it.key, it.value) }
    }

    private fun entityTags(c: Collection, entity: Map<String, Any?>): List<String> =
        c.schema.filterValues { it.osType == "text" }.keys
            .flatMap { f -> extractKeywords(Docs.flattenText(entity[f])) }
            .distinct()

    private fun extractKeywords(text: String): List<String> {
        val words = wordRe.findAll(text.lowercase())
            .map { it.value.trim('\'', '-') }
            .filter { it.length >= 3 && it !in stop }
            .toList()
        val counts = LinkedHashMap<String, Int>()
        words.forEach { counts.merge(it, 1, Int::plus) }
        words.windowed(2).forEach { pair ->
            if (pair.all { it !in stop }) counts.merge(pair.joinToString(" "), 2, Int::plus)
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }.take(8)
    }

    private fun mergeValue(c: Collection, field: String, existing: Any?, incoming: Any?): Any? {
        if (existing == null) return normalizeValue(c, field, incoming)
        if (incoming == null) return normalizeValue(c, field, existing)
        if (c.schema[field]?.cardinality != "array") return existing
        return mergeTagged(toTagged(existing), toTagged(incoming))
    }

    private fun normalizeValue(c: Collection, field: String, value: Any?): Any? =
        if (c.schema[field]?.cardinality == "array") toTagged(value) else value

    private fun toTagged(value: Any?): List<Map<String, Any?>> = when (value) {
        null -> emptyList()
        is List<*> -> value.flatMap { toTagged(it) }
        is Map<*, *> -> {
            val v = value["value"] ?: return emptyList()
            listOf(mapOf("type" to (value["type"]?.toString()?.ifBlank { "other" } ?: "other"), "value" to v))
        }
        else -> listOf(mapOf("type" to "other", "value" to value))
    }

    private fun mergeTagged(a: List<Map<String, Any?>>, b: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val byValue = LinkedHashMap<String, Map<String, Any?>>()
        for (item in a + b) {
            val v = item["value"] ?: continue
            val key = v.toString().trim().lowercase()
            val prev = byValue[key]
            if (prev == null || prev["type"] == "other" && item["type"] != "other") byValue[key] = item
        }
        return byValue.values.toList()
    }

    private fun mappingJson(c: Collection): String {
        val obj = buildJsonObject {
            putJsonObject("properties") {
                c.schema.forEach { (field, a) ->
                    if (a.cardinality == "array") {
                        putJsonObject(field) {
                            put("type", "nested")
                            putJsonObject("properties") {
                                putJsonObject("type") { put("type", "keyword") }
                                putJsonObject("value") { put("type", a.osType ?: "keyword") }
                            }
                        }
                    } else {
                        putJsonObject(field) { put("type", a.osType ?: "keyword") }
                    }
                }
                putJsonObject("_source_file") { put("type", "keyword") }
                putJsonObject("_tags") { put("type", "keyword") }
            }
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    /** Preserve multi-valued fields as tagged nested objects in the per-collection OpenSearch index. */
    private fun reindex(c: Collection): OsStatus = runCatching {
        if (!opensearch.available()) return OsStatus("unavailable")
        val flat = c.entities.map { d ->
            val out = LinkedHashMap<String, Any?>()
            d.entries.forEach { (k, v) -> out[k] = normalizeValue(c, k, v) }
            out["_tags"] = entityTags(c, d)
            out
        }
        OsStatus("indexed", c.index, opensearch.indexDocs(c.index, mappingJson(c), flat))
    }.getOrElse { OsStatus("error", detail = it.message?.take(160)) }
}
