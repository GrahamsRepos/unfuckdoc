package com.unfuckdoc.domain

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
import ai.djl.repository.zoo.Criteria
import jakarta.inject.Inject
import kotlin.math.sqrt

/**
 * all-MiniLM-L6-v2 (384-d) via DJL. Loaded lazily — the model is only pulled the first time an
 * unrecognized field name actually needs a semantic match, so the common (all-recognized) path
 * never pays for it. Predictor access is synchronized (DJL predictors are not thread-safe).
 */
class MiniLmEmbedder @Inject constructor() : Embedder {

    override val enabled = true

    private val predictor by lazy {
        Criteria.builder()
            .setTypes(String::class.java, FloatArray::class.java)
            .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2")
            .optEngine("PyTorch")
            .optTranslatorFactory(TextEmbeddingTranslatorFactory())
            .build()
            .loadModel()
            .newPredictor()
    }

    @Synchronized
    override fun embed(text: String): FloatArray {
        val v = predictor.predict(text)
        var n = 0.0
        for (x in v) n += (x * x).toDouble()
        val norm = sqrt(n).toFloat().coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / norm }   // L2-normalize -> dot == cosine
    }
}
