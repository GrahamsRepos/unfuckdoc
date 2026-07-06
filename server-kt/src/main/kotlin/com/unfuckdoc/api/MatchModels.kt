package com.unfuckdoc.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MatchKey(
    val field: String, val kind: String, val uniqueness: Double,
    val fillA: Int? = null, val fillB: Int? = null,
)

@Serializable
data class MatchCandidates(val keys: List<MatchKey>, val error: String? = null)

@Serializable
data class MatchPair(val sim: Double, val a: Map<String, String>, val b: Map<String, String>)

@Serializable
data class MatchResult(
    val key: String, val threshold: Double,
    val rowsA: Int, val rowsB: Int, val keyedA: Int,
    val matched: Int, val exact: Int, val unmatchedA: Int, val unmatchedB: Int,
    val displayA: List<String>, val displayB: List<String>, val pairs: List<MatchPair>,
    val dsl: JsonElement? = null,
    val error: String? = null,
)
