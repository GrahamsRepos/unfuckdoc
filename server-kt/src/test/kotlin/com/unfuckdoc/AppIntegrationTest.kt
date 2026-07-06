package com.unfuckdoc

import com.unfuckdoc.support.IntegrationTest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.every
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppIntegrationTest : IntegrationTest() {

    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject

    private suspend fun loadSample(client: io.ktor.client.HttpClient, name: String) =
        client.post("/api/load_sample") {
            contentType(ContentType.Application.Json); setBody("""{"name":"$name"}""")
        }

    @Test
    fun `overview is empty before anything is loaded`() = withApp { client ->
        val body = client.get("/api/overview").bodyAsText()
        assertEquals(false, obj(body)["loaded"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `load runs the real pipeline (opensearch unavailable)`() = withApp { client ->
        val res = loadSample(client, "samples/crm_contacts.csv")
        assertEquals(HttpStatusCode.OK, res.status)
        val d = obj(res.bodyAsText())
        assertEquals(true, d["loaded"]!!.jsonPrimitive.boolean)
        assertEquals(120, d["n_rows"]!!.jsonPrimitive.int)
        assertEquals("unavailable", d["opensearch"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        // canonical mapping is present and snake_case
        assertTrue(res.bodyAsText().contains("first_name"))
    }

    @Test
    fun `load indexes via the mocked OpenSearch (bound through Guice)`() = withApp { client ->
        every { opensearch.available() } returns true
        every { opensearch.indexDocs(any(), any(), any()) } returns 77

        val d = obj(loadSample(client, "samples/crm_contacts.csv").bodyAsText())
        val os = d["opensearch"]!!.jsonObject
        assertEquals("indexed", os["status"]!!.jsonPrimitive.content)
        assertEquals(77, os["count"]!!.jsonPrimitive.int)          // count came from the mock
        verify { opensearch.indexDocs(any(), any(), any()) }        // and it was actually called
    }

    @Test
    fun `search applies a field filter over the loaded dataset`() = withApp { client ->
        loadSample(client, "samples/multi_contacts.csv")
        val res = client.post("/api/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"filters":[{"field":"country","value":"Japan"}],"size":50}""")
        }
        val d = obj(res.bodyAsText())
        val count = d["count"]!!.jsonPrimitive.int
        assertTrue(count > 0, "expected some Japan rows")
        d["results"]!!.jsonArray.forEach { r ->
            assertEquals("Japan", r.jsonObject["row"]!!.jsonObject["country"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `load extracts tags from free text fields`() = withApp { client ->
        val d = obj(loadSample(client, "samples/crm_contacts.csv").bodyAsText())
        assertTrue(d["all_tags"]!!.jsonArray.isNotEmpty(), "expected extracted tags")
        assertTrue(d["tags"]!!.jsonObject.isNotEmpty(), "expected tags grouped by free-text field")
    }

    @Test
    fun `blank keyword search browses results and still supports exact tag filters`() = withApp { client ->
        val overview = obj(loadSample(client, "samples/crm_contacts.csv").bodyAsText())
        val tag = overview["all_tags"]!!.jsonArray.first().jsonPrimitive.content

        val blank = client.post("/api/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"keyword","q":"","size":50}""")
        }
        assertTrue(obj(blank.bodyAsText())["count"]!!.jsonPrimitive.int > 0, "expected blank keyword search to return rows")

        val tagged = client.post("/api/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"keyword","q":"","tag":"$tag","size":50}""")
        }
        val d = obj(tagged.bodyAsText())
        assertTrue(d["count"]!!.jsonPrimitive.int > 0, "expected exact tag matches")
        d["results"]!!.jsonArray.forEach { r ->
            val keywords = r.jsonObject["keywords"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(tag in keywords, "expected result keywords to contain the exact tag")
        }
    }

    @Test
    fun `keyword search targets extracted keywords`() = withApp { client ->
        val overview = obj(loadSample(client, "samples/crm_contacts.csv").bodyAsText())
        val tag = overview["all_tags"]!!.jsonArray.first().jsonPrimitive.content
        val res = client.post("/api/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"keyword","q":"$tag","size":50}""")
        }
        val d = obj(res.bodyAsText())
        assertTrue(d["count"]!!.jsonPrimitive.int > 0, "expected keyword matches")
        d["results"]!!.jsonArray.forEach { r ->
            val keywords = r.jsonObject["keywords"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(keywords.any { tag in it || it in tag }, "expected keyword result to expose matching tags")
        }
    }

    @Test
    fun `keyword search paginates through the full result set`() = withApp { client ->
        loadSample(client, "samples/crm_contacts.csv")
        val page1 = obj(client.post("/api/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"keyword","q":"","size":5,"page":1}""")
        }.bodyAsText())
        val page2 = obj(client.post("/api/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"keyword","q":"","size":5,"page":2}""")
        }.bodyAsText())
        assertEquals(page1["total"]!!.jsonPrimitive.int, page2["total"]!!.jsonPrimitive.int)
        assertTrue(page1["results"]!!.jsonArray != page2["results"]!!.jsonArray, "expected different rows on the next page")
    }

    @Test
    fun `search can return all canonical columns`() = withApp { client ->
        loadSample(client, "samples/crm_contacts.csv")
        val compact = obj(client.post("/api/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"q":"retail","mode":"keyword","size":10}""")
        }.bodyAsText())
        val full = obj(client.post("/api/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"q":"retail","mode":"keyword","size":10,"show_all_columns":true}""")
        }.bodyAsText())
        assertTrue(full["display_columns"]!!.jsonArray.size > compact["display_columns"]!!.jsonArray.size)
        assertTrue(full["display_columns"]!!.jsonArray.contains(compact["display_columns"]!!.jsonArray.first()))
    }

    @Test
    fun `collection search accepts multiple source files`() = withApp { client ->
        client.post("/api/collections") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"accounts","key":"company"}""")
        }
        client.post("/api/collections/accounts/add") {
            contentType(ContentType.Application.Json)
            setBody("""{"sample":"collections/tagged_contacts_single.csv"}""")
        }
        client.post("/api/collections/accounts/add") {
            contentType(ContentType.Application.Json)
            setBody("""{"sample":"collections/tagged_contacts_slotted.csv"}""")
        }
        val res = client.post("/api/collections/accounts/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"source_files":["tagged_contacts_single","tagged_contacts_slotted"],"size":20}""")
        }
        val d = obj(res.bodyAsText())
        assertTrue(d["count"]!!.jsonPrimitive.int > 0)
    }

    @Test
    fun `schema endpoint returns a JSON Schema for the loaded dataset`() = withApp { client ->
        loadSample(client, "samples/multi_contacts.csv")
        val body = client.get("/api/schema").bodyAsText()
        val d = obj(body)
        assertEquals("object", d["type"]!!.jsonPrimitive.content)
        assertTrue(body.contains("json-schema.org/draft/2020-12"))
        assertTrue(d["properties"]!!.jsonObject.containsKey("phone"))
    }
}
