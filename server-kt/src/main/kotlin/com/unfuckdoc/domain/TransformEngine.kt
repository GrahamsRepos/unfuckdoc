package com.unfuckdoc.domain

/**
 * A tiny, SAFE expression language for row-level field transforms (OpenRefine-style "mutate before
 * process"). No arbitrary code — only field references, string/number literals, and a fixed whitelist
 * of pure functions. Cannot touch the filesystem, network, or host, so it's safe in a multi-tenant
 * host. Grammar:  expr := func '(' (expr (',' expr)*)? ')' | field | string | number
 * Values are strings; numeric/boolean functions parse/format as needed. Example:
 *   concat(upper(trim(city)), " ", default(region, "?"))
 *   to_number(strip(raw_price, "$,"))
 *   if(gt(to_number(amount), "1000"), "big", "small")
 */
class TransformEngine {

    fun interface Expr { fun eval(row: Map<String, String?>): String? }

    /** Compile an expression once; evaluate per row. Throws IllegalArgumentException on bad syntax/fn. */
    fun compile(src: String): Expr {
        val p = Parser(src)
        val e = p.parseExpr()
        p.expectEnd()
        return e
    }

    // ---- functions (pure, whitelisted) ----
    private val funcs: Map<String, (List<String?>) -> String?> = buildMap {
        // strings
        put("upper") { a -> a[0]?.uppercase() }
        put("lower") { a -> a[0]?.lowercase() }
        put("trim") { a -> a[0]?.trim() }
        put("strip") { a -> a[0]?.trim { it in (a.getOrNull(1) ?: " ") } }          // strip(s, chars)
        put("replace") { a -> a[0]?.replace(a[1] ?: "", a[2] ?: "") }
        put("regex_replace") { a -> a[0]?.replace(Regex(a[1] ?: ""), a[2] ?: "") }
        put("regex_extract") { a -> a[1]?.let { Regex(it).find(a[0] ?: "")?.value } }  // regex_extract(s, pattern)
        put("substring") { a -> slice(a[0], a.getOrNull(1), a.getOrNull(2)) }
        put("split") { a -> a[0]?.split(a[1] ?: ",")?.getOrNull((a.getOrNull(2) ?: "0").toIntOrNull() ?: 0)?.trim() }
        put("concat") { a -> a.joinToString("") { it ?: "" } }
        put("digits") { a -> a[0]?.filter { it.isDigit() } }
        put("length") { a -> (a[0]?.length ?: 0).toString() }
        put("default") { a -> a[0]?.takeIf { it.isNotBlank() } ?: a.getOrNull(1) }
        put("coalesce") { a -> a.firstOrNull { !it.isNullOrBlank() } }
        // numbers
        put("to_number") { a -> num(a[0])?.let { fmt(it) } }
        put("round") { a -> num(a[0])?.let { n -> val p = (a.getOrNull(1) ?: "0").toIntOrNull() ?: 0; fmt(Math.round(n * pow10(p)) / pow10(p)) } }
        put("add") { a -> binNum(a) { x, y -> x + y } }
        put("sub") { a -> binNum(a) { x, y -> x - y } }
        put("mul") { a -> binNum(a) { x, y -> x * y } }
        put("div") { a -> binNum(a) { x, y -> if (y == 0.0) null else x / y } }
        // logic / comparison  (return "true"/"false")
        put("eq") { a -> bool(a[0]?.trim() == a[1]?.trim()) }
        put("gt") { a -> cmp(a) { c -> c > 0 } }
        put("lt") { a -> cmp(a) { c -> c < 0 } }
        put("gte") { a -> cmp(a) { c -> c >= 0 } }
        put("lte") { a -> cmp(a) { c -> c <= 0 } }
        put("contains") { a -> bool((a[0] ?: "").contains(a[1] ?: "", ignoreCase = true)) }
        put("not") { a -> bool(!truthy(a[0])) }
        put("and") { a -> bool(a.all { truthy(it) }) }
        put("or") { a -> bool(a.any { truthy(it) }) }
        put("if") { a -> if (truthy(a[0])) a.getOrNull(1) else a.getOrNull(2) }
    }

    private fun slice(s: String?, from: String?, to: String?): String? {
        if (s == null) return null
        val a = (from ?: "0").toIntOrNull()?.coerceIn(0, s.length) ?: 0
        val b = (to ?: s.length.toString()).toIntOrNull()?.coerceIn(a, s.length) ?: s.length
        return s.substring(a, b)
    }
    private fun num(s: String?) = s?.replace(Regex("[,\\s$€£¥₹]"), "")?.toDoubleOrNull()
    private fun fmt(d: Double) = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
    private fun pow10(p: Int) = Math.pow(10.0, p.toDouble())
    private fun binNum(a: List<String?>, op: (Double, Double) -> Double?) = num(a[0])?.let { x -> num(a[1])?.let { y -> op(x, y)?.let(::fmt) } }
    private fun cmp(a: List<String?>, ok: (Int) -> Boolean): String {
        val x = num(a[0]); val y = num(a[1])
        val c = if (x != null && y != null) x.compareTo(y) else (a[0] ?: "").compareTo(a[1] ?: "")
        return bool(ok(c))
    }
    private fun bool(b: Boolean) = if (b) "true" else "false"
    private fun truthy(s: String?) = s != null && s.isNotBlank() && s != "false" && s != "0"

    // ---- parser (recursive descent) ----
    private inner class Parser(val s: String) {
        var i = 0
        fun parseExpr(): Expr {
            skip()
            val start = i
            if (i < s.length && s[i] == '"') return literal(readString())
            if (i < s.length && (s[i].isDigit() || s[i] == '-' && i + 1 < s.length && s[i + 1].isDigit())) return literal(readNumber())
            val id = readIdent()
            require(id.isNotEmpty()) { "expected expression at $start" }
            skip()
            if (i < s.length && s[i] == '(') {          // function call
                i++
                val args = mutableListOf<Expr>()
                skip()
                if (i < s.length && s[i] != ')') {
                    args.add(parseExpr()); skip()
                    while (i < s.length && s[i] == ',') { i++; args.add(parseExpr()); skip() }
                }
                require(i < s.length && s[i] == ')') { "expected ')' at $i" }
                i++
                val fn = funcs[id] ?: throw IllegalArgumentException("unknown function '$id'")
                return Expr { row -> fn(args.map { it.eval(row) }) }
            }
            return Expr { row -> row[id] }             // bare identifier -> field reference
        }
        fun expectEnd() { skip(); require(i >= s.length) { "unexpected '${s.substring(i)}'" } }
        private fun literal(v: String) = Expr { v }
        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun readIdent(): String { val a = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++; return s.substring(a, i) }
        private fun readNumber(): String { val a = i; if (s[i] == '-') i++; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++; return s.substring(a, i) }
        private fun readString(): String {
            i++; val sb = StringBuilder()
            while (i < s.length && s[i] != '"') { if (s[i] == '\\' && i + 1 < s.length) i++; sb.append(s[i]); i++ }
            require(i < s.length) { "unterminated string" }; i++
            return sb.toString()
        }
    }
}
