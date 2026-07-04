package com.unfuckdoc.domain

import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * Deterministic column-name unification — Kotlin port of `canonicalize` / `CANON`.
 * CANON is ordered by priority: the first type-compatible canonical whose alias appears wins
 * (so "email_address" -> email, "Company Name" -> company); otherwise the column keeps its own name.
 */
@Singleton
class Canonicalizer @Inject constructor() {

    private data class Canon(val name: String, val aliases: Set<String>, val types: Set<String>?)

    private val str = setOf("enum", "identifier", "free_text")

    private val canon = listOf(
        Canon("amount", setOf("price", "cost", "amt", "amount", "value", "total", "subtotal", "fee",
            "charge", "salary", "revenue", "spend", "balance", "budget", "mrr", "arr", "payment", "paid"), setOf("numeric")),
        Canon("quantity", setOf("qty", "quantity", "count", "units", "unit", "stock", "inventory"), setOf("numeric")),
        Canon("rating", setOf("rating", "score", "points", "point", "stars", "star", "rank", "grade"), setOf("numeric")),
        Canon("age", setOf("age", "years"), setOf("numeric")),
        Canon("email", setOf("email", "emails", "mail", "e"), str),
        Canon("phone", setOf("phone", "tel", "telephone", "mobile", "cell", "fax", "msisdn", "number"), str),
        Canon("first_name", setOf("firstname", "fname", "givenname", "given", "forename", "first"), str),
        Canon("last_name", setOf("lastname", "lname", "surname", "familyname", "family", "last"), str),
        Canon("company", setOf("company", "organization", "organisation", "org", "employer", "business",
            "vendor", "supplier", "winery", "brand", "account", "firm"), str),
        Canon("full_name", setOf("name", "fullname", "person", "taster"), str),
        Canon("job_title", setOf("jobtitle", "role", "position", "designation", "occupation"), str),
        Canon("country", setOf("country", "cntry", "ctry", "nation", "countrycode"), str),
        Canon("region", setOf("region", "state", "province", "county", "territory", "area"), str),
        Canon("interests", setOf("interest", "interests", "hobby", "hobbies", "topic", "topics"), setOf("enum", "identifier")),
        Canon("city", setOf("city", "town", "municipality"), str),
        Canon("address", setOf("address", "addr", "street"), null),
        Canon("postal_code", setOf("zip", "zipcode", "postal", "postcode", "postalcode"), str),
        Canon("date", setOf("date", "datetime", "timestamp", "created", "updated", "modified", "day", "time"), setOf("date", "numeric")),
        Canon("url", setOf("url", "link", "website", "site", "web", "homepage", "handle"), str),
        Canon("identifier", setOf("id", "identifier", "uuid", "guid", "key", "ref", "reference", "sku", "code"), setOf("identifier", "numeric")),
        Canon("gender", setOf("gender", "sex"), setOf("enum")),
        Canon("currency", setOf("currency", "ccy"), setOf("enum", "identifier")),
        Canon("description", setOf("description", "desc", "notes", "note", "comment", "comments", "summary",
            "bio", "review", "text", "details", "detail", "remarks", "about", "content"), setOf("free_text", "identifier", "enum")),
    )

    private val camel = Regex("([a-z0-9])([A-Z])")
    private val nonAlnum = Regex("[^A-Za-z0-9]+")

    fun nameTokens(name: String): List<String> =
        camel.replace(name) { "${it.groupValues[1]} ${it.groupValues[2]}" }
            .lowercase()
            .split(nonAlnum)
            .filter { it.isNotEmpty() && it.toIntOrNull() == null }   // drop empties & pure numbers (region_1 -> region)

    /** Canonical vocabulary exposed for the semantic layer: name, alias words, allowed kinds. */
    data class Spec(val name: String, val aliases: Set<String>, val types: Set<String>?)
    val specs: List<Spec> get() = canon.map { Spec(it.name, it.aliases, it.types) }

    /** Returns canonical name + method ("alias" | "identity"). */
    fun canonicalize(name: String, kind: String): Pair<String, String> {
        val toks = nameTokens(name).toSet()
        for (c in canon) {
            if (c.types != null && kind !in c.types) continue
            if (toks.any { it in c.aliases }) return c.name to "alias"
        }
        val ident = nameTokens(name).joinToString("_").ifEmpty { name.lowercase() }
        return ident to "identity"
    }
}
