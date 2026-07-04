package com.unfuckdoc.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Null-aware cell cleaning shared by consolidation and indexing. */
object Cleaner {
    private val money = Regex("[,\\s$€£¥₹]")
    private val dateFormats = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd", "MM/dd/yyyy", "M/d/yyyy", "d/M/yyyy",
        "MMM d, yyyy", "MMM d yyyy", "dd-MM-yyyy",
    ).map { DateTimeFormatter.ofPattern(it) }

    /** Coerce one populated cell to its typed value (or null if it fails its type). */
    fun cleanCell(raw: String, kind: String): Any? = when (kind) {
        "numeric" -> money.replace(raw, "").toDoubleOrNull()
        "boolean" -> raw.lowercase() in setOf("true", "yes")
        "date" -> normalizeDate(raw)
        else -> raw
    }

    fun normalizeDate(raw: String): String {
        val s = raw.trim().removeSuffix("Z").substringBefore("T")
        for (f in dateFormats) {
            try { return LocalDate.parse(s, f).format(DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: Exception) {}
        }
        return s
    }
}
