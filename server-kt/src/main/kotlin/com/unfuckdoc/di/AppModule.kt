package com.unfuckdoc.di

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import com.unfuckdoc.domain.Canonicalizer
import com.unfuckdoc.domain.Classifier
import com.unfuckdoc.domain.CsvReader
import com.unfuckdoc.domain.IndexBuilder
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.opensearch.OpenSearchService

/**
 * Guice wiring. @Provides methods keep the domain classes DI-agnostic (no @Inject annotations),
 * and Guice resolves the graph (e.g. Pipeline's Classifier + Canonicalizer) automatically.
 */
class AppModule : AbstractModule() {

    @Provides @Singleton
    fun classifier(): Classifier = Classifier()

    @Provides @Singleton
    fun canonicalizer(): Canonicalizer = Canonicalizer()

    @Provides @Singleton
    fun csvReader(): CsvReader = CsvReader()

    @Provides @Singleton
    fun indexBuilder(): IndexBuilder = IndexBuilder()

    @Provides @Singleton
    fun pipeline(classifier: Classifier, canonicalizer: Canonicalizer): Pipeline =
        Pipeline(classifier, canonicalizer)

    @Provides @Singleton
    fun openSearch(): OpenSearchService = OpenSearchService(
        host = System.getenv("OPENSEARCH_HOST") ?: "localhost",
        port = System.getenv("OPENSEARCH_PORT")?.toIntOrNull() ?: 9200,
    )
}
