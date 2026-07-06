package com.unfuckdoc.domain

import jakarta.inject.Inject
import kotlinx.serialization.Serializable

/** One canonical field's consolidation shape. */
@Serializable
data class Unified(
    val canonical: String,
    val cardinality: String,   // "scalar" | "array"
    val style: String,         // "single" | "positional" | "semantic"
    val kind: String,
    val osType: String?,
    val sources: List<String>,
    val labels: List<String?>,
)

data class ConsolidateResult(val unified: List<Unified>, val docs: List<Map<String, Any?>>)

/**
 * Consolidates columns sharing a canonical key into one field. Fill co-occupancy decides scalar
 * (survivorship) vs array (concurrent+distinct), and array-ification is gated on a shared column
 * stem. Kotlin port of the Python consolidation.
 */
class Consolidator @Inject constructor() {

    private data class Shape(
        val cardinality: String, val style: String, val labels: Map<String, String?>,
        val kind: String, val osType: String?, val sources: List<ColumnInfo>,
    )

    fun consolidate(rows: List<Map<String, String?>>, infos: List<ColumnInfo>): ConsolidateResult {
        val n = rows.size
        val groups = LinkedHashMap<String, MutableList<ColumnInfo>>()
        infos.filter { it.searchable }.forEach { groups.getOrPut(it.canonical) { mutableListOf() }.add(it) }

        val shapes = LinkedHashMap<String, Shape>()
        for ((canon, cols) in groups) {
            val kinds = cols.map { it.kind }
            val kind = if (kinds.all { it == "free_text" }) "free_text"
                       else kinds.firstOrNull { it != "free_text" } ?: kinds.first()
            val (labels, style0, hasStem) = qualifiers(cols.map { it.name })
            val sig = cardSignal(cols.map { it.name }, rows, kind, n)
            var card = sig.cardinality
            // Coalesce synonyms (no shared name stem) to scalar ONLY when they don't strongly
            // co-occupy. Strong co-occupancy = real multi-value slots (Mobile/Work/Home Phone all
            // map to `phone`) -> keep as an array even without a shared stem.
            if (card == "array" && !hasStem && sig.coOccupancy < 0.5) card = "scalar"
            shapes[canon] = Shape(card, if (card == "array") style0 else "single", labels, kind, cols.first().osType, cols)
        }

        val docs = rows.map { row ->
            val doc = LinkedHashMap<String, Any?>()
            for ((canon, sh) in shapes) {
                val items = sh.sources.mapNotNull { info ->
                    val raw = row[info.name]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val v = Cleaner.cleanCell(raw, sh.kind) ?: return@mapNotNull null
                    sh.labels[info.name] to v
                }
                if (sh.kind == "free_text") {
                    if (items.isNotEmpty()) doc[canon] = items.first().second.toString()
                    continue
                }
                if (items.isEmpty()) continue
                val seen = LinkedHashSet<Any?>()
                val dedup = items.filter { seen.add(it.second) }
                when {
                    sh.cardinality == "scalar" -> doc[canon] = dedup.first().second
                    sh.style == "semantic" -> doc[canon] = dedup.map { mapOf("type" to (it.first ?: "other"), "value" to it.second) }
                    else -> doc[canon] = dedup.map { it.second }
                }
            }
            doc
        }

        val unified = shapes.map { (canon, sh) ->
            Unified(canon, sh.cardinality, sh.style, sh.kind, sh.osType,
                sh.sources.map { it.name }, sh.sources.map { sh.labels[it.name] })
        }
        return ConsolidateResult(unified, docs)
    }

    /** Common prefix/suffix over raw names -> per-column qualifier + style + whether a real stem exists. */
    private fun qualifiers(cols: List<String>): Triple<Map<String, String?>, String, Boolean> {
        if (cols.size == 1) return Triple(mapOf(cols[0] to null), "single", false)
        val pre = lcp(cols)
        val suf = lcp(cols.map { it.reversed() }).reversed()
        val stem = (pre + suf).filter { it.isLetterOrDigit() }
        val hasStem = stem.length >= 3
        val labels = LinkedHashMap<String, String?>()
        var positional = true
        for (c in cols) {
            val q = if (pre.length + suf.length < c.length) c.substring(pre.length, c.length - suf.length) else ""
            val ql = q.replace(Regex("[^A-Za-z0-9]+"), " ").trim()
            labels[c] = ql
            if (ql.isNotEmpty() && ql.toIntOrNull() == null) positional = false
        }
        return Triple(labels, if (positional) "positional" else "semantic", hasStem)
    }

    private fun lcp(strs: List<String>): String {
        val lo = strs.min(); val hi = strs.max(); var i = 0
        while (i < lo.length && lo[i] == hi[i]) i++
        return lo.substring(0, i)
    }

    private data class CardSignal(val cardinality: String, val coOccupancy: Double)

    /** Decide scalar vs array from fill co-occupancy: `coOccupancy` = fraction of rows where >=2 of
     *  the columns are populated (real multi-value slots), and array requires those to also differ. */
    private fun cardSignal(cols: List<String>, rows: List<Map<String, String?>>, kind: String, n: Int): CardSignal {
        if (cols.size == 1 || kind == "free_text") return CardSignal("scalar", 0.0)
        var concurrent = 0; var distinct = 0
        for (row in rows) {
            val vals = cols.mapNotNull { row[it]?.trim()?.takeIf { s -> s.isNotEmpty() } }
            if (vals.size >= 2) { concurrent++; if (vals.toSet().size >= 2) distinct++ }
        }
        val co = if (n == 0) 0.0 else concurrent.toDouble() / n
        if (co < 0.02) return CardSignal("scalar", co)
        return CardSignal(if (distinct.toDouble() / maxOf(concurrent, 1) >= 0.5) "array" else "scalar", co)
    }
}
