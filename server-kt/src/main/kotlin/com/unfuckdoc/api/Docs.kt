package com.unfuckdoc.api

/** Shared helpers over consolidated docs (scalar / array / {type,value}) — used by dataset,
 *  collection, and match services. */
object Docs {

    /** Explode a consolidated value into its primitive members (arrays/objects flattened). */
    fun fieldValues(v: Any?): List<Any?> = when (v) {
        null -> emptyList()
        is List<*> -> v.flatMap { fieldValues(it) }
        is Map<*, *> -> v["value"]?.let { listOf(it) } ?: emptyList()
        else -> listOf(v)
    }

    fun flattenText(v: Any?): String = when (v) {
        null -> ""
        is List<*> -> v.joinToString(" ") { flattenText(it) }
        is Map<*, *> -> v["value"]?.toString() ?: ""
        else -> v.toString()
    }

    /** Lowercased text blob of a doc's non-internal fields, for keyword search. */
    fun blob(doc: Map<String, Any?>): String =
        doc.entries.filterNot { it.key.startsWith("_") }.joinToString(" ") { flattenText(it.value) }.lowercase()

    private val nonAlnum = Regex("[^a-z0-9]+")

    /** Keyword match: every term in the query must appear in the doc, punctuation- and order-
     *  independent. So "open plan kitchen" matches "open-plan kitchen" (and vice versa). */
    fun textMatch(doc: Map<String, Any?>, query: String): Boolean {
        val norm = " " + nonAlnum.replace(blob(doc), " ").trim() + " "
        val terms = nonAlnum.replace(query.lowercase(), " ").trim().split(' ').filter { it.isNotEmpty() }
        return terms.all { norm.contains(it) }
    }

    fun dtypeOf(osType: String?): String? = when (osType) {
        "double", "long", "integer", "float" -> "num"; "date" -> "date"; else -> null
    }

    /** Normalize a value for use as a dedup/join key (email lowercased, phone digits-only, else
     *  lowercased + punctuation-stripped). Empty string means "no key" (not dedupable). */
    fun normKey(v: Any?, canon: String): String {
        val s = flattenText(v).trim().lowercase()
        return when (canon) {
            "email" -> s.replace(" ", "")
            "phone" -> s.filter { it.isDigit() }
            else -> s.replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
        }
    }

    /** One display name regardless of vendor granularity: full_name, else first + last. */
    fun rowName(doc: Map<String, Any?>): String {
        val full = flattenText(doc["full_name"]).trim()
        if (full.isNotEmpty()) return full
        return listOf(flattenText(doc["first_name"]).trim(), flattenText(doc["last_name"]).trim())
            .filter { it.isNotEmpty() }.joinToString(" ").trim()
    }

    private val opRe = Regex("^(>=|<=|>|<)\\s*(.+)$")
    private val rangeReNum = Regex("^(.+?)\\s*(?:\\.\\.|to|-)\\s*(.+)$")
    private val rangeReDate = Regex("^(.+?)\\s*(?:\\.\\.|to)\\s*(.+)$")

    /** Exact match, or numeric/date comparison-and-range expressions when dtype is num/date. */
    fun filterMatch(values: List<Any?>, needle: String, dtype: String?): Boolean {
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

    // ---- geo ----
    private val geoRe = Regex("^\\s*(-?\\d{1,3}\\.\\d+)\\s*[, ]\\s*(-?\\d{1,3}\\.\\d+)\\s*$")

    /** Parse a coordinate field value into (lat, lng), from a "lat,lng" string or {lat,lon} map. */
    fun parseLatLng(v: Any?): Pair<Double, Double>? = when (v) {
        null -> null
        is Map<*, *> -> {
            val lat = (v["lat"] as? Number)?.toDouble() ?: v["lat"]?.toString()?.toDoubleOrNull()
            val lng = ((v["lon"] ?: v["lng"]) as? Number)?.toDouble() ?: (v["lon"] ?: v["lng"])?.toString()?.toDoubleOrNull()
            if (lat != null && lng != null) lat to lng else null
        }
        else -> geoRe.matchEntire(v.toString().trim())?.let {
            val lat = it.groupValues[1].toDoubleOrNull(); val lng = it.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) lat to lng else null
        }
    }

    fun inBbox(p: Pair<Double, Double>, minLat: Double, minLng: Double, maxLat: Double, maxLng: Double): Boolean =
        p.first in minLat..maxLat && p.second in minLng..maxLng

    /** Ray-casting point-in-polygon. `poly` is a ring of [lat, lng] vertices. */
    fun inPolygon(p: Pair<Double, Double>, poly: List<Pair<Double, Double>>): Boolean {
        if (poly.size < 3) return false
        val (lat, lng) = p
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (latI, lngI) = poly[i]; val (latJ, lngJ) = poly[j]
            if ((lngI > lng) != (lngJ > lng) &&
                lat < (latJ - latI) * (lng - lngI) / (lngJ - lngI) + latI) inside = !inside
            j = i
        }
        return inside
    }
}
