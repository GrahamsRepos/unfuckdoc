package com.unfuckdoc.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.sqrt

/**
 * Embedder backed by any OpenAI-compatible `/v1/embeddings` endpoint — Ollama (nomic/bge/mxbai),
 * vLLM, LocalAI, OVH AI Endpoints, OpenRouter. Swap the model/host by env, no code change:
 *   EMBED_BASE_URL=http://localhost:11434/v1  EMBED_MODEL=nomic-embed-text
 * L2-normalizes so dot == cosine (matches MiniLmEmbedder).
 */
class OpenAiEmbedder(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
) : Embedder {
    override val enabled = true

    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override fun embed(text: String): FloatArray {
        val body = json.encodeToString(EmbedRequest.serializer(), EmbedRequest(model, text))
        val b = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/embeddings"))
            .header("Content-Type", "application/json")
            .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
        val resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() in 200..299) { "embeddings ${resp.statusCode()}: ${resp.body().take(200)}" }
        val v = json.decodeFromString(EmbedResponse.serializer(), resp.body()).data.first().embedding
        var n = 0.0
        for (x in v) n += (x * x)
        val norm = sqrt(n).toFloat().coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / norm }
    }

    @Serializable private data class EmbedRequest(val model: String, val input: String)
    @Serializable private data class EmbedResponse(val data: List<Item>)
    @Serializable private data class Item(val embedding: FloatArray)
}
