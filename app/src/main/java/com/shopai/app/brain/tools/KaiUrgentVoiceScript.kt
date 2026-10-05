package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiLang

/** One thing Kai says while a reminder rings. */
data class VoiceLine(
    val text: String,
    /** The line asks the owner to act: Kai's open hand comes out with it. */
    val gesture: Boolean,
)

/**
 * What Kai keeps saying while a reminder rings, until the owner acts — like a person gently
 * but persistently reminding, never a recording on repeat:
 *
 *   "Owner, Praba-ku call panna vendiya neram aachu. Ippo call pannalama?"   (the screen's own words)
 *        … 6.5 s …  "Praba-ku call pannunga Owner."
 *        … 9 s …    "Seekiram pannunga Owner."
 *        … 11 s …   "Owner, Praba-ku call pannalama?"
 *        … 13 s …   "Praba-ku call panna marakkadheenga Owner."
 *        … 15 s …   "Owner, idha ippo mudichidalaama?"
 *        … then the follow-ups again, 18 s apart — never the same line twice in a row.
 *
 * Pauses are counted from the end of the previous line. Call / Done / Snooze end it at once
 * with one short answer. Plain rules, no AI.
 */
object KaiUrgentVoiceScript {

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) {
        KaiLang.TAMIL -> ta
        KaiLang.TANGLISH -> tl
        KaiLang.ENGLISH -> en
    }

    fun languageCode(lang: KaiLang) = if (lang == KaiLang.ENGLISH) "en-IN" else "ta-IN"

    /** The lines in order: [0] is the screen's own words, then the follow-ups. */
    fun lines(r: KaiReminder, lang: KaiLang = r.lang): List<VoiceLine> {
        val opening = VoiceLine(KaiUrgentWords.text(r, lang).speech, gesture = true)
        val p = r.person?.takeIf { it.isNotBlank() }
        val soon = VoiceLine(pick(lang, ta = "சீக்கிரம் பண்ணுங்க ஓனர்.", tl = "Seekiram pannunga Owner.", en = "Let's do it soon, Owner."), gesture = false)
        val finish = VoiceLine(pick(lang, ta = "ஓனர், இதை இப்போ முடிச்சிடலாமா?", tl = "Owner, idha ippo mudichidalaama?", en = "Owner, shall we finish this now?"), gesture = true)
        val followUps = when {
            r.action == ReminderAction.CALL && p != null -> listOf(
                VoiceLine(pick(lang, ta = "$p-க்கு call பண்ணுங்க ஓனர்.", tl = "$p-ku call pannunga Owner.", en = "Please call $p, Owner."), gesture = true),
                soon,
                VoiceLine(pick(lang, ta = "ஓனர், $p-க்கு call பண்ணலாமா?", tl = "Owner, $p-ku call pannalama?", en = "Owner, shall we call $p now?"), gesture = true),
                VoiceLine(pick(lang, ta = "$p-க்கு call பண்ண மறக்காதீங்க ஓனர்.", tl = "$p-ku call panna marakkadheenga Owner.", en = "Don't forget to call $p, Owner."), gesture = false),
                finish,
            )
            r.action == ReminderAction.MESSAGE && p != null -> listOf(
                VoiceLine(pick(lang, ta = "$p-க்கு message அனுப்புங்க ஓனர்.", tl = "$p-ku message pannunga Owner.", en = "Please message $p, Owner."), gesture = true),
                soon,
                VoiceLine(pick(lang, ta = "ஓனர், $p-க்கு message அனுப்பலாமா?", tl = "Owner, $p-ku message pannalama?", en = "Owner, shall we message $p now?"), gesture = true),
                VoiceLine(pick(lang, ta = "$p-க்கு message பண்ண மறக்காதீங்க ஓனர்.", tl = "$p-ku message panna marakkadheenga Owner.", en = "Don't forget to message $p, Owner."), gesture = false),
                finish,
            )
            else -> listOf(
                VoiceLine(pick(lang, ta = "ஓனர், இதை முடிச்சிடுங்க.", tl = "Owner, idha mudichidunga.", en = "Owner, please take care of this."), gesture = true),
                soon,
                VoiceLine(pick(lang, ta = "ஓனர், reminder இன்னும் pending-ல இருக்கு.", tl = "Owner, reminder innum pending-la irukku.", en = "Owner, this reminder is still pending."), gesture = false),
                VoiceLine(pick(lang, ta = "மறக்காதீங்க ஓனர்.", tl = "Marakkadheenga Owner.", en = "Don't forget, Owner."), gesture = false),
                finish,
            )
        }
        return listOf(opening) + followUps
    }

    /** Which line the [n]th utterance is: the opening once, then the follow-ups in a cycle. */
    fun lineAt(n: Int, size: Int): Int = if (n < size) n else 1 + (n - 1) % (size - 1)

    /** The pause before the [n]th utterance (after the previous one ended), ms — longer as it goes on, capped. */
    fun pauseBefore(n: Int): Long = when (n) {
        0 -> 0L
        1 -> 6_500L
        2 -> 9_000L
        3 -> 11_000L
        4 -> 13_000L
        5 -> 15_000L
        else -> 18_000L
    }

    // ------------------------------------------------------------------ the owner acted: one short answer

    /** Call Now — before the dialer opens. Never "called": only that the call screen is opening. */
    fun callAck(r: KaiReminder, lang: KaiLang = r.lang): String {
        val p = r.person?.takeIf { it.isNotBlank() }
        return if (p != null) pick(lang, ta = "சரி ஓனர், $p-க்கு call screen திறக்குறேன்.", tl = "Seri Owner, $p-ku call screen open pannuren.", en = "Okay Owner, opening the call screen for $p.")
        else pick(lang, ta = "சரி ஓனர், call screen திறக்குறேன்.", tl = "Seri Owner, call screen open pannuren.", en = "Okay Owner, opening the call screen.")
    }

    fun doneAck(lang: KaiLang) = pick(lang, ta = "சரி ஓனர்.", tl = "Seri Owner.", en = "Okay Owner.")

    fun snoozeAck(minutes: Long, lang: KaiLang) =
        pick(lang, ta = "சரி ஓனர், $minutes நிமிஷம் கழிச்சு நினைவூட்டுறேன்.", tl = "Seri Owner, $minutes minutes-ku remind pannuren.", en = "Okay Owner, I'll remind you in $minutes minutes.")

    // ------------------------------------------------------------------ the screen's compact controls

    data class Controls(val call: String, val done: String, val snooze: String)

    fun controls(minutes: Long, lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> Controls(call = "இப்போ Call", done = "முடிஞ்சது", snooze = "$minutes நிமி கழிச்சு")
        KaiLang.TANGLISH, KaiLang.ENGLISH -> Controls(call = "Call now", done = "Done", snooze = "Snooze $minutes min")
    }
}
