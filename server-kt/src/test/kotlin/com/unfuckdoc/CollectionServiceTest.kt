package com.unfuckdoc

import com.unfuckdoc.api.CollectionService
import com.unfuckdoc.api.FieldFilter
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
            opensearch,
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
            unavailableOpenSearch(),
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
            unavailableOpenSearch(),
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
        service.putCanonical("deals", "deal_size", "double")
        service.setMapping("deals", "spend", "deal_size")

        val detail = service.detail("deals")!!
        // the declared type is what the field reports AND what search filters by
        assertEquals("double", detail.schema.first { it.field == "deal_size" }.osType)

        val res = service.search("deals", "", "", emptyList(), listOf(FieldFilter("deal_size", ">150")), 10, 1)!!
        assertEquals(1, res.total, "range filter on a numeric custom canonical should match only spend>150")
    }

    private fun unavailableOpenSearch(): OpenSearchService {
        val opensearch = mockk<OpenSearchService>()
        every { opensearch.available() } returns false
        return opensearch
    }
}
