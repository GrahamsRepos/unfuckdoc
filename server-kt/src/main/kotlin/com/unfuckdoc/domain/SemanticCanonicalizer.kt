package com.unfuckdoc.domain

import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * Canonicalization with a semantic fallback. The deterministic dictionary runs first (fast, $0);
 * only names that fall through to `identity` get embedded and matched to the nearest TYPE-COMPATIBLE
 * canonical anchor. A hit requires both an absolute threshold and a margin over the runner-up, so an
 * ambiguous name (e.g. between first_name/last_name/full_name) is never force-routed — it stays
 * identity. Semantic assignments are tagged method="semantic" (counted like an LLM escalation).
 */
@Singleton
class SemanticCanonicalizer @Inject constructor(
    private val deterministic: Canonicalizer,
    private val embedder: Embedder,
) {
    private val threshold = 0.5    // best canonical must clear this cosine
    private val margin = 0.05      // ...and beat the runner-up by this, else it's ambiguous -> identity

    private val nameTokens = Regex("[^A-Za-z0-9]+")

    /** Per canonical: the embedding of each alias word (+ the canonical name as a phrase). A column
     *  name scores against a canonical by its BEST-matching alias, so 'individual' hits full_name via
     *  'person' rather than being diluted by an averaged bag. */
    private val anchors: List<Triple<String, Set<String>?, List<FloatArray>>> by lazy {
        deterministic.specs.map { spec ->
            val words = (listOf(spec.name.replace("_", " ")) + spec.aliases.filter { it.length >= 3 }).distinct()
            Triple(spec.name, spec.types, words.map { embedder.embed(it) })
        }
    }

    private fun clean(name: String) = nameTokens.replace(name, " ").trim().ifEmpty { name }

    private fun score(q: FloatArray, embs: List<FloatArray>): Double {
        var best = -1.0
        for (e in embs) { val d = dot(q, e); if (d > best) best = d }
        return best
    }

    fun canonicalize(name: String, kind: String): Pair<String, String> {
        val (canon, method) = deterministic.canonicalize(name, kind)
        if (method == "alias" || !embedder.enabled) return canon to method

        // identity fallback -> nearest type-compatible canonical (max-over-aliases) with a margin guard
        val q = embedder.embed(clean(name))
        var best = -1.0; var bestName: String? = null; var second = -1.0
        for ((cname, types, embs) in anchors) {
            if (types != null && kind !in types) continue          // same type gate as the dictionary
            val s = score(q, embs)
            if (s > best) { second = best; best = s; bestName = cname }
            else if (s > second) second = s
        }
        return if (bestName != null && best >= threshold && best - second >= margin)
            bestName to "semantic"
        else
            canon to method
    }

    /** Test/diagnostic: ranked type-compatible canonicals for a name. */
    fun rank(name: String, kind: String): List<Pair<String, Double>> {
        val q = embedder.embed(clean(name))
        return anchors.filter { it.second == null || kind in it.second!! }
            .map { it.first to score(q, it.third) }.sortedByDescending { it.second }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var s = 0.0
        for (i in a.indices) s += (a[i] * b[i]).toDouble()
        return s
    }
}
