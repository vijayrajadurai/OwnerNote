package com.shopai.app.brain.tools

import com.shopai.app.brain.chat.KaiHeard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Owner, 21 Oct 2026: "voice-la nariya correct solla matanga, but kai athoda intent ah purinjikanum — yeppadi sonnalum". A word Kai
 * can't read is the word of Kai's it sounds like — never a different word, never the shop's own name.
 */
class KaiWordSoundTest {
    @Test fun aWordIsTheWordOfKaisItSoundsLike() {
        for ((said, kai) in listOf("taranum" to "tharanum", "tharnum" to "tharanum", "thararnum" to "tharanum",
            "kredit" to "credit", "kudutten" to "kuduthen", "kuduthaen" to "kuduthen", "katinen" to "kattinen", "kattunen" to "kattinen",
            "vananum" to "vaanganum", "kudukannum" to "kudukkanum", "kudukkanam" to "kudukkanum", "thandan" to "thandhan",
            "vandhathu" to "vandhuchu", "vanthathu" to "vandhuchu", "erakkinen" to "irakkinen", "vithuchi" to "vithuchu", "vikkuchu" to "vithuchu",
            "sels" to "sales", "expairy" to "expiry", "kensal" to "cancel", "yarellam" to "yaar yaar"))
            assertEquals(said, kai, KaiWordSound.readsAs(said))
    }

    @Test fun wordsNotNearAnyOfKaisStayAsTheyAre() {
        // A word not in the list: never turned into another answer (CLAUDE.md) — products, money words, a new name.
        for (w in listOf("thakkali", "vengayam", "return", "current", "sugar", "packet", "bottle", "selvi", "suresh", "arisi", "maavu",
            "paruppu", "biscuit", "cutting", "facial", "salary", "rent", "zorbuttan", "panninom", "alteration"))
            assertNull(w, KaiWordSound.readsAs(w))
        // A form Kai's rules already read stays itself — never another verb ("kuduthachu" is I paid, not "kudukkanum").
        for (w in listOf("kuduthachu", "vandhurukku", "anuppitaan", "vithuduchu"))
            assertEquals(w, w, KaiWordSound.readsAs(w))
        // Sounds exactly like one meaning and a step from another ("kaden": katten = I paid / kadan = credit): asked, never chosen.
        assertNull(KaiWordSound.readsAs("kaden"))
        for (w in listOf<String>())
            assertNull(w, KaiWordSound.readsAs(w))
    }

    @Test fun onlyTheWordsKaiAskedAboutAndNeverANameBeforeTheAmount() {
        val keep = setOf("kumar")
        assertEquals("Kumar 500 tharanum", KaiWordSound.fixWords("Kumar 500 taranum", listOf("taranum"), keep))
        // "Kannan" (a new person) before the amount is never a verb — only the word after it is.
        assertNull(KaiWordSound.fixWords("Kannan 500 kadan", listOf("kannan"), keep))
        assertEquals("Kannan 500 tharanum", KaiWordSound.fixWords("Kannan 500 taranum", listOf("kannan", "taranum"), keep))
        // Two words after the amount that would both become a money verb: neither.
        assertNull(KaiWordSound.fixWords("Kumar 500 taranum tharnum", listOf("taranum", "tharnum"), keep))
        // A line with its own money verb gets no second one; a word Kai wasn't asking about is never changed.
        assertNull(KaiWordSound.fixWords("Kumar 500 kaden kuduthan", listOf("kaden"), keep))
        assertNull(KaiWordSound.fixWords("Kumar 500 taranum", listOf("kumar"), keep))
    }

    @Test fun ofTheMicsGuessesKaiTakesTheOneItReads() {
        val people = listOf("Kumar", "Ramesh"); val products = listOf("Colgate", "Rice"); val today = LocalDate.of(2026, 10, 21)
        assertEquals(1, KaiHeard.pick(listOf("kumar 2000 blah blah", "Kumar 2000 kuduthan"), people, products, today))
        assertEquals(1, KaiHeard.pick(listOf("கோல் கேட் ரெண்டு பாக் பந்து", "Colgate 2 box vandhuchu"), people, products, today))
        // The phone's first guess unless another reads clearly better.
        assertEquals(0, KaiHeard.pick(listOf("Kumar 2000 kuduthan", "Kumar 2000 kudutha"), people, products, today))
        assertEquals(0, KaiHeard.pick(listOf("குமார் 2000 குடுத்தான்", "குமார் 2000 குடை தான்"), people, products, today))
        assertEquals(0, KaiHeard.pick(listOf("Kumar 2000 kuduthan"), people, products, today))
    }
}
