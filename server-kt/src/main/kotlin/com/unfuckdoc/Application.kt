package com.unfuckdoc

import com.google.inject.Guice
import com.unfuckdoc.di.AppModule
import com.unfuckdoc.routes.registerRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val injector = Guice.createInjector(AppModule())
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false })
        }
        install(CallLogging)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
            }
        }
        registerRoutes(injector)
        println("unfuckdoc-kt (Ktor + Guice) → http://localhost:$port")
    }.start(wait = true)
}
