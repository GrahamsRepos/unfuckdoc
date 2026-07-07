package com.unfuckdoc

import com.unfuckdoc.api.CollectionService
import com.unfuckdoc.api.FieldFilter
import com.unfuckdoc.api.GeoFilter
import com.unfuckdoc.domain.Canonicalizer
import com.unfuckdoc.domain.Classifier
import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.MiniLmEmbedder
import com.unfuckdoc.domain.NoopEmbedder
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.domain.SemanticCanonicalizer
import com.unfuckdoc.opensearch.OpenSearchService
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionServiceTest {

    @Test
    fun `mixed scalar and slotted canonical fields index as tagged nested objects`() {
        val opensearch = mockk<OpenSearchService>()
        val indexedMappings = mutableListOf<String>()
        val indexedDocs = mutableListOf<List<Map<String, Any?>>>()
        every { opensearch.available() } returns true
        every { opensearch.indexDocs(any(), any(), any()) } answers {
            indexedMappings.add(secondArg())
            indexedDocs.add(thirdArg())
            thirdArg<List<Map<String, Any?>>>().size
        }

        val service = CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), NoopEmbedder)),
            Consolidator(),
            opensearch, NoopEmbedder,
        )
        service.create("contacts", "email")

        service.add(
            "contacts", "single.csv",
            listOf("email", "phone"),
            listOf(mapOf("email" to "a@example.com", "phone" to "+1-111-0000")),
        )
        service.add(
            "contacts", "slotted.csv",
            listOf("email", "phone_home", "phone_work"),
            listOf(mapOf("email" to "a@example.com", "phone_home" to "+1-111-0000", "phone_work" to "+1-222-0000")),
        )

        val detail = service.detail("contacts")!!
        assertEquals("array", detail.schema.first { it.field == "phone" }.cardinality)
        assertTrue(indexedMappings.last().contains(""""phone":{"type":"nested""""))

        val phone = indexedDocs.last().single()["phone"] as List<*>
        assertEquals(
            listOf(
                mapOf("type" to "home", "value" to "+1-111-0000"),
                mapOf("type" to "work", "value" to "+1-222-0000"),
            ),
            phone,
        )
    }

    @Test
    fun `association key can be changed and collection is rebuilt`() {
        val service = CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), NoopEmbedder)),
            Consolidator(),
            unavailableOpenSearch(), NoopEmbedder,
        )
        service.create("accounts", "email")

        service.add(
            "accounts", "a.csv",
            listOf("email", "Company Name", "Notes"),
            listOf(mapOf("email" to "one@example.com", "Company Name" to "Acme", "Notes" to "Uses segmentation for retail analytics and needs reliable tags for regional customer cohorts")),
        )
        service.add(
            "accounts", "b.csv",
            listOf("email", "Company", "Notes"),
            listOf(mapOf("email" to "two@example.com", "Company" to "Acme", "Notes" to "Needs campaign tags and audience grouping across source files for account level planning")),
        )

        assertEquals(2, service.detail("accounts")!!.nRecords)

        val detail = service.setKey("accounts", "company")!!
        assertEquals("company", detail.keyField)
        assertEquals(1, detail.nRecords)
        assertEquals(1, detail.merged)
        assertTrue(detail.tags.isNotEmpty(), "expected tags extracted across source files")

        val tagged = service.search("accounts", "", detail.tags.first().tag, emptyList(), emptyList(), 10, 1)!!
        assertEquals(1, tagged.count)
        assertTrue(tagged.display.containsAll(listOf("_source_file", "email", "company", "description")))

        val sourceFiltered = service.search("accounts", "", "", listOf(detail.files.first().name), emptyList(), 10, 1)!!
        assertEquals(1, sourceFiltered.count)

        val bothFiles = service.search(
            "accounts",
            "",
            "",
            detail.files.map { it.name },
            emptyList(),
            10,
            1,
        )!!
        assertEquals(1, bothFiles.count)
    }

    @Test
    fun `custom canonical declared type governs search filtering`() {
        val service = CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), NoopEmbedder)),
            Consolidator(),
            unavailableOpenSearch(), NoopEmbedder,
        )
        service.create("deals", "email")
        service.add(
            "deals", "d.csv",
            listOf("email", "spend"),
            listOf(
                mapOf("email" to "a@example.com", "spend" to "100"),
                mapOf("email" to "b@example.com", "spend" to "300"),
            ),
        )
        service.putCanonical("deals", "deal_size", "double", false)
        service.setMapping("deals", "spend", "deal_size")

        val detail = service.detail("deals")!!
        // the declared type is what the field reports AND what search filters by
        assertEquals("double", detail.schema.first { it.field == "deal_size" }.osType)

        val res = service.search("deals", "", "", emptyList(), listOf(FieldFilter("deal_size", ">150")), 10, 1)!!
        assertEquals(1, res.total, "range filter on a numeric custom canonical should match only spend>150")
    }

    @Test
    fun `multi-value custom canonical splits delimited cells into a list and filters per element`() {
        val service = CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), NoopEmbedder)),
            Consolidator(),
            unavailableOpenSearch(), NoopEmbedder,
        )
        service.create("aud", "email")
        service.add(
            "aud", "i.csv",
            listOf("email", "interests"),
            listOf(
                mapOf("email" to "a@example.com", "interests" to "golf; wine; travel"),
                mapOf("email" to "b@example.com", "interests" to "wine|cooking"),
            ),
        )
        service.putCanonical("aud", "interests", "keyword", true)

        val detail = service.detail("aud")!!
        val field = detail.schema.first { it.field == "interests" }
        assertEquals("array", field.cardinality)
        // enumerated per element, not one lumped string
        val values = field.values?.map { (it as JsonArray)[0].jsonPrimitive.content }?.toSet()
        assertEquals(setOf("golf", "wine", "travel", "cooking"), values)

        val res = service.search("aud", "", "", emptyList(), listOf(FieldFilter("interests", "wine")), 10, 1)!!
        assertEquals(2, res.total, "both records list wine among their interests")
    }

    @Test
    fun `same-key duplicates dedupe when exact, else collect differing values and flag the conflict`() {
        val service = CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), NoopEmbedder)),
            Consolidator(),
            unavailableOpenSearch(), NoopEmbedder,
        )
        service.create("dup", "email")
        service.add(
            "dup", "d.csv",
            listOf("email", "company"),
            listOf(
                mapOf("email" to "a@example.com", "company" to "Acme"),        // conflicting pair ->
                mapOf("email" to "a@example.com", "company" to "Acme Corp"),    //   array + flag
                mapOf("email" to "b@example.com", "company" to "Blue"),         // exact duplicate ->
                mapOf("email" to "b@example.com", "company" to "Blue"),         //   deduped, no flag
            ),
        )

        val detail = service.detail("dup")!!
        assertEquals(2, detail.nRecords)   // two entities
        val company = detail.schema.first { it.field == "company" }
        assertEquals(1, company.conflicts)  // only a@example.com disagreed

        // a@ keeps both values; b@ stays a single scalar
        val a = service.search("dup", "", "", emptyList(), listOf(FieldFilter("email", "a@example.com")), 5, 1)!!
        assertEquals("Acme Acme Corp", a.results.single()["company"])
        val b = service.search("dup", "", "", emptyList(), listOf(FieldFilter("email", "b@example.com")), 5, 1)!!
        assertEquals("Blue", b.results.single()["company"])
    }

    @Test
    fun `keyword search is punctuation and order independent`() {
        val service = CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), NoopEmbedder)),
            Consolidator(), unavailableOpenSearch(), NoopEmbedder,
        )
        service.create("props", "email")
        service.add("props", "p.csv", listOf("email", "description"), listOf(
            mapOf("email" to "a@x.com", "description" to "Bright open-plan kitchen with island and city views"),
            mapOf("email" to "b@x.com", "description" to "Cosy studio near the park"),
        ))
        // spaced query matches hyphenated text
        assertEquals(1, service.search("props", "open plan kitchen", "", emptyList(), emptyList(), 10, 1)!!.total)
        // order-independent, all terms required
        assertEquals(1, service.search("props", "kitchen island", "", emptyList(), emptyList(), 10, 1)!!.total)
        // a term not present -> no match
        assertEquals(0, service.search("props", "open plan garage", "", emptyList(), emptyList(), 10, 1)!!.total)
    }

    @Test
    fun `semantic search ranks a free-text field by meaning, not keyword overlap`() {
        val service = CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), MiniLmEmbedder())),
            Consolidator(), unavailableOpenSearch(), MiniLmEmbedder(),
        )
        service.create("props", "email")
        service.add("props", "p.csv", listOf("email", "description"), listOf(
            mapOf("email" to "a@x.com", "description" to "Spacious family home with a large garden, lawn, and outdoor patio"),
            mapOf("email" to "b@x.com", "description" to "Modern apartment right next to the subway station and public transit"),
        ))
        // "backyard" shares no keyword with either row; semantically it's the garden/outdoor one
        val res = service.search("props", "backyard", "", emptyList(), emptyList(), 10, 1, null, "semantic")!!
        assertEquals(2, res.total)
        assertTrue(res.results.first()["description"]!!.contains("garden"), "garden home should rank first for 'backyard'")

        // keyword mode with the same term finds nothing (no literal match) — proving it's really semantic
        val kw = service.search("props", "backyard", "", emptyList(), emptyList(), 10, 1, null, "keyword")!!
        assertEquals(0, kw.total)
    }

    @Test
    fun `enrichment join attaches reference fields by a shared field, enabling geo search`() {
        val service = CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), NoopEmbedder)),
            Consolidator(), unavailableOpenSearch(), NoopEmbedder,
        )
        service.create("ppl", "email")
        service.add("ppl", "people.csv", listOf("email", "city"), listOf(
            mapOf("email" to "a@x.com", "city" to "London"),
            mapOf("email" to "b@x.com", "city" to "Paris"),
            mapOf("email" to "c@x.com", "city" to "Tokyo"),
        ))
        // join a location reference on city -> attach its coordinates (canonical `location`, geo_point)
        val resp = service.addEnrichment("ppl", "city_coords.csv", "city",
            listOf("city", "coordinates"), listOf(
                mapOf("city" to "London", "coordinates" to "51.5074,-0.1278"),
                mapOf("city" to "Paris", "coordinates" to "48.8566,2.3522"),
            ))
        val det = resp.detail!!
        assertEquals(listOf("location"), det.enrichments.first().attached)
        assertEquals("geo_point", det.schema.first { it.field == "location" }.osType)

        // people now carry coords -> a bbox around London finds the London person (Paris/Tokyo outside)
        val res = service.search("ppl", "", "", emptyList(), emptyList(), 10, 1,
            GeoFilter("location", bbox = listOf(50.0, -5.0, 55.0, 5.0)))!!
        assertEquals(1, res.total)
        assertEquals("London", res.results.single()["city"])
    }

    @Test
    fun `orphan sweep deletes stale indexes but spares live collections`() {
        val opensearch = mockk<OpenSearchService>(relaxed = true)
        every { opensearch.available() } returns false   // keep merge in-memory
        every { opensearch.listIndices("col_*") } returns listOf("col_keepme", "col_gone", "col_old")
        every { opensearch.listIndices("kt_*") } returns listOf("kt_scratch")
        val deleted = mutableListOf<String>()
        every { opensearch.deleteIndex(any()) } answers { deleted.add(firstArg()) }

        val service = CollectionService(
            Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), NoopEmbedder)),
            Consolidator(),
            opensearch, NoopEmbedder,
        )
        service.create("keepme", "email")   // live -> index col_keepme

        val orphans = service.orphanIndexes()
        assertEquals(listOf("col_gone", "col_old", "kt_scratch"), orphans)  // col_keepme excluded

        service.cleanupOrphans()
        assertEquals(setOf("col_gone", "col_old", "kt_scratch"), deleted.toSet())
    }

    private fun unavailableOpenSearch(): OpenSearchService {
        val opensearch = mockk<OpenSearchService>()
        every { opensearch.available() } returns false
        return opensearch
    }
}
