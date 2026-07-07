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

@Serializable
data class GeoPoint(val lat: Double, val lng: Double, val label: String)

@Serializable
data class GeoPointsResponse(val field: String, val points: List<GeoPoint>)

/** A user-defined canonical field: a named target (with a declared type) that columns can be mapped
 *  onto, offered in the mapping dropdowns alongside the built-in canonicals. `array` = multi-value:
 *  the field holds a list (delimited cells are split; multiple columns/files union). */
@Serializable
data class CustomCanonical(val name: String, val osType: String, val array: Boolean = false, val inUse: Boolean = false)

/** A lookup/enrichment join: for each entity, find a row in `source` where `joinField` matches and
 *  attach that row's other canonical fields (e.g. join people to a location table on `city` to
 *  attach `location` coords). Entities keep their identity; they gain the attached fields. */
@Serializable
data class EnrichmentJoin(val source: String, val joinField: String, val attached: List<String>, val matched: Int, val fromCollection: Boolean = false)

/** A structured attribute extracted from free-text by the LLM (e.g. has_garden:boolean from a
 *  description) — becomes a typed, filterable field. `values` optionally constrains an enum. */
@Serializable
data class ExtractedAttribute(val name: String, val osType: String, val values: List<String> = emptyList(), val filled: Int = 0)

/** A row-level field transform (safe expression DSL) applied before processing — e.g.
 *  field="price", expr='to_number(strip(raw_price, "$,"))'. */
@Serializable
data class FieldTransform(val field: String, val expr: String)

@Serializable
data class CollectionDetail(
    val name: String, val index: String, val nRecords: Int,
    val keyField: String, val rawRecords: Int, val merged: Int,
    val schema: List<SchemaFieldDto>, val files: List<CollectionFileDto>,
    val segments: List<Segment>, val opensearch: OsStatus,
    val tags: List<CollectionTag> = emptyList(),
    val customCanonicals: List<CustomCanonical> = emptyList(),
    val semanticSearch: Boolean = false,   // vector search available (embeddings on + a free-text field)
    val enrichments: List<EnrichmentJoin> = emptyList(),
    val extractions: List<ExtractedAttribute> = emptyList(),
    val llmAvailable: Boolean = false,
    val transforms: List<FieldTransform> = emptyList(),
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
    val scores: List<Double> = emptyList(),   // per-result cosine similarity (semantic mode only)
)
