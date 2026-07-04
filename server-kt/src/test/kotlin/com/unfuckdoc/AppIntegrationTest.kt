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
    fun `schema endpoint returns a JSON Schema for the loaded dataset`() = withApp { client ->
        loadSample(client, "samples/multi_contacts.csv")
        val body = client.get("/api/schema").bodyAsText()
        val d = obj(body)
        assertEquals("object", d["type"]!!.jsonPrimitive.content)
        assertTrue(body.contains("json-schema.org/draft/2020-12"))
        assertTrue(d["properties"]!!.jsonObject.containsKey("phone"))
    }
}
