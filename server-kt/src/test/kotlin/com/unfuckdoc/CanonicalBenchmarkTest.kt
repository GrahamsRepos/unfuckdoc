package com.unfuckdoc

import com.unfuckdoc.domain.Canonicalizer
import com.unfuckdoc.domain.Classifier
import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.CsvReader
import com.unfuckdoc.domain.MiniLmEmbedder
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.domain.SemanticCanonicalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * In-process regression gate for canonical inference + consolidation, scored against the
 * ground-truth answer keys under data/benchmark/. No server needed — runs the real Pipeline.
 * Locks in a baseline so reranker/LLM/multilingual work can be measured, not guessed.
 */
class CanonicalBenchmarkTest {
    private val dataDir = File(System.getenv("DATA_DIR") ?: "../data")
    private val csv = CsvReader()
    private val pipeline = Pipeline(Classifier(), SemanticCanonicalizer(Canonicalizer(), MiniLmEmbedder()))
    private val consolidator = Consolidator()

    private data class Score(var correct: Int = 0, var fp: Int = 0, var miss: Int = 0, var wrong: Int = 0) {
        val n get() = correct + fp + miss + wrong
    }

    private fun grade(answersFile: String): Score {
        val answers = Json.parseToJsonElement(File(dataDir, "benchmark/$answersFile").readText()).jsonObject
        val s = Score()
        for ((rel, colsEl) in answers) {
            val (headers, rows) = csv.parse(File(dataDir, "benchmark/$rel").readText())
            val pred = pipeline.process(rel, headers, rows).columns
                .associate { it.name to (it.canonical to it.canonicalMethod) }
            for ((col, want) in colsEl.jsonObject) {
                val wantCanon = want.jsonArray[0].jsonPrimitive.content
                val wantIdentity = want.jsonArray[1].jsonPrimitive.content == "identity"
                val (gotCanon, method) = pred[col] ?: ("?" to "?")
                val gotIdentity = method == "identity"
                when {
                    gotCanon == wantCanon -> s.correct++
                    wantIdentity && !gotIdentity -> s.fp++    // costly: confident wrong canonical
                    !wantIdentity && gotIdentity -> s.miss++
                    else -> s.wrong++
                }
            }
        }
        return s
    }

    @Test
    fun `adversarial suite holds its baseline`() {
        val s = grade("benchmark_answers.json")
        println("adversarial: ${s.correct}/${s.n} correct, fp=${s.fp} miss=${s.miss} wrong=${s.wrong}")
        // baseline locked at 27/42, 7 FP — assert no regression below it
        assertTrue(s.correct >= 27, "adversarial correct regressed: ${s.correct} < 27")
        assertTrue(s.fp <= 7, "adversarial false-positives regressed: ${s.fp} > 7")
    }

    @Test
    fun `sales multi-source suite holds its baseline`() {
        val s = grade("sales_answers.json")
        println("sales: ${s.correct}/${s.n} correct, fp=${s.fp} miss=${s.miss} wrong=${s.wrong}")
        assertTrue(s.correct >= 53, "sales correct regressed: ${s.correct} < 53")
        assertTrue(s.fp <= 2, "sales false-positives regressed: ${s.fp} > 2")
    }

    @Test
    fun `multiple phone columns consolidate into one multi-valued canonical`() {
        val (headers, rows) = csv.parse(File(dataDir, "benchmark/sales/multichannel_crm.csv").readText())
        val cons = consolidator.consolidate(rows, pipeline.process("multichannel_crm.csv", headers, rows).columns)
        val phone = cons.unified.first { it.canonical == "phone" }
        assertTrue(phone.cardinality == "array", "phone should be a multi-valued array, was ${phone.cardinality}")
        // a record should carry all three distinct phones, not just one
        val doc = cons.docs.first { it["phone"] is List<*> }
        val phones = doc["phone"] as List<*>
        assertTrue(phones.size >= 3, "expected >=3 phones consolidated, got ${phones.size}: $phones")
    }
}
