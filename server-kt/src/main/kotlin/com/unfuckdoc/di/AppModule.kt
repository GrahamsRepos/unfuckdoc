package com.unfuckdoc.di

import com.unfuckdoc.domain.Canonicalizer
import com.unfuckdoc.domain.Classifier
import com.unfuckdoc.domain.CsvReader
import com.unfuckdoc.domain.IndexBuilder
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.opensearch.OpenSearchService
import org.koin.dsl.module

/** Koin wiring — the whole pipeline is a graph of small, testable singletons. */
val appModule = module {
    single { Classifier() }
    single { Canonicalizer() }
    single { CsvReader() }
    single { IndexBuilder() }
    single { Pipeline(get(), get()) }
    single {
        OpenSearchService(
            host = System.getenv("OPENSEARCH_HOST") ?: "localhost",
            port = System.getenv("OPENSEARCH_PORT")?.toIntOrNull() ?: 9200,
        )
    }
}
