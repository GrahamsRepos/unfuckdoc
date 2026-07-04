package com.unfuckdoc.api

/**
 * High-level entity categories that group canonical fields — the "supertype" layer
 * (person = employee/customer/lead, place = country/city/…). Used for the cluster view.
 */
object Categories {
    private val byCanonical = mapOf(
        "full_name" to "person", "first_name" to "person", "last_name" to "person",
        "email" to "person", "phone" to "person", "age" to "person", "gender" to "person",
        "company" to "company", "job_title" to "company",
        "country" to "place", "region" to "place", "city" to "place",
        "address" to "place", "postal_code" to "place", "location" to "place",
        "date" to "temporal",
        "amount" to "financial", "currency" to "financial", "quantity" to "financial",
        "url" to "web",
        "interests" to "interests",
        "rating" to "rating",
        "description" to "content",
        "identifier" to "meta",
    )

    /** Display order for the cluster view. */
    val order = listOf("person", "company", "place", "temporal", "financial",
        "interests", "web", "content", "rating", "meta", "other")

    fun of(canonical: String): String = byCanonical[canonical] ?: "other"

    fun rank(category: String): Int = order.indexOf(category).let { if (it < 0) order.size else it }
}
