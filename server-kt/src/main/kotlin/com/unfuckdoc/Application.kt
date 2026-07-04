package com.unfuckdoc

import com.unfuckdoc.di.appModule
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
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(Koin) {
            slf4jLogger()
            modules(appModule)
        }
        install(ContentNegotiation) {
            json(Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false })
        }
        install(CallLogging)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
            }
        }
        registerRoutes()
        println("unfuckdoc-kt (Ktor + Koin) → http://localhost:$port")
    }.start(wait = true)
}
