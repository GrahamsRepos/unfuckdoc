package com.unfuckdoc

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
import kotlinx.serialization.json.Json

/** Ktor wiring, parameterized by the controller — so tests can mount it with a mock-injected one. */
fun Application.module(controller: ApiController) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
        }
    }
    routing { controller.install(this) }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val controller = appInjector().getInstance<ApiController>()
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(controller)
        println("unfuckdoc-kt (Ktor + kotlin-guice) → http://localhost:$port")
    }.start(wait = true)
}
