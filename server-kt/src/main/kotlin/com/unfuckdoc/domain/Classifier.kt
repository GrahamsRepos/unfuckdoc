package com.unfuckdoc.domain

import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

/**
 * Deterministic column classifier — a Kotlin port of `score_classes` / `classify` from
 * clean_and_enrich.py. Emits a class plus a confidence *margin*; only below-margin columns
 * would escalate to an LLM (counted, never per-cell). No LLM is called here.
 */
class Classifier(private val margin: Double = 0.25) {

    private val bool = setOf("true", "false", "yes", "no")
    private val money = Regex("[,\\s$€£¥₹]")
    private val dateFormats = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd", "MM/dd/yyyy", "M/d/yyyy", "d/M/yyyy",
        "MMM d, yyyy", "MMM d yyyy", "dd-MM-yyyy", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss",
    ).map { DateTimeFormatter.ofPattern(it) }

    private fun denum(s: String) = money.replace(s, "")
    private fun isNum(v: String) = denum(v).toDoubleOrNull() != null
    private fun isDate(v: String): Boolean {
        val s = v.trim()
        if (s.length < 6 || s.toDoubleOrNull() != null) return false
        for (f in dateFormats) {
            try { f.parse(s) { it: TemporalAccessor -> it }; return true } catch (_: Exception) {}
        }
        return false
    }

    /** Per-class scores + descriptive stats for a column's populated values. */
    fun scoreClasses(values: List<String>): Pair<Map<String, Double>, ColumnStats> {
        val v = values
        val n = v.size.coerceAtLeast(1)
        val uniq = v.toSet().size
        val ratio = uniq.toDouble() / n
        val avgLen = v.map { it.length }.average()
        val avgWords = v.map { it.trim().split(Regex("\\s+")).filter { w -> w.isNotEmpty() }.size }.average()
        val numf = v.count { isNum(it) }.toDouble() / n

        val scores = mutableMapOf<String, Double>()
        scores["numeric"] = numf
        scores["boolean"] = if (uniq <= 3) v.count { it.lowercase() in bool }.toDouble() / n else 0.0
        scores["date"] = if (numf < 0.5 && avgWords <= 3 && avgLen >= 6) v.count { isDate(it) }.toDouble() / n else 0.0
        scores["free_text"] = if (avgWords >= 12) minOf(1.0, avgWords / 25.0) else 0.0

        val isStr = numf < 0.5 && scores["free_text"] == 0.0
        scores["enum"] = if (isStr && uniq <= 50 && ratio <= 0.6) 0.8 else 0.0
        scores["identifier"] = if (isStr && scores["enum"] == 0.0) 0.7 else 0.0

        return scores to ColumnStats(uniq, round3(ratio), round1(avgLen), round1(avgWords))
    }

    /** Classify one column; escalate (flagged) only when the top-two margin is below the gate. */
    fun classify(populated: List<String>): Classification {
        if (populated.isEmpty())
            return Classification("empty", null, null, "n/a", null, false)

        val (scores, stats) = scoreClasses(populated.take(1500))
        val ranked = scores.entries.sortedByDescending { it.value }
        val (top, ts) = ranked[0].toPair()
        val (_, ss) = ranked[1].toPair()
        val gap = ts - ss

        return if (gap >= margin) {
            Classification(top, OS_TYPE[top], round2(gap), "deterministic", stats, false)
        } else {
            // The ONLY escalation point. A real impl would call a constrained LLM here.
            val guess = scores.maxByOrNull { it.value }!!.key
            Classification(guess, OS_TYPE[guess], round2(gap), "LLM", stats, true)
        }
    }

    companion object {
        val OS_TYPE = mapOf(
            "boolean" to "boolean", "numeric" to "double", "date" to "date",
            "enum" to "keyword", "identifier" to "keyword", "free_text" to "text",
        )
    }
}

private fun round1(x: Double) = Math.round(x * 10) / 10.0
private fun round2(x: Double) = Math.round(x * 100) / 100.0
private fun round3(x: Double) = Math.round(x * 1000) / 1000.0
