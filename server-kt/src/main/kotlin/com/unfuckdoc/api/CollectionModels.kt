package com.unfuckdoc.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CollectionSummary(
    val name: String, val index: String, val nFiles: Int, val nRecords: Int, val nFields: Int,
    val keyField: String,
)

@Serializable
data class SchemaFieldDto(
    val field: String, val osType: String?, val kind: String, val cardinality: String,
    val sources: List<String>, val nSources: Int, val count: Int, val conflict: Boolean,
    val values: List<JsonElement>? = null,   // enumerable low-cardinality values: each a [value, count] array
    val conflicts: Int = 0,                  // entities where same-key duplicates disagreed on this field
)

@Serializable
data class FileMappingEntry(val column: String, val canonical: String, val method: String)

@Serializable
data class CollectionFileDto(val name: String, val rows: Int, val mapping: List<FileMappingEntry>)

/** A named, saved filtered view of the joined collection (e.g. "cold leads", "customers · Cape Town"). */
@Serializable
data class Segment(val name: String, val filters: List<FieldFilter>, val count: Int = 0)

@Serializable
data class CollectionTag(val tag: String, val count: Int)

/** Geo constraint on a geo_point field: a bounding box [minLat,minLng,maxLat,maxLng] OR a polygon
 *  ring of [lat,lng] vertices. */
@Serializable
data class GeoFilter(
    val field: String, val bbox: List<Double>? = null, val polygon: List<List<Double>>? = null,
)

/** A user-defined canonical field: a named target (with a declared type) that columns can be mapped
 *  onto, offered in the mapping dropdowns alongside the built-in canonicals. `array` = multi-value:
 *  the field holds a list (delimited cells are split; multiple columns/files union). */
@Serializable
data class CustomCanonical(val name: String, val osType: String, val array: Boolean = false, val inUse: Boolean = false)

@Serializable
data class CollectionDetail(
    val name: String, val index: String, val nRecords: Int,
    val keyField: String, val rawRecords: Int, val merged: Int,
    val schema: List<SchemaFieldDto>, val files: List<CollectionFileDto>,
    val segments: List<Segment>, val opensearch: OsStatus,
    val tags: List<CollectionTag> = emptyList(),
    val customCanonicals: List<CustomCanonical> = emptyList(),
)

@Serializable
data class CollectionAddResponse(
    val added: String? = null, val error: String? = null,
    val mapping: List<FileMappingEntry> = emptyList(), val detail: CollectionDetail? = null,
)

@Serializable
data class CollectionSearchResponse(
    val display: List<String>, val count: Int, val total: Int, val page: Int, val pageSize: Int,
    val results: List<Map<String, String>>,
    val dsl: JsonElement, val index: String, val error: String? = null,
)
