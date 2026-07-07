package com.unfuckdoc.di

import com.unfuckdoc.api.CollectionService
import com.unfuckdoc.api.DatasetService
import com.unfuckdoc.api.MatchService
import com.unfuckdoc.domain.Canonicalizer
import com.unfuckdoc.domain.Classifier
import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.CsvReader
import com.unfuckdoc.domain.Embedder
import com.unfuckdoc.domain.IndexBuilder
import com.unfuckdoc.domain.LlmClient
import com.unfuckdoc.domain.MiniLmEmbedder
import com.unfuckdoc.domain.NoopEmbedder
import com.unfuckdoc.domain.NoopLlm
import com.unfuckdoc.domain.OpenAiChatClient
import com.unfuckdoc.domain.OpenAiEmbedder
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
        bind<CollectionService>().`in`<Singleton>()
        bind<MatchService>().`in`<Singleton>()
        bind<ApiController>().`in`<Singleton>()

        // embeddings for semantic field-name matching + semantic search:
        //   UNFUCK_NO_EMBED=1        -> off
        //   EMBED_BASE_URL set       -> any OpenAI-compatible endpoint (Ollama nomic/bge, OVH, …)
        //   default                  -> in-process all-MiniLM-L6-v2 via DJL
        val embedBase = System.getenv("EMBED_BASE_URL")
        when {
            System.getenv("UNFUCK_NO_EMBED") != null -> bind<Embedder>().toInstance(NoopEmbedder)
            embedBase != null -> bind<Embedder>().toInstance(
                OpenAiEmbedder(embedBase, System.getenv("EMBED_MODEL") ?: "nomic-embed-text", System.getenv("EMBED_API_KEY"))
            )
            else -> bind<Embedder>().to<MiniLmEmbedder>().`in`<Singleton>()
        }

        // gated chat LLM (attribute extraction, NL→query): OpenAI-compatible endpoint via LLM_BASE_URL
        val llmBase = System.getenv("LLM_BASE_URL")
        if (llmBase != null)
            bind<LlmClient>().toInstance(OpenAiChatClient(llmBase, System.getenv("LLM_MODEL") ?: "qwen2.5:7b", System.getenv("LLM_API_KEY")))
        else
            bind<LlmClient>().toInstance(NoopLlm)

        // constructed from env config
        bind<OpenSearchService>().toInstance(
            OpenSearchService(
                host = System.getenv("OPENSEARCH_HOST") ?: "localhost",
                port = System.getenv("OPENSEARCH_PORT")?.toIntOrNull() ?: 9200,
            )
        )
    }
}
