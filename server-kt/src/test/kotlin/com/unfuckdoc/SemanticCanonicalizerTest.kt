package com.unfuckdoc

import com.unfuckdoc.domain.Canonicalizer
import com.unfuckdoc.domain.MiniLmEmbedder
import com.unfuckdoc.domain.SemanticCanonicalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-embedding test (loads MiniLM via DJL). Verifies the semantic fallback maps unrecognized
 * field-name synonyms onto the right canonical, while the deterministic dictionary still wins for
 * known aliases and the type gate prevents bad routes.
 */
class SemanticCanonicalizerTest {

    private val sc = SemanticCanonicalizer(Canonicalizer(), MiniLmEmbedder())

    @Test
    fun `unknown synonyms resolve semantically to the right canonical`() {
        // not in the dictionary -> identity in the deterministic pass, then matched by embedding
        assertEquals("full_name" to "semantic", sc.canonicalize("individual", "identifier"))  // via 'person'
        assertEquals("company" to "semantic", sc.canonicalize("corporation", "enum"))
        assertEquals("url" to "semantic", sc.canonicalize("webpage", "identifier"))
    }

    @Test
    fun `deterministic aliases still win (no embedding needed)`() {
        assertEquals("email" to "alias", sc.canonicalize("e-mail", "identifier"))
        assertEquals("first_name" to "alias", sc.canonicalize("First Name", "enum"))
    }

    @Test
    fun `type gate blocks incompatible semantic matches`() {
        // 'individual' as free_text may become full_name (string-compatible) but never a numeric field
        val (canon, _) = sc.canonicalize("individual", "free_text")
        assertTrue(canon != "amount" && canon != "rating", "must not route to a numeric canonical")
    }
}
