package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** What Kai keeps saying while a reminder rings: natural progression, real pauses, short answers. */
class KaiUrgentVoiceScriptTest {
    private val t0 = 1_800_000_000_000L

    private fun reminder(action: ReminderAction = ReminderAction.CALL, person: String? = "Praba", lang: KaiLang = KaiLang.TANGLISH) = KaiReminderFlow.trigger(
        KaiReminder(
            id = "R1", title = "Call Praba", task = "Praba-ku call panna", action = action, person = person, phone = "+919000000001",
            triggerAt = t0, zone = "Asia/Kolkata", notificationMessage = "", sourceText = "Praba-ku 2 minutes-la call pannanum",
            createdAt = t0 - 120_000, lang = lang, amount = if (action == ReminderAction.PAYMENT) BigDecimal(500) else null,
        ),
        t0, newOccurrence = true,
    )

    @Test
    fun callReminderProgression() {
        val r = reminder()
        assertEquals(
            listOf(
                "Owner, Praba-ku call panna vendiya neram aachu. Ippo call pannalama?",
                "Praba-ku call pannunga Owner.",
                "Seekiram pannunga Owner.",
                "Owner, Praba-ku call pannalama?",
                "Praba-ku call panna marakkadheenga Owner.",
                "Owner, idha ippo mudichidalaama?",
            ),
            KaiUrgentVoiceScript.lines(r).map { it.text },
        )
        // The opening is exactly the screen's own words.
        assertEquals(KaiUrgentWords.text(r).speech, KaiUrgentVoiceScript.lines(r).first().text)
        // The lines that ask bring the hand out; "Seekiram" is attention, not a gesture.
        assertEquals(listOf(true, true, false, true, false, true), KaiUrgentVoiceScript.lines(r).map { it.gesture })
    }

    @Test
    fun everyLanguageAndKindHasItsOwnWords() {
        val ta = KaiUrgentVoiceScript.lines(reminder(lang = KaiLang.TAMIL)).map { it.text }
        assertTrue(ta[1], ta[1].contains("Praba-க்கு") && ta[1].contains("ஓனர்"))
        val en = KaiUrgentVoiceScript.lines(reminder(lang = KaiLang.ENGLISH)).map { it.text }
        assertEquals("Please call Praba, Owner.", en[1])
        assertEquals("en-IN", KaiUrgentVoiceScript.languageCode(KaiLang.ENGLISH))
        assertEquals("ta-IN", KaiUrgentVoiceScript.languageCode(KaiLang.TANGLISH))
        val msg = KaiUrgentVoiceScript.lines(reminder(action = ReminderAction.MESSAGE)).map { it.text }
        assertEquals("Praba-ku message pannunga Owner.", msg[1])
        val pay = KaiUrgentVoiceScript.lines(reminder(action = ReminderAction.PAYMENT)).map { it.text }
        assertFalse("no call words for a payment", pay.drop(1).any { it.contains("call", ignoreCase = true) })
        for (lines in listOf(ta, en, msg, pay)) {
            assertEquals(6, lines.size)
            assertEquals("no line twice", lines.size, lines.toSet().size)
        }
    }

    // Persistent but natural: the opening once, then the follow-ups in turn — never the same line twice in a row.
    @Test
    fun cycleNeverRepeatsALineBackToBack() {
        val size = 6
        val order = (0 until 40).map { KaiUrgentVoiceScript.lineAt(it, size) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 1, 2, 3, 4, 5, 1), order.take(12))
        assertEquals("the opening only once", 1, order.count { it == 0 })
        assertTrue(order.zipWithNext().none { (a, b) -> a == b })
    }

    // Not every 2 seconds: 5–8 s after the opening, then 8–12 s, then longer, capped.
    @Test
    fun pausesLikeAPerson() {
        assertEquals(0L, KaiUrgentVoiceScript.pauseBefore(0))
        assertTrue(KaiUrgentVoiceScript.pauseBefore(1) in 5_000L..8_000L)
        assertTrue(KaiUrgentVoiceScript.pauseBefore(2) in 8_000L..12_000L)
        val later = (1..50).map(KaiUrgentVoiceScript::pauseBefore)
        assertEquals(later.sorted(), later)
        assertTrue(later.all { it in 5_000L..20_000L })
    }

    @Test
    fun shortAnswersNeverClaimACall() {
        val r = reminder()
        assertEquals("Seri Owner, Praba-ku call screen open pannuren.", KaiUrgentVoiceScript.callAck(r))
        assertEquals("Seri Owner.", KaiUrgentVoiceScript.doneAck(KaiLang.TANGLISH))
        assertEquals("Seri Owner, 5 minutes-ku remind pannuren.", KaiUrgentVoiceScript.snoozeAck(5, KaiLang.TANGLISH))
        assertEquals("Seri Owner, call screen open pannuren.", KaiUrgentVoiceScript.callAck(reminder(person = null)))
        for (lang in KaiLang.values()) {
            val ack = KaiUrgentVoiceScript.callAck(r, lang)
            for (claim in listOf("pannitten", "called", "பண்ணிட்டேன்", "answered")) assertFalse(ack, ack.contains(claim, ignoreCase = true))
        }
        assertNotEquals(KaiUrgentVoiceScript.doneAck(KaiLang.TAMIL), KaiUrgentVoiceScript.doneAck(KaiLang.ENGLISH))
    }

    @Test
    fun compactControls() {
        val c = KaiUrgentVoiceScript.controls(5, KaiLang.TANGLISH)
        assertEquals("Call now", c.call)
        assertEquals("Done", c.done)
        assertEquals("Snooze 5 min", c.snooze)
        val ta = KaiUrgentVoiceScript.controls(5, KaiLang.TAMIL)
        assertTrue(ta.snooze.contains("5"))
        // Short enough for one compact row.
        for (l in KaiLang.values()) KaiUrgentVoiceScript.controls(5, l).let { assertTrue(listOf(it.call, it.done, it.snooze).all { s -> s.length <= 16 }) }
    }
}
