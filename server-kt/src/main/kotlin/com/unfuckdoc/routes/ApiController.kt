package com.unfuckdoc.routes

import com.unfuckdoc.api.CollectionService
import com.unfuckdoc.api.DatasetService
import com.unfuckdoc.api.FieldFilter
import com.unfuckdoc.api.MatchService
import com.unfuckdoc.domain.CsvReader
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import jakarta.inject.Inject
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.io.File

@Serializable data class LoadRequest(val name: String)
@Serializable data class SearchRequest(
    val q: String = "", val mode: String = "keyword", val field: String? = null,
    val tag: String = "", val filters: List<FieldFilter> = emptyList(), val size: Int = 12,
    val page: Int = 1,
    val showAllColumns: Boolean = false,
)
@Serializable data class CreateCollectionRequest(val name: String, val key: String = "email")
@Serializable data class AddSampleRequest(val sample: String = "")
@Serializable data class CollectionSearchRequest(
    val q: String = "", val tag: String = "",
    @SerialName("source_files") val sourceFiles: List<String> = emptyList(),
    val filters: List<FieldFilter> = emptyList(), val size: Int = 30,
    val page: Int = 1,
)
@Serializable data class SegmentRequest(val name: String, val filters: List<FieldFilter> = emptyList())
@Serializable data class MappingOverrideRequest(val column: String, val canonical: String = "")
@Serializable data class CustomCanonicalRequest(val name: String, val type: String = "keyword")
@Serializable data class CollectionKeyRequest(val key: String)
@Serializable data class MatchCandidatesRequest(val a: String, val b: String)
@Serializable data class MatchRequest(val a: String, val b: String, val key: String? = null, val threshold: Double = 0.85)

/**
 * HTTP layer implementing the API contract the RR7 frontend expects. Dependencies are injected by
 * Guice via the constructor (@Inject). `install(route)` attaches the handlers to a Ktor Route.
 */
class ApiController @Inject constructor(
    private val csv: CsvReader,
    private val dataset: DatasetService,
    private val collections: CollectionService,
    private val match: MatchService,
) {
    private val dataDir = File(System.getenv("DATA_DIR") ?: "../data").canonicalFile

    private fun sampleText(name: String): String? {
        val f = File(dataDir, name).canonicalFile
        return if (f.path.startsWith(dataDir.path) && f.isFile) f.readText() else null
    }

    private suspend fun ApplicationCall.readCsvUpload(): Pair<String, String>? {
        var bytes: ByteArray? = null
        var filename = "upload.csv"
        receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem) {
                filename = part.originalFileName ?: filename
                bytes = part.provider().readRemaining().readByteArray()
            }
            part.dispose()
        }
        return bytes?.let { filename to it.toString(Charsets.UTF_8) }
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

        get("/api/schema") {
            call.respond(dataset.schema() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "load a dataset first")))
        }

        post("/api/load_sample") {
            val name = call.receive<LoadRequest>().name
            val text = sampleText(name) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown sample"))
            val (headers, rows) = csv.parse(text)
            call.respond(dataset.load(name.substringAfterLast('/'), headers, rows))
        }

        post("/api/upload") {
            val up = call.readCsvUpload() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file uploaded"))
            val (headers, rows) = csv.parse(up.second)
            call.respond(dataset.load(up.first, headers, rows))
        }

        post("/api/search") {
            val req = call.receive<SearchRequest>()
            call.respond(dataset.search(req.mode, req.field, req.q, req.tag, req.filters, req.size, req.page, req.showAllColumns))
        }

        // ---- collections ----
        get("/api/collections") { call.respond(mapOf("collections" to collections.list())) }

        post("/api/collections") {
            val req = call.receive<CreateCollectionRequest>()
            call.respond(collections.create(req.name.trim(), req.key)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid or duplicate name")))
        }

        get("/api/collections/{name}") {
            call.respond(collections.detail(call.parameters["name"]!!) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown collection")))
        }

        delete("/api/collections/{name}") {
            collections.delete(call.parameters["name"]!!)
            call.respond(mapOf("ok" to true))
        }

        post("/api/collections/{name}/add") {
            val name = call.parameters["name"]!!
            val add = if (call.request.contentType().match(ContentType.MultiPart.FormData)) {
                val up = call.readCsvUpload() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file"))
                val (headers, rows) = csv.parse(up.second)
                collections.add(name, up.first, headers, rows)
            } else {
                val sample = call.receive<AddSampleRequest>().sample
                val text = sampleText(sample) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown sample"))
                val (headers, rows) = csv.parse(text)
                collections.add(name, sample.substringAfterLast('/'), headers, rows)
            }
            call.respond(add)
        }

        post("/api/collections/{name}/search") {
            val req = call.receive<CollectionSearchRequest>()
            call.respond(collections.search(call.parameters["name"]!!, req.q, req.tag, req.sourceFiles, req.filters, req.size, req.page)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown collection")))
        }

        post("/api/collections/{name}/segments") {
            val req = call.receive<SegmentRequest>()
            call.respond(collections.putSegment(call.parameters["name"]!!, req.name.trim(), req.filters)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown collection")))
        }

        post("/api/collections/{name}/mapping") {
            val req = call.receive<MappingOverrideRequest>()
            call.respond(collections.setMapping(call.parameters["name"]!!, req.column, req.canonical)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown collection")))
        }

        post("/api/collections/{name}/key") {
            val req = call.receive<CollectionKeyRequest>()
            call.respond(collections.setKey(call.parameters["name"]!!, req.key)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown collection")))
        }

        delete("/api/collections/{name}/segments/{seg}") {
            call.respond(collections.deleteSegment(call.parameters["name"]!!, call.parameters["seg"]!!)
                ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown collection")))
        }

        post("/api/collections/{name}/canonicals") {
            val req = call.receive<CustomCanonicalRequest>()
            call.respond(collections.putCanonical(call.parameters["name"]!!, req.name, req.type)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown collection")))
        }

        delete("/api/collections/{name}/canonicals/{canon}") {
            call.respond(collections.deleteCanonical(call.parameters["name"]!!, call.parameters["canon"]!!)
                ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown collection")))
        }

        // ---- match ----
        post("/api/match_candidates") {
            val r = call.receive<MatchCandidatesRequest>()
            call.respond(match.candidates(r.a, r.b))
        }

        post("/api/match") {
            val r = call.receive<MatchRequest>()
            call.respond(match.match(r.a, r.b, r.key, r.threshold))
        }
    }
}
