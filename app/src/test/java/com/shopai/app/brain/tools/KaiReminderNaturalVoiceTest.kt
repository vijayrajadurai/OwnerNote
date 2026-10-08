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
        assertEquals(listOf("Owner... Kumar-ku call panna vendiya neram aachu.", "Call pannunga.", "Call pannalama?"), opening(call))
        // The name once in the whole cycle, not in every line.
        val all = KaiUrgentVoiceScript.written(call, KaiLang.TANGLISH)
        assertEquals(1, all.count { it.text.contains("Kumar") })
    }

    // 19. Payment: no amount invented; the said amount only.
    @Test
    fun paymentVoice() {
        val pay = r("Kumar-ku payment panna", ReminderAction.PAYMENT, "Kumar")
        assertEquals(listOf("Owner... Kumar-ku payment panna vendiya time aachu.", "Idha check pannunga.", "Open pannalama?"), opening(pay))
        val all = KaiUrgentVoiceScript.written(pay, KaiLang.TANGLISH).flatMap { it.parts }
        assertTrue("no amount invented: $all", all.none { it.contains("₹") || Regex("""\d""").containsMatchIn(it) })
        val said = r("Kumar-ku payment panna", ReminderAction.PAYMENT, "Kumar", amount = BigDecimal(5000))
        assertEquals("Owner... Kumar-ku ₹5,000 payment panna vendiya time aachu.", opening(said)[0])
        // Current bill: the owner's own words.
        val bill = r("current bill pay panna", ReminderAction.PAYMENT, time = LocalTime.of(18, 0))
        assertEquals(listOf("Owner... current bill pay panna vendiya time aachu.", "Idha ippo pannunga.", "Open pannalama?"), opening(bill))
    }

    // 20. Personal: "Owner... 4 mani aachu." "Paiyana school-la irundhu kootitu vara vendiya neram." "Kelambalama?"
    @Test
    fun personalVoice() {
        val pickup = r("Paiyana school-la irundhu kootitu vara", time = LocalTime.of(16, 0))
        assertEquals(listOf("Owner... 4 mani aachu.", "Paiyana school-la irundhu kootitu vara vendiya neram.", "Kelambalama?"), opening(pickup))
        // Spoken in Tamil script for the natural voice, the owner's words included.
        assertEquals(listOf("ஓனர்... 4 மணி ஆச்சு.", "பையனை ஸ்கூல்ல இருந்து கூட்டிட்டு வர வேண்டிய நேரம்.", "கிளம்பலாமா?"),
            KaiUrgentVoiceScript.lines(pickup).first().parts)
        // "In 2 minutes" (no clock time): no hour is invented.
        assertEquals("Owner... neram aachu.", opening(r("Paiyana school-la irundhu kootitu vara"))[0])
    }

    // 21. Generic / gym: "Owner... 8 mani aachu." "Gym poganum-nu reminder." "Ready-a?"
    @Test
    fun genericVoice() {
        assertEquals(listOf("Owner... 8 mani aachu.", "Gym poganum-nu reminder.", "Ready-a?"), opening(r("gym poganum", time = LocalTime.of(8, 0))))
        assertEquals(listOf("Owner... 8:30 aachu.", "Office-ku kelambanum-nu reminder.", "Kelambalama?"), opening(r("office-ku kelambanum", time = LocalTime.of(8, 30))))
        assertEquals(listOf("Owner... 10 mani aachu.", "Gate lock neram.", "Ippo pannalama?"), opening(r("gate lock", time = LocalTime.of(22, 0))))
        assertEquals(listOf("Owner... rent-nu remind panna sonneenga.", "Idha ippo pannunga.", "Ippo pannalama?"), opening(r("rent")))
        assertEquals(listOf("Owner... Colgate stock check panna vendiya time aachu.", "Idha ippo pannunga.", "Ippo pannalama?"),
            opening(r("Colgate stock check panna", ReminderAction.STOCK)))
    }

    // 22. First attempt: natural; the pauses inside the turn are the spec's (≈250–500 ms, ≈700–1200 ms once joined).
    @Test
    fun firstAttemptTurnShape() {
        for (x in listOf(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar"), r("gym poganum", time = LocalTime.of(8, 0)), r("Kumar-ku payment panna", ReminderAction.PAYMENT, "Kumar"))) {
            val first = KaiUrgentVoiceScript.lines(x).first()
            assertEquals(3, first.parts.size)
            assertEquals(listOf(KaiUrgentVoiceScript.GAP_AFTER_FIRST_MS, KaiUrgentVoiceScript.GAP_BEFORE_QUESTION_MS), first.gapsMs)
            assertTrue(first.parts.last().endsWith("?"))
            assertEquals(first.parts.joinToString(" "), first.text)
        }
        // + the clips' kept edges (WavJoin): the pause heard lands in the spec's windows.
        val edges = (com.shopai.app.data.tts.WavJoin.LEAD_KEEP_MS + com.shopai.app.data.tts.WavJoin.TAIL_KEEP_MS).toLong()
        assertTrue(KaiUrgentVoiceScript.GAP_AFTER_FIRST_MS + edges in 250L..500L)
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
        assertEquals(listOf("Owner, Kumar-ku innum call pannala.", "Ippo call pannunga.", "Call pannalama?"), a2)
        assertEquals(listOf("Owner, Kumar call romba neram-a pending-la irukku.", "Udane call pannunga.", "Ippove call pannalama?"), a3)
        assertEquals(a3, a5)
        assertNotEquals(a1, a2)
        assertNotEquals(a2, a3)
        val personal2 = opening(r("Paiyana school-la irundhu kootitu vara", time = LocalTime.of(16, 0), attempt = 2))
        assertEquals("Owner, Paiyana school-la irundhu kootitu vara innum pending-la irukku.", personal2[0])
        // Urgent, never angry or shouting.
        for (attempt in 1..5) for (x in listOf(r("Kumar-ku call panna", ReminderAction.CALL, "Kumar", attempt = attempt), r("gym poganum", attempt = attempt))) {
            for (l in KaiUrgentVoiceScript.written(x, KaiLang.TANGLISH) + KaiUrgentVoiceScript.written(x, KaiLang.ENGLISH)) {
                assertFalse(l.text, l.text.contains("!"))
                assertFalse(l.text, l.text.any { it.isLetter() && it.isUpperCase() } && l.text == l.text.uppercase())
                for (harsh in listOf("enna aachu", "kekkala", "why", "hurry up")) assertFalse(l.text, l.text.contains(harsh, true))
            }
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
