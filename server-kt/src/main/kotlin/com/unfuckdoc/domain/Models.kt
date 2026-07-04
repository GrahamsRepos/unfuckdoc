package com.unfuckdoc.domain

import kotlinx.serialization.Serializable

/** Statistics computed while scoring a column's populated cells. */
data class ColumnStats(
    val cardinality: Int,
    val distinctRatio: Double,
    val avgLen: Double,
    val avgWords: Double,
)

/** Outcome of classifying one column. */
data class Classification(
    val kind: String,
    val osType: String?,
    val margin: Double?,
    val source: String,          // "deterministic" | "LLM" | "n/a"
    val stats: ColumnStats?,
    val escalated: Boolean,
)

@Serializable
data class ColumnInfo(
    val name: String,
    val kind: String,
    val osType: String?,
    val fillRate: Double,
    val margin: Double?,
    val source: String,
    val searchable: Boolean,
    val canonical: String,
    val canonicalMethod: String,
    val cardinality: Int? = null,
    val distinctRatio: Double? = null,
    val avgWords: Double? = null,
    val note: String? = null,
)

@Serializable
data class MergeGroup(val canonical: String, val columns: List<String>, val unified: Boolean)

@Serializable
data class ProcessResult(
    val filename: String,
    val nRows: Int,
    val nCols: Int,
    val llmCalls: Int,
    val coerced: Int,
    val quarantine: Int,
    val columns: List<ColumnInfo>,
    val kindCounts: Map<String, Int>,
    val mergeGroups: List<MergeGroup>,
    val fuzzy: List<String>,
)
