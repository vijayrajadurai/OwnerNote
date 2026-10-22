package com.shopai.app.books.integration

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * The text of a PDF with where it sits on the page — enough to read a report another ledger app exported (a "Customer List
 * Report", owner, 19 Oct 2026). Plain Kotlin, nothing downloaded: the file's own objects, its Flate streams (java.util.zip), its
 * fonts' ToUnicode maps, and the page's drawing operators (cm, q/Q, BT, Tf, Td, Tm, Tj, TJ, form XObjects).
 *
 * Reads PDFs that carry text (made by a browser or an app); a scanned page has none and gives no lines.
 */
object PdfText {

    /** One run of text on a page: where it starts (x from the left, y from the top) and its size. */
    data class Run(val page: Int, val x: Float, val y: Float, val size: Float, val text: String, val endX: Float = x)

    /** A line of a page: its runs left to right, split where a gap wider than a word space is (a table's columns). */
    data class Line(val page: Int, val y: Float, val cells: List<Run>) {
        val text: String get() = cells.joinToString("  ") { it.text }
    }

    // ------------------------------------------------------------------ objects

    private class Obj(val dict: String, val stream: ByteArray?)

    private fun latin1(b: ByteArray, from: Int = 0, to: Int = b.size) = String(b, from, to - from, Charsets.ISO_8859_1)

    private fun objects(pdf: ByteArray): Map<Int, Obj> {
        val text = latin1(pdf)
        val out = HashMap<Int, Obj>()
        val head = Regex("""(\d+)\s+0\s+obj\b""")
        var m = head.find(text)
        while (m != null) {
            val num = m.groupValues[1].toInt()
            val start = m.range.last + 1
            val end = text.indexOf("endobj", start).takeIf { it >= 0 } ?: break
            val body = text.substring(start, end)
            val s = body.indexOf("stream")
            if (s >= 0 && body.lastIndexOf("endstream") > s) {
                var dataStart = start + s + "stream".length
                if (text[dataStart] == '\r') dataStart++
                if (text[dataStart] == '\n') dataStart++
                val dict = body.substring(0, s)
                val len = Regex("""/Length\s+(\d+)(?!\s+\d+\s+R)""").find(dict)?.groupValues?.get(1)?.toIntOrNull()
                val dataEnd = if (len != null && dataStart + len <= pdf.size) dataStart + len else text.lastIndexOf("endstream", end).let { e ->
                    var x = e; if (x > 0 && text[x - 1] == '\n') x--; if (x > 0 && text[x - 1] == '\r') x--; x
                }
                out[num] = Obj(dict, pdf.copyOfRange(dataStart, maxOf(dataStart, dataEnd)))
            } else out[num] = Obj(body, null)
            m = head.find(text, end)
        }
        // PDF 1.5+ files (Word's "minimum size", iText, many billing apps) keep their small objects — pages, fonts — packed in
        // compressed object streams: "num offset" pairs, then the objects from /First.
        for (o in out.values.filter { Regex("""/Type\s*/ObjStm\b""").containsMatchIn(it.dict) }) {
            val bytes = data(o) ?: continue
            val first = Regex("""/First\s+(\d+)""").find(o.dict)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= bytes.size } ?: continue
            val pairs = Regex("""\d+""").findAll(latin1(bytes, 0, first)).map { it.value.toInt() }.toList().chunked(2).filter { it.size == 2 }
            pairs.forEachIndexed { k, (num, off) ->
                val from = first + off
                val to = pairs.getOrNull(k + 1)?.let { first + it[1] } ?: bytes.size
                if (from in 0..to && to <= bytes.size && num !in out) out[num] = Obj(latin1(bytes, from, to).trim(), null)
            }
        }
        return out
    }

    /** A stream's bytes, its filters undone in order (Flate, ASCII85 — ReportLab's "/ASCII85Decode /FlateDecode" —, ASCIIHex). */
    private fun data(o: Obj): ByteArray? {
        var bytes = o.stream ?: return null
        val filters = Regex("""/Filter\s*(\[[^\]]*]|/\w+)""").find(o.dict)?.groupValues?.get(1)?.let { f -> Regex("""/(\w+)""").findAll(f).map { it.groupValues[1] }.toList() }.orEmpty()
        for (f in filters) bytes = when (f) {
            "FlateDecode", "Fl" -> inflate(bytes)
            "ASCII85Decode", "A85" -> ascii85(bytes)
            "ASCIIHexDecode", "AHx" -> asciiHex(bytes)
            else -> null
        } ?: return null
        return bytes
    }

    private fun inflate(raw: ByteArray): ByteArray? = runCatching {
        val inf = Inflater(); inf.setInput(raw)
        val buf = ByteArray(8192); val bos = ByteArrayOutputStream()
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; bos.write(buf, 0, n) }
        inf.end(); bos.toByteArray()
    }.getOrNull()

    private fun ascii85(raw: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        val group = IntArray(5); var n = 0
        var i = if (raw.size >= 2 && raw[0] == '<'.code.toByte() && raw[1] == '~'.code.toByte()) 2 else 0
        while (i < raw.size) {
            val c = raw[i++].toInt() and 0xFF
            when {
                c == '~'.code -> break
                c == 'z'.code && n == 0 -> repeat(4) { out.write(0) }
                c in '!'.code..'u'.code -> {
                    group[n++] = c - '!'.code
                    if (n == 5) {
                        val v = group.fold(0L) { acc, d -> acc * 85 + d }
                        for (k in 3 downTo 0) out.write((v shr (8 * k)).toInt() and 0xFF)
                        n = 0
                    }
                }
                c <= 32 -> {}
                else -> return null
            }
        }
        // A last group of 2–4 digits: padded with 'u', its first n − 1 bytes kept.
        if (n == 1) return null
        if (n > 1) {
            for (k in n until 5) group[k] = 84
            val v = group.fold(0L) { acc, d -> acc * 85 + d }
            for (k in 0 until n - 1) out.write((v shr (8 * (3 - k))).toInt() and 0xFF)
        }
        return out.toByteArray()
    }

    private fun asciiHex(raw: ByteArray): ByteArray? {
        val h = StringBuilder()
        for (b in raw) { val ch = (b.toInt() and 0xFF).toChar(); if (ch == '>') break; if (ch.isLetterOrDigit()) h.append(ch) }
        if (h.length % 2 == 1) h.append('0')
        return runCatching { ByteArray(h.length / 2) { k -> h.substring(k * 2, k * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    private val ref = Regex("""(\d+)\s+0\s+R""")

    /** The dictionary after [key] (inline `<< … >>` or a reference to one). */
    private fun dictAt(dict: String, key: String, objs: Map<Int, Obj>): String? {
        val i = Regex("""/$key\s*""").find(dict) ?: return null
        val rest = dict.substring(i.range.last + 1)
        if (rest.startsWith("<<")) return balanced(rest)
        return ref.find(rest)?.takeIf { it.range.first == 0 }?.let { objs[it.groupValues[1].toInt()]?.dict }
    }

    private fun balanced(s: String): String {
        var depth = 0; var i = 0
        while (i < s.length - 1) {
            if (s[i] == '<' && s[i + 1] == '<') { depth++; i += 2; continue }
            if (s[i] == '>' && s[i + 1] == '>') { depth--; i += 2; if (depth == 0) return s.substring(0, i); continue }
            i++
        }
        return s
    }

    /** Name → object number in a resource dictionary (`/F5 5 0 R`). */
    private fun named(dict: String?): Map<String, Int> =
        dict?.let { Regex("""/([^\s/<>\[\]()]+)\s+(\d+)\s+0\s+R""").findAll(it).associate { m -> m.groupValues[1] to m.groupValues[2].toInt() } }.orEmpty()

    // ------------------------------------------------------------------ fonts

    private class Font(val twoByte: Boolean, val map: Map<Int, String>, val widths: Map<Int, Float>, val defaultWidth: Float) {
        fun codes(bytes: ByteArray): List<Int> =
            if (twoByte) (0 until bytes.size / 2).map { k -> (bytes[2 * k].toInt() and 0xFF shl 8) or (bytes[2 * k + 1].toInt() and 0xFF) }
            else bytes.map { it.toInt() and 0xFF }
        fun text(code: Int): String = map[code] ?: if (twoByte) "" else code.toChar().toString()
        fun width(code: Int): Float = widths[code] ?: defaultWidth
    }

    /** An array after [key], inline or by reference, as its inner text. */
    private fun arrayAt(dict: String, key: String, objs: Map<Int, Obj>): String? {
        val i = Regex("""/$key\s*""").find(dict) ?: return null
        val rest = dict.substring(i.range.last + 1)
        val inner = if (rest.startsWith("[")) rest else ref.find(rest)?.takeIf { it.range.first == 0 }?.let { objs[it.groupValues[1].toInt()]?.dict?.trim() } ?: return null
        var depth = 0
        for ((k, c) in inner.withIndex()) { if (c == '[') depth++; if (c == ']') { depth--; if (depth == 0) return inner.substring(1, k) } }
        return null
    }

    private fun font(num: Int, objs: Map<Int, Obj>): Font {
        val d = objs[num]?.dict.orEmpty()
        val twoByte = d.contains("/Type0") || d.contains("/Identity-H")
        val map = HashMap<Int, String>()
        // Glyph widths (thousandths of the size): a CID font's /W and /DW, a simple font's /FirstChar + /Widths.
        val widths = HashMap<Int, Float>()
        var dw = if (twoByte) 1000f else 500f
        if (twoByte) {
            val desc = arrayAt(d, "DescendantFonts", objs)?.let { ref.find(it) }?.let { objs[it.groupValues[1].toInt()]?.dict }.orEmpty()
            Regex("""/DW\s+([\d.]+)""").find(desc)?.groupValues?.get(1)?.toFloatOrNull()?.let { dw = it }
            arrayAt(desc, "W", objs)?.let { w ->
                val toks = Regex("""\[|]|[\d.]+""").findAll(w).map { it.value }.toList()
                var k = 0
                while (k < toks.size) {
                    val first = toks[k].toFloatOrNull()?.toInt() ?: run { k++; null } ?: continue
                    if (toks.getOrNull(k + 1) == "[") {
                        var j = k + 2; var c = first
                        while (j < toks.size && toks[j] != "]") { toks[j].toFloatOrNull()?.let { widths[c++] = it }; j++ }
                        k = j + 1
                    } else {
                        val last = toks.getOrNull(k + 1)?.toFloatOrNull()?.toInt(); val wv = toks.getOrNull(k + 2)?.toFloatOrNull()
                        if (last != null && wv != null) for (c in first..minOf(last, first + 65535)) widths[c] = wv
                        k += 3
                    }
                }
            }
        } else {
            val firstChar = Regex("""/FirstChar\s+(\d+)""").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            arrayAt(d, "Widths", objs)?.let { w -> Regex("""[\d.]+""").findAll(w).forEachIndexed { k, m -> widths[firstChar + k] = m.value.toFloat() } }
        }
        Regex("""/ToUnicode\s+(\d+)\s+0\s+R""").find(d)?.groupValues?.get(1)?.toInt()?.let { objs[it] }?.let(::data)?.let { cmap ->
            val t = latin1(cmap)
            fun hex(s: String) = s.trim().toInt(16)
            fun uni(h: String): String = h.trim().chunked(4).mapNotNull { it.toIntOrNull(16) }.let { cps ->
                val sb = StringBuilder(); var i = 0
                while (i < cps.size) { val c = cps[i]; if (c in 0xD800..0xDBFF && i + 1 < cps.size) { sb.appendCodePoint(0x10000 + ((c - 0xD800) shl 10) + (cps[i + 1] - 0xDC00)); i += 2 } else { sb.append(c.toChar()); i++ } }
                sb.toString()
            }
            Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL).findAll(t).forEach { b ->
                Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""").findAll(b.groupValues[1]).forEach { map[hex(it.groupValues[1])] = uni(it.groupValues[2]) }
            }
            Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL).findAll(t).forEach { b ->
                Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(<[0-9A-Fa-f]+>|\[[^\]]*])""").findAll(b.groupValues[1]).forEach { r ->
                    val lo = hex(r.groupValues[1]); val hi = hex(r.groupValues[2]); val dst = r.groupValues[3]
                    if (dst.startsWith("[")) Regex("""<([0-9A-Fa-f]+)>""").findAll(dst).forEachIndexed { k, h -> map[lo + k] = uni(h.groupValues[1]) }
                    else { val base = dst.trim('<', '>'); val start = base.takeLast(4).toInt(16); val prefix = uni(base.dropLast(4))
                        for (c in lo..minOf(hi, lo + 0xFFFF)) map[c] = prefix + (start + (c - lo)).toChar() }
                }
            }
        }
        return Font(twoByte, map, widths, dw)
    }

    // ------------------------------------------------------------------ the page's operators

    private sealed interface Tok
    private data class Num(val v: Float) : Tok
    private data class Str(val b: ByteArray) : Tok
    private data class Name(val n: String) : Tok
    private data class Arr(val items: List<Tok>) : Tok
    private data class Op(val op: String) : Tok
    /** An inline dictionary (a marked-content span's properties: its /ActualText). */
    private class Dict(val raw: ByteArray) : Tok
    private object Skip : Tok

    private class Lexer(val s: ByteArray) {
        var i = 0
        private fun ws(c: Int) = c == 32 || c == 10 || c == 13 || c == 9 || c == 12 || c == 0
        private fun delim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code || c == ']'.code || c == '/'.code || c == '{'.code || c == '}'.code || c == '%'.code
        fun next(): Tok? {
            while (i < s.size) {
                val c = s[i].toInt() and 0xFF
                when {
                    ws(c) -> i++
                    c == '%'.code -> { while (i < s.size && s[i].toInt() != 10 && s[i].toInt() != 13) i++ }
                    else -> return read(c)
                }
            }
            return null
        }
        private fun read(c: Int): Tok {
            when {
                c == '('.code -> {
                    i++; var depth = 1; val out = ByteArrayOutputStream()
                    while (i < s.size) {
                        val b = s[i].toInt() and 0xFF
                        if (b == '\\'.code && i + 1 < s.size) {
                            val n = s[i + 1].toInt() and 0xFF; i += 2
                            when (n) {
                                'n'.code -> out.write(10); 'r'.code -> out.write(13); 't'.code -> out.write(9); 'b'.code -> out.write(8); 'f'.code -> out.write(12)
                                in '0'.code..'7'.code -> { var v = n - '0'.code; var k = 0
                                    while (k < 2 && i < s.size && (s[i].toInt() and 0xFF) in '0'.code..'7'.code) { v = v * 8 + ((s[i].toInt() and 0xFF) - '0'.code); i++; k++ }
                                    out.write(v and 0xFF) }
                                10, 13 -> {}
                                else -> out.write(n)
                            }
                            continue
                        }
                        if (b == '('.code) depth++
                        if (b == ')'.code) { depth--; if (depth == 0) { i++; break } }
                        out.write(b); i++
                    }
                    return Str(out.toByteArray())
                }
                c == '<'.code && i + 1 < s.size && s[i + 1].toInt() == '<'.code -> {
                    // a dictionary (marked content properties): kept whole, as it is
                    val st = i
                    var depth = 0
                    while (i < s.size - 1) {
                        if (s[i].toInt() == '<'.code && s[i + 1].toInt() == '<'.code) { depth++; i += 2; continue }
                        if (s[i].toInt() == '>'.code && s[i + 1].toInt() == '>'.code) { depth--; i += 2; if (depth == 0) break; continue }
                        i++
                    }
                    return Dict(s.copyOfRange(st, minOf(i, s.size)))
                }
                c == '<'.code -> {
                    i++; val sb = StringBuilder()
                    while (i < s.size && s[i].toInt() != '>'.code) { val ch = (s[i].toInt() and 0xFF).toChar(); if (ch.isLetterOrDigit()) sb.append(ch); i++ }
                    i++
                    val h = if (sb.length % 2 == 1) sb.append('0').toString() else sb.toString()
                    return Str(ByteArray(h.length / 2) { k -> h.substring(k * 2, k * 2 + 2).toInt(16).toByte() })
                }
                c == '['.code -> {
                    i++; val items = mutableListOf<Tok>()
                    while (true) {
                        while (i < s.size && ws(s[i].toInt() and 0xFF)) i++
                        if (i >= s.size) break
                        if (s[i].toInt() == ']'.code) { i++; break }
                        items += next() ?: break
                    }
                    return Arr(items)
                }
                c == '/'.code -> {
                    i++; val st = i
                    while (i < s.size && !ws(s[i].toInt() and 0xFF) && !delim(s[i].toInt() and 0xFF)) i++
                    return Name(latin1(s, st, i))
                }
                c == ']'.code || c == '>'.code || c == ')'.code || c == '{'.code || c == '}'.code -> { i++; return Skip }
                else -> {
                    val st = i
                    while (i < s.size && !ws(s[i].toInt() and 0xFF) && !delim(s[i].toInt() and 0xFF)) i++
                    if (i == st) { i++; return Skip }
                    val word = latin1(s, st, i)
                    return word.toFloatOrNull()?.let { Num(it) } ?: Op(word)
                }
            }
        }
    }

    /** A marked-content span's /ActualText ("<FEFF0BAA0BCD>" = "ப்"), or null when it has none. */
    private fun actualText(dict: ByteArray): String? {
        val at = latin1(dict).indexOf("/ActualText").takeIf { it >= 0 } ?: return null
        val str = Lexer(dict.copyOfRange(at + "/ActualText".length, dict.size)).next() as? Str ?: return null
        val b = str.b
        return if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xFF) == 0xFF) String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else latin1(b)
    }

    /** a b c d e f — a PDF matrix. */
    private class M(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
        fun times(o: M) = M(a * o.a + b * o.c, a * o.b + b * o.d, c * o.a + d * o.c, c * o.b + d * o.d, e * o.a + f * o.c + o.e, e * o.b + f * o.d + o.f)
        companion object { val I = M(1f, 0f, 0f, 1f, 0f, 0f) }
    }

    private class State(var ctm: M)

    private fun run(content: ByteArray, resources: String?, objs: Map<Int, Obj>, page: Int, start: M, out: MutableList<Run>, depth: Int = 0) {
        if (depth > 4) return
        val fonts = named(resources?.let { dictAt(it, "Font", objs) }).mapValues { (_, n) -> font(n, objs) }
        val xobjects = named(resources?.let { dictAt(it, "XObject", objs) })
        val stack = ArrayDeque<M>()
        var ctm = start
        var tm = M.I; var tlm = M.I
        var font: Font? = null; var size = 1f; var leading = 0f
        var charSpace = 0f; var wordSpace = 0f; var hScale = 1f
        val args = mutableListOf<Tok>()
        // Marked-content spans open now; a span with /ActualText (LibreOffice / Word's Tamil and other joined letters: "ப்" drawn as
        // glyphs the font maps to "ப") gives its words in place of its glyphs' — one run, from its first glyph to its last.
        val marks = ArrayDeque<Boolean>()
        var actual: String? = null
        var actualDepth = 0
        var actualRun: Run? = null
        fun emit(bytes: ByteArray) {
            val f = font ?: return
            val codes = f.codes(bytes)
            val text = if (actual != null) "\u0000" else codes.joinToString("") { f.text(it) }
            // How far the text moves: each glyph's width, the character / word spacing, the horizontal scale.
            val advance = codes.sumOf { c -> ((f.width(c) / 1000f * size + charSpace + (if (f.text(c) == " ") wordSpace else 0f)) * hScale).toDouble() }.toFloat()
            if (text.isNotEmpty()) {
                val m = M(size, 0f, 0f, size, 0f, 0f).times(tm).times(ctm)
                val p = tm.times(ctm)
                val end = M(1f, 0f, 0f, 1f, advance, 0f).times(tm).times(ctm)
                val h = kotlin.math.sqrt(m.c * m.c + m.d * m.d)
                if (actual != null) actualRun = actualRun?.copy(endX = end.e) ?: Run(page, p.e, p.f, h, actual!!, end.e)
                else out += Run(page, p.e, p.f, h, text, end.e)
            }
            tm = M(1f, 0f, 0f, 1f, advance, 0f).times(tm)
        }
        val lexer = Lexer(content)
        while (true) {
            val t = lexer.next() ?: break
            if (t !is Op) { if (t != Skip) args += t; continue }
            fun n(k: Int) = (args.getOrNull(args.size - k) as? Num)?.v ?: 0f
            when (t.op) {
                "BMC" -> marks.addLast(false)
                "BDC" -> {
                    val said = if (actual == null) (args.lastOrNull() as? Dict)?.let { actualText(it.raw) } else null
                    marks.addLast(said != null)
                    if (said != null) { actual = said; actualDepth = marks.size; actualRun = null }
                }
                "EMC" -> {
                    if (actual != null && marks.size == actualDepth) { actualRun?.let { out += it }; actual = null; actualRun = null }
                    marks.removeLastOrNull()
                }
                "q" -> stack.addLast(ctm)
                "Q" -> ctm = stack.removeLastOrNull() ?: ctm
                "cm" -> if (args.size >= 6) ctm = M(n(6), n(5), n(4), n(3), n(2), n(1)).times(ctm)
                "BT" -> { tm = M.I; tlm = M.I }
                "Tf" -> { size = n(1); (args.getOrNull(args.size - 2) as? Name)?.let { font = fonts[it.n] } }
                "TL" -> leading = n(1)
                "Tc" -> charSpace = n(1)
                "Tw" -> wordSpace = n(1)
                "Tz" -> hScale = n(1) / 100f
                "Td" -> { tlm = M(1f, 0f, 0f, 1f, n(2), n(1)).times(tlm); tm = tlm }
                "TD" -> { leading = -n(1); tlm = M(1f, 0f, 0f, 1f, n(2), n(1)).times(tlm); tm = tlm }
                "Tm" -> { tlm = M(n(6), n(5), n(4), n(3), n(2), n(1)); tm = tlm }
                "T*" -> { tlm = M(1f, 0f, 0f, 1f, 0f, -leading).times(tlm); tm = tlm }
                "Tj" -> (args.lastOrNull() as? Str)?.let { emit(it.b) }
                "'", "\"" -> { tlm = M(1f, 0f, 0f, 1f, 0f, -leading).times(tlm); tm = tlm; (args.lastOrNull() as? Str)?.let { emit(it.b) } }
                "TJ" -> (args.lastOrNull() as? Arr)?.items?.forEach { item ->
                    when (item) {
                        is Str -> emit(item.b)
                        is Num -> tm = M(1f, 0f, 0f, 1f, -item.v / 1000f * size * hScale, 0f).times(tm)
                        else -> {}
                    }
                }
                "Do" -> (args.lastOrNull() as? Name)?.let { xobjects[it.n] }?.let { num ->
                    val o = objs[num] ?: return@let
                    if (!o.dict.contains("/Form")) return@let
                    val mx = Regex("""/Matrix\s*\[\s*([-\d.\s]+)]""").find(o.dict)?.groupValues?.get(1)?.trim()?.split(Regex("""\s+"""))?.mapNotNull { it.toFloatOrNull() }
                    val fm = if (mx != null && mx.size == 6) M(mx[0], mx[1], mx[2], mx[3], mx[4], mx[5]) else M.I
                    data(o)?.let { run(it, dictAt(o.dict, "Resources", objs) ?: resources, objs, page, fm.times(ctm), out, depth + 1) }
                }
            }
            args.clear()
        }
    }

    // ------------------------------------------------------------------ pages → lines

    /** Every run of text, page by page (pages in the order the file lists them). */
    fun runs(pdf: ByteArray): List<Run> {
        val objs = objects(pdf)
        val pageNums = pageOrder(objs)
        val out = mutableListOf<Run>()
        pageNums.forEachIndexed { idx, num ->
            val page = objs[num] ?: return@forEachIndexed
            val height = Regex("""/MediaBox\s*\[\s*[-\d.]+\s+[-\d.]+\s+[-\d.]+\s+([-\d.]+)\s*]""").find(page.dict)?.groupValues?.get(1)?.toFloatOrNull() ?: 842f
            val resources = dictAt(page.dict, "Resources", objs)
            val contents = Regex("""/Contents\s*(\[[^\]]*]|\d+\s+0\s+R)""").find(page.dict)?.groupValues?.get(1).orEmpty()
            val runs = mutableListOf<Run>()
            ref.findAll(contents).forEach { r -> objs[r.groupValues[1].toInt()]?.let(::data)?.let { run(it, resources, objs, idx + 1, M.I, runs) } }
            // y from the top of the page
            out += runs.map { it.copy(y = height - it.y) }
        }
        return out
    }

    private fun pageOrder(objs: Map<Int, Obj>): List<Int> {
        val root = objs.values.firstOrNull { Regex("""/Type\s*/Pages\b""").containsMatchIn(it.dict) && !Regex("""/Parent\b""").containsMatchIn(it.dict) }
        fun kids(o: Obj): List<Int> = Regex("""/Kids\s*\[([^\]]*)]""").find(o.dict)?.groupValues?.get(1)?.let { ref.findAll(it).map { m -> m.groupValues[1].toInt() }.toList() }.orEmpty()
            .flatMap { k -> val ko = objs[k] ?: return@flatMap emptyList(); if (Regex("""/Type\s*/Pages\b""").containsMatchIn(ko.dict)) kids(ko) else listOf(k) }
        return root?.let(::kids)?.takeIf { it.isNotEmpty() }
            ?: objs.filter { (_, o) -> Regex("""/Type\s*/Page\b(?!s)""").containsMatchIn(o.dict) }.keys.sorted()
    }

    /**
     * The runs joined into lines, top to bottom: glyphs on one baseline become words, and a gap wider than a word space
     * (about four-fifths of the text's size) starts a new cell (a table's next column).
     */
    fun lines(pdf: ByteArray): List<Line> = linesOf(runs(pdf))

    fun linesOf(runs: List<Run>): List<Line> {
        val out = mutableListOf<Line>()
        for ((page, onPage) in runs.groupBy { it.page }.toSortedMap()) {
            val sorted = onPage.filter { it.text.isNotBlank() || it.text == " " }.sortedBy { it.y }
            val rows = mutableListOf<MutableList<Run>>()
            for (r in sorted) {
                val row = rows.lastOrNull()?.takeIf { kotlin.math.abs(it.first().y - r.y) <= maxOf(2f, r.size * 0.3f) }
                if (row != null) row += r else rows += mutableListOf(r)
            }
            for (row in rows) {
                val glyphs = row.sortedBy { it.x }
                val cells = mutableListOf<Run>()
                var cur: Run? = null
                for (g in glyphs) {
                    val c = cur
                    // (where the text before ended: its own end, or — a run whose width the file didn't give — a little after it starts)
                    val prevEnd = c?.let { if (it.endX > it.x) it.endX else it.x + it.size * 0.55f * it.text.length }
                    val gap = if (prevEnd == null) Float.MAX_VALUE else g.x - prevEnd
                    if (c != null && gap <= g.size * 0.8f) {
                        val space = gap > g.size * 0.15f && !c.text.endsWith(" ") && !g.text.startsWith(" ")
                        cur = c.copy(text = c.text + (if (space) " " else "") + g.text, endX = maxOf(c.endX, g.endX))
                    } else {
                        c?.let { cells += it.copy(text = it.text.trim()) }
                        cur = g
                    }
                }
                cur?.let { cells += it.copy(text = it.text.trim()) }
                val kept = cells.filter { it.text.isNotEmpty() }
                if (kept.isNotEmpty()) out += Line(page, row.first().y, kept)
            }
        }
        return out
    }
}
