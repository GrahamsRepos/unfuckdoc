package com.unfuckdoc

import com.unfuckdoc.api.CollectionService
import com.unfuckdoc.api.GeoFilter
import com.unfuckdoc.domain.Canonicalizer
import com.unfuckdoc.domain.Classifier
import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.NoopEmbedder
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.domain.SemanticCanonicalizer
import com.unfuckdoc.opensearch.OpenSearchService
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {
    private fun service(): CollectionService {
        val os = mockk<OpenSearchService>(relaxed = true)
        every { os.available() } returns false
        return CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), NoopEmbedder)),
            Consolidator(), os,
        )
    }

    @Test
    fun `classifier detects a coordinate column as geo_point and canonicalises to location`() {
        val cls = Classifier().classify(listOf("40.7128,-74.0060", "51.5074,-0.1278", "35.6762,139.6503"))
        assertEquals("geo_point", cls.kind)
        assertEquals("geo_point", cls.osType)
        val (canon, method) = SemanticCanonicalizer(Canonicalizer(), NoopEmbedder).canonicalize("coordinates", "geo_point")
        assertEquals("location" to "alias", canon to method)
    }

    @Test
    fun `european decimal commas and integers are not mistaken for coordinates`() {
        // "12,34" (European 12.34) must read as numeric (comma stripped), NOT geo
        assertEquals("numeric", Classifier().classify(listOf("12,34", "56,78", "12,34", "90,10")).kind)
    }

    @Test
    fun `search filters entities by bounding box and polygon`() {
        val s = service()
        s.create("places", "email")
        s.add("places", "p.csv", listOf("email", "coordinates"), listOf(
            mapOf("email" to "ny@x.com", "coordinates" to "40.7128,-74.0060"),   // New York
            mapOf("email" to "ldn@x.com", "coordinates" to "51.5074,-0.1278"),   // London
            mapOf("email" to "tok@x.com", "coordinates" to "35.6762,139.6503"),  // Tokyo
        ))
        // sanity: coordinates mapped to `location`
        assertEquals("geo_point", s.detail("places")!!.schema.first { it.field == "location" }.osType)

        // bbox around western Europe -> only London
        val bbox = GeoFilter("location", bbox = listOf(45.0, -10.0, 55.0, 10.0))
        val eu = s.search("places", "", "", emptyList(), emptyList(), 10, 1, bbox)!!
        assertEquals(1, eu.total)

        // polygon roughly around the US east coast -> only New York
        val usPoly = GeoFilter("location", polygon = listOf(
            listOf(38.0, -80.0), listOf(43.0, -80.0), listOf(43.0, -70.0), listOf(38.0, -70.0),
        ))
        val us = s.search("places", "", "", emptyList(), emptyList(), 10, 1, usPoly)!!
        assertEquals(1, us.total)
    }
}
