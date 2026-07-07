package com.unfuckdoc.domain

import jakarta.inject.Inject
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.io.StringReader

/** Parse CSV/TSV/semicolon/pipe text into headers + rows (blank cells become null — coverage, not
 *  signal). The delimiter is auto-detected from the header line so European (`;`), tab (`.tsv`), and
 *  pipe exports ingest without configuration. */
class CsvReader @Inject constructor() {
    private val candidates = listOf(',', '\t', ';', '|')

    /** Pick the delimiter by counting candidates in the header line (outside double quotes). Ties and
     *  no-delimiter files fall back to comma. */
    internal fun sniffDelimiter(text: String): Char {
        val header = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        var inQuote = false
        val counts = HashMap<Char, Int>()
        for (ch in header) {
            if (ch == '"') inQuote = !inQuote
            else if (!inQuote && ch in candidates) counts.merge(ch, 1, Int::plus)
        }
        // prefer the most frequent; on a tie keep candidate order (comma first)
        return candidates.filter { (counts[it] ?: 0) > 0 }
            .maxByOrNull { counts[it]!! } ?: ','
    }

    fun parse(text: String): Pair<List<String>, List<Map<String, String?>>> {
        val format: CSVFormat = CSVFormat.DEFAULT.builder()
            .setDelimiter(sniffDelimiter(text))
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .build()
        val parser: CSVParser = format.parse(StringReader(text))
        parser.use { p ->
            val headers: List<String> = p.headerNames
            val rows: List<Map<String, String?>> = p.records.map { rec: CSVRecord ->
                headers.associateWith { h: String ->
                    val v: String? = if (rec.isMapped(h)) rec.get(h) else null
                    if (v.isNullOrBlank()) null else v
                }
            }
            return headers to rows
        }
    }
}
