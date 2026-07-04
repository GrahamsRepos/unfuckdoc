package com.unfuckdoc.domain

import jakarta.inject.Inject
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.io.StringReader

/** Parse CSV text into headers + rows (blank cells become null — coverage, not signal). */
class CsvReader @Inject constructor() {
    fun parse(text: String): Pair<List<String>, List<Map<String, String?>>> {
        val format: CSVFormat = CSVFormat.DEFAULT.builder()
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
