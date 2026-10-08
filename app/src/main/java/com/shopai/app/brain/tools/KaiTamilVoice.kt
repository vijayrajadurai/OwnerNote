package com.shopai.app.brain.tools

import java.time.LocalTime

/**
 * How spoken Tamil sounds, for Kai's natural (Sarvam) voice, which reads Tamil script:
 *
 *  - a person's name in Tamil letters, with the spoken dative ("Kumar-ku" → "குமாருக்கு",
 *    "Praba-ku" → "பிரபாக்கு"), never a Latin name glued to a Tamil ending ("Kumar-க்கு");
 *  - clock times and small numbers as people say them ("நாலு மணி", "எட்டரை", "அஞ்சு நிமிஷம்"),
 *    not as digits the voice reads formally ("நான்கு மணி").
 *
 * Plain rules plus a small pronunciation list for common names; no AI, nothing stored.
 */
object KaiTamilVoice {

    /** Common names whose long vowels the spelling doesn't show (pronunciation, not phrases). */
    private val names = mapOf(
        "kumar" to "குமார்", "gopal" to "கோபால்", "prakash" to "பிரகாஷ்", "ramesh" to "ரமேஷ்", "suresh" to "சுரேஷ்",
        "ganesh" to "கணேஷ்", "mahesh" to "மகேஷ்", "dinesh" to "தினேஷ்", "rajesh" to "ராஜேஷ்", "raja" to "ராஜா",
        "raju" to "ராஜு", "ravi" to "ரவி", "praba" to "பிரபா", "prabha" to "பிரபா", "mani" to "மணி", "murugan" to "முருகன்",
        "selvam" to "செல்வம்", "senthil" to "செந்தில்", "karthik" to "கார்த்திக்", "vijay" to "விஜய்", "arun" to "அருண்",
        "bala" to "பாலா", "siva" to "சிவா", "shiva" to "சிவா", "anand" to "ஆனந்த்", "saravanan" to "சரவணன்",
        "kannan" to "கண்ணன்", "lakshmi" to "லட்சுமி", "priya" to "பிரியா", "divya" to "திவ்யா", "kavitha" to "கவிதா",
        "meena" to "மீனா", "latha" to "லதா", "mohan" to "மோகன்", "ruthran" to "ருத்ரன்", "ravi kumar" to "ரவிக்குமார்",
        "babu" to "பாபு", "rani" to "ராணி", "devi" to "தேவி", "ram" to "ராம்", "raman" to "ராமன்", "pandian" to "பாண்டியன்",
        "velu" to "வேலு", "mani kandan" to "மணிகண்டன்", "manikandan" to "மணிகண்டன்", "sathish" to "சதீஷ்", "satish" to "சதீஷ்",
    )

    private val independent = mapOf(
        "a" to "அ", "aa" to "ஆ", "i" to "இ", "ii" to "ஈ", "ee" to "ஈ", "u" to "உ", "uu" to "ஊ", "oo" to "ஊ",
        "e" to "எ", "ai" to "ஐ", "o" to "ஒ", "au" to "ஔ",
    )
    private val signs = mapOf(
        "a" to "", "aa" to "ா", "i" to "ி", "ii" to "ீ", "ee" to "ீ", "u" to "ு", "uu" to "ூ", "oo" to "ூ",
        "e" to "ெ", "ai" to "ை", "o" to "ொ", "au" to "ௌ",
    )
    private val vowelTokens = listOf("aa", "ai", "au", "ee", "ii", "oo", "uu", "a", "i", "u", "e", "o")
    private val consonantTokens = listOf("ksh", "ng", "nj", "ch", "sh", "zh", "th", "dh", "bh", "ph", "kh", "gh",
        "k", "g", "c", "q", "s", "j", "t", "d", "n", "m", "p", "b", "y", "r", "l", "v", "w", "h", "f", "z", "x")

    private sealed interface Tok { val latin: String }
    private data class V(override val latin: String) : Tok
    private data class C(override val latin: String) : Tok

    private fun tokens(w: String): List<Tok>? {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < w.length) {
            val v = vowelTokens.firstOrNull { w.startsWith(it, i) }
            if (v != null) { out += V(v); i += v.length; continue }
            val c = consonantTokens.firstOrNull { w.startsWith(it, i) } ?: return null
            out += C(c)
            i += c.length
        }
        return out
    }

    /** The Tamil letter for consonant [c] at [i] in [t] (initial / after n / before a consonant matter). */
    private fun consonant(c: String, i: Int, t: List<Tok>): String {
        val prev = t.getOrNull(i - 1)?.latin
        val next = t.getOrNull(i + 1)
        return when (c) {
            "ksh" -> "க்ஷ"; "ng" -> "ங"; "nj" -> "ஞ"; "ch" -> "ச"; "sh" -> "ஷ"; "zh" -> "ழ"
            "th", "dh" -> "த"; "bh", "b", "p" -> "ப"; "ph", "f" -> "ஃப"; "kh", "gh", "k", "g", "c", "q" -> "க"
            "s" -> if (next is C) "ஸ" else "ச"
            "j" -> "ஜ"; "m" -> "ம"; "y" -> "ய"; "r" -> "ர"; "l" -> "ல"; "v", "w" -> "வ"; "h" -> "ஹ"; "z" -> "ஸ"; "x" -> "க்ஸ"
            "t" -> if (prev == "n") "த" else "ட"
            "d" -> if (i == 0 || prev == "n") "த" else "ட"
            "n" -> when {
                i == 0 -> "ந"
                next?.latin in setOf("th", "dh", "d", "t") -> "ந"
                else -> "ன"
            }
            else -> c
        }
    }

    /** A Latin (Tanglish / English) word in Tamil letters — for names; null when it can't be read. */
    fun tamil(word: String): String? {
        val w = word.lowercase().trim()
        names[w]?.let { return it }
        if (w.isEmpty() || !w.all { it in 'a'..'z' }) return null
        val t = tokens(w) ?: return null
        val sb = StringBuilder()
        // "Praba", "Krishna": Tamil opens a consonant + r cluster with a vowel ("பிரபா").
        val cluster = t.size > 2 && t[0] is C && t[1] is C && t[1].latin == "r"
        var i = 0
        while (i < t.size) {
            val tok = t[i]
            if (tok is V) {
                val v = vowel(tok.latin, i, t)
                sb.append(longIndependent[v] ?: independent[v] ?: "")
                i++
                continue
            }
            sb.append(consonant(tok.latin, i, t))
            val next = t.getOrNull(i + 1)
            when {
                cluster && i == 0 -> { sb.append("ி"); i++ }
                next is V -> { sb.append(sign(vowel(next.latin, i + 1, t))); i += 2 }
                else -> { sb.append("்"); i++ }
            }
        }
        return sb.toString()
    }

    /** e / o are long in names ("Suresh", "Mohan") unless two consonants follow ("Senthil"); a final a is long ("Praba"). */
    private fun vowel(v: String, i: Int, t: List<Tok>): String {
        val twoConsonants = t.getOrNull(i + 1) is C && t.getOrNull(i + 2) is C
        return when {
            v == "a" && i == t.lastIndex && i > 0 -> "aa"
            v == "e" && !twoConsonants -> "ē"
            v == "o" && !twoConsonants -> "ō"
            else -> v
        }
    }

    private val longSigns = mapOf("ē" to "ே", "ō" to "ோ")
    private val longIndependent = mapOf("ē" to "ஏ", "ō" to "ஓ")
    private fun sign(v: String) = longSigns[v] ?: signs[v] ?: ""

    /** The spoken dative: "குமார்" → "குமாருக்கு", "பிரபா" → "பிரபாக்கு", "ரவி" → "ரவிக்கு". */
    fun dative(stem: String): String = when {
        stem.endsWith("்") -> stem.dropLast(1) + "ுக்கு"
        else -> stem + "க்கு"
    }

    /** The spoken object ending: "பையன்" → "பையனை", "அம்மா" → "அம்மாவை", "ரவி" → "ரவியை". */
    fun accusative(stem: String): String = when {
        stem.endsWith("்") -> stem.dropLast(1) + "ை"
        stem.endsWith("ி") || stem.endsWith("ீ") || stem.endsWith("ை") -> stem + "யை"
        else -> stem + "வை"
    }

    private val small = listOf("", "ஒரு", "ரெண்டு", "மூணு", "நாலு", "அஞ்சு", "ஆறு", "ஏழு", "எட்டு", "ஒம்பது", "பத்து", "பதினொரு", "பன்னெண்டு")
    private val alone = listOf("", "ஒண்ணு", "ரெண்டு", "மூணு", "நாலு", "அஞ்சு", "ஆறு", "ஏழு", "எட்டு", "ஒம்பது", "பத்து", "பதினொண்ணு", "பன்னெண்டு")

    /** "5 நிமிஷம்" said as "அஞ்சு நிமிஷம்" (1–12; anything else stays a number). */
    fun count(n: Long): String = if (n in 1..12) small[n.toInt()] else n.toString()

    /** 4:00 → "நாலு மணி", 8:30 → "எட்டரை மணி", 8:15 → "எட்டே கால் மணி", 8:45 → "எட்டே முக்கால் மணி"; other minutes stay digits. */
    fun clock(t: LocalTime): String {
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        val stem = alone[h].dropLast(1) // every word ends in the vowel sign ு: "எட்டு" → "எட்ட"
        return when (t.minute) {
            0 -> "${small[h]} மணி"
            30 -> "${stem}ரை மணி"
            15 -> "${stem}ே கால் மணி"
            45 -> "${stem}ே முக்கால் மணி"
            else -> "$h:${t.minute.toString().padStart(2, '0')}"
        }
    }
}
