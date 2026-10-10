package com.shopai.app.brain.tools

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * Kai's calculator — exact decimal arithmetic in plain code (never an AI
 * guess). Understands what an owner types or says:
 *
 *   "10 * 3"                      → 30
 *   "25000 la 18% GST evlo?"      → GST ₹4,500, total ₹29,500
 *   "1180 incl 18% GST"           → base ₹1,000, GST ₹180
 *   "1000 la 10% discount"        → discount ₹100, final ₹900
 *   "25000 la 18%"                → ₹4,500
 *   "50 kg × ₹82"                 → ₹4,100
 *   "cost 80 sell 100"            → profit ₹20 (25% on cost)
 *   "2 kg evlo gram"              → 2,000 g
 *
 * Returns null when the text is not a calculation (so "Ramesh ku 5000
 * kuduthen" or "10 minutes kalichu" never become one).
 */
object KaiCalculator {

    enum class Kind { EXPRESSION, GST_ADD, GST_INCLUDED, DISCOUNT, PERCENT_OF, PROFIT, CONVERSION }

    /** One labelled figure of the answer ("GST" → 4500). [money]: show with ₹. */
    data class Figure(val label: Label, val value: BigDecimal, val money: Boolean, val unit: String? = null)

    enum class Label { RESULT, GST, TOTAL, BASE, DISCOUNT, FINAL, PROFIT, LOSS, MARGIN_PERCENT }

    data class Answer(val kind: Kind, val figures: List<Figure>) {
        val main: Figure get() = figures.last()
    }

    private val mc = MathContext(34, RoundingMode.HALF_UP)
    private const val NUM = """(\d[\d,]*(?:\.\d+)?)"""

    fun solve(raw: String): Answer? {
        val text = normalise(raw)
        if (!text.any(Char::isDigit)) return null
        // A written date (10/10/2026) is not a division.
        if (Regex("""\b\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4}\b""").containsMatchIn(text)) return null
        val money = text.contains('₹') || Regex("""\b(rs|rupees?|ruba|rubai|gst|discount|profit|price|rate|amount)\b""").containsMatchIn(text)

        gstAndPercent(text)?.let { return it }
        profit(text)?.let { return it }
        conversion(text)?.let { return it }
        return expression(text, money)
    }

    // ------------------------------------------------------------ GST / discount / %

    private fun gstAndPercent(t: String): Answer? {
        val pct = Regex("""$NUM\s*(?:%|percent|sathaveedham|சதவீதம்)""").find(t) ?: return null
        val rate = num(pct.groupValues[1]) ?: return null
        // The base: the other number in the sentence ("25000 la 18%", "18% of 25000", "18% gst on 25000").
        val others = Regex(NUM).findAll(t).filter { it.range.first != pct.range.first }.mapNotNull { num(it.groupValues[1]) }.toList()
        if (others.size != 1) return null
        val base = others.single()
        val hundred = BigDecimal(100)
        val part = base.multiply(rate, mc).divide(hundred, mc)
        return when {
            has(t, "gst", "tax", "vari", "வரி") && has(t, "incl", "inclusive", "including", "included", "ulla", "serthu", "serth", "with gst") -> {
                val baseValue = base.multiply(hundred, mc).divide(hundred + rate, mc)
                Answer(Kind.GST_INCLUDED, listOf(
                    Figure(Label.BASE, money(baseValue), true),
                    Figure(Label.GST, money(base - money(baseValue)), true),
                ))
            }
            has(t, "gst", "tax", "vari", "வரி") -> Answer(Kind.GST_ADD, listOf(
                Figure(Label.GST, money(part), true),
                Figure(Label.TOTAL, money(base + money(part)), true),
            ))
            has(t, "discount", "disc", "off", "kammi", "kurai", "thallupadi", "தள்ளுபடி") -> Answer(Kind.DISCOUNT, listOf(
                Figure(Label.DISCOUNT, money(part), true),
                Figure(Label.FINAL, money(base - money(part)), true),
            ))
            // "200 + 10%" is an expression (handled there); a plain "x la y%" is a percentage of x.
            Regex("""\d\s*[+\-]\s*\d[\d,.]*\s*%""").containsMatchIn(t) -> null
            else -> Answer(Kind.PERCENT_OF, listOf(Figure(Label.RESULT, clean(part), t.contains('₹') || has(t, "rs", "rupees"))))
        }
    }

    // ------------------------------------------------------------ profit

    private fun profit(t: String): Answer? {
        val cost = Regex("""(?:cost|cp|purchase|vaangunadhu|vanginadhu|vaanginadhu|vangiyadhu|vaangi|vangi|kolmudhal)\D{0,12}?$NUM""").find(t)
            ?: Regex("""$NUM\s*(?:ku|kku)\s*(?:vaangi|vangi|vaanginen|vanginen)""").find(t) ?: return null
        val sell = Regex("""(?:sell|sale|sp|selling|vithadhu|vitradhu|vithaa|vitha|vikka|vithen)\D{0,12}?$NUM""").find(t)
            ?: Regex("""$NUM\s*(?:ku|kku)\s*(?:vithaa|vithen|vitha|vikka)""").find(t) ?: return null
        val c = num(cost.groupValues[1]) ?: return null
        val s = num(sell.groupValues[1]) ?: return null
        if (c.signum() <= 0) return null
        val diff = s - c
        val pct = diff.abs().multiply(BigDecimal(100), mc).divide(c, mc)
        return Answer(Kind.PROFIT, listOf(
            Figure(Label.MARGIN_PERCENT, clean(pct.setScale(2, RoundingMode.HALF_UP)), false, "%"),
            Figure(if (diff.signum() >= 0) Label.PROFIT else Label.LOSS, money(diff.abs()), true),
        ))
    }

    // ------------------------------------------------------------ units

    private data class UnitDef(val names: List<String>, val group: String, val factor: BigDecimal, val shown: String)

    private val units = listOf(
        UnitDef(listOf("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "கிலோ"), "mass", BigDecimal(1000), "kg"),
        UnitDef(listOf("g", "gm", "gms", "gram", "grams", "கிராம்"), "mass", BigDecimal.ONE, "g"),
        UnitDef(listOf("ton", "tons", "tonne", "tonnes"), "mass", BigDecimal(1_000_000), "ton"),
        UnitDef(listOf("l", "ltr", "ltrs", "litre", "litres", "liter", "liters", "லிட்டர்"), "volume", BigDecimal(1000), "L"),
        UnitDef(listOf("ml", "millilitre", "milliliter"), "volume", BigDecimal.ONE, "ml"),
        UnitDef(listOf("dozen", "doz", "dz"), "count", BigDecimal(12), "dozen"),
        UnitDef(listOf("pcs", "pc", "pieces", "piece", "nos", "no"), "count", BigDecimal.ONE, "pcs"),
        UnitDef(listOf("m", "mtr", "meter", "metre", "meters", "metres"), "length", BigDecimal(100), "m"),
        UnitDef(listOf("cm"), "length", BigDecimal.ONE, "cm"),
    )

    private fun unitOf(word: String): UnitDef? = units.firstOrNull { word in it.names }

    /** "2 kg evlo gram", "1500 g in kg", "3 dozen evlo pieces". */
    private fun conversion(t: String): Answer? {
        val m = Regex("""$NUM\s*([a-z஀-௿]+)\b.*?\b(?:in|to|evlo|ethana|enna|how many|=|la)\s+([a-z஀-௿]+)""").find(t) ?: return null
        val from = unitOf(m.groupValues[2]) ?: return null
        val to = unitOf(m.groupValues[3]) ?: return null
        if (from.group != to.group || from === to) return null
        val value = num(m.groupValues[1]) ?: return null
        val result = value.multiply(from.factor, mc).divide(to.factor, mc)
        return Answer(Kind.CONVERSION, listOf(Figure(Label.RESULT, clean(result.setScale(3, RoundingMode.HALF_UP)), false, to.shown)))
    }

    // ------------------------------------------------------------ expressions

    private fun expression(t: String, moneyWords: Boolean): Answer? {
        val unitWords = units.flatMap { it.names }.joinToString("|") { Regex.escape(it) }
        var s = " $t "
        // "50 kg × ₹82", "50 x 82", "10x3": x between numbers (a unit may follow the first).
        s = Regex("""(\d)\s*(?:(?:$unitWords)\s*)?[x×]\s*₹?\s*(?=\d)""").replace(s) { "${it.groupValues[1]} * " }
        s = s.replace('×', '*').replace('÷', '/')
            .replace(Regex("""(?<=\d)\s*(perukkal|perukki|perukka)\s*(?=\d)"""), " * ")
            .replace(Regex("""(?<=\d)\s*(kooda|koodu|koottal|kootal|kootti|serthu|கூட|கூட்டல்)\s*(?=\d)"""), " + ")
            .replace(Regex("""(?<=\d)\s*(kazhithal|kalithal)\s*(?=\d)"""), " - ")
            .replace(Regex("""(?<=\d)\s*(vaguthal|vakuthal)\s*(?=\d)"""), " / ")
            .replace(Regex("""\b(into|times|multiplied by|perukku)\b"""), " * ")
            .replace(Regex("""\b(divided by|divide by)\b"""), " / ")
            .replace(Regex("""\bplus\b"""), " + ")
            .replace(Regex("""\bminus\b"""), " - ")
            .replace('₹', ' ').replace(Regex("""\b(rs\.?|rupees?|ruba|rubai)\b"""), " ")
            .replace(Regex("""(?<=\d)\s*($unitWords)\b"""), " ")
        val expr = Regex("""[(\d][\d\s.,+\-*/%()]*[\d)%]""").findAll(s).map { it.value.trim() }
            .filter { Regex("""[\d)]\s*[+\-*/]\s*[\d(]""").containsMatchIn(it) }
            .maxByOrNull { it.length } ?: return null
        // A phone number ("98765-43210") or a range ("2-3 days") is not maths.
        if (Regex("""^\d{4,}-\d{4,}$""").matches(expr)) return null
        if (Regex("""^\d{1,2}-\d{1,2}$""").matches(expr) && Regex("""\b(days?|naal|naatkal|hours?|mani|weeks?|months?)\b""").containsMatchIn(t)) return null
        val value = runCatching { Parser(expr.replace(",", "")).parse() }.getOrNull() ?: return null
        val money = moneyWords || t.contains('₹')
        return Answer(Kind.EXPRESSION, listOf(Figure(Label.RESULT, if (money) money(value) else clean(value.setScale(6, RoundingMode.HALF_UP)), money)))
    }
    /** + - * / with precedence, parentheses, unary minus, and "a + b%" / "a - b%" as a percentage of a. */
    private class Parser(private val s: String) {
        private var i = 0
        private val mc = MathContext(34, RoundingMode.HALF_UP)

        fun parse(): BigDecimal {
            val v = sum()
            skip()
            require(i == s.length) { "unexpected '${s.substring(i)}'" }
            return v
        }

        private fun skip() { while (i < s.length && s[i] == ' ') i++ }

        private fun sum(): BigDecimal {
            var v = product()
            while (true) {
                skip()
                if (i >= s.length) return v
                val op = s[i]
                if (op != '+' && op != '-') return v
                i++
                val (rhs, percent) = productWithPercent()
                val amount = if (percent) v.multiply(rhs, mc).divide(BigDecimal(100), mc) else rhs
                v = if (op == '+') v + amount else v - amount
            }
        }

        /** The right side of + / −: "10%" there means 10% of the left side. */
        private fun productWithPercent(): Pair<BigDecimal, Boolean> {
            val start = i
            val v = product()
            skip()
            if (i < s.length && s[i] == '%') { i++; return v to true }
            i = i.coerceAtLeast(start)
            return v to false
        }

        private fun product(): BigDecimal {
            var v = unary()
            while (true) {
                skip()
                if (i >= s.length) return v
                when (s[i]) {
                    '*' -> { i++; v = v.multiply(unary(), mc) }
                    '/' -> {
                        i++
                        val d = unary()
                        require(d.signum() != 0) { "division by zero" }
                        v = v.divide(d, mc)
                    }
                    else -> return v
                }
            }
        }

        private fun unary(): BigDecimal {
            skip()
            if (i < s.length && s[i] == '-') { i++; return unary().negate() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return atom()
        }

        private fun atom(): BigDecimal {
            skip()
            require(i < s.length) { "end of input" }
            if (s[i] == '(') {
                i++
                val v = sum()
                skip()
                require(i < s.length && s[i] == ')') { "missing )" }
                i++
                return postfixPercent(v)
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start) { "number expected" }
            return postfixPercent(BigDecimal(s.substring(start, i)))
        }

        /** "18% * 200": a bare percentage inside a product is value / 100 (but "+ 10%" is handled by [sum]). */
        private fun postfixPercent(v: BigDecimal): BigDecimal {
            val save = i
            skip()
            if (i < s.length && s[i] == '%') {
                // Leave "a + b%" for sum(); here only "b% * c" / "b% of" style.
                var j = i + 1
                while (j < s.length && s[j] == ' ') j++
                if (j < s.length && (s[j] == '*' || s[j] == '/')) { i++; return v.divide(BigDecimal(100), mc) }
            }
            i = save
            return v
        }
    }

    // ------------------------------------------------------------ helpers

    private fun normalise(raw: String): String = raw.lowercase(Locale.ROOT)
        .replace("rs.", "rs ").replace("₹ ", "₹")
        .replace(Regex("""[?!]"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun has(t: String, vararg words: String) = words.any { w -> Regex("""(?<![\p{L}])${Regex.escape(w)}(?![\p{L}])""").containsMatchIn(t) }

    private fun num(s: String): BigDecimal? = s.replace(",", "").toBigDecimalOrNull()

    /** Money: exact to the paisa. */
    private fun money(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

    private fun clean(v: BigDecimal): BigDecimal = v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    /** "4,500" / "1,24,500.50" / "30" — Indian grouping, decimals only when there are any. */
    fun format(f: Figure): String {
        val v = clean(f.value)
        val negative = v.signum() < 0
        val plain = v.abs().toPlainString()
        val whole = plain.substringBefore('.')
        val frac = plain.substringAfter('.', "")
        val grouped = if (whole.length <= 3) whole else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        val fracShown = if (f.money && frac.isNotEmpty()) "." + frac.padEnd(2, '0').take(2) else if (frac.isNotEmpty()) ".$frac" else ""
        val body = grouped + fracShown
        return (if (negative) "-" else "") + (if (f.money) "₹" else "") + body + (f.unit?.let { if (it == "%") "%" else " $it" } ?: "")
    }
}
