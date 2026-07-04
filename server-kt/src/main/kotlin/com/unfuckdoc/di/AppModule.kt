package com.unfuckdoc.di

import com.unfuckdoc.domain.Canonicalizer
import com.unfuckdoc.domain.Classifier
import com.unfuckdoc.domain.CsvReader
import com.unfuckdoc.domain.Pipeline
import org.koin.dsl.module

/** Koin wiring — the whole pipeline is a graph of small, testable singletons. */
val appModule = module {
    single { Classifier() }
    single { Canonicalizer() }
    single { CsvReader() }
    single { Pipeline(get(), get()) }
}
