package com.unfuckdoc

import com.unfuckdoc.domain.CsvReader
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvReaderTest {
    private val csv = CsvReader()

    @Test
    fun `detects comma tab semicolon and pipe delimiters`() {
        assertEquals(',', csv.sniffDelimiter("a,b,c\n1,2,3"))
        assertEquals('\t', csv.sniffDelimiter("a\tb\tc\n1\t2\t3"))
        assertEquals(';', csv.sniffDelimiter("a;b;c\n1;2;3"))
        assertEquals('|', csv.sniffDelimiter("a|b|c\n1|2|3"))
        // no delimiter -> comma fallback
        assertEquals(',', csv.sniffDelimiter("single\nrow"))
    }

    @Test
    fun `does not count delimiters inside quoted header cells`() {
        // header has one real semicolon separator; the comma lives inside a quoted cell
        assertEquals(';', csv.sniffDelimiter("\"Name, full\";email"))
    }

    @Test
    fun `parses a semicolon-delimited file into typed columns`() {
        val (headers, rows) = csv.parse("email;country;amount\na@x.com;Japan;100\nb@x.com;Germany;200")
        assertEquals(listOf("email", "country", "amount"), headers)
        assertEquals(2, rows.size)
        assertEquals("Japan", rows[0]["country"])
        assertEquals("200", rows[1]["amount"])
    }

    @Test
    fun `parses a tab-delimited file`() {
        val (headers, rows) = csv.parse("email\tcity\na@x.com\tOsaka")
        assertEquals(listOf("email", "city"), headers)
        assertEquals("Osaka", rows[0]["city"])
    }
}
