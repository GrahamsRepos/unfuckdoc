package com.unfuckdoc.domain

import jakarta.inject.Inject

/**
 * Orchestrates classify -> canonicalize -> (light) clean over a parsed CSV, producing the same
 * catalog/merge-group/counters the Python `process_dataframe` exposes for the dashboard.
 * Enrichment (embeddings/keywords) is intentionally out of this slice — see README for the
 * DJL / fastembed path.
 */
class Pipeline @Inject constructor(
    private val classifier: Classifier,
    private val canonicalizer: SemanticCanonicalizer,
) {
    private val money = Regex("[,\\s$€£¥₹]")

    /** @param overrides raw column name -> forced canonical (user manual mapping); wins over inference. */
    fun process(
        filename: String, columns: List<String>, rows: List<Map<String, String?>>,
        overrides: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val n = rows.size
        var llm = 0

        val infos = columns.map { col ->
            val populated = rows.mapNotNull { it[col] }.filter { it.isNotBlank() }
            val cls = classifier.classify(populated)
            if (cls.escalated) llm++

            val fill = if (n > 0) round3(populated.size.toDouble() / n) else 0.0
            val junk = cls.kind == "numeric" &&
                (cls.stats?.distinctRatio ?: 0.0) > 0.99 &&
                col.lowercase().startsWith("unnamed")
            val searchable = if (junk) false else cls.osType != null
            val (canon, method) = overrides[col]?.takeIf { it.isNotBlank() }?.let { it to "override" }
                ?: canonicalizer.canonicalize(col, cls.kind)

            ColumnInfo(
                name = col, kind = cls.kind, osType = cls.osType, fillRate = fill, margin = cls.margin,
                source = cls.source, searchable = searchable, canonical = canon, canonicalMethod = method,
                cardinality = cls.stats?.cardinality, distinctRatio = cls.stats?.distinctRatio,
                avgWords = cls.stats?.avgWords, note = if (junk) "looks like a row index — excluded" else null,
            )
        }

        // merge groups: canonical -> contributing searchable columns
        val groups = LinkedHashMap<String, MutableList<String>>()
        infos.filter { it.searchable }.forEach { groups.getOrPut(it.canonical) { mutableListOf() }.add(it.name) }
        val merge = groups.map { (k, v) -> MergeGroup(k, v, v.size > 1) }
            .sortedWith(compareBy({ !it.unified }, { -it.columns.size }, { it.canonical }))

        // light clean pass: currency-aware numeric coercion + quarantine counts
        var coerced = 0
        var quarantine = 0
        val searchableInfos = infos.filter { it.searchable && it.kind == "numeric" }
        for (row in rows) for (info in searchableInfos) {
            val raw = row[info.name]?.takeIf { it.isNotBlank() } ?: continue
            val cln = money.replace(raw, "")
            if (cln.toDoubleOrNull() == null) quarantine++ else if (cln != raw) coerced++
        }

        return ProcessResult(
            filename = filename, nRows = n, nCols = columns.size, llmCalls = llm,
            coerced = coerced, quarantine = quarantine, columns = infos,
            kindCounts = infos.groupingBy { it.kind }.eachCount(),
            mergeGroups = merge,
            fuzzy = infos.filter { it.kind == "free_text" }.map { it.canonical },
        )
    }
}

private fun round3(x: Double) = Math.round(x * 1000) / 1000.0
