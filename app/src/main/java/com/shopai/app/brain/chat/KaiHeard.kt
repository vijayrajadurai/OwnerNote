package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiUnderstanding
import com.shopai.app.brain.tools.KaiProductSound
import com.shopai.app.brain.tools.KaiSpokenWords
import com.shopai.app.brain.tools.KaiWordSound
import java.time.LocalDate
import java.util.Locale

/**
 * The phone's speech-to-text hears a line a few ways (up to five guesses, its best first). Owners don't always say it clearly
 * (owner, 21 Oct 2026: "voice-la nariya correct solla matanga, but kai athoda intent ah purinjikanum"), so Kai takes the guess it
 * reads best — a person in the books, a product of the shop, one amount, words Kai knows — and the phone's own first guess
 * whenever another isn't clearly better. Reads only: the chosen line then goes to Kai like any other (nothing saved before Confirm).
 */
object KaiHeard {

    /** The index of the guess Kai reads best; 0 (the phone's best) unless another reads clearly better. */
    fun pick(guesses: List<String>, people: List<String>, products: List<String>, today: LocalDate): Int {
        if (guesses.size <= 1) return 0
        val scores = guesses.map { score(it, people, products, today) }
        val best = scores.indices.maxByOrNull { scores[it] } ?: 0
        // Another guess only when it reads clearly better (two points) than the phone's own first one.
        return if (best != 0 && scores[best] >= scores[0] + 2) best else 0
    }

    /** How well Kai reads [line]: a known person or product +3 each, one amount +2 (two or more −1), a known word +1, an unknown −2. */
    fun score(line: String, people: List<String>, products: List<String>, today: LocalDate): Int {
        val text = if (KaiSpokenWords.hasTamil(line)) {
            KaiSpokenWords.withNames(KaiSpokenWords.normalize(runCatching { KaiProductSound.withProducts(line, products, people) }.getOrDefault(line)), people + products)
        } else line
        var score = 0
        if (KaiUnderstanding.knownPerson(text, people) != null) score += 3
        if (products.any { p -> p.isNotBlank() && text.contains(p, ignoreCase = true) }) score += 3
        val amounts = runCatching { KaiUnderstanding.amountsIn(text, today) }.getOrDefault(emptyList())
        score += when (amounts.size) { 0 -> 0; 1 -> 2; else -> -1 }
        val own = (people + products).flatMap { it.lowercase(Locale.ROOT).split(Regex("""[^\p{L}]+""")) }.toSet()
        for (w in Regex("""\p{L}+""").findAll(text).map { it.value.lowercase(Locale.ROOT) }) {
            score += when {
                w in own -> 0
                // (a word of Enga Vechen's list — "பாஸ்போர்ட்", "அலமாரி", "வச்சேன்" — is a word Kai reads)
                w.any { it in '஀'..'௿' } -> if (com.shopai.app.brain.tools.KaiKept.knows(w)) 1 else -2 // a Tamil word the mic left that Kai's words don't read
                KaiLexicon.knows(w) || KaiUnderstanding.isNumberWord(w) -> 1
                KaiWordSound.readsAs(w) != null -> if (KaiWordSound.readsAs(w) == w) 1 else 0
                w.length <= 2 -> 0
                com.shopai.app.brain.tools.KaiKept.knows(w) -> 1
                else -> -2
            }
        }
        return score
    }
}
