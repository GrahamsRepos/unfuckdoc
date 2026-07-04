package com.unfuckdoc.api

import com.unfuckdoc.domain.ColumnInfo
import com.unfuckdoc.domain.MergeGroup
import com.unfuckdoc.domain.Unified
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// snake_case on the wire (JsonNamingStrategy.SnakeCase) to match the Python API the RR7 frontend expects.

@Serializable
data class OsStatus(val status: String, val index: String? = null, val count: Int? = null, val detail: String? = null)

@Serializable
data class FacetDto(
    val field: String, val kind: String, val osType: String?, val cardinality: String,
    val distinct: Int, val values: List<JsonElement>? = null,   // each element is a [value, count] array
)

@Serializable
data class RegistrySource(val ref: String, val kind: String? = null, val osType: String? = null, val method: String? = null)

@Serializable
data class RegistryEntry(
    val canonical: String, val sources: List<RegistrySource>,
    val nFiles: Int, val nColumns: Int, val unified: Boolean,
)

@Serializable
data class Overview(
    val loaded: Boolean,
    val filename: String? = null,
    val nRows: Int = 0, val nCols: Int = 0,
    val llmCalls: Int = 0, val coerced: Int = 0, val quarantine: Int = 0,
    val columns: List<ColumnInfo> = emptyList(),
    val kindCounts: Map<String, Int> = emptyMap(),
    val mergeGroups: List<MergeGroup> = emptyList(),
    val fuzzy: List<String> = emptyList(),
    val tags: Map<String, List<JsonElement>> = emptyMap(),
    val allTags: List<String> = emptyList(),
    val unified: List<Unified> = emptyList(),
    val facets: List<FacetDto> = emptyList(),
    val mapping: JsonElement = JsonObject(emptyMap()),
    val embedder: String? = null,
    val vecDim: Int? = null,
    val sampleDocs: List<JsonElement> = emptyList(),
    val displayColumns: List<String> = emptyList(),
    val registry: List<RegistryEntry> = emptyList(),
    val opensearch: OsStatus = OsStatus("unknown"),
)

@Serializable
data class FieldFilter(val field: String, val value: String)

@Serializable
data class SearchResult(val score: JsonElement, val row: JsonObject, val keywords: List<String> = emptyList())

@Serializable
data class SearchResponse(
    val mode: String, val field: String?, val tag: String, val filters: List<FieldFilter>,
    val count: Int, val displayColumns: List<String>, val results: List<SearchResult>,
    val dsl: JsonElement, val index: String? = null, val error: String? = null,
)
