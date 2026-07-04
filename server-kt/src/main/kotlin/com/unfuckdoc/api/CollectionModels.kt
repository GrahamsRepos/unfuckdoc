package com.unfuckdoc.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionSummary(
    val name: String, val index: String, val nFiles: Int, val nRecords: Int, val nFields: Int,
)

@Serializable
data class SchemaFieldDto(
    val field: String, val osType: String?, val kind: String, val cardinality: String,
    val sources: List<String>, val nSources: Int, val count: Int, val conflict: Boolean,
)

@Serializable
data class FileMappingEntry(val column: String, val canonical: String, val method: String)

@Serializable
data class CollectionFileDto(val name: String, val rows: Int, val mapping: List<FileMappingEntry>)

@Serializable
data class CollectionDetail(
    val name: String, val index: String, val nRecords: Int,
    val schema: List<SchemaFieldDto>, val files: List<CollectionFileDto>, val opensearch: OsStatus,
)

@Serializable
data class CollectionAddResponse(
    val added: String? = null, val error: String? = null,
    val mapping: List<FileMappingEntry> = emptyList(), val detail: CollectionDetail? = null,
)

@Serializable
data class CollectionSearchResponse(
    val display: List<String>, val count: Int, val results: List<Map<String, String>>,
    val dsl: JsonElement, val index: String, val error: String? = null,
)
