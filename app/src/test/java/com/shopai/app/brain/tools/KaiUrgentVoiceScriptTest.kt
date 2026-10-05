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

    // Tanglish owner: the words follow the spec's progression, spoken in Tamil script so the natural
    // (Sarvam) voice reads them like a person — never Latin letters read by a Tamil voice.
    @Test
    fun callReminderProgressionInTheNaturalVoice() {
        val r = reminder()
        assertEquals(
            listOf(
                "ஓனர், Praba-க்கு கால் பண்ண வேண்டிய நேரம் ஆச்சு. இப்போ கால் பண்ணலாமா?",
                "Praba-க்கு கால் பண்ணுங்க ஓனர்.",
                "சீக்கிரம் பண்ணுங்க ஓனர்.",
                "ஓனர், Praba-க்கு கால் பண்ணலாமா?",
                "Praba-க்கு கால் பண்ண மறக்காதீங்க ஓனர்.",
                "ஓனர், இதை இப்போ முடிச்சிடலாமா?",
            ),
            KaiUrgentVoiceScript.lines(r).map { it.text },
        )
        assertEquals("ta-IN", KaiUrgentVoiceScript.languageCode(KaiLang.TANGLISH))
        // The lines that ask bring the hand out; "Seekiram" is attention, not a gesture.
        assertEquals(listOf(true, true, false, true, false, true), KaiUrgentVoiceScript.lines(r).map { it.gesture })
        // The same words the owner reads (Tanglish on screen), in the spec's order.
        assertEquals(
            listOf("Praba-ku call pannunga Owner.", "Seekiram pannunga Owner.", "Owner, Praba-ku call pannalama?"),
            KaiUrgentVoiceScript.written(r, KaiLang.TANGLISH).drop(1).take(3).map { it.text },
        )
    }

    // No Latin word is left for the Tamil voice to stumble on — only names.
    @Test
    fun tamilVoiceGetsNoEnglishWordsExceptNames() {
        for (action in ReminderAction.values()) for (lang in listOf(KaiLang.TAMIL, KaiLang.TANGLISH)) {
            val r = reminder(action = action, lang = lang)
            val said = KaiUrgentVoiceScript.lines(r).map { it.text } + KaiUrgentVoiceScript.callAck(r) +
                KaiUrgentVoiceScript.doneAck(lang) + KaiUrgentVoiceScript.snoozeAck(5, lang)
            for (line in said) {
                val latin = Regex("[A-Za-z]+").findAll(line).map { it.value }.filter { it != "Praba" }.toList()
                assertTrue("$action/$lang: '$line' has $latin", latin.isEmpty())
            }
        }
        // English owners hear English.
        assertEquals("Please call Praba, Owner.", KaiUrgentVoiceScript.lines(reminder(lang = KaiLang.ENGLISH))[1].text)
        assertEquals("Okay Owner.", KaiUrgentVoiceScript.doneAck(KaiLang.ENGLISH))
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
        // Spoken in Tamil script for a Tanglish owner (the spec's "Seri Owner, Praba-ku call screen open pannuren.").
        assertEquals("சரி ஓனர், Praba-க்கு கால் ஸ்க்ரீன் திறக்குறேன்.", KaiUrgentVoiceScript.callAck(r))
        assertEquals("சரி ஓனர்.", KaiUrgentVoiceScript.doneAck(KaiLang.TANGLISH))
        assertEquals("சரி ஓனர், 5 நிமிஷம் கழிச்சு நினைவூட்டுறேன்.", KaiUrgentVoiceScript.snoozeAck(5, KaiLang.TANGLISH))
        assertEquals("சரி ஓனர், கால் ஸ்க்ரீன் திறக்குறேன்.", KaiUrgentVoiceScript.callAck(reminder(person = null)))
        assertEquals("Seri Owner, Praba-ku call screen open pannuren.", KaiUrgentVoiceScript.callAckWritten(r, KaiLang.TANGLISH))
        for (lang in KaiLang.values()) {
            val ack = KaiUrgentVoiceScript.callAck(r, lang)
            for (claim in listOf("pannitten", "called", "பண்ணிட்டேன்", "answered")) assertFalse(ack, ack.contains(claim, ignoreCase = true))
        }
        assertNotEquals(KaiUrgentVoiceScript.doneAck(KaiLang.TAMIL), KaiUrgentVoiceScript.doneAck(KaiLang.ENGLISH))
        for (claim in listOf("பண்ணிட்டேன்", "திறந்துட்டேன்")) assertFalse(KaiUrgentVoiceScript.callAck(r).contains(claim))
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
