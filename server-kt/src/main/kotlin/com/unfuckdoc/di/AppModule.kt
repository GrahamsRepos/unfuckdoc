package com.unfuckdoc.di

import com.unfuckdoc.api.DatasetService
import com.unfuckdoc.domain.Canonicalizer
import com.unfuckdoc.domain.Classifier
import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.CsvReader
import com.unfuckdoc.domain.Embedder
import com.unfuckdoc.domain.IndexBuilder
import com.unfuckdoc.domain.MiniLmEmbedder
import com.unfuckdoc.domain.NoopEmbedder
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.domain.SemanticCanonicalizer
import com.unfuckdoc.opensearch.OpenSearchService
import com.unfuckdoc.routes.ApiController
import dev.misfitlabs.kotlinguice4.KotlinModule
import jakarta.inject.Singleton

/**
 * Explicit Guice wiring via kotlin-guice's reified bind DSL. Every service is bound in Singleton
 * scope here (rather than via @Singleton on the class); the classes keep @Inject constructors so
 * Guice knows how to construct them. All bindings are overridable in tests via [appInjector].
 */
class AppModule : KotlinModule() {
    override fun configure() {
        bind<Classifier>().`in`<Singleton>()
        bind<Canonicalizer>().`in`<Singleton>()
        bind<CsvReader>().`in`<Singleton>()
        bind<IndexBuilder>().`in`<Singleton>()
        bind<Consolidator>().`in`<Singleton>()
        bind<Pipeline>().`in`<Singleton>()
        bind<SemanticCanonicalizer>().`in`<Singleton>()
        bind<DatasetService>().`in`<Singleton>()
        bind<ApiController>().`in`<Singleton>()

        // real neural embeddings for semantic field-name matching, unless UNFUCK_NO_EMBED=1
        if (System.getenv("UNFUCK_NO_EMBED") != null)
            bind<Embedder>().toInstance(NoopEmbedder)
        else
            bind<Embedder>().to<MiniLmEmbedder>().`in`<Singleton>()

        // constructed from env config
        bind<OpenSearchService>().toInstance(
            OpenSearchService(
                host = System.getenv("OPENSEARCH_HOST") ?: "localhost",
                port = System.getenv("OPENSEARCH_PORT")?.toIntOrNull() ?: 9200,
            )
        )
    }
}
