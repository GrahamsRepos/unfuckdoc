package com.unfuckdoc.routes

import com.unfuckdoc.domain.CsvReader
import com.unfuckdoc.domain.Dsl
import com.unfuckdoc.domain.IndexBuilder
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.opensearch.OpenSearchService
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject
import java.io.File

private fun slug(filename: String): String =
    "kt_" + filename.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)

fun Application.registerRoutes() {
    val pipeline by inject<Pipeline>()
    val csv by inject<CsvReader>()
    val indexBuilder by inject<IndexBuilder>()
    val opensearch by inject<OpenSearchService>()
    val dataDir = File(System.getenv("DATA_DIR") ?: "../data").canonicalFile

    fun readCsv(sample: String?, body: String): Pair<List<String>, List<Map<String, String?>>>? {
        val text = if (sample != null) {
            val f = File(dataDir, sample).canonicalFile
            if (!f.path.startsWith(dataDir.path) || !f.isFile) return null
            f.readText()
        } else body
        return csv.parse(text)
    }

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

        get("/api/opensearch") {
            call.respond(mapOf("available" to opensearch.available().toString()))
        }

        // Classify -> consolidate -> index into OpenSearch (real opensearch-java client).
        post("/api/index") {
            val sample = call.request.queryParameters["sample"]
            val parsed = readCsv(sample, call.receiveText())
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown sample"))
            val (headers, rows) = parsed
            val filename = sample?.substringAfterLast('/') ?: "upload.csv"
            val result = pipeline.process(filename, headers, rows)
            val bundle = indexBuilder.build(rows, result.columns)
            if (!opensearch.available())
                return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "OpenSearch not reachable on :9200"))
            val index = slug(filename)
            val count = opensearch.indexDocs(index, bundle.mappingJson, bundle.docs)
            call.respond(buildJsonObject {
                put("index", index); put("count", count); put("fields", bundle.properties.size)
            })
        }

        // Build a query DSL from params, run it via opensearch-java, return hits + the DSL.
        post("/api/search") {
            val params = call.request.queryParameters
            val index = params["index"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "index required"))
            val q = params["q"]
            val filters = params.entries()
                .filter { it.key == "f" }
                .flatMap { it.value }
                .mapNotNull { s -> s.indexOf(':').takeIf { it > 0 }?.let { s.substring(0, it) to s.substring(it + 1) } }
                .toMap()
            val size = params["size"]?.toIntOrNull() ?: 10
            if (!opensearch.available())
                return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "OpenSearch not reachable"))
            val query = Dsl.query(q, filters, listOf("*"))
            val hits = opensearch.search(index, Dsl.toJson(query), size)
            call.respond(buildJsonObject {
                put("index", index)
                put("dsl", Dsl.display(query, size))
                put("count", hits.size)
                put("results", buildJsonArray { hits.forEach { add(Dsl.anyToJson(it)) } })
            })
        }
    }
}
