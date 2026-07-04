package com.unfuckdoc.di

import com.google.inject.Provides
import com.unfuckdoc.opensearch.OpenSearchService
import dev.misfitlabs.kotlinguice4.KotlinModule
import jakarta.inject.Singleton

/**
 * Guice wiring via kotlin-guice's KotlinModule. The pipeline classes are @Inject/@Singleton, so
 * Guice just-in-time binds them — the module only provides the binding that needs runtime config
 * (OpenSearchService host/port). Everything is overridable in tests via [appInjector].
 */
class AppModule : KotlinModule() {
    override fun configure() {
        // no explicit bindings — @Inject constructors are just-in-time bound
    }

    @Provides @Singleton
    fun openSearch(): OpenSearchService = OpenSearchService(
        host = System.getenv("OPENSEARCH_HOST") ?: "localhost",
        port = System.getenv("OPENSEARCH_PORT")?.toIntOrNull() ?: 9200,
    )
}
