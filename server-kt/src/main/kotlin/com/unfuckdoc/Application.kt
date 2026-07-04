package com.unfuckdoc

import com.google.inject.Injector
import com.unfuckdoc.di.appInjector
import com.unfuckdoc.routes.ApiController
import dev.misfitlabs.kotlinguice4.getInstance
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * The stripped-down app primitive: install plugins + routes from a Guice [Injector]. Both `main()`
 * and integration tests build on this — tests just pass an injector with mock bindings.
 */
@OptIn(ExperimentalSerializationApi::class)
fun Application.installApp(injector: Injector) {
    install(ContentNegotiation) {
        // snake_case wire format to match the Python API contract the RR7 frontend expects.
        json(Json {
            prettyPrint = true; encodeDefaults = true; explicitNulls = false
            namingStrategy = JsonNamingStrategy.SnakeCase
        })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
        }
    }
    routing { injector.getInstance<ApiController>().install(this) }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val injector = appInjector()
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        installApp(injector)
        println("unfuckdoc-kt (Ktor + kotlin-guice) → http://localhost:$port")
    }.start(wait = true)
}
