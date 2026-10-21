package com.shopai.app.util

import java.util.Locale

/**
 * Matches a name written in Tamil with the same name typed / spoken in
 * English letters: "Kumar" ↔ "குமார்", "Ganesh" ↔ "கணேஷ்", "Dinesh" ↔ "தினேஷ்".
 *
 * Both are reduced to the same sound key: their consonants in groups that
 * Tamil writes with one letter (k/g, t/d/th/dh, p/b, s/sh/ch/j, l/zh/ḷ, r/ṟ,
 * n/ṇ/ṉ/ng). Short names (one consonant, "Abi") also compare their vowels.
 * Plain rules — no AI, no network.
 */
object NameSound {

    private val tamilConsonants = mapOf(
        'க' to "k", 'ங' to "n", 'ச' to "s", 'ஞ' to "n", 'ட' to "t", 'ண' to "n", 'த' to "t", 'ந' to "n",
        'ப' to "p", 'ம' to "m", 'ய' to "y", 'ர' to "r", 'ல' to "l", 'வ' to "v", 'ழ' to "l", 'ள' to "l",
        'ற' to "r", 'ன' to "n", 'ஜ' to "s", 'ஷ' to "s", 'ஸ' to "s", 'ஹ' to "",
    )
    private val tamilVowelSigns = mapOf('ா' to 'a', 'ி' to 'i', 'ீ' to 'i', 'ு' to 'u', 'ூ' to 'u', 'ெ' to 'e', 'ே' to 'e', 'ை' to 'e', 'ொ' to 'o', 'ோ' to 'o', 'ௌ' to 'o')
    private val tamilVowels = mapOf('அ' to 'a', 'ஆ' to 'a', 'இ' to 'i', 'ஈ' to 'i', 'உ' to 'u', 'ஊ' to 'u', 'எ' to 'e', 'ஏ' to 'e', 'ஐ' to 'e', 'ஒ' to 'o', 'ஓ' to 'o', 'ஔ' to 'o')
    private const val PULLI = '்'

    private fun isTamil(c: Char) = c in '஀'..'௿'

    /** (consonant key, vowel shape) of a name in either script. */
    private fun sound(name: String): Pair<String, String> {
        val s = name.trim()
        return if (s.any(::isTamil)) tamil(s) else latin(s)
    }

    private fun tamil(raw: String): Pair<String, String> {
        // "ட்ச" is how Tamil writes "ksh" / "x" (லட்சுமி = Lakshmi, லட்சம் = laksham): read as "க்ச" (k + s).
        // "ங்க" is the "ng" English letters write ("லுங்கி" = Lungi, "தங்கம்" = Thangam): one sound, as "ng" is read below.
        val s = raw.replace("ட்ச", "க்ச").replace("க்ஷ", "க்ச").replace("ங்க", "ங")
        val cons = StringBuilder()
        val vowels = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            when {
                // A final "ய்" is a vowel sound ("விஜய்" = Vijay), as a final "y" is in English letters.
                c == 'ய' && next == PULLI && i + 2 >= s.length -> { vowels.append('i'); i++ }
                // English "u" / "ai" written with a gliding "ய" ("டிஸ்ட்ரிபியூட்டர்" = distributor, "பெயிண்ட்" = paint): no consonant of its own.
                c == 'ய' && i > 0 && next != null && (s[i - 1] in "ிீ" && next in "ுூ" || s[i - 1] in "ெே" && next in "ிீ") -> {
                    vowels.append(tamilVowelSigns.getValue(next)); i++
                }
                c in tamilConsonants -> {
                    cons.append(tamilConsonants.getValue(c))
                    when {
                        next == PULLI -> i++
                        next != null && next in tamilVowelSigns -> { vowels.append(tamilVowelSigns.getValue(next)); i++ }
                        else -> vowels.append('a')
                    }
                }
                c in tamilVowels -> vowels.append(tamilVowels.getValue(c))
            }
            i++
        }
        return collapse(cons.toString()) to collapse(vowels.toString())
    }

    private fun latin(raw: String): Pair<String, String> {
        // ("Centre", "Cement", "Lucy": a "c" before e / i / y is an s — "சென்டர்", "சிமெண்ட்")
        var s = raw.lowercase(Locale.ROOT).filter { it in 'a'..'z' }.replace(Regex("""c(?=[eiy])"""), "s")
        s = s.replace("sh", "s").replace("ch", "s").replace("zh", "l").replace("th", "t").replace("dh", "t")
            .replace("ph", "p").replace("bh", "p").replace("kh", "k").replace("gh", "k").replace("ng", "n")
            .replace("ee", "i").replace("oo", "u").replace("aa", "a").replace("ai", "e").replace("au", "o")
        val cons = StringBuilder()
        val vowels = StringBuilder()
        s.forEachIndexed { i, c ->
            when (c) {
                'a', 'e', 'i', 'o', 'u' -> vowels.append(c)
                // A final "y" is a vowel sound ("Aby"); elsewhere a consonant ("Priya").
                'y' -> if (i == s.lastIndex) vowels.append('i') else cons.append('y')
                'g', 'c', 'q' -> cons.append('k')
                'd' -> cons.append('t')
                'b', 'f' -> cons.append('p')
                'w' -> cons.append('v')
                'j', 'z' -> cons.append('s')
                'x' -> cons.append("ks")
                'h' -> Unit
                else -> cons.append(c)
            }
        }
        return collapse(cons.toString()) to collapse(vowels.toString())
    }

    private fun collapse(s: String): String = s.replace(Regex("""(.)\1+"""), "$1")

    /** The sound key with "ங்க" / "nk" / "ng" + vowel all read as n + k — null when the name has none of them. */
    private fun nkSound(name: String): String? {
        val s = name.trim()
        return if (s.any(::isTamil)) {
            if (!s.contains("ங்க")) null else tamil(s.replace("ங்க", "ன்க")).first
        } else {
            val l = s.lowercase(Locale.ROOT)
            if (!Regex("""n[kg]""").containsMatchIn(l)) null else latin(l.replace("ng", "nk")).first
        }
    }

    /** The vowels of a name in order (a / e / i / o / u), as [key] reads its consonants. */
    fun vowels(name: String): String = sound(name).second

    /** The sound key of a name (its consonant skeleton). */
    fun key(name: String): String = sound(name).first

    /** Same name in Tamil and English letters (or two spellings of it). Different scripts only — or an exact match. */
    private fun startsWithVowel(name: String): Boolean {
        val c = name.first()
        return if (isTamil(c)) c in '\u0B85'..'\u0B94' else c.lowercaseChar() in "aeiou"
    }

    fun same(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.equals(y, ignoreCase = true)) return true
        // Two English spellings ("Kumar" / "Kamar") are different people: only across scripts.
        if (x.any(::isTamil) == y.any(::isTamil)) return false
        // "அறிவு" (Arivu) is not "Ravi": one starts with a vowel, the other doesn't.
        if (startsWithVowel(x) != startsWithVowel(y)) return false
        val (kx, vx) = sound(x)
        val (ky, vy) = sound(y)
        // "சங்கர்" = Shankar, "பங்கஜ்" = Pankaj: Tamil writes "nk" as "ங்க" too — its k heard (owner, 16 Oct 2026). Only for that pair.
        val latin = if (x.any(::isTamil)) y else x
        val (lv, tv) = if (x.any(::isTamil)) vy to vx else vx to vy
        if (kx != ky && nkSound(x) != null && nkSound(x) == nkSound(y)) return (kx.length >= 2 || vx == vy) && vowelsAgree(lv, tv, latin)
        if (kx.isEmpty() || kx != ky) return false
        // One consonant ("Abi" / "Appu") is too little to go on: the vowels must agree too.
        return (kx.length >= 2 || vx == vy) && vowelsAgree(lv, tv, latin)
    }

    /**
     * A one-syllable word in English letters against a Tamil name (owner's phone, 21 Oct 2026: a typed "kens" was taken as the
     * books' "கணேஷ்" — the same consonants k-n-s): its vowel must open the Tamil name too ("o" / "u" may be Tamil's "ஆ" — "Shop" /
     * "ஷாப்"), and the Tamil name has at most one more vowel. Longer words (names, loan words like "Horlicks" / "ஹார்லிக்ஸ்") are
     * left to their consonants as before.
     */
    private fun vowelsAgree(latinVowels: String, tamilVowels: String, latinWord: String): Boolean {
        val w = latinWord.trim()
        if (w.contains(' ') || latinVowels.length != 1 || tamilVowels.isEmpty()) return true
        fun eq(l: Char, t: Char) = l == t || l in "ou" && t == 'a'
        val cluster = Regex("""(?i)^[^aeiou][rlyv]""").containsMatchIn(w)
        val t = if (cluster && tamilVowels.length > 1 && tamilVowels[0] in "iu" && !eq(latinVowels[0], tamilVowels[0])) tamilVowels.drop(1) else tamilVowels
        return eq(latinVowels[0], t[0]) && t.length <= 2
    }
}
