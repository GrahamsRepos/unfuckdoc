package com.unfuckdoc.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A gated chat LLM used for the ambiguous residual — attribute extraction, NL→query planning.
 *  Never per-row at query time; used at ingest (extraction) or once per question (planning). */
interface LlmClient {
    val enabled: Boolean
    /** Chat completion constrained to a JSON object; returns the raw JSON string ("{}" if disabled). */
    fun completeJson(system: String, user: String): String
}

object NoopLlm : LlmClient {
    override val enabled = false
    override fun completeJson(system: String, user: String) = "{}"
}

/** Any OpenAI-compatible /v1/chat/completions endpoint — Ollama (qwen2.5), vLLM, OVH AI Endpoints. */
class OpenAiChatClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
) : LlmClient {
    override val enabled = true

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun completeJson(system: String, user: String): String {
        val req = ChatRequest(
            model = model,
            messages = listOf(Msg("system", system), Msg("user", user)),
            responseFormat = ResponseFormat("json_object"),
            temperature = 0.0, stream = false,
        )
        val b = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(ChatRequest.serializer(), req)))
        val resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() in 200..299) { "chat ${resp.statusCode()}: ${resp.body().take(200)}" }
        return json.decodeFromString(ChatResponse.serializer(), resp.body()).choices.firstOrNull()?.message?.content ?: "{}"
    }

    @Serializable private data class ChatRequest(
        val model: String, val messages: List<Msg>,
        @kotlinx.serialization.SerialName("response_format") val responseFormat: ResponseFormat,
        val temperature: Double, val stream: Boolean,
    )
    @Serializable private data class Msg(val role: String, val content: String)
    @Serializable private data class ResponseFormat(val type: String)
    @Serializable private data class ChatResponse(val choices: List<Choice>)
    @Serializable private data class Choice(val message: Msg)
}
