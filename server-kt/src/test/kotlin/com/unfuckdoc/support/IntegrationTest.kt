package com.unfuckdoc.support

import com.unfuckdoc.di.appInjector
import com.unfuckdoc.domain.Embedder
import com.unfuckdoc.domain.NoopEmbedder
import com.unfuckdoc.installApp
import com.unfuckdoc.opensearch.OpenSearchService
import dev.misfitlabs.kotlinguice4.KotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk

/**
 * Base for HTTP integration tests. Spins up the real app (via [installApp]) over a **mocked
 * OpenSearch bound through Guice**, so endpoints exercise the full pipeline without a real cluster.
 *
 * Options:
 *  - [opensearch] is a relaxed MockK, exposed so a test can `every { ... }` / `verify { ... }`.
 *    Defaults to unavailable (load won't try to index).
 *  - `embedder` param swaps the semantic embedder (NoopEmbedder by default to keep tests fast;
 *    pass MiniLmEmbedder() for a real semantic integration test).
 */
abstract class IntegrationTest {

    protected lateinit var opensearch: OpenSearchService

    protected fun withApp(
        embedder: Embedder = NoopEmbedder,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        opensearch = mockk(relaxed = true)
        every { opensearch.available() } returns false

        val injector = appInjector(object : KotlinModule() {
            override fun configure() {
                bind<OpenSearchService>().toInstance(opensearch)
                bind<Embedder>().toInstance(embedder)
            }
        })
        application { installApp(injector) }

        val client = createClient { install(ContentNegotiation) { json() } }
        block(client)
    }
}
