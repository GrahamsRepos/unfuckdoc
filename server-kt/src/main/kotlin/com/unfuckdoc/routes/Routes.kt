package com.unfuckdoc.routes

import com.unfuckdoc.domain.CsvReader
import com.unfuckdoc.domain.Pipeline
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject
import java.io.File

fun Application.registerRoutes() {
    val pipeline by inject<Pipeline>()
    val csv by inject<CsvReader>()
    val dataDir = File(System.getenv("DATA_DIR") ?: "../data").canonicalFile

    routing {
        get("/health") { call.respondText("ok") }

        get("/api/samples") {
            val roots = listOf(dataDir, File(dataDir, "samples"), File(dataDir, "collections"))
            val found = roots.filter { it.isDirectory }.flatMap { root ->
                root.listFiles { f -> f.extension.equals("csv", true) }?.sorted()?.map {
                    dataDir.toPath().relativize(it.toPath()).toString()
                } ?: emptyList()
            }
            call.respond(mapOf("samples" to found))
        }

        // Classify + canonicalize a CSV. Source: ?sample=<relpath under data/> OR the raw request body.
        post("/api/process") {
            val sample = call.request.queryParameters["sample"]
            val text = if (sample != null) {
                val f = File(dataDir, sample).canonicalFile
                if (!f.path.startsWith(dataDir.path) || !f.isFile) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown sample"))
                    return@post
                }
                f.readText()
            } else {
                call.receiveText()
            }
            val (headers, rows) = csv.parse(text)
            if (headers.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no columns parsed"))
                return@post
            }
            val filename = sample?.substringAfterLast('/') ?: "upload.csv"
            call.respond(pipeline.process(filename, headers, rows))
        }
    }
}
