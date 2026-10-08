package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Kai's natural reminder voice per kind: one turn of short sentences with a breath between them
 * (no restart), urgent but human, more direct on later attempts, never inventing an amount.
 */
class KaiReminderNaturalVoiceTest {
    private val t0 = 1_800_000_000_000L

    private fun r(
        task: String, action: ReminderAction = ReminderAction.TASK, person: String? = null, time: LocalTime? = null,
        attempt: Int = 1, amount: BigDecimal? = null, lang: KaiLang = KaiLang.TANGLISH, contactId: String? = null,
    ) = KaiReminder(
        id = "R1", title = task, task = task, action = action, person = person, contactId = contactId, triggerAt = t0, time = time,
        zone = "Asia/Kolkata", notificationMessage = "", sourceText = task, createdAt = t0 - 60_000, attemptCount = attempt, amount = amount, lang = lang,
    )

    private fun opening(x: KaiReminder) = KaiUrgentVoiceScript.written(x, KaiLang.TANGLISH).first().parts

    // 18. Call: "Owner... Kumar-ku call panna vendiya neram aachu." [short] "Call pannunga." [beat] "Call pannalama?"
    @Test
    fun callVoice() {
        val call = r("Kumar-ku call panna", ReminderAction.CALL, "Kumar")
        assertEquals(listOf("Owner... Kumar-ku call panna vendiya neram aachu. Call pannidunga.", "Ippo pannalama?"), opening(call))
        // Heard in Tamil letters: the name with its spoken ending, no Latin glued to Tamil ("Kumar-க்கு").
        assertEquals(listOf("ஓனர்... குமாருக்கு கால் பண்ண வேண்டிய நேரம் ஆச்சு. கால் பண்ணிடுங்க.", "இப்போ பண்ணலாமா?"),
            KaiUrgentVoiceScript.lines(call).first().parts)
        // The name once in the whole cycle, not in every line.
        val all = KaiUrgentVoiceScript.written(call, KaiLang.TANGLISH)
        assertEquals(1, all.count { it.text.contains("Kumar") })
    }

    // 19. Payment: no amount invented; the said amount only.
    @Test
    fun paymentVoice() {
        val pay = r("Kumar-ku payment panna", ReminderAction.PAYMENT, "Kumar")
        assertEquals(listOf("Owner... Kumar-ku payment panna vendiya time aachu. Oru thadava check pannidunga.", "Open pannalama?"), opening(pay))
        val all = KaiUrgentVoiceScript.written(pay, KaiLang.TANGLISH).flatMap { it.parts }
        assertTrue("no amount invented: $all", all.none { it.contains("₹") || Regex("""\d""").containsMatchIn(it) })
        val said = r("Kumar-ku payment panna", ReminderAction.PAYMENT, "Kumar", amount = BigDecimal(5000))
        assertTrue(opening(said)[0].startsWith("Owner... Kumar-ku ₹5,000 payment panna vendiya time aachu."))
        // Current bill: the owner's own words.
        val bill = r("current bill pay panna", ReminderAction.PAYMENT, time = LocalTime.of(18, 0))
        assertEquals(listOf("Owner... current bill pay panna vendiya time aachu. Ippo pannidunga.", "Open pannalama?"), opening(bill))
    }

    // 20. Personal: "Owner... 4 mani aachu." "Paiyana school-la irundhu kootitu vara vendiya neram." "Kelambalama?"
    @Test
    fun personalVoice() {
        val pickup = r("Paiyana school-la irundhu kootitu vara", time = LocalTime.of(16, 0))
        assertEquals(listOf("Owner... 4 mani aachu. Paiyana school-la irundhu kootitu varanum.", "Kelambalama?"), opening(pickup))
        // Spoken in Tamil script for the natural voice, the owner's words included.
        assertEquals(listOf("ஓனர்... நாலு மணி ஆச்சு. பையனை ஸ்கூல்ல இருந்து கூட்டிட்டு வரணும்.", "கிளம்பலாமா?"),
            KaiUrgentVoiceScript.lines(pickup).first().parts)
        // "In 2 minutes" (no clock time): no hour is invented.
        assertTrue(opening(r("Paiyana school-la irundhu kootitu vara"))[0].startsWith("Owner... neram aachu."))
    }

    // 21. Generic / gym: "Owner... 8 mani aachu." "Gym poganum-nu reminder." "Ready-a?"
    @Test
    fun genericVoice() {
        assertEquals(listOf("Owner... 8 mani aachu. Gym poganum.", "Ready-a?"), opening(r("gym poganum", time = LocalTime.of(8, 0))))
        assertEquals(listOf("Owner... 8:30 aachu. Office-ku kelambanum.", "Kelambalama?"), opening(r("office-ku kelambanum", time = LocalTime.of(8, 30))))
        assertEquals(listOf("ஓனர்... எட்டரை மணி ஆச்சு. ஆபீஸுக்கு கிளம்பணும்.", "கிளம்பலாமா?"), KaiUrgentVoiceScript.lines(r("office-ku kelambanum", time = LocalTime.of(8, 30))).first().parts)
        assertEquals(listOf("Owner... 10 mani aachu. Gate lock time.", "Ippo pannalama?"), opening(r("gate lock", time = LocalTime.of(22, 0))))
        assertEquals(listOf("Owner... rent pathi nyabagapaduththa sonneenga. Ippo pannidunga.", "Ippo pannalama?"), opening(r("rent")))
        assertEquals(listOf("Owner... Colgate stock check panna vendiya time aachu. Ippo pannidunga.", "Ippo pannalama?"),
            opening(r("Colgate stock check panna", ReminderAction.STOCK)))
    }

    // 22. First attempt: natural; a beat before the question (≈700–1200 ms once joined).
    @Test
    fun firstAttemptTurnShape() {
        for (x in listOf(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar"), r("gym poganum", time = LocalTime.of(8, 0)), r("Kumar-ku payment panna", ReminderAction.PAYMENT, "Kumar"))) {
            val first = KaiUrgentVoiceScript.lines(x).first()
            // The statement's sentences are one request (they flow like speech), then the question.
            assertEquals(2, first.parts.size)
            assertTrue(first.parts[0], first.parts[0].count { it == '.' } >= 2)
            assertEquals(listOf(KaiUrgentVoiceScript.GAP_BEFORE_QUESTION_MS), first.gapsMs)
            assertTrue(first.parts.last().endsWith("?"))
            assertEquals(first.parts.joinToString(" "), first.text)
        }
        // + the clips' kept edges (WavJoin): the pause heard lands in the spec's windows.
        val edges = (com.shopai.app.data.tts.WavJoin.LEAD_KEEP_MS + com.shopai.app.data.tts.WavJoin.TAIL_KEEP_MS).toLong()
        assertTrue(KaiUrgentVoiceScript.GAP_BEFORE_QUESTION_MS + edges in 700L..1200L)
        // Between turns: still a person's pause, not a machine gun.
        assertTrue(KaiUrgentVoiceScript.pauseBefore(1) >= 5_000L)
    }

    // 23. Repeated attempts: more direct (2), then urgent (3+) — never angry, same attempts / retry engine.
    @Test
    fun repeatedAttemptsEscalate() {
        val a1 = opening(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar", attempt = 1))
        val a2 = opening(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar", attempt = 2))
        val a3 = opening(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar", attempt = 3))
        val a5 = opening(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar", attempt = 5))
        assertEquals(listOf("Owner, Kumar-ku innum call pannalaye. Ippo pannidunga.", "Ippo pannalama?"), a2)
        assertEquals(listOf("Owner, Kumar-ku innum call pannave illaye. Romba neram aachu, udane pannidunga.", "Ippove pannalama?"), a3)
        assertEquals(a3, a5)
        assertNotEquals(a1, a2)
        assertNotEquals(a2, a3)
        val personal2 = opening(r("Paiyana school-la irundhu kootitu vara", time = LocalTime.of(16, 0), attempt = 2))
        assertEquals("Owner, Paiyana school-la irundhu kootitu vara innum pending-la irukku. Ippo pannidunga.", personal2[0])
        // Urgent, never angry or shouting.
        for (attempt in 1..5) for (x in listOf(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar", attempt = attempt), r("gym poganum", attempt = attempt))) {
            for (l in KaiUrgentVoiceScript.written(x, KaiLang.TANGLISH) + KaiUrgentVoiceScript.written(x, KaiLang.ENGLISH)) {
                assertFalse(l.text, l.text.contains("!"))
                assertFalse(l.text, l.text.any { it.isLetter() && it.isUpperCase() } && l.text == l.text.uppercase())
                for (harsh in listOf("enna aachu", "kekkala", "why", "hurry up")) assertFalse(l.text, l.text.contains(harsh, true))
            }
        }
    }

    // Spoken Tamil, not read-out Tamil: names in Tamil letters with the spoken ending, hours as people say them.
    @Test
    fun spokenTamilIsColloquial() {
        assertEquals("நாலு மணி", KaiTamilVoice.clock(LocalTime.of(16, 0)))
        assertEquals("எட்டரை மணி", KaiTamilVoice.clock(LocalTime.of(8, 30)))
        assertEquals("ஒம்பதே கால் மணி", KaiTamilVoice.clock(LocalTime.of(9, 15)))
        assertEquals("பத்தே முக்கால் மணி", KaiTamilVoice.clock(LocalTime.of(22, 45)))
        assertEquals("பன்னெண்டு மணி", KaiTamilVoice.clock(LocalTime.of(0, 0)))
        assertEquals("அஞ்சு", KaiTamilVoice.count(5))
        for ((latin, ta) in listOf("Kumar" to "குமார்", "Praba" to "பிரபா", "Suresh" to "சுரேஷ்", "Anitha" to "அனிதா", "Selvi" to "செல்வி", "Gokul" to "கோகுல்")) {
            assertEquals(latin, ta, KaiTamilVoice.tamil(latin))
        }
        assertEquals("குமாருக்கு", KaiTamilVoice.dative("குமார்"))
        assertEquals("பிரபாக்கு", KaiTamilVoice.dative("பிரபா"))
        assertEquals("பையனை", KaiTamilVoice.accusative("பையன்"))
        assertEquals("பையனை ஸ்கூல்ல இருந்து பிக்கப் பண்ணணும்.", KaiUrgentVoiceScript.spoken("son-a school-la irundhu pickup pannanum.", KaiLang.TAMIL))
        assertEquals("சரி ஓனர், அஞ்சு நிமிஷம் கழிச்சு மறுபடியும் சொல்றேன்.", KaiUrgentVoiceScript.snoozeAck(5, KaiLang.TANGLISH))
        // No Latin letters left for the Tamil voice in any turn, any attempt — the name included.
        for (attempt in 1..5) for (x in listOf(r("Gokul-ku call panna", ReminderAction.CALL, "Gokul", attempt = attempt), r("Anitha-ku payment panna", ReminderAction.PAYMENT, "Anitha", attempt = attempt),
            r("Paiyana school-la irundhu kootitu vara", time = LocalTime.of(16, 0), attempt = attempt))) {
            for (line in KaiUrgentVoiceScript.lines(x).flatMap { it.parts }) assertFalse(line, Regex("[A-Za-z]").containsMatchIn(line))
        }
    }

    // The kind follows the reminder (derived, never stored): business and personal stay apart.
    @Test
    fun kindsAndPersonalScope() {
        assertEquals(ReminderKind.CALL, KaiReminderKind.of(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar")))
        assertEquals(ReminderKind.PAYMENT, KaiReminderKind.of(r("current bill pay panna", ReminderAction.PAYMENT)))
        assertEquals(ReminderKind.PERSONAL, KaiReminderKind.of(r("Paiyana school-la irundhu kootitu vara")))
        assertEquals(ReminderKind.BUSINESS, KaiReminderKind.of(r("Colgate stock check panna", ReminderAction.STOCK)))
        assertEquals(ReminderKind.BUSINESS, KaiReminderKind.of(r("supplier order confirm panna")))
        assertEquals(ReminderKind.FOLLOW_UP, KaiReminderKind.of(r("Ravi order follow up")))
        assertEquals(ReminderKind.TASK, KaiReminderKind.of(r("terrace clean panna")))
        assertEquals(ReminderKind.GENERIC, KaiReminderKind.of(r("rent")))
        assertTrue(KaiReminderKind.personal(r("Amma-ku call panna", ReminderAction.CALL, "Amma", contactId = "phone:7")))
        assertFalse(KaiReminderKind.personal(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar", contactId = "c1")))
        assertFalse(KaiReminderKind.personal(r("Colgate stock check panna", ReminderAction.STOCK)))
    }

    // Prefetch keeps every sentence of a turn on its own (the turn is joined from them, instantly).
    @Test
    fun prefetchHasEverySentence() {
        val call = KaiReminderFlow.trigger(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar"), t0, newOccurrence = true)
        val texts = KaiUrgentVoiceScript.prefetchTexts(call)
        for (part in KaiUrgentVoiceScript.lines(call).first().parts) assertTrue(part, part in texts)
        // The next attempt's opening sentences too.
        for (part in KaiUrgentVoiceScript.lines(call.copy(attemptCount = 2)).first().parts) assertTrue(part, part in texts)
        assertEquals(texts.distinct(), texts)
    }
}
