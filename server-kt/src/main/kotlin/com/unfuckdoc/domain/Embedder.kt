package com.unfuckdoc.domain

/** Produces an L2-normalized embedding for a short text (a field name), so dot product == cosine. */
interface Embedder {
    val enabled: Boolean
    fun embed(text: String): FloatArray
}

/** Disabled embedder — semantic matching is skipped, canonicalization stays deterministic-only. */
object NoopEmbedder : Embedder {
    override val enabled = false
    override fun embed(text: String): FloatArray = FloatArray(0)
}
