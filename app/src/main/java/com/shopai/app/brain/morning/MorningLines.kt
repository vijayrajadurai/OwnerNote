package com.shopai.app.brain.morning

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One thing Kai says in Morning Work: [display] in the owner's language,
 * [speech] for Kai's voice (Tamil and Tanglish are spoken in Tamil script —
 * the Tamil voice can't read Tanglish — English in English). Same facts,
 * so the text and the voice never disagree.
 */
data class MorningLine(val display: String, val speech: String, val speechLanguage: String, val mood: KaiMood) {
    operator fun plus(other: MorningLine): MorningLine = when {
        display.isEmpty() -> other
        other.display.isEmpty() -> this
        else -> MorningLine("$display\n${other.display}", "$speech ${other.speech}", speechLanguage, other.mood)
    }

    companion object {
        val EMPTY = MorningLine("", "", "ta-IN", KaiMood.NEUTRAL)
    }
}

/** Kai's Morning Work lines — short, friendly, every number from the records. */
internal object MorningLines {

    private fun line(lang: KaiLang, mood: KaiMood, ta: String, tl: String, en: String) = MorningLine(
        display = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en },
        speech = noEmoji(if (lang == KaiLang.ENGLISH) en else ta),
        speechLanguage = if (lang == KaiLang.ENGLISH) "en-IN" else "ta-IN",
        mood = mood,
    )

    private fun noEmoji(s: String) = s.replace(Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}✓]"""), "").replace(Regex(""" {2,}"""), " ").trim()

    private fun r(a: Double) = KaiFormat.rupees(a)
    private fun said(a: Double) = "${KaiFormat.spokenAmount(a)} ரூபாய்"
    private fun qty(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')

    fun greetWord(hour: Int, lang: KaiLang): String = when {
        hour < 12 -> when (lang) { KaiLang.TAMIL -> "காலை வணக்கம்"; else -> "Good morning" }
        hour < 17 -> when (lang) { KaiLang.TAMIL -> "மதிய வணக்கம்"; else -> "Good afternoon" }
        else -> when (lang) { KaiLang.TAMIL -> "மாலை வணக்கம்"; else -> "Good evening" }
    }

    fun greeting(count: Int, hour: Int, lang: KaiLang): MorningLine {
        val g = greetWord(hour, lang)
        val gTa = greetWord(hour, KaiLang.TAMIL)
        if (count == 0) {
            return line(lang, KaiMood.HAPPY,
                ta = "$gTa ஓனர் 👋 இன்னைக்கு முக்கியமான வேலை எதுவும் இல்ல. எல்லாம் clear!",
                tl = "$g Owner 👋 Innaiku important work edhuvum illa. Ellam clear!",
                en = "$g Owner 👋 Nothing important needs your attention today. All clear!")
        }
        val plural = if (count == 1) "task" else "tasks"
        return line(lang, KaiMood.EXPLAINING,
            ta = "$gTa ஓனர் 👋 இன்னைக்கு $count முக்கியமான வேலை இருக்கு. ஆரம்பிக்கலாமா?",
            tl = "$g Owner 👋 Innaiku $count important $plural irukku. Start pannalama?",
            en = "$g Owner 👋 You have $count important $plural today. Shall we start?")
    }

    fun offlineNote(lang: KaiLang) = line(lang, KaiMood.CONCERNED,
        ta = "கடைசியா sync ஆன business தகவல் காட்டுறேன்.",
        tl = "Last sync aana business information kaatuven.",
        en = "Showing last synced business information.")

    fun noData(lang: KaiLang) = line(lang, KaiMood.CONCERNED,
        ta = "உங்க business records-ல இதை சரிபார்க்க முடியல ஓனர்.",
        tl = "Owner, business records-la idha verify panna mudiyala.",
        en = "I couldn't verify this from your business records.")

    private fun dueText(t: MorningTask, today: LocalDate, lang: KaiLang): Triple<String, String, String>? {
        val due = t.dueDate ?: return null
        val late = today.toEpochDay() - due.toEpochDay()
        return when {
            late > 0 -> Triple("$late நாள் லேட்", "$late naal late", if (late == 1L) "1 day overdue" else "$late days overdue")
            late == 0L -> Triple("இன்னைக்கு due", "innaikku due", "due today")
            else -> Triple("${KaiFormat.date(due, KaiLang.TAMIL, today)} due", "${KaiFormat.date(due, KaiLang.TANGLISH, today)} due", "due ${KaiFormat.date(due, KaiLang.ENGLISH, today)}")
        }
    }

    /** "1 OF 6 — Kumar: ₹12,000 pending, due today. Call, WhatsApp, or remind later?" */
    fun prompt(t: MorningTask, position: Int, total: Int, first: Boolean, today: LocalDate, lang: KaiLang): MorningLine {
        val n = t.title
        val a = t.facts.amount ?: 0.0
        val d = dueText(t, today, lang)
        val headTa = if (first) "முதல்ல" else "அடுத்து"
        val headTl = if (first) "First" else "Next"
        val headEn = if (first) "First," else "Next,"
        val body = when (t.taskType) {
            MorningTaskType.COLLECT_PAYMENT -> line(lang, KaiMood.CREDIT,
                ta = "$headTa $n-கிட்ட ${said(a)} பாக்கி இருக்கு, ${d?.first.orEmpty()}. கால் பண்ணவா, WhatsApp அனுப்பவா, இல்ல அப்புறம் ஞாபகப்படுத்தவா?",
                tl = "$headTl $n-ku ${r(a)} pending irukku, ${d?.second.orEmpty()}. Call pannava, WhatsApp anuppava, illa later remind pannava?",
                en = "$headEn $n has ${r(a)} pending, ${d?.third.orEmpty()}. Call, WhatsApp, or remind you later?")
            MorningTaskType.PAYMENT_FOLLOWUP -> if (t.facts.partialBills > 0 && t.facts.paid != null) line(lang, KaiMood.CREDIT,
                ta = "$headTa $n ${said(t.facts.paid)} கொடுத்தாங்க, இன்னும் ${said(a)} பாக்கி. Follow-up பண்ணலாமா?",
                tl = "$headTl $n ${r(t.facts.paid)} kuduthutaanga, balance ${r(a)} pending. Follow-up pannalama?",
                en = "$headEn $n paid ${r(t.facts.paid)}; ${r(a)} is still pending. Follow up?")
            else line(lang, KaiMood.CREDIT,
                ta = "$headTa $n-கிட்ட ${said(a)} பாக்கி${d?.let { ", ${it.first}" } ?: ", due date இல்ல"}. Follow-up பண்ணலாமா?",
                tl = "$headTl $n-ku ${r(a)} pending${d?.let { ", ${it.second}" } ?: ", due date illa"}. Follow-up pannalama?",
                en = "$headEn $n has ${r(a)} pending${d?.let { ", ${it.third}" } ?: ", no due date"}. Follow up?")
            MorningTaskType.SUPPLIER_PAYMENT -> line(lang, KaiMood.DEBIT,
                ta = "$headTa $n-க்கு ${said(a)} பேமெண்ட், ${d?.first.orEmpty()}. பேமெண்ட் பாக்கலாமா, இல்ல அப்புறம் ஞாபகப்படுத்தவா?",
                tl = "$headTl $n-ku ${r(a)} payment ${d?.second.orEmpty()}. Payment paakalama, illa later remind pannava?",
                en = "$headEn ${r(a)} payment to $n is ${d?.third.orEmpty()}. View the payment, or remind you later?")
            MorningTaskType.LOW_STOCK -> {
                val u = t.facts.unit.orEmpty().lowercase(Locale.ROOT)
                val s = qty(t.facts.stock ?: 0.0)
                val level = t.facts.reorderLevel ?: t.facts.minimum
                val levelTa = level?.let { " (reorder level ${qty(it)} $u)" }.orEmpty()
                if ((t.facts.stock ?: 0.0) <= 0.0) line(lang, KaiMood.CONCERNED,
                    ta = "$headTa $n ஸ்டாக் தீர்ந்துடுச்சு. Purchase சேர்க்கலாமா?",
                    tl = "$headTl $n stock theerndhuduchu. Purchase add pannalama?",
                    en = "$headEn $n is out of stock. Add a purchase?")
                else line(lang, KaiMood.CONCERNED,
                    ta = "$headTa $n ஸ்டாக் $s $u தான் இருக்கு$levelTa. Purchase சேர்க்கலாமா?",
                    tl = "$headTl $n stock $s $u thaan irukku$levelTa. Purchase add pannalama?",
                    en = "$headEn $n stock is $s $u${level?.let { ", reorder level ${qty(it)} $u" }.orEmpty()}. Add a purchase?")
            }
            MorningTaskType.EXPIRY -> {
                val exp = t.facts.expiryDay?.let(LocalDate::ofEpochDay)
                val expired = exp != null && exp.isBefore(today)
                val b = t.facts.batchNo.orEmpty()
                if (expired) line(lang, KaiMood.SERIOUS,
                    ta = "$headTa $n batch $b காலாவதி ஆயிடுச்சு. ஸ்டாக் பாருங்க.",
                    tl = "$headTl $n batch $b expire aagiduchu. Stock paarunga.",
                    en = "$headEn $n batch $b has expired. Please check the stock.")
                else line(lang, KaiMood.CONCERNED,
                    ta = "$headTa $n batch $b ${exp?.let { KaiFormat.date(it, KaiLang.TAMIL, today) }.orEmpty()} காலாவதி ஆகும்.",
                    tl = "$headTl $n batch $b ${exp?.let { KaiFormat.date(it, KaiLang.TANGLISH, today) }.orEmpty()} expire aagum.",
                    en = "$headEn $n batch $b expires ${exp?.let { KaiFormat.date(it, KaiLang.ENGLISH, today) }.orEmpty()}.")
            }
            MorningTaskType.REMINDER -> line(lang, KaiMood.REMINDER,
                ta = "$headTa reminder: $n. முடிஞ்சதா?",
                tl = "$headTl reminder: $n. Mudinjidicha?",
                en = "$headEn reminder: $n. Is it done?")
            MorningTaskType.PENDING_DRAFT -> line(lang, KaiMood.EXPLAINING,
                ta = "$headTa ஒரு ${draftName(n, lang)} draft உங்க confirm-க்கு காத்திருக்கு. பாக்கலாமா?",
                tl = "$headTl oru ${draftName(n, lang)} draft unga confirm-ku wait pannudhu. Paakalama?",
                en = "$headEn a ${draftName(n, lang)} draft is waiting for your confirmation. Review it?")
        }
        val count = line(lang, body.mood, ta = "$position / $total —", tl = "$position of $total —", en = "$position of $total —")
        return MorningLine("${count.display} ${body.display}", body.speech, body.speechLanguage, body.mood)
    }

    private fun draftName(kind: String, lang: KaiLang): String {
        val k = kind.uppercase(Locale.ROOT)
        return when {
            k.contains("PAYMENT") -> if (lang == KaiLang.TAMIL) "பேமெண்ட்" else "payment"
            k.contains("PURCHASE") -> if (lang == KaiLang.TAMIL) "பர்சேஸ்" else "purchase"
            k.contains("SALE") || k.contains("INVOICE") -> if (lang == KaiLang.TAMIL) "பில்" else "bill"
            else -> if (lang == KaiLang.TAMIL) "entry" else "entry"
        }
    }

    fun doneNext(lang: KaiLang) = line(lang, KaiMood.SUCCESS, ta = "முடிஞ்சது ✓", tl = "Done Owner ✓", en = "Done ✓")

    fun skipped(lang: KaiLang) = line(lang, KaiMood.NEUTRAL, ta = "சரி, skip பண்ணிட்டேன்.", tl = "Sari, skip pannitten.", en = "Okay, skipped.")

    fun callReady(name: String, lang: KaiLang) = line(lang, KaiMood.NEUTRAL,
        ta = "சரி ஓனர். $name-க்கு கால் பண்ண ready.",
        tl = "Sure Owner. $name-ku call panna ready.",
        en = "Sure Owner. Ready to call $name.")

    fun noPhone(name: String, lang: KaiLang) = line(lang, KaiMood.CLARIFY,
        ta = "$name போன் நம்பர் பதிவுல இல்ல ஓனர்.",
        tl = "$name phone number available illa owner.",
        en = "$name's phone number isn't available, Owner.")

    /** The message for the customer (the owner can edit it before sending). */
    fun whatsAppMessage(name: String, amount: Double, lang: KaiLang): String = when (lang) {
        KaiLang.TAMIL -> "$name, ${r(amount)} பாக்கி இருக்கு. தயவு செஞ்சு செட்டில் பண்ணுங்க."
        KaiLang.TANGLISH -> "$name, ${r(amount)} pending irukku. Please settle pannunga."
        KaiLang.ENGLISH -> "$name, ${r(amount)} is pending. Please settle it."
    }

    fun whatsAppReady(name: String, lang: KaiLang) = line(lang, KaiMood.EXPLAINING,
        ta = "$name-க்கு WhatsApp message ready. மாத்தலாம், இல்ல Send அழுத்துங்க.",
        tl = "$name-ku WhatsApp message ready. Edit pannalam, illa Send pannunga.",
        en = "WhatsApp message for $name is ready. Edit it, or tap Send.")

    fun whatsAppOpened(lang: KaiLang) = line(lang, KaiMood.SUCCESS,
        ta = "WhatsApp-ல message ready. அங்க Send அழுத்துங்க.",
        tl = "WhatsApp-la message ready. Anga Send pannunga.",
        en = "The message is ready in WhatsApp. Tap Send there.")

    fun whatsAppOnlyCustomers(lang: KaiLang) = line(lang, KaiMood.CLARIFY,
        ta = "WhatsApp follow-up customer பாக்கிக்கு மட்டும் தான்.",
        tl = "WhatsApp follow-up customer pending-ku mattum thaan.",
        en = "WhatsApp follow-ups are for customer dues only.")

    fun cancelled(lang: KaiLang) = line(lang, KaiMood.NEUTRAL,
        ta = "சரி, cancel பண்ணிட்டேன். எதுவும் save ஆகல.", tl = "Sari, cancel pannitten. Edhuvum save aagala.", en = "Okay, cancelled. Nothing was saved.")

    fun askRemindWhen(lang: KaiLang) = line(lang, KaiMood.CLARIFY,
        ta = "எப்போ ஞாபகப்படுத்தணும்? 30 நிமிஷம், மதியம், இல்ல நாளைக்கு காலை?",
        tl = "Eppo remind pannanum? 30 minutes, after lunch, illa tomorrow morning?",
        en = "When should I remind you? In 30 minutes, after lunch, or tomorrow morning?")

    fun timeText(at: LocalDateTime, today: LocalDate, lang: KaiLang): String {
        val clock = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        return "${KaiFormat.date(at.toLocalDate(), lang, today)} $clock"
    }

    fun reminderSet(at: LocalDateTime, today: LocalDate, lang: KaiLang) = line(lang, KaiMood.REMINDER,
        ta = "சரி ஓனர், ${timeText(at, today, KaiLang.TAMIL)} ஞாபகப்படுத்தறேன்.",
        tl = "Sari Owner, ${timeText(at, today, KaiLang.TANGLISH)} remind panren.",
        en = "Okay Owner, I'll remind you ${timeText(at, today, KaiLang.ENGLISH)}.")

    fun reminderQueued(lang: KaiLang) = line(lang, KaiMood.REMINDER,
        ta = "இப்போ net இல்ல — net வந்ததும் reminder set ஆகும்.",
        tl = "Ippo net illa — net vandhathum reminder set aagum.",
        en = "You're offline — the reminder will be set once you're back online.")

    fun reminderFailed(lang: KaiLang) = line(lang, KaiMood.ERROR,
        ta = "Reminder set பண்ண முடியல. கொஞ்சம் அப்புறம் try பண்ணுங்க.",
        tl = "Reminder set panna mudiyala. Konjam apram try pannunga.",
        en = "I couldn't set the reminder. Please try again in a bit.")

    fun actionFailed(lang: KaiLang) = line(lang, KaiMood.ERROR,
        ta = "அதை open பண்ண முடியல ஓனர்.", tl = "Adha open panna mudiyala owner.", en = "I couldn't open that, Owner.")

    fun askAmount(name: String, outgoing: Boolean, lang: KaiLang) = if (outgoing) line(lang, KaiMood.CLARIFY,
        ta = "$name-க்கு எவ்வளவு பேமெண்ட் பண்ணணும்?", tl = "$name-ku evlo amount payment panna?", en = "How much should be paid to $name?")
    else line(lang, KaiMood.CLARIFY,
        ta = "$name-கிட்ட எவ்வளவு வாங்கினீங்க?", tl = "$name kitta evlo amount vaangineenga?", en = "How much did you receive from $name?")

    fun amountUnclear(name: String?, lang: KaiLang) = line(lang, KaiMood.CLARIFY,
        ta = "ஓனர், amount சரியா கேக்கல.${name?.let { " $it-க்கு எவ்வளவு?" } ?: " எவ்வளவு?"}",
        tl = "Owner, amount clear-ah kekala.${name?.let { " $it-ku evlo amount?" } ?: " Evlo amount?"}",
        en = "Owner, I didn't catch the amount clearly.${name?.let { " How much for $it?" } ?: " How much?"}")

    fun askWho(lang: KaiLang) = line(lang, KaiMood.CLARIFY,
        ta = "யாருக்கு பேமெண்ட்?", tl = "Yaaru-ku payment?", en = "Who is the payment for?")

    fun whichOne(names: List<String>, lang: KaiLang): MorningLine {
        val list = names.take(3).joinToString(when (lang) { KaiLang.ENGLISH -> " or "; KaiLang.TAMIL -> " இல்ல "; KaiLang.TANGLISH -> " illa " })
        return line(lang, KaiMood.CLARIFY, ta = "எந்த ஒன்னு? $list?", tl = "Yaaru? $list?", en = "Which one — $list?")
    }

    fun notFound(name: String, lang: KaiLang) = line(lang, KaiMood.CLARIFY,
        ta = "$name பதிவுல இல்ல ஓனர்.", tl = "$name records-la illa owner.", en = "$name isn't in your records, Owner.")

    fun paymentDraft(d: MorningPaymentDraft, lang: KaiLang) = if (d.outgoing) line(lang, KaiMood.DEBIT,
        ta = "${d.partyName}-க்கு ${said(d.amount)} பேமெண்ட் draft ready. Confirm பண்ணலாமா?",
        tl = "${d.partyName}-ku ${r(d.amount)} payment draft ready. Confirm pannalama?",
        en = "${r(d.amount)} payment to ${d.partyName} is ready to review. Confirm?")
    else line(lang, KaiMood.CREDIT,
        ta = "${d.partyName}-கிட்ட ${said(d.amount)} வந்ததா பதிவு பண்ண draft ready. Confirm பண்ணலாமா?",
        tl = "${d.partyName} kitta ${r(d.amount)} vandhadha record panna draft ready. Confirm pannalama?",
        en = "${r(d.amount)} received from ${d.partyName} is ready to review. Confirm?")

    fun confirmAgain(lang: KaiLang) = line(lang, KaiMood.CLARIFY,
        ta = "Confirm பண்ணலாமா? 'ஆமா' இல்ல 'வேண்டாம்' சொல்லுங்க.",
        tl = "Confirm pannalama? 'Yes' illa 'Cancel' sollunga.",
        en = "Shall I confirm? Say 'Yes' or 'Cancel'.")

    fun nothingToConfirm(lang: KaiLang) = line(lang, KaiMood.CLARIFY,
        ta = "Confirm பண்ண எதுவும் இல்ல ஓனர்.", tl = "Confirm panna edhuvum illa owner.", en = "There's nothing to confirm, Owner.")

    fun paymentDone(d: MorningPaymentDraft, lang: KaiLang) = if (d.outgoing) line(lang, KaiMood.SUCCESS,
        ta = "பேமெண்ட் ${said(d.amount)} confirm ஆச்சு ஓனர்.", tl = "Payment ${r(d.amount)} confirmed Owner.", en = "Payment of ${r(d.amount)} confirmed, Owner.")
    else line(lang, KaiMood.SUCCESS,
        ta = "${d.partyName}-கிட்ட ${said(d.amount)} வந்தது பதிவு ஆச்சு ஓனர்.",
        tl = "${d.partyName} kitta ${r(d.amount)} received-nu record aachu Owner.",
        en = "${r(d.amount)} received from ${d.partyName} is recorded, Owner.")

    fun paymentFailed(message: String?, lang: KaiLang) = line(lang, KaiMood.ERROR,
        ta = "பேமெண்ட் save ஆகல.${message?.let { " $it" }.orEmpty()}",
        tl = "Payment save aagala.${message?.let { " $it" }.orEmpty()}",
        en = "The payment wasn't saved.${message?.let { " $it" }.orEmpty()}")

    fun notAllowed(lang: KaiLang) = line(lang, KaiMood.SERIOUS,
        ta = "இந்த வேலைக்கு owner அனுமதி வேணும்.", tl = "Indha action-ku owner permission venum.", en = "This action needs the owner's permission.")

    fun opening(lang: KaiLang) = line(lang, KaiMood.NEUTRAL, ta = "திறக்கறேன்.", tl = "Open panren.", en = "Opening it.")

    fun modeSwitched(mode: ResponseMode, lang: KaiLang) = if (mode == ResponseMode.TEXT) {
        line(lang, KaiMood.NEUTRAL, ta = "சரி, இனி type-ல சொல்றேன்.", tl = "Sari, ini type-la solren.", en = "Okay, I'll reply in text now.")
    } else {
        line(lang, KaiMood.NEUTRAL, ta = "சரி, இனி voice-ல சொல்றேன்.", tl = "Sari, ini voice-la solren.", en = "Okay, I'll reply by voice now.")
    }

    fun unknown(lang: KaiLang) = line(lang, KaiMood.CLARIFY,
        ta = "ஓனர், புரியல. 'அடுத்து', 'skip', 'கால்', இல்ல 'அப்புறம் ஞாபகப்படுத்து' சொல்லுங்க.",
        tl = "Owner, puriyala. 'Next', 'Skip', 'Call', illa 'Remind later' sollunga.",
        en = "Sorry Owner, I didn't get that. Say 'Next', 'Skip', 'Call' or 'Remind later'.")

    fun complete(s: MorningSummary, lang: KaiLang): MorningLine {
        val partsEn = buildList {
            add("${s.completed} completed")
            if (s.postponed > 0) add("${s.postponed} postponed")
            if (s.skipped > 0) add("${s.skipped} skipped")
            if (s.failed > 0) add("${s.failed} failed")
            if (s.pending > 0) add("${s.pending} still pending")
        }.joinToString(", ")
        val partsTa = buildList {
            add("${s.completed} முடிஞ்சது")
            if (s.postponed > 0) add("${s.postponed} அப்புறம்")
            if (s.skipped > 0) add("${s.skipped} skip")
            if (s.failed > 0) add("${s.failed} fail")
            if (s.pending > 0) add("${s.pending} இன்னும் pending")
        }.joinToString(", ")
        return line(lang, KaiMood.HAPPY,
            ta = "🎉 Morning Work முடிஞ்சது! ${s.total} வேலை — $partsTa. இன்னைக்கு முக்கியமான வேலை எல்லாம் பாத்தாச்சு ஓனர்.",
            tl = "🎉 Morning Work Complete! ${s.total} tasks — $partsEn. Owner, innaiku important work ellam mudinjiduchu.",
            en = "🎉 Morning Work Complete! ${s.total} tasks — $partsEn. All important morning work is done, Owner.")
    }

    fun summary(s: MorningSummary, lang: KaiLang) = line(lang, KaiMood.EXPLAINING,
        ta = "இன்னைக்கு: வசூல் ${r(s.collections)}, பேமெண்ட் ${r(s.payments)}, ஸ்டாக் குறைவு ${s.lowStockProducts}, reminders ${s.reminders}, follow-ups ${s.followUps}.",
        tl = "Innaiku: Collections ${r(s.collections)}, Payments ${r(s.payments)}, Low stock ${s.lowStockProducts} products, Reminders ${s.reminders}, Pending follow-ups ${s.followUps}.",
        en = "Today: Collections ${r(s.collections)}, Payments ${r(s.payments)}, Low stock ${s.lowStockProducts} products, Reminders ${s.reminders}, Pending follow-ups ${s.followUps}.")

    fun allDoneAlready(lang: KaiLang) = line(lang, KaiMood.HAPPY,
        ta = "இன்னைக்கு எல்லா வேலையும் பாத்தாச்சு ஓனர்.", tl = "Innaiku ellaa task-um mudinjiduchu owner.", en = "All of today's tasks are handled, Owner.")
}
