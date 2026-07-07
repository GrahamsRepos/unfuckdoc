package com.unfuckdoc

import com.unfuckdoc.domain.TransformEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransformEngineTest {
    private val e = TransformEngine()
    private fun ev(expr: String, row: Map<String, String?>) = e.compile(expr).eval(row)

    @Test fun `field reference and string functions`() {
        val row = mapOf("city" to "  london ", "first" to "Ada", "last" to "Lovelace")
        assertEquals("LONDON", ev("upper(trim(city))", row))
        assertEquals("Ada Lovelace", ev("""concat(first, " ", last)""", row))
        assertEquals("london", ev("trim(city)", row))
    }

    @Test fun `numeric coercion and arithmetic`() {
        val row = mapOf("raw" to "$1,200.50", "qty" to "3")
        assertEquals("1200.5", ev("""to_number(strip(raw, "$,"))""", row))
        assertEquals("3601.5", ev("""mul(to_number(strip(raw, "$,")), qty)""", row))   // 1200.5*3
        assertEquals("1201", ev("""round(to_number(strip(raw, "$,")), 0)""", row))
    }

    @Test fun `regex, split, digits`() {
        val row = mapOf("phone" to "+1 (555) 123-4567", "path" to "collections/hot.csv")
        assertEquals("15551234567", ev("digits(phone)", row))
        assertEquals("hot.csv", ev("""split(path, "/", 1)""", row))
        assertEquals("REF", ev("""regex_extract(default(missing, "REF-9"), "[A-Z]+")""", row))
    }

    @Test fun `conditionals and comparison`() {
        val big = mapOf("amount" to "1500")
        val small = mapOf("amount" to "200")
        val expr = """if(gt(to_number(amount), "1000"), "big", "small")"""
        assertEquals("big", ev(expr, big))
        assertEquals("small", ev(expr, small))
        assertEquals("true", ev("""contains(city, "ond")""", mapOf("city" to "London")))
    }

    @Test fun `coalesce and default handle missing fields`() {
        assertEquals("fallback", ev("""default(missing, "fallback")""", emptyMap()))
        assertEquals("b", ev("""coalesce(a, b, c)""", mapOf("a" to "", "b" to "b", "c" to "c")))
    }

    @Test fun `rejects unknown functions and bad syntax (no arbitrary code)`() {
        assertFailsWith<IllegalArgumentException> { e.compile("exec(city)") }       // not whitelisted
        assertFailsWith<IllegalArgumentException> { e.compile("System.exit(0)") }   // not a call it accepts
        assertFailsWith<IllegalArgumentException> { e.compile("upper(city") }        // unbalanced
    }
}
