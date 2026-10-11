package com.shopai.app.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long answer is voiced in pieces the proxy accepts (≤ 500 characters), and the pieces are the whole text. */
class SpeechChunksTest {

    private val list = "Owner, records-la history paartha:\n" +
        "Late-aa tharuvaanga (12):\n" +
        (1..12).joinToString("\n") { "$it. Customer $it — 3 thadavaiyum late, sarasari $it naal; ippo ${it + 2} naal thaandiduchu." } +
        "\nCorrect date-la tharuvaanga: Ramesh, Lakshmi, Anbu."

    @Test fun aShortAnswerIsOnePiece() {
        assertEquals(listOf("Kumar ₹4,000 tharanum Owner."), SpeechChunks.split("Kumar ₹4,000 tharanum Owner."))
    }

    @Test fun aLongListIsCutAtLinesUnderTheLimit() {
        val pieces = SpeechChunks.split(list)
        assertTrue(list.length > 500)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= SpeechChunks.MAX_CHARS })
        // Nothing dropped or reordered: the same words in the same order.
        assertEquals(list.split(Regex("""\s+""")), pieces.joinToString(" ").split(Regex("""\s+""")))
        // Each row stays whole (cut between rows, not inside one).
        assertTrue(pieces.none { p -> Regex("""^\S*—""").containsMatchIn(p) })
    }

    @Test fun oneHugeSentenceIsCutAtCommasThenSpaces() {
        val long = (1..80).joinToString(", ") { "item $it" }
        val pieces = SpeechChunks.split(long)
        assertTrue(pieces.all { it.length <= SpeechChunks.MAX_CHARS })
        assertEquals(long.split(Regex("""\s+""")), pieces.joinToString(" ").split(Regex("""\s+""")))
    }

    @Test fun tamilTextToo() {
        val ta = (1..30).joinToString(" ") { "$it. குமார் ₹4,000 தரணும், சில தடவை late." }
        val pieces = SpeechChunks.split(ta)
        assertTrue(pieces.all { it.length <= SpeechChunks.MAX_CHARS })
        assertEquals(ta.split(Regex("""\s+""")), pieces.joinToString(" ").split(Regex("""\s+""")))
    }
}
