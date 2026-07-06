package com.unfuckdoc.api

import com.unfuckdoc.domain.ColumnInfo
import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.Dsl
import com.unfuckdoc.domain.IndexBuilder
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.opensearch.OpenSearchService
import jakarta.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Holds the single loaded dataset (Python STATE parity) and builds the Overview + serves search. */
class DatasetService @Inject constructor(
    private val pipeline: Pipeline,
    private val consolidator: Consolidator,
    private val indexBuilder: IndexBuilder,
    private val opensearch: OpenSearchService,
) {
    private data class Loaded(
        val overview: Overview,
        val docs: List<Map<String, Any?>>,
        val fuzzy: List<String>,
    )

    @Volatile private var current: Loaded? = null
    private val registry = LinkedHashMap<String, LinkedHashMap<String, RegistrySource>>()

    private val displayOrder = listOf(
        "full_name", "first_name", "last_name", "company", "job_title", "title", "email",
        "phone", "country", "region", "city", "amount", "rating", "date", "interests",
    )

    fun overview(): Overview = current?.overview ?: Overview(loaded = false)

    /** JSON Schema (Draft 2020-12) for the loaded dataset's mapping, or null if nothing loaded. */
    fun schema(): JsonObject? = current?.overview?.let { ov ->
        JsonSchema.convert(ov.mapping as JsonObject, ov.unified, ov.filename ?: "dataset")
    }

    fun load(filename: String, headers: List<String>, rows: List<Map<String, String?>>): Overview {
        val result = pipeline.process(filename, headers, rows)
        val cons = consolidator.consolidate(rows, result.columns)
        val fuzzy = cons.unified.filter { it.kind == "free_text" }.map { it.canonical }
        val docs = enrichKeywords(cons.docs, fuzzy)

        // best-effort index the scalar docs into OpenSearch (matches Python indexing on load)
        val os = runCatching {
            if (!opensearch.available()) OsStatus("unavailable")
            else {
                val bundle = indexBuilder.build(rows, result.columns)
                val idx = "kt_" + filename.substringBeforeLast('.').lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
                OsStatus("indexed", idx, opensearch.indexDocs(idx, bundle.mappingJson, bundle.docs))
            }
        }.getOrElse { OsStatus("error", detail = it.message?.take(160)) }

        updateRegistry(filename, result.columns)

        val tagCounts = buildTagCounts(docs, fuzzy)
        val facets = buildFacets(cons.unified, docs)
        val display = displayColumns(cons.unified)
        val overview = Overview(
            loaded = true, filename = filename, nRows = result.nRows, nCols = result.nCols,
            llmCalls = result.llmCalls, coerced = result.coerced, quarantine = result.quarantine,
            columns = result.columns, kindCounts = result.kindCounts, mergeGroups = result.mergeGroups,
            fuzzy = fuzzy,
            tags = tagCounts.mapValues { (_, counts) ->
                counts.entries.sortedByDescending { it.value }.take(25).map { Dsl.anyToJson(listOf(it.key, it.value)) }
            },
            allTags = tagCounts.values
                .fold(LinkedHashMap<String, Int>()) { acc, counts ->
                    counts.forEach { (k, v) -> acc.merge(k, v, Int::plus) }
                    acc
                }
                .entries.sortedByDescending { it.value }.take(40).map { it.key },
            unified = cons.unified, facets = facets, mapping = buildMapping(cons.unified),
            embedder = null, vecDim = null,
            sampleDocs = docs.take(5).map { Dsl.anyToJson(it) },
            displayColumns = display, registry = registryView(), opensearch = os,
        )
        current = Loaded(overview, docs, fuzzy)
        return overview
    }

    fun search(
        mode: String,
        field: String?,
        q: String,
        tag: String,
        filters: List<FieldFilter>,
        size: Int,
        page: Int,
        showAllColumns: Boolean = false,
    ): SearchResponse {
        val safeSize = size.coerceAtLeast(1)
        val safePage = page.coerceAtLeast(1)
        val offset = ((safePage - 1).toLong() * safeSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val loaded = current ?: return SearchResponse(mode, field, tag, filters, 0, 0, safePage, safeSize, emptyList(), emptyList(),
            Dsl.display(Dsl.query(q, emptyMap(), listOf("*")), safeSize), error = "upload a CSV first")
        val dtypes = loaded.overview.unified.associate { it.canonical to dtypeOf(it.osType) }
        val display = if (showAllColumns) displayColumns(loaded.overview.unified, true) else loaded.overview.displayColumns
        val ql = q.lowercase()
        val tagValue = tag.trim()

        val out = ArrayList<SearchResult>()
        var total = 0
        for (doc in loaded.docs) {
            if (tagValue.isNotEmpty() && !hasExactTag(doc, loaded.fuzzy, tagValue)) continue
            if (filters.any { f -> !filterMatch(fieldValues(doc[f.field]), f.value, dtypes[f.field]) }) continue
            if (ql.isNotEmpty()) {
                val haystack = if (mode == "keyword") keywordBlob(doc, loaded.fuzzy) else blob(doc)
                if (ql !in haystack) continue
            }
            if (total >= offset && out.size < safeSize) {
                val row = buildJsonObject { display.forEach { c -> if (doc.containsKey(c)) put(c, Dsl.anyToJson(doc[c])) } }
                val score = if (ql.isBlank()) JsonPrimitive("●") else JsonPrimitive(1)
                val highlight = tagValue.ifBlank { if (mode == "keyword") q.trim() else "" }.ifBlank { null }
                out.add(SearchResult(score, row, primaryKeywords(doc, field, loaded.fuzzy, highlight)))
            }
            total++
        }
        val filterMap = filters.associate { it.field to it.value }
        val query = Dsl.query(q.ifBlank { null }, filterMap, listOf("*"), tagValue.ifBlank { null }, loaded.fuzzy.firstOrNull())
        return SearchResponse(mode, field, tag, filters, out.size, total, safePage, safeSize, display, out,
            Dsl.display(query, safeSize), index = loaded.overview.opensearch.index)
    }

    // ---- overview building blocks ----
    private fun buildFacets(unified: List<com.unfuckdoc.domain.Unified>, docs: List<Map<String, Any?>>): List<FacetDto> =
        unified.filter { it.kind != "free_text" }.map { u ->
            val counts = LinkedHashMap<String, Int>()
            docs.forEach { d -> fieldValues(d[u.canonical]).forEach { v -> counts.merge(v.toString(), 1, Int::plus) } }
            val values = if (u.osType == "keyword" && counts.size in 1..40)
                counts.entries.sortedByDescending { it.value }.map {
                    Dsl.anyToJson(listOf(it.key, it.value))
                } else null
            FacetDto(u.canonical, u.kind, u.osType, u.cardinality, counts.size, values)
        }

    private fun buildMapping(unified: List<com.unfuckdoc.domain.Unified>): JsonObject = buildJsonObject {
        putJsonObject("mappings") {
            putJsonObject("properties") {
                unified.forEach { u ->
                    if (u.cardinality == "array" && u.style == "semantic") {
                        // labeled {type,value} array -> object mapping (matches the stored docs)
                        putJsonObject(u.canonical) {
                            putJsonObject("properties") {
                                putJsonObject("type") { put("type", "keyword") }
                                putJsonObject("value") { put("type", u.osType ?: "keyword") }
                            }
                        }
                    } else {
                        putJsonObject(u.canonical) { put("type", u.osType ?: "keyword") }
                    }
                }
            }
        }
    }

    private fun displayColumns(unified: List<com.unfuckdoc.domain.Unified>, full: Boolean = false): List<String> {
        val present = unified.filter { it.kind != "free_text" }.map { it.canonical }.toSet()
        val cols = displayOrder.filter { it in present }.toMutableList()
        if (full) {
            cols.addAll(unified.filter { it.kind != "free_text" && it.canonical !in cols }.map { it.canonical }.sorted())
            return cols
        }
        unified.filter { it.kind != "free_text" && it.canonical !in cols }.forEach { if (cols.size < 8) cols.add(it.canonical) }
        return cols.take(8)
    }

    private fun updateRegistry(filename: String, infos: List<ColumnInfo>) {
        infos.filter { it.searchable }.forEach { i ->
            registry.getOrPut(i.canonical) { LinkedHashMap() }["$filename · ${i.name}"] =
                RegistrySource("$filename · ${i.name}", i.kind, i.osType, i.canonicalMethod)
        }
    }

    private fun registryView(): List<RegistryEntry> = registry.map { (canon, srcs) ->
        val files = srcs.keys.map { it.substringBefore(" · ") }.toSet()
        RegistryEntry(canon, srcs.values.toList(), files.size, srcs.size, files.size > 1)
    }.sortedWith(compareBy({ !it.unified }, { -it.nFiles }, { -it.nColumns }, { it.canonical }))

    // ---- helpers ----
    private val wordRe = Regex("[A-Za-z][A-Za-z0-9'-]{2,}")
    private val stop = setOf(
        "the", "and", "for", "with", "from", "that", "this", "they", "them", "their", "will", "would",
        "about", "into", "onto", "than", "then", "have", "has", "had", "needs", "wants", "asked",
        "prefers", "main", "more", "less", "very", "also", "but", "not", "are", "was", "were",
    )

    private fun enrichKeywords(docs: List<Map<String, Any?>>, fuzzy: List<String>): List<Map<String, Any?>> =
        if (fuzzy.isEmpty()) docs else docs.map { doc ->
            val out = LinkedHashMap<String, Any?>(doc)
            fuzzy.forEach { f ->
                out["${f}_keywords"] = extractKeywords(flattenText(doc[f])).take(8)
            }
            out
        }

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
            .map { it.key }
    }

    private fun buildTagCounts(docs: List<Map<String, Any?>>, fuzzy: List<String>): Map<String, LinkedHashMap<String, Int>> =
        fuzzy.associateWith { f ->
            val counts = LinkedHashMap<String, Int>()
            docs.forEach { d -> (d["${f}_keywords"] as? List<*>)?.forEach { counts.merge(it.toString(), 1, Int::plus) } }
            counts
        }

    private fun hasExactTag(doc: Map<String, Any?>, fuzzy: List<String>, tag: String): Boolean =
        fuzzy.any { f -> (doc["${f}_keywords"] as? List<*>)?.any { it.toString() == tag } == true }

    private fun keywordBlob(doc: Map<String, Any?>, fuzzy: List<String>): String =
        fuzzy.joinToString(" ") { f ->
            (doc["${f}_keywords"] as? List<*>)?.joinToString(" ") { it.toString() } ?: ""
        }.lowercase()

    private fun primaryKeywords(doc: Map<String, Any?>, field: String?, fuzzy: List<String>, highlight: String?): List<String> {
        val f = field?.takeIf { it.isNotBlank() } ?: fuzzy.firstOrNull() ?: return emptyList()
        val keywords = (doc["${f}_keywords"] as? List<*>)?.map { it.toString() } ?: emptyList()
        val matched = highlight?.let { h -> keywords.firstOrNull { it == h || h in it || it in h } }
        return if (matched != null)
            (listOf(matched) + keywords.filterNot { it == matched }).take(5)
        else
            keywords.take(5)
    }

    private fun dtypeOf(osType: String?): String? = when (osType) {
        "double", "long", "integer", "float" -> "num"; "date" -> "date"; else -> null
    }

    private fun fieldValues(v: Any?): List<Any?> = when (v) {
        null -> emptyList()
        is List<*> -> v.flatMap { fieldValues(it) }
        is Map<*, *> -> v["value"]?.let { listOf(it) } ?: emptyList()
        else -> listOf(v)
    }

    private fun flattenText(v: Any?): String = when (v) {
        null -> ""; is List<*> -> v.joinToString(" ") { flattenText(it) }
        is Map<*, *> -> v["value"]?.toString() ?: ""; else -> v.toString()
    }

    private fun blob(doc: Map<String, Any?>): String = doc.values.joinToString(" ") { flattenText(it) }.lowercase()

    private val opRe = Regex("^(>=|<=|>|<)\\s*(.+)$")
    private val rangeReNum = Regex("^(.+?)\\s*(?:\\.\\.|to|-)\\s*(.+)$")
    private val rangeReDate = Regex("^(.+?)\\s*(?:\\.\\.|to)\\s*(.+)$")

    private fun filterMatch(values: List<Any?>, needle: String, dtype: String?): Boolean {
        val s = needle.trim()
        if (dtype == "num") {
            val nums = values.mapNotNull { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() }
            opRe.matchEntire(s)?.let { m -> val t = m.groupValues[2].toDoubleOrNull() ?: return false
                return nums.any { cmpNum(m.groupValues[1], it, t) } }
            rangeReNum.matchEntire(s)?.let { m ->
                val lo = m.groupValues[1].toDoubleOrNull(); val hi = m.groupValues[2].toDoubleOrNull()
                if (lo != null && hi != null) return nums.any { it in minOf(lo, hi)..maxOf(lo, hi) } }
            s.toDoubleOrNull()?.let { t -> return nums.any { it == t } }
        } else if (dtype == "date") {
            val strs = values.map { it.toString().trim() }
            opRe.matchEntire(s)?.let { m -> val t = m.groupValues[2].trim(); return strs.any { cmpStr(m.groupValues[1], it, t) } }
            rangeReDate.matchEntire(s)?.let { m -> val lo = m.groupValues[1].trim(); val hi = m.groupValues[2].trim()
                return strs.any { it in lo..hi } }
        }
        val n = s.lowercase()
        return values.any { it?.toString()?.trim()?.lowercase() == n }
    }

    private fun cmpNum(op: String, x: Double, t: Double) = when (op) { ">" -> x > t; ">=" -> x >= t; "<" -> x < t; "<=" -> x <= t; else -> x == t }
    private fun cmpStr(op: String, x: String, t: String) = when (op) { ">" -> x > t; ">=" -> x >= t; "<" -> x < t; "<=" -> x <= t; else -> x == t }
}
