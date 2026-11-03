package rip.failed.daisy.core

/**
 * Just enough JSON to store and reload what this app produces.
 *
 * Hand-rolled for the same reason as the XML scan: no dependency, and it stays
 * runnable in build.sh with nothing but kotlinc. The store is the only writer,
 * so the grammar it must read is exactly the grammar it writes -- this is not a
 * general-purpose parser and should not be pointed at foreign JSON.
 *
 * Two halves:
 *  - [write] builds a string, used by [Store].
 *  - [Value] is a small tree with [parse], used to read it back.
 *
 * Numbers are read as Double and converted by the caller via [Value.long], which
 * is safe here because epoch millis (~1.7e12) are well inside a Double's exact
 * integer range (2^53).
 */
object Json {

    // --- writing -------------------------------------------------------------

    /**
     * Escapes a string for inclusion in JSON.
     *
     * Control characters must be escaped or the output is not valid JSON. The
     * unicode escapes use \\uXXXX form; everything printable passes through,
     * including non-ASCII, since the file is written as UTF-8.
     */
    fun quote(s: String): String {
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    /** Builds an object body from already-encoded values, skipping nulls. */
    class Obj {
        private val parts = ArrayList<String>()
        private val sb = StringBuilder()

        fun put(key: String, s: String?) = apply {
            if (s == null) return@apply
            parts.add(quote(key) + ":" + quote(s))
        }

        fun put(key: String, n: Long?) = apply {
            if (n == null) return@apply
            parts.add(quote(key) + ":" + n)
        }

        fun put(key: String, b: Boolean) = apply {
            parts.add(quote(key) + ":" + b)
        }

        /** Inserts a value already rendered as JSON. Caller owns its validity. */
        fun raw(key: String, json: String) = apply {
            parts.add(quote(key) + ":" + json)
        }

        fun arr(key: String, values: List<String>) = apply {
            sb.setLength(0)
            sb.append('[')
            values.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                sb.append(quote(v))
            }
            sb.append(']')
            parts.add(quote(key) + ":" + sb)
        }

        /** Objects rendered by nested [Obj]s. */
        fun objs(key: String, values: List<Obj>) = apply {
            sb.setLength(0)
            sb.append('[')
            values.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                sb.append(v.render())
            }
            sb.append(']')
            parts.add(quote(key) + ":" + sb)
        }

        fun render(): String {
            val o = StringBuilder()
            o.append('{')
            parts.forEachIndexed { i, p ->
                if (i > 0) o.append(',')
                o.append(p)
            }
            o.append('}')
            return o.toString()
        }
    }

    // --- reading -------------------------------------------------------------

    sealed interface Value {
        data class Str(val v: String) : Value
        data class Num(val v: Double) : Value
        data class Bool(val v: Boolean) : Value
        data object Null : Value
        data class Arr(val v: List<Value>) : Value
        data class Obj(val v: Map<String, Value>) : Value

        /** Convenience accessors; all return null rather than throwing. */
        fun str(): String? = (this as? Str)?.v
        fun long(): Long? = (this as? Num)?.v?.toLong()
        fun bool(): Boolean? = (this as? Bool)?.v
        fun obj(): Map<String, Value>? = (this as? Obj)?.v
        fun arr(): List<Value>? = (this as? Arr)?.v
        fun get(key: String): Value? = obj()?.get(key)
    }

    /** Parses [src]. Returns null on malformed input rather than throwing. */
    fun parse(src: String): Value? {
        val p = Reader(src)
        return try {
            val v = p.value()
            p.ws()
            if (p.i < src.length) null else v
        } catch (_: Exception) {
            null
        }
    }

    private class Reader(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun expect(c: Char) {
            ws()
            if (i >= s.length || s[i] != c) throw IllegalStateException("expected $c at $i")
            i++
        }

        fun value(): Value {
            ws()
            if (i >= s.length) throw IllegalStateException("eof")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Value.Str(string())
                't' -> { lit("true"); Value.Bool(true) }
                'f' -> { lit("false"); Value.Bool(false) }
                'n' -> { lit("null"); Value.Null }
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalStateException("bad $c")
            }
        }

        fun lit(word: String) {
            if (!s.startsWith(word, i)) throw IllegalStateException("bad literal")
            i += word.length
        }

        fun obj(): Value {
            expect('{')
            val m = LinkedHashMap<String, Value>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return Value.Obj(m) }
            while (true) {
                ws()
                val k = string()
                expect(':')
                m[k] = value()
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                expect('}')
                return Value.Obj(m)
            }
        }

        fun arr(): Value {
            expect('[')
            val l = ArrayList<Value>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return Value.Arr(l) }
            while (true) {
                l.add(value())
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                expect(']')
                return Value.Arr(l)
            }
        }

        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalStateException("eof in string")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw IllegalStateException("eof in escape")
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalStateException("short \\u")
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalStateException("bad escape $e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        fun num(): Value {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(start, i)
            return Value.Num(t.toDoubleOrNull() ?: throw IllegalStateException("bad number $t"))
        }
    }
}
