package com.unfuckdoc.routes

import com.unfuckdoc.api.DatasetService
import com.unfuckdoc.api.FieldFilter
import com.unfuckdoc.domain.CsvReader
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class LoadRequest(val name: String)

@Serializable
data class SearchRequest(
    val q: String = "", val mode: String = "keyword", val field: String? = null,
    val tag: String = "", val filters: List<FieldFilter> = emptyList(), val size: Int = 12,
)

/**
 * HTTP layer implementing the API contract the RR7 frontend expects. Dependencies are injected by
 * Guice via the constructor (@Inject). `install(route)` attaches the handlers to a Ktor Route.
 */
@Singleton
class ApiController @Inject constructor(
    private val csv: CsvReader,
    private val dataset: DatasetService,
) {
    private val dataDir = File(System.getenv("DATA_DIR") ?: "../data").canonicalFile

    private fun sampleText(name: String): String? {
        val f = File(dataDir, name).canonicalFile
        return if (f.path.startsWith(dataDir.path) && f.isFile) f.readText() else null
    }

    fun install(route: Route): Unit = with(route) {
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

        get("/api/overview") { call.respond(dataset.overview()) }

        post("/api/load_sample") {
            val name = call.receive<LoadRequest>().name
            val text = sampleText(name)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown sample"))
            val (headers, rows) = csv.parse(text)
            call.respond(dataset.load(name.substringAfterLast('/'), headers, rows))
        }

        post("/api/upload") {
            var bytes: ByteArray? = null
            var filename = "upload.csv"
            call.receiveMultipart().forEachPart { part ->
                if (part is PartData.FileItem) {
                    filename = part.originalFileName ?: filename
                    bytes = part.provider().readRemaining().readByteArray()
                }
                part.dispose()
            }
            val text = bytes?.toString(Charsets.UTF_8)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file uploaded"))
            val (headers, rows) = csv.parse(text)
            call.respond(dataset.load(filename, headers, rows))
        }

        post("/api/search") {
            val req = call.receive<SearchRequest>()
            call.respond(dataset.search(req.mode, req.field, req.q, req.tag, req.filters, req.size))
        }
    }
}
