package com.unfuckdoc

import com.unfuckdoc.di.appInjector
import com.unfuckdoc.domain.Embedder
import com.unfuckdoc.domain.NoopEmbedder
import com.unfuckdoc.opensearch.OpenSearchService
import com.unfuckdoc.routes.ApiController
import dev.misfitlabs.kotlinguice4.KotlinModule
import dev.misfitlabs.kotlinguice4.getInstance
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Demonstrates the testability seam: build the real injector but selectively override one binding
 * with a mock. The rest of the graph (DatasetService <- Pipeline, Consolidator, IndexBuilder) is
 * wired for real via @Inject.
 */
class ApiControllerTest {

    /** Real container, but swap OpenSearchService for a mock and skip embeddings (keep tests fast). */
    private fun controllerWith(os: OpenSearchService): ApiController =
        appInjector(object : KotlinModule() {
            override fun configure() {
                bind<OpenSearchService>().toInstance(os)
                bind<Embedder>().toInstance(NoopEmbedder)
            }
        }).getInstance<ApiController>()

    @Test
    fun `overview is empty before anything is loaded`() = testApplication {
        application { module(controllerWith(mockk(relaxed = true))) }
        val res = client.post("/api/search") {
            contentType(ContentType.Application.Json); setBody("""{"q":"x"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("upload a CSV first" in res.bodyAsText(), res.bodyAsText())
    }

    @Test
    fun `load runs the real pipeline against a mocked OpenSearch`() = testApplication {
        val os = mockk<OpenSearchService> {
            every { available() } returns true
            every { indexDocs(any(), any(), any()) } returns 120
        }
        application { module(controllerWith(os)) }

        val res = client.post("/api/load_sample") {
            contentType(ContentType.Application.Json); setBody("""{"name":"samples/crm_contacts.csv"}""")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue("\"loaded\": true" in body, body)     // real pipeline + consolidation ran
        assertTrue("\"count\": 120" in body, body)        // mocked OpenSearch was used during load
        assertTrue("first_name" in body, body)            // snake_case canonical mapping
    }
}
