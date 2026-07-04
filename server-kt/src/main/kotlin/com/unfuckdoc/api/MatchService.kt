package com.unfuckdoc.api

import com.unfuckdoc.domain.Consolidator
import com.unfuckdoc.domain.CsvReader
import com.unfuckdoc.domain.Pipeline
import com.unfuckdoc.domain.Unified
import jakarta.inject.Inject
import java.io.File
import kotlin.math.max

/** Fuzzy record linkage between two datasets on a shared canonical key. */
class MatchService @Inject constructor(
    private val csv: CsvReader,
    private val pipeline: Pipeline,
    private val consolidator: Consolidator,
) {
    private class Processed(val unified: List<Unified>, val docs: List<Map<String, Any?>>, val rows: Int)

    private val dataDir = File(System.getenv("DATA_DIR") ?: "../data").canonicalFile
    private val cache = HashMap<String, Processed>()
    private val displayOrder = listOf("full_name", "first_name", "last_name", "company", "job_title", "title", "email", "phone")

    private fun process(name: String): Processed = cache.getOrPut(name) {
        val f = File(dataDir, name).canonicalFile
        require(f.path.startsWith(dataDir.path) && f.isFile) { "unknown dataset: $name" }
        val (headers, rows) = csv.parse(f.readText())
        val result = pipeline.process(name, headers, rows)
        val cons = consolidator.consolidate(rows, result.columns)
        Processed(cons.unified, cons.docs, result.nRows)
    }

    fun candidates(a: String, b: String): MatchCandidates = try {
        MatchCandidates(sharedKeys(process(a), process(b)))
    } catch (e: Exception) {
        MatchCandidates(emptyList(), e.message)
    }

    fun match(a: String, b: String, key: String?, threshold: Double): MatchResult {
        if (a == b) return err("pick two different datasets")
        val ra: Processed; val rb: Processed
        try { ra = process(a); rb = process(b) } catch (e: Exception) { return err(e.message ?: "load error") }
        val k = key?.takeIf { it.isNotBlank() } ?: sharedKeys(ra, rb).firstOrNull()?.field
            ?: return err("no shared canonical field to match on")

        // block dataset B by first char of the normalized key
        val blocks = HashMap<Char, MutableList<Pair<Int, String>>>()
        rb.docs.forEachIndexed { j, d ->
            val nk = normKey(d[k], k); if (nk.isNotEmpty()) blocks.getOrPut(nk[0]) { mutableListOf() }.add(j to nk)
        }

        val dispA = matchDisplay(ra); val dispB = matchDisplay(rb)
        val pairs = ArrayList<MatchPair>()
        var matched = 0; var exact = 0; val matchedB = HashSet<Int>()
        for (d in ra.docs) {
            val nk = normKey(d[k], k); if (nk.isEmpty()) continue
            var best = 0.0; var bestJ = -1
            for ((j, bk) in blocks[nk[0]] ?: emptyList()) { val s = sim(nk, bk); if (s > best) { best = s; bestJ = j } }
            if (best >= threshold && bestJ >= 0) {
                matched++; matchedB.add(bestJ); if (best >= 1.0) exact++
                if (pairs.size < 500) pairs.add(MatchPair(round3(best),
                    dispA.associateWith { Docs.flattenText(d[it]) }, dispB.associateWith { Docs.flattenText(rb.docs[bestJ][it]) }))
            }
        }
        pairs.sortWith(compareBy({ it.sim >= 1.0 }, { -it.sim }))   // interesting fuzzy matches first
        val keyedA = ra.docs.count { normKey(it[k], k).isNotEmpty() }
        return MatchResult(k, threshold, ra.rows, rb.rows, keyedA, matched, exact,
            keyedA - matched, rb.rows - matchedB.size, dispA, dispB, pairs.take(25))
    }

    // ---- internals ----
    private fun sharedKeys(a: Processed, b: Processed): List<MatchKey> {
        val primA = a.unified.filter { it.kind != "free_text" }.associateBy { it.canonical }
        val primB = b.unified.filter { it.kind != "free_text" }.map { it.canonical }.toSet()
        return (primA.keys intersect primB).map { canon ->
            val vals = a.docs.map { normKey(it[canon], canon) }.filter { it.isNotEmpty() }
            val uniq = if (vals.isNotEmpty()) vals.toSet().size.toDouble() / vals.size else 0.0
            MatchKey(canon, primA[canon]!!.kind, round3(uniq),
                vals.size, b.docs.count { normKey(it[canon], canon).isNotEmpty() })
        }.sortedWith(compareBy({ it.kind == "numeric" }, { it.kind == "date" }, { -it.uniqueness }, { -(it.fillA ?: 0) }))
    }

    private fun matchDisplay(p: Processed): List<String> {
        val present = p.unified.map { it.canonical }.toSet()
        return displayOrder.filter { it in present }.take(4)
    }

    private fun normKey(v: Any?, canon: String): String {
        val s = Docs.flattenText(v).trim().lowercase()
        return when (canon) {
            "email" -> s.replace(" ", "")
            "phone" -> s.filter { it.isDigit() }
            else -> s.replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
        }
    }

    private fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val base = lcsRatio(a, b)
        val sa = a.split(" ").sorted().joinToString(" "); val sb = b.split(" ").sorted().joinToString(" ")
        return max(base, lcsRatio(sa, sb))
    }

    private fun lcsRatio(a: String, b: String): Double {
        val la = a.length; val lb = b.length
        if (la == 0 || lb == 0) return 0.0
        val dp = IntArray(lb + 1)
        for (i in 1..la) {
            var prev = 0
            for (j in 1..lb) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev + 1 else max(dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return 2.0 * dp[lb] / (la + lb)
    }

    private fun round3(x: Double) = Math.round(x * 1000) / 1000.0
    private fun err(msg: String) = MatchResult("", 0.0, 0, 0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(), msg)
}
