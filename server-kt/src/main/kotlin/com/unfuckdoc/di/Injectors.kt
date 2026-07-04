package com.unfuckdoc.di

import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Module
import com.google.inject.util.Modules

/**
 * Build the application injector, optionally overriding bindings. Tests pass an override module
 * (e.g. `bind<OpenSearchService>().toInstance(mock)`) to swap selected deps for mocks while the rest
 * of the real graph is wired normally:
 *
 *   appInjector(object : KotlinModule() {
 *       override fun configure() { bind<OpenSearchService>().toInstance(fake) }
 *   })
 */
fun appInjector(vararg overrides: Module): Injector =
    Guice.createInjector(Modules.override(AppModule()).with(overrides.toList()))
