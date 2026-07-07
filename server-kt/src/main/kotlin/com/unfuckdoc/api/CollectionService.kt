package com.unfuckdoc.api

import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.Dsl
import com.unfuckdoc.domain.Embedder
import com.unfuckdoc.domain.LlmClient
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.domain.TransformEngine
import com.unfuckdoc.opensearch.OpenSearchService
import jakarta.inject.Inject
import kotlinx.serialization.json.Json as KJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
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
    private val embedder: Embedder,
    private val llm: LlmClient,
) {
    private data class ExtractSpec(val name: String, val osType: String, val values: List<String>)
    private val transformEngine = TransformEngine()
    private class Agg(var osType: String?, var kind: String, var cardinality: String) {
        val sources = mutableListOf<String>()
        var count = 0
        var valueConflicts = 0   // entities where same-key duplicates disagreed on this scalar field
        val types = mutableSetOf<String?>()
    }
    private class RawFile(val name: String, val headers: List<String>, val rows: List<Map<String, String?>>)
    /** A lookup join. Reference is either a raw file (headers+rows) or another collection (refCollection). */
    private class Enrich(val source: String, val joinField: String,
                         val headers: List<String>?, val rows: List<Map<String, String?>>?,
                         val refCollection: String?) { var attached: List<String> = emptyList(); var matched = 0 }
    private class Collection(val name: String, val index: String, var keyField: String) {
        val rawFiles = mutableListOf<RawFile>()                     // retained so a re-map can rebuild
        val overrides = LinkedHashMap<String, String>()             // raw column name -> forced canonical
        val schema = LinkedHashMap<String, Agg>()
        val files = mutableListOf<CollectionFileDto>()
        val entities = mutableListOf<MutableMap<String, Any?>>()   // deduped buckets
        val keyIndex = HashMap<String, Int>()                       // normalized key value -> bucket index
        val segments = mutableListOf<Pair<String, List<FieldFilter>>>()   // named filtered views
        val customCanonicals = LinkedHashMap<String, String>()            // user-defined canonical -> osType
        val customArrays = LinkedHashSet<String>()                        // customs declared multi-value (list)
        val enrichments = mutableListOf<Enrich>()                         // lookup joins: attach fields from a reference
        val transforms = mutableListOf<Pair<String, String>>()            // (output field, DSL expression), applied pre-process
        val extractSpecs = mutableListOf<ExtractSpec>()                   // LLM-extracted attributes from free text
        val extractCache = HashMap<String, Map<String, Any?>>()          // (text|specs) -> extracted values, to skip re-calling the LLM
        var vectors: List<FloatArray>? = null      // per-entity embeddings of the free-text field(s); lazy
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

    private fun liveIndexes(): Set<String> = collections.values.map { it.index }.toSet()

    /** App-managed OpenSearch indexes with no live collection behind them — the leak left after a
     *  restart/crash (in-memory collections vanish but their `col_*` indexes persist), plus the
     *  transient `kt_*` Explore/dataset scratch indexes (recreated on next load). */
    fun orphanIndexes(): List<String> {
        val live = liveIndexes()
        return (opensearch.listIndices("col_*").filterNot { it in live } + opensearch.listIndices("kt_*")).distinct().sorted()
    }

    /** Delete the orphan indexes and return what was removed. */
    fun cleanupOrphans(): List<String> {
        val orphans = orphanIndexes()
        orphans.forEach { runCatching { opensearch.deleteIndex(it) } }
        return orphans
    }

    fun add(name: String, filename: String, headers: List<String>, rows: List<Map<String, String?>>): CollectionAddResponse {
        val c = collections[name] ?: return CollectionAddResponse(error = "unknown collection")
        if (c.rawFiles.any { short(it.name) == short(filename) }) return CollectionAddResponse(error = "${short(filename)} already in collection")

        c.rawFiles.add(RawFile(filename, headers, rows))
        val mapping = ingest(c, filename, headers, rows)
        if (c.enrichments.isNotEmpty()) applyEnrichments(c)   // re-attach lookups to the new entities
        if (c.extractSpecs.isNotEmpty()) applyExtraction(c)   // extract attributes for the new entities
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

    /** Add a lookup/enrichment join: attach fields from `source` onto entities whose `joinField`
     *  matches. `joinField` must be a canonical present in both the collection and the reference. */
    fun addEnrichment(name: String, source: String, joinField: String, headers: List<String>, rows: List<Map<String, String?>>): CollectionAddResponse {
        val c = collections[name] ?: return CollectionAddResponse(error = "unknown collection")
        val refUnified = consolidator.consolidate(rows, pipeline.process(source, headers, rows).columns).unified.map { it.canonical }
        if (joinField !in refUnified) return CollectionAddResponse(error = "$source has no '$joinField' field to join on")
        if (joinField !in c.schema.keys) return CollectionAddResponse(error = "collection has no '$joinField' field")
        c.enrichments.removeAll { it.source == source }
        c.enrichments.add(Enrich(source, joinField, headers, rows, null))
        rebuild(c); c.opensearch = reindex(c)
        return CollectionAddResponse(added = short(source), detail = detail(c))
    }

    /** Enrich from ANOTHER collection: attach its fields onto entities sharing `joinField`
     *  (e.g. customers ⋈ a city→geo collection on `city` to attach `location`). Kept live — a
     *  rebuild re-reads the reference collection's current entities. */
    fun addEnrichmentCollection(name: String, refName: String, joinField: String): CollectionAddResponse {
        val c = collections[name] ?: return CollectionAddResponse(error = "unknown collection")
        val rc = collections[refName] ?: return CollectionAddResponse(error = "unknown reference collection")
        if (refName == name) return CollectionAddResponse(error = "a collection can't enrich from itself")
        if (joinField !in rc.schema.keys) return CollectionAddResponse(error = "collection '$refName' has no '$joinField' field")
        if (joinField !in c.schema.keys) return CollectionAddResponse(error = "collection has no '$joinField' field")
        c.enrichments.removeAll { it.source == refName }
        c.enrichments.add(Enrich(refName, joinField, null, null, refName))
        rebuild(c); c.opensearch = reindex(c)
        return CollectionAddResponse(added = refName, detail = detail(c))
    }

    fun removeEnrichment(name: String, source: String): CollectionDetail? {
        val c = collections[name] ?: return null
        c.enrichments.removeAll { it.source == source || short(it.source) == source }
        rebuild(c); c.opensearch = reindex(c)
        return detail(c)
    }

    /** Attach reference fields onto each entity whose join value matches (non-overwrite). */
    private fun applyEnrichments(c: Collection) {
        for (en in c.enrichments) {
            // reference docs + field types come from either another collection or a raw file
            val refDocs: List<Map<String, Any?>>
            val refFields: List<Triple<String, String?, Pair<String, String>>>   // canonical -> (osType, (kind, cardinality))
            if (en.refCollection != null) {
                val rc = collections[en.refCollection] ?: continue
                refDocs = rc.entities
                refFields = rc.schema.map { Triple(it.key, it.value.osType, it.value.kind to it.value.cardinality) }
            } else {
                val cons = consolidator.consolidate(en.rows!!, pipeline.process(en.source, en.headers!!, en.rows).columns)
                refDocs = cons.docs
                refFields = cons.unified.map { Triple(it.canonical, it.osType, it.kind to it.cardinality) }
            }
            val lookup = HashMap<String, Map<String, Any?>>()
            for (doc in refDocs) {
                val jv = Docs.normKey(doc[en.joinField], en.joinField)
                if (jv.isNotEmpty()) lookup.putIfAbsent(jv, doc.filterKeys { it != en.joinField && !it.startsWith("_") })
            }
            en.attached = refFields.map { it.first }.filter { it != en.joinField && !it.startsWith("_") }
            // register attached fields in the schema (typed from the reference) so they're searchable/geo
            for ((canon, osType, kc) in refFields) if (canon != en.joinField && !canon.startsWith("_")) {
                val agg = c.schema.getOrPut(canon) { Agg(osType, kc.first, kc.second) }
                if (en.source !in agg.sources) agg.sources.add(en.source)
            }
            var matched = 0
            for (e in c.entities) {
                val attrs = lookup[Docs.normKey(e[en.joinField], en.joinField)] ?: continue
                var any = false
                for ((k, v) in attrs) if (v != null && e[k] == null) { e[k] = v; any = true }
                if (any) matched++
            }
            en.matched = matched
        }
        c.schema.forEach { (canon, agg) -> agg.count = c.entities.count { it[canon] != null } }
        c.vectors = null
    }

    /** Define a row-level transform: output field + a safe-DSL expression, applied before processing.
     *  Blank expression removes the transform. Compilation errors are returned (no unsafe code runs). */
    fun setTransform(name: String, field: String, expr: String): CollectionAddResponse {
        val c = collections[name] ?: return CollectionAddResponse(error = "unknown collection")
        val fn = field.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (fn.isEmpty()) return CollectionAddResponse(error = "invalid field name")
        c.transforms.removeAll { it.first == fn }
        if (expr.isNotBlank()) {
            runCatching { transformEngine.compile(expr) }.onFailure { return CollectionAddResponse(error = "expression error: ${it.message}") }
            c.transforms.add(fn to expr.trim())
        }
        rebuild(c); c.opensearch = reindex(c)
        return CollectionAddResponse(added = fn, detail = detail(c))
    }

    /** Run every transform over the raw rows, adding/overwriting its output column. Header order: the
     *  file's columns plus any new transform outputs. */
    private fun applyTransforms(c: Collection, headers: List<String>, rows: List<Map<String, String?>>): Pair<List<String>, List<Map<String, String?>>> {
        val compiled = c.transforms.mapNotNull { (fld, ex) -> runCatching { transformEngine.compile(ex) }.getOrNull()?.let { fld to it } }
        val outHeaders = (headers + compiled.map { it.first }).distinct()
        val outRows = rows.map { row ->
            val m = LinkedHashMap<String, String?>(row)
            for ((fld, e) in compiled) m[fld] = runCatching { e.eval(row) }.getOrNull()
            m
        }
        return outHeaders to outRows
    }

    private val extractTypes = setOf("keyword", "boolean", "long", "double")

    /** Define an LLM-extracted attribute (name + type, optional enum values) and populate it from
     *  each entity's free text. Fixes negation/logic that vector search can't ("no garden" -> false). */
    fun addExtraction(name: String, attr: String, type: String, values: List<String>): CollectionAddResponse {
        val c = collections[name] ?: return CollectionAddResponse(error = "unknown collection")
        if (!llm.enabled) return CollectionAddResponse(error = "no LLM configured (set LLM_BASE_URL)")
        if (textFields(c).isEmpty()) return CollectionAddResponse(error = "collection has no free-text field to extract from")
        val an = attr.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (an.isEmpty()) return CollectionAddResponse(error = "invalid attribute name")
        c.extractSpecs.removeAll { it.name == an }
        c.extractSpecs.add(ExtractSpec(an, type.takeIf { it in extractTypes } ?: "keyword", values.map { it.trim() }.filter { it.isNotEmpty() }))
        c.extractCache.clear()   // spec set changed -> re-extract
        rebuild(c); c.opensearch = reindex(c)
        return CollectionAddResponse(added = an, detail = detail(c))
    }

    fun removeExtraction(name: String, attr: String): CollectionDetail? {
        val c = collections[name] ?: return null
        c.extractSpecs.removeAll { it.name == attr }
        c.extractCache.clear()
        rebuild(c); c.opensearch = reindex(c)
        return detail(c)
    }

    /** One LLM call per entity fills all defined attributes from its free text (cached by text+specs). */
    private fun applyExtraction(c: Collection) {
        if (c.extractSpecs.isEmpty() || !llm.enabled) return
        val fields = textFields(c)
        val specKey = c.extractSpecs.joinToString("|") { "${it.name}:${it.osType}:${it.values.joinToString(",")}" }
        val system = "You extract structured attributes from a property/record description. Reply with a " +
            "STRICT JSON object and nothing else. Keys and types:\n" +
            c.extractSpecs.joinToString("\n") { s ->
                val t = when (s.osType) { "boolean" -> "boolean (true/false)"; "long", "double" -> "number"; else ->
                    if (s.values.isNotEmpty()) "one of ${s.values}" else "short string" }
                "- ${s.name}: $t"
            } + "\nUse the negation and wording carefully (e.g. 'no garden' -> has_garden false). " +
            "If a value is not stated, use null."
        for (e in c.entities) {
            val text = fields.joinToString(" ") { Docs.flattenText(e[it]) }.trim()
            if (text.isEmpty()) continue
            val cacheKey = "$specKey||$text"
            val extracted = c.extractCache.getOrPut(cacheKey) {
                runCatching {
                    val obj = KJson.parseToJsonElement(llm.completeJson(system, text)).jsonObject
                    c.extractSpecs.associate { s -> s.name to coerce(obj[s.name]?.jsonPrimitive, s.osType) }
                }.getOrDefault(emptyMap())
            }
            for ((k, v) in extracted) if (v != null) e[k] = v
        }
        // register the extracted attributes as typed schema fields
        for (s in c.extractSpecs) {
            val agg = c.schema.getOrPut(s.name) { Agg(s.osType, kindFor(s.osType), "scalar") }
            agg.osType = s.osType
            if ("(extracted)" !in agg.sources) agg.sources.add("(extracted)")
            agg.count = c.entities.count { it[s.name] != null }
        }
        c.vectors = null
    }

    private fun coerce(p: kotlinx.serialization.json.JsonPrimitive?, osType: String): Any? {
        if (p == null || p.contentOrNull == null || p.contentOrNull.equals("null", true)) return null
        return when (osType) {
            "boolean" -> p.booleanOrNull ?: p.contentOrNull?.toBooleanStrictOrNull()
            "long" -> p.doubleOrNull?.toLong()
            "double" -> p.doubleOrNull
            else -> p.contentOrNull
        }
    }
    private fun kindFor(osType: String) = when (osType) { "boolean" -> "boolean"; "long", "double" -> "numeric"; else -> "enum" }

    /** Re-run classify -> (override-aware) canonicalize -> merge over every retained file, then joins, then extraction. */
    private fun rebuild(c: Collection) {
        c.schema.clear(); c.files.clear(); c.entities.clear(); c.keyIndex.clear()
        c.rawRecords = 0; c.merged = 0
        for (rf in c.rawFiles) ingest(c, rf.name, rf.headers, rf.rows)
        applyEnrichments(c)
        applyExtraction(c)
    }

    /** Process one file (honouring overrides) and dedup-merge its records into the collection. */
    private fun ingest(c: Collection, filename: String, rawHeaders: List<String>, rawRows: List<Map<String, String?>>): List<FileMappingEntry> {
        // row-level transforms mutate values BEFORE classification/canonicalization (OpenRefine-style)
        val (headers, rows) = if (c.transforms.isEmpty()) rawHeaders to rawRows else applyTransforms(c, rawHeaders, rawRows)
        val result = pipeline.process(filename, headers, rows, c.overrides)
        val cons = consolidator.consolidate(rows, result.columns)
        val mapping = result.columns.filter { it.searchable }
            .map { FileMappingEntry(it.name, it.canonical, it.canonicalMethod) }

        for (u in cons.unified) {
            val agg = c.schema.getOrPut(u.canonical) { Agg(u.osType, u.kind, u.cardinality) }
            if (filename !in agg.sources) agg.sources.add(filename)
            agg.types.add(u.osType)
            if (u.cardinality == "array") agg.cardinality = "array"
            // a user-defined canonical's declared type/cardinality govern the field before the merge
            // reads them (search dtypes, facets, display all follow).
            c.customCanonicals[u.canonical]?.let { agg.osType = it }
            if (u.canonical in c.customArrays) agg.cardinality = "array"
        }
        for (doc in cons.docs) {
            c.rawRecords++
            val kv = Docs.normKey(doc[c.keyField], c.keyField)
            val idx = if (kv.isNotEmpty()) c.keyIndex[kv] else null
            if (idx != null) {
                val e = c.entities[idx]
                for ((k, v) in doc) if (v != null) e[k] = mergeValue(c, k, e[k], listValue(c, k, v))
                val src = sources(e).toMutableList()
                if (filename !in src) {
                    src.add(filename)
                    e["_source_file"] = src
                }
                c.merged++
            } else {
                val e = LinkedHashMap<String, Any?>()
                for ((k, v) in doc) if (v != null) e[k] = normalizeValue(c, k, listValue(c, k, v))
                e["_source_file"] = mutableListOf(filename)
                c.entities.add(e)
                if (kv.isNotEmpty()) c.keyIndex[kv] = c.entities.size - 1
            }
        }
        c.schema.forEach { (canon, agg) ->
            agg.count = c.entities.count { it[canon] != null }
            // a scalar field holding a list = same-key duplicates disagreed and were collected -> flag it
            agg.valueConflicts = if (agg.cardinality == "array") 0 else c.entities.count { it[canon] is List<*> }
        }
        c.files.add(CollectionFileDto(filename, result.nRows, mapping))
        c.vectors = null   // entities changed -> stale embeddings; recompute lazily on next semantic search
        return mapping
    }

    /** Embeddable text fields: genuinely free-text columns, plus anything mapped to the `description`
     *  canonical (notes/bio/review/comments) — the "larger text" fields worth vectorising. */
    private fun textFields(c: Collection): List<String> =
        c.schema.entries.filter { it.value.kind == "free_text" || it.key == "description" }.map { it.key }

    /** Lazily embed each entity's free-text field(s) into a per-entity vector. Returns null if unavailable. */
    private fun ensureVectors(c: Collection): List<FloatArray>? {
        if (!embedder.enabled) return null
        val fields = textFields(c)
        if (fields.isEmpty()) return null
        c.vectors?.let { return it }
        val vecs = c.entities.map { e ->
            val text = fields.joinToString(" ") { Docs.flattenText(e[it]) }.trim()
            if (text.isEmpty()) FloatArray(0) else embedder.embed(text)
        }
        c.vectors = vecs
        return vecs
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return -1.0
        var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s   // both L2-normalized -> cosine
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

    /** Define (or update) a user-defined canonical target with a declared type. `array` = multi-value. */
    fun putCanonical(name: String, canon: String, osType: String, array: Boolean): CollectionDetail? {
        val c = collections[name] ?: return null
        val cn = canon.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (cn.isEmpty()) return detail(c)
        c.customCanonicals[cn] = osType.takeIf { it in allowedTypes } ?: "keyword"
        if (array) c.customArrays.add(cn) else c.customArrays.remove(cn)
        rebuild(c); c.opensearch = reindex(c)
        return detail(c)
    }

    /** Remove a user-defined canonical; any columns mapped to it fall back to inference. */
    fun deleteCanonical(name: String, canon: String): CollectionDetail? {
        val c = collections[name] ?: return null
        c.customCanonicals.remove(canon)
        c.customArrays.remove(canon)
        c.overrides.entries.removeAll { it.value == canon }   // drop overrides pointing at it
        rebuild(c); c.opensearch = reindex(c)
        return detail(c)
    }

    // split a delimited multi-value cell into its members (for canonicals declared multi-value)
    private val listDelim = Regex("\\s*[;|,\\n/]\\s*")
    private fun splitMulti(s: String): List<String> =
        s.split(listDelim).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** For a canonical declared multi-value, split a single delimited cell ("golf; wine") into a list
     *  so it consolidates into one array field. Non-array fields / non-strings pass through. */
    private fun listValue(c: Collection, field: String, v: Any?): Any? =
        if (field in c.customArrays && v is String) splitMulti(v) else v

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
        geo: GeoFilter? = null,
        mode: String = "keyword",
    ): CollectionSearchResponse? {
        val c = collections[name] ?: return null
        val display = collDisplay(c)
        val dtypes = c.schema.mapValues { Docs.dtypeOf(it.value.osType) }
        val tagValue = tag.trim()
        val sourceValues = sourceFiles.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val poly = geo?.polygon?.mapNotNull { if (it.size >= 2) it[0] to it[1] else null }
        val safeSize = size.coerceAtLeast(1)
        val safePage = page.coerceAtLeast(1)
        val offset = ((safePage - 1).toLong() * safeSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        // structured filters apply in both modes; keyword `q` matches by substring, semantic `q` ranks by vector
        val vectors = if (mode == "semantic" && q.isNotBlank()) ensureVectors(c) else null
        val qvec = vectors?.let { embedder.embed(q) }

        val filteredIdx = c.entities.indices.filter { i ->
            val d = c.entities[i]
            (tagValue.isEmpty() || entityTags(c, d).contains(tagValue)) &&
                (sourceValues.isEmpty() || sourceFiles(d).any { it in sourceValues }) &&
                filters.all { f -> Docs.filterMatch(Docs.fieldValues(d[f.field]), f.value, dtypes[f.field]) } &&
                (geo == null || geoMatch(d[geo.field], geo, poly))
        }
        // keep the cosine score alongside each index in semantic mode (null otherwise)
        val ranked: List<Pair<Int, Double?>> = if (qvec != null && vectors != null) {
            filteredIdx.mapNotNull { i -> vectors[i].takeIf { it.isNotEmpty() }?.let { i to dot(qvec, it) } }
                .sortedByDescending { it.second }.map { it.first to it.second }
        } else if (q.isNotBlank()) {
            filteredIdx.filter { Docs.textMatch(c.entities[it], q) }.map { it to null }
        } else filteredIdx.map { it to null }

        val total = ranked.size
        val page = ranked.drop(offset).take(safeSize)
        val out = page.map { (i, _) ->
            val d = c.entities[i]
            display.associateWith { col ->
                when (col) {
                    "_source_file" -> sourceFiles(d).joinToString(", ")
                    "name" -> Docs.rowName(d)
                    else -> Docs.flattenText(d[col])
                }
            }
        }
        // per-result cosine scores (rounded), only in semantic mode
        val scores = if (qvec != null) page.map { (_, s) -> Math.round((s ?: 0.0) * 1000) / 1000.0 } else emptyList()
        val query = Dsl.query(
            q.ifBlank { null },
            filters.associate { it.field to it.value },
            listOf("*"),
            tagValue.ifBlank { null },
            "_tags",
            sourceValues.firstOrNull(),
            "_source_file",
        )
        return CollectionSearchResponse(display, out.size, total, safePage, safeSize, out, Dsl.display(query, safeSize), c.index, scores = scores)
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

    /** All plottable [lat,lng] points (with a label) for a geo field — for the map. */
    fun geoPoints(name: String, field: String, limit: Int): List<GeoPoint>? {
        val c = collections[name] ?: return null
        val out = ArrayList<GeoPoint>()
        for (d in c.entities) {
            val p = Docs.fieldValues(d[field]).firstNotNullOfOrNull { Docs.parseLatLng(it) } ?: continue
            val label = Docs.rowName(d).ifBlank {
                listOf("city", "company", "email", "identifier").firstNotNullOfOrNull { k -> Docs.flattenText(d[k]).ifBlank { null } }
                    ?: field
            }
            out.add(GeoPoint(p.first, p.second, label))
            if (out.size >= limit) break
        }
        return out
    }

    /** True if any coordinate value of the entity's geo field falls in the bbox / polygon. */
    private fun geoMatch(value: Any?, geo: GeoFilter, poly: List<Pair<Double, Double>>?): Boolean {
        val points = Docs.fieldValues(value).mapNotNull { Docs.parseLatLng(it) }
        if (points.isEmpty()) return false
        return points.any { p ->
            (geo.bbox?.let { it.size == 4 && Docs.inBbox(p, it[0], it[1], it[2], it[3]) } ?: false) ||
                (poly?.let { Docs.inPolygon(p, it) } ?: false)
        }
    }

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
                    a.sources.size, a.count, a.types.size > 1, facetValues(c, field, a.osType), a.valueConflicts)
            }
        val segments = c.segments.map { (n, f) -> Segment(n, f, segmentCount(c, f)) }
        val custom = c.customCanonicals.entries.map { (n, t) -> CustomCanonical(n, t, n in c.customArrays, c.schema.containsKey(n)) }
        val enrichments = c.enrichments.map { EnrichmentJoin(short(it.source), it.joinField, it.attached, it.matched, it.refCollection != null) }
        val extractions = c.extractSpecs.map { s -> ExtractedAttribute(s.name, s.osType, s.values, c.entities.count { it[s.name] != null }) }
        return CollectionDetail(c.name, c.index, c.entities.size, c.keyField, c.rawRecords, c.merged,
            schema, c.files.map { CollectionFileDto(short(it.name), it.rows, it.mapping) }, segments, c.opensearch,
            collectionTags(c), custom, embedder.enabled && textFields(c).isNotEmpty(), enrichments, extractions, llm.enabled,
            c.transforms.map { FieldTransform(it.first, it.second) })
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
        if (c.schema[field]?.cardinality == "array") return mergeTagged(toTagged(existing), toTagged(incoming))
        // scalar, same key: if the values agree keep one (exact dedupe); if they differ, collect the
        // distinct values into a list instead of dropping the loser. Conflicts are flagged in detail().
        val vals = LinkedHashSet<Any?>()
        addScalars(vals, existing); addScalars(vals, incoming)
        return if (vals.size <= 1) vals.firstOrNull() else vals.toList()
    }

    private fun addScalars(into: MutableSet<Any?>, v: Any?) {
        when (v) {
            null -> {}
            is List<*> -> v.forEach { addScalars(into, it) }
            is Map<*, *> -> v["value"]?.let { into.add(it) }
            else -> into.add(v)
        }
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
