package com.unfuckdoc

import com.unfuckdoc.di.appInjector
import com.unfuckdoc.opensearch.OpenSearchService
import com.unfuckdoc.routes.ApiController
import dev.misfitlabs.kotlinguice4.KotlinModule
import dev.misfitlabs.kotlinguice4.getInstance
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Demonstrates the testability seam: build the real injector but selectively override one binding
 * with a mock. The rest of the graph (Pipeline <- Classifier, Canonicalizer, IndexBuilder) is wired
 * for real via @Inject.
 */
class ApiControllerTest {

    /** Real container, but swap OpenSearchService for the given (mock) instance. */
    private fun controllerWith(os: OpenSearchService): ApiController =
        appInjector(object : KotlinModule() {
            override fun configure() {
                bind<OpenSearchService>().toInstance(os)
            }
        }).getInstance<ApiController>()

    @Test
    fun `process uses the real pipeline graph`() = testApplication {
        val controller = controllerWith(mockk(relaxed = true))
        application { module(controller) }

        val res = client.post("/api/process") { setBody("First Name,Amount\nAisha,\$1000\nLiam,\$2000\n") }

        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue("first_name" in body, body)
        assertTrue("amount" in body, body)
    }

    @Test
    fun `index uses the mocked OpenSearch, not a real cluster`() = testApplication {
        val os = mockk<OpenSearchService> {
            every { available() } returns true
            every { indexDocs(any(), any(), any()) } returns 99
        }
        val controller = controllerWith(os)
        application { module(controller) }

        val res = client.post("/api/index") { setBody("First Name,Amount\nAisha,1000\n") }

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("99" in res.bodyAsText(), res.bodyAsText())
    }
}
