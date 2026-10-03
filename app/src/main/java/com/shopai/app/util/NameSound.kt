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

    private fun tamil(s: String): Pair<String, String> {
        val cons = StringBuilder()
        val vowels = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            when {
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
        var s = raw.lowercase(Locale.ROOT).filter { it in 'a'..'z' }
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

    /** The sound key of a name (its consonant skeleton). */
    fun key(name: String): String = sound(name).first

    /** Same name in Tamil and English letters (or two spellings of it). Different scripts only — or an exact match. */
    fun same(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.equals(y, ignoreCase = true)) return true
        // Two English spellings ("Kumar" / "Kamar") are different people: only across scripts.
        if (x.any(::isTamil) == y.any(::isTamil)) return false
        val (kx, vx) = sound(x)
        val (ky, vy) = sound(y)
        if (kx.isEmpty() || kx != ky) return false
        // One consonant ("Abi" / "Appu") is too little to go on: the vowels must agree too.
        return kx.length >= 2 || vx == vy
    }
}
