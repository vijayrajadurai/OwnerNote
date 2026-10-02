package com.shopai.app.brain

import com.shopai.app.brain.KaiFormat.date
import com.shopai.app.brain.KaiFormat.rupees
import com.shopai.app.brain.KaiFormat.spokenAmount
import java.time.LocalDate
import java.time.LocalTime

/** Kai's mood while giving a reply (the character's reaction after speaking). */
enum class KaiMood { NEUTRAL, CREDIT, DEBIT, SUCCESS, SERIOUS, EXPLAINING, CLARIFY, REMINDER, HAPPY, CONCERNED, SURPRISED, ERROR }

/**
 * One reply from Kai: what he shows ([display], in the owner's language)
 * and what he says ([speech]) — built together from the same facts, so the
 * text and the voice can never disagree. Tamil and Tanglish are spoken in
 * Tamil script (the Tamil voice can't read Tanglish); English in English.
 */
data class KaiReply(
    val display: String,
    val speech: String,
    val speechLanguage: String,
    val mood: KaiMood,
)

/**
 * Kai's voice: short, clear, natural, "Owner" — like a trusted accountant.
 * Every amount, name and date comes from the facts passed in.
 */
object KaiResponder {

    private fun reply(lang: KaiLang, mood: KaiMood, tamil: String, tanglish: String, english: String) = KaiReply(
        display = when (lang) { KaiLang.TAMIL -> tamil; KaiLang.TANGLISH -> tanglish; KaiLang.ENGLISH -> english },
        speech = if (lang == KaiLang.ENGLISH) english else tamil,
        speechLanguage = if (lang == KaiLang.ENGLISH) "en-IN" else "ta-IN",
        mood = mood,
    )

    private fun ta(amount: Double) = "${spokenAmount(amount)} ரூபாய்"

    // ----------------------------------------------------------- recording

    /** Before saving a spoken entry: what Kai understood, for the owner to confirm. */
    fun confirm(r: KaiIntent.Record, lang: KaiLang, today: LocalDate): KaiReply {
        val name = r.person!!
        val amount = r.amount!!
        val dueTa = r.dueDate?.let { " ${date(it, KaiLang.TAMIL, today)} due." }.orEmpty()
        val dueTl = r.dueDate?.let { " ${date(it, KaiLang.TANGLISH, today)} due." }.orEmpty()
        val dueEn = r.dueDate?.let { ", due ${date(it, KaiLang.ENGLISH, today)}" }.orEmpty()
        return if (r.direction == Direction.RECEIVABLE) {
            reply(
                lang, KaiMood.CREDIT,
                "ஓனர், $name கிட்ட ${ta(amount)} வாங்கணும்.$dueTa சேவ் பண்ணவா?",
                "Owner, $name kitta ${rupees(amount)} receive pannanum.$dueTl Save pannava?",
                "Owner, $name owes you ${rupees(amount)}$dueEn. Shall I save it?",
            )
        } else {
            reply(
                lang, KaiMood.DEBIT,
                "ஓனர், $name-க்கு ${ta(amount)} கொடுக்கணும்.$dueTa சேவ் பண்ணவா?",
                "Owner, $name-ku ${rupees(amount)} kudukkanum.$dueTl Save pannava?",
                "Owner, you owe $name ${rupees(amount)}$dueEn. Shall I save it?",
            )
        }
    }

    /** Asks only for what is missing — never fills it in. */
    fun clarify(r: KaiIntent.Record, lang: KaiLang): KaiReply = when {
        r.amountAmbiguous -> reply(
            lang, KaiMood.CLARIFY,
            "ஓனர், ரெண்டு தொகை கேட்டுச்சு. எந்த தொகை சேவ் பண்ணணும்?",
            "Owner, rendu amount kettuchu. Endha amount save pannanum?",
            "Owner, I heard two amounts. Which one should I save?",
        )
        r.person == null -> reply(
            lang, KaiMood.CLARIFY,
            "ஓனர், யாரு கிட்ட? பேர் சொல்லுங்க.",
            "Owner, yaaru kitta? Per sollunga.",
            "Owner, who is this for? Please tell me the name.",
        )
        r.amount == null -> reply(
            lang, KaiMood.CLARIFY,
            "ஓனர், ${r.person}-க்கு எவ்வளவு தொகை?",
            "Owner, ${r.person}-ku evlo amount?",
            "Owner, how much for ${r.person}?",
        )
        else -> reply(
            lang, KaiMood.CLARIFY,
            "ஓனர், ${r.person} உங்களுக்கு தரணுமா, இல்ல நீங்க ${r.person}-க்கு கொடுக்கணுமா?",
            "Owner, ${r.person} ungalukku tharanuma, illa neenga ${r.person}-ku kudukkanuma?",
            "Owner, does ${r.person} owe you, or do you owe ${r.person}?",
        )
    }

    /**
     * After an entry is saved (voice, manual form, bill or note), from the
     * saved values. [reminderSet] only when a reminder really exists.
     */
    fun recorded(
        name: String,
        amount: Double,
        direction: Direction,
        dueDate: LocalDate?,
        reminderSet: Boolean,
        lang: KaiLang,
        today: LocalDate,
    ): KaiReply {
        val dueTa = dueDate?.let { " ${date(it, KaiLang.TAMIL, today)} due." }.orEmpty()
        val dueTl = dueDate?.let { " ${date(it, KaiLang.TANGLISH, today)} due." }.orEmpty()
        val dueEn = dueDate?.let { ", due ${date(it, KaiLang.ENGLISH, today)}" }.orEmpty()
        val remTa = if (reminderSet) " நான் ரிமைண்டர் வெச்சிருக்கேன்." else ""
        val remTl = if (reminderSet) " Naan reminder vachiruken." else ""
        val remEn = if (reminderSet) " I've set a reminder." else ""
        return if (direction == Direction.RECEIVABLE) {
            reply(
                lang, KaiMood.SUCCESS,
                "ஓனர், $name கிட்ட ${ta(amount)} வாங்கணும்.$dueTa சேவ் பண்ணிட்டேன்.$remTa",
                "Owner, $name kitta ${rupees(amount)} receive panna pending irukku.$dueTl Save panniten.$remTl",
                "Owner, $name owes you ${rupees(amount)}$dueEn. Saved.$remEn",
            )
        } else {
            reply(
                lang, KaiMood.SUCCESS,
                "ஓனர், $name-க்கு ${ta(amount)} கொடுக்கணும்.$dueTa சேவ் பண்ணிட்டேன்.$remTa",
                "Owner, $name-ku ${rupees(amount)} kudukkanum.$dueTl Save panniten.$remTl",
                "Owner, you owe $name ${rupees(amount)}$dueEn. Saved.$remEn",
            )
        }
    }

    /** After a payment is recorded (collection from a customer / payment to a supplier), from the saved transaction. */
    fun paymentRecorded(name: String, paid: Double, stillPending: Double, direction: Direction, lang: KaiLang): KaiReply {
        val settled = stillPending <= 0.005
        return if (direction == Direction.RECEIVABLE) {
            reply(
                lang, KaiMood.HAPPY,
                "ஓனர், $name கிட்ட இருந்து ${ta(paid)} வந்துச்சு. " + if (settled) "முழுசா செட்டில் ஆயிடுச்சு." else "இன்னும் ${ta(stillPending)} பாக்கி.",
                "Owner, $name kitta irundhu ${rupees(paid)} vandhuchu. " + if (settled) "Full-ah settle aayiduchu." else "Innum ${rupees(stillPending)} pending.",
                "Owner, received ${rupees(paid)} from $name. " + if (settled) "Fully settled." else "${rupees(stillPending)} still pending.",
            )
        } else {
            reply(
                lang, KaiMood.SUCCESS,
                "ஓனர், $name-க்கு ${ta(paid)} கொடுத்தாச்சு. " + if (settled) "முழுசா செட்டில் ஆயிடுச்சு." else "இன்னும் ${ta(stillPending)} கொடுக்கணும்.",
                "Owner, $name-ku ${rupees(paid)} kuduthachu. " + if (settled) "Full-ah settle aayiduchu." else "Innum ${rupees(stillPending)} kudukkanum.",
                "Owner, paid ${rupees(paid)} to $name. " + if (settled) "Fully settled." else "${rupees(stillPending)} still to pay.",
            )
        }
    }

    /** After the owner creates a reminder, from the saved reminder. */
    fun reminderSet(title: String, dueDate: LocalDate, lang: KaiLang, today: LocalDate): KaiReply = reply(
        lang, KaiMood.REMINDER,
        "ஓனர், \"$title\" — ${date(dueDate, KaiLang.TAMIL, today)} ரிமைண்டர் வெச்சிருக்கேன்.",
        "Owner, \"$title\" — ${date(dueDate, KaiLang.TANGLISH, today)} reminder vachiruken.",
        "Owner, I've set a reminder for \"$title\" — ${date(dueDate, KaiLang.ENGLISH, today)}.",
    )

    // ------------------------------------------------------------- answers

    fun personBalance(name: String, matches: List<PartyFacts>, lang: KaiLang, today: LocalDate): KaiReply {
        if (matches.isEmpty()) {
            return reply(
                lang, KaiMood.CLARIFY,
                "ஓனர், $name-னு யாரும் உங்க கணக்குல இல்ல.",
                "Owner, $name-nu yaarum unga kanakku-la illa.",
                "Owner, there's no $name in your accounts.",
            )
        }
        if (matches.map { it.name }.distinct().size > 1) {
            val names = matches.map { it.name }.distinct().joinToString(", ")
            return reply(
                lang, KaiMood.CLARIFY,
                "ஓனர், யாரு? $names",
                "Owner, yaaru? $names",
                "Owner, which one? $names",
            )
        }
        val parts = matches.map { p -> balanceLine(p, lang, today) }
        val pending = matches.any { it.pending > 0.005 }
        return reply(
            lang, if (!pending) KaiMood.NEUTRAL else if (matches.any { it.side == Direction.PAYABLE }) KaiMood.DEBIT else KaiMood.CREDIT,
            parts.joinToString(" ") { it.first },
            parts.joinToString(" ") { it.second },
            parts.joinToString(" ") { it.third },
        )
    }

    private fun balanceLine(p: PartyFacts, lang: KaiLang, today: LocalDate): Triple<String, String, String> {
        if (p.pending <= 0.005) {
            return Triple(
                "ஓனர், ${p.name} கிட்ட இப்போ பாக்கி எதுவும் இல்ல.",
                "Owner, ${p.name} kitta ippo pending edhuvum illa.",
                "Owner, nothing is pending with ${p.name} now.",
            )
        }
        val dueTa = p.nextDue?.let { " ${date(it, KaiLang.TAMIL, today)} due." }.orEmpty()
        val dueTl = p.nextDue?.let { " ${date(it, KaiLang.TANGLISH, today)} due." }.orEmpty()
        val dueEn = p.nextDue?.let { ", due ${date(it, KaiLang.ENGLISH, today)}" }.orEmpty()
        return if (p.side == Direction.RECEIVABLE) Triple(
            "ஓனர், ${p.name} கிட்ட ${ta(p.pending)} பாக்கி இருக்கு.$dueTa",
            "Owner, ${p.name} kitta ${rupees(p.pending)} pending irukku.$dueTl",
            "Owner, ${p.name} owes you ${rupees(p.pending)}$dueEn.",
        ) else Triple(
            "ஓனர், ${p.name}-க்கு ${ta(p.pending)} கொடுக்கணும்.$dueTa",
            "Owner, ${p.name}-ku ${rupees(p.pending)} kudukkanum.$dueTl",
            "Owner, you owe ${p.name} ${rupees(p.pending)}$dueEn.",
        )
    }

    fun history(h: PartyHistory, lastPaymentOnly: Boolean, lang: KaiLang, today: LocalDate): KaiReply {
        val name = h.party.name
        val last = h.lastPayment
        val lastTa = last?.let { "கடைசி பேமெண்ட் ${ta(it.amount)}, ${date(it.date!!, KaiLang.TAMIL, today)}." } ?: "இதுவரை பேமெண்ட் எதுவும் வரல."
        val lastTl = last?.let { "Last payment ${rupees(it.amount)}, ${date(it.date!!, KaiLang.TANGLISH, today)}." } ?: "Idhuvarai payment edhuvum varala."
        val lastEn = last?.let { "Last payment ${rupees(it.amount)} on ${date(it.date!!, KaiLang.ENGLISH, today)}." } ?: "No payments yet."
        if (lastPaymentOnly) {
            return reply(lang, KaiMood.EXPLAINING, "ஓனர், $name — $lastTa", "Owner, $name — $lastTl", "Owner, $name — $lastEn")
        }
        val (pTa, pTl, pEn) = balanceLine(h.party, lang, today)
        val n = h.entries.size
        return reply(
            lang, KaiMood.EXPLAINING,
            "$pTa மொத்தம் $n entry, ${ta(h.totalBilled)}; வந்தது ${ta(h.totalPaid)}. $lastTa",
            "$pTl Mothama $n entry, ${rupees(h.totalBilled)}; vandhadhu ${rupees(h.totalPaid)}. $lastTl",
            "$pEn $n entries totalling ${rupees(h.totalBilled)}; ${rupees(h.totalPaid)} paid. $lastEn",
        )
    }

    fun whoOwesMe(s: BusinessSnapshot, lang: KaiLang, today: LocalDate): KaiReply {
        val list = s.owesMe()
        if (list.isEmpty()) {
            return reply(lang, KaiMood.NEUTRAL, "ஓனர், இப்போ யாரும் பாக்கி இல்ல.", "Owner, ippo yaarum pending illa.", "Owner, nobody owes you right now.")
        }
        val top = list.take(3)
        return reply(
            lang, KaiMood.CREDIT,
            "ஓனர், மொத்தம் ${ta(s.totalReceivable())} வசூல் பண்ணணும். " + top.joinToString(", ") { "${it.name} ${ta(it.pending)}${dueTa(it, today)}" } + ".",
            "Owner, mothama ${rupees(s.totalReceivable())} collect pannanum. " + top.joinToString(", ") { "${it.name} ${rupees(it.pending)}${dueTl(it, today)}" } + ".",
            "Owner, ${rupees(s.totalReceivable())} to collect in all. " + top.joinToString(", ") { "${it.name} ${rupees(it.pending)}${dueEn(it, today)}" } + ".",
        )
    }

    fun whomDoIOwe(s: BusinessSnapshot, lang: KaiLang, today: LocalDate): KaiReply {
        val list = s.iOwe()
        if (list.isEmpty()) {
            return reply(lang, KaiMood.NEUTRAL, "ஓனர், நீங்க யாருக்கும் கொடுக்க வேண்டியது இல்ல.", "Owner, neenga yaarukkum kudukka vendiyadhu illa.", "Owner, you don't owe anyone right now.")
        }
        val top = list.take(3)
        return reply(
            lang, KaiMood.DEBIT,
            "ஓனர், மொத்தம் ${ta(s.totalPayable())} கொடுக்கணும். " + top.joinToString(", ") { "${it.name} ${ta(it.pending)}${dueTa(it, today)}" } + ".",
            "Owner, mothama ${rupees(s.totalPayable())} kudukkanum. " + top.joinToString(", ") { "${it.name} ${rupees(it.pending)}${dueTl(it, today)}" } + ".",
            "Owner, you owe ${rupees(s.totalPayable())} in all. " + top.joinToString(", ") { "${it.name} ${rupees(it.pending)}${dueEn(it, today)}" } + ".",
        )
    }

    fun dueToday(s: BusinessSnapshot, lang: KaiLang, today: LocalDate): KaiReply {
        val due = s.dueOn(today)
        val overdue = s.overdue(today)
        if (due.isEmpty() && overdue.isEmpty()) {
            return reply(lang, KaiMood.NEUTRAL, "ஓனர், இன்னைக்கு எந்த பேமெண்ட்டும் due இல்ல.", "Owner, innaikku endha payment-um due illa.", "Owner, nothing is due today.")
        }
        fun line(p: PartyFacts, l: KaiLang) = when (l) {
            KaiLang.TAMIL -> if (p.side == Direction.RECEIVABLE) "${p.name} கிட்ட ${ta(p.pending)} வாங்கணும்" else "${p.name}-க்கு ${ta(p.pending)} கொடுக்கணும்"
            KaiLang.TANGLISH -> if (p.side == Direction.RECEIVABLE) "${p.name} kitta ${rupees(p.pending)} vanganum" else "${p.name}-ku ${rupees(p.pending)} kudukkanum"
            KaiLang.ENGLISH -> if (p.side == Direction.RECEIVABLE) "collect ${rupees(p.pending)} from ${p.name}" else "pay ${p.name} ${rupees(p.pending)}"
        }
        val od = overdue.size
        return reply(
            lang, if (od > 0) KaiMood.SERIOUS else KaiMood.REMINDER,
            "ஓனர், இன்னைக்கு due: " + (due.joinToString(", ") { line(it, KaiLang.TAMIL) }.ifEmpty { "எதுவும் இல்ல" }) + "." + if (od > 0) " $od பேமெண்ட் தேதி தாண்டிடுச்சு." else "",
            "Owner, innaikku due: " + (due.joinToString(", ") { line(it, KaiLang.TANGLISH) }.ifEmpty { "edhuvum illa" }) + "." + if (od > 0) " $od payment date thaandiduchu." else "",
            "Owner, due today: " + (due.joinToString(", ") { line(it, KaiLang.ENGLISH) }.ifEmpty { "nothing" }) + "." + if (od > 0) " $od payment(s) are overdue." else "",
        )
    }

    fun totalPending(s: BusinessSnapshot, lang: KaiLang): KaiReply {
        val r = s.totalReceivable()
        val p = s.totalPayable()
        return reply(
            lang, KaiMood.EXPLAINING,
            "ஓனர், நீங்க வாங்க வேண்டியது ${ta(r)}. கொடுக்க வேண்டியது ${ta(p)}.",
            "Owner, neenga vaanga vendiyadhu ${rupees(r)}. Kudukka vendiyadhu ${rupees(p)}.",
            "Owner, you have ${rupees(r)} to collect and ${rupees(p)} to pay.",
        )
    }

    fun weekCollection(s: BusinessSnapshot, lang: KaiLang, today: LocalDate): KaiReply {
        val c = s.collectionsNext7Days(today)
        return reply(
            lang, KaiMood.EXPLAINING,
            "ஓனர், இந்த வாரம் ${ta(c)} வசூல் வரணும்.",
            "Owner, indha week ${rupees(c)} collection varanum.",
            "Owner, ${rupees(c)} is due to come in this week.",
        )
    }

    /** Home briefing — only from the owner's real data. */
    fun briefing(s: BusinessSnapshot, lang: KaiLang, today: LocalDate, now: LocalTime = LocalTime.now()): KaiReply {
        val (gTa, gTl, gEn) = when (now.hour) {
            in 5..11 -> Triple("காலை வணக்கம் ஓனர்.", "Good morning Owner.", "Good morning Owner.")
            in 12..16 -> Triple("மதிய வணக்கம் ஓனர்.", "Vanakkam Owner.", "Good afternoon Owner.")
            else -> Triple("மாலை வணக்கம் ஓனர்.", "Good evening Owner.", "Good evening Owner.")
        }
        val collect = (s.dueOn(today) + s.dueOn(today.plusDays(1)) + s.overdue(today))
            .filter { it.side == Direction.RECEIVABLE }.distinctBy { it.id }.sortedBy { it.nextDue }
        val pay = s.iOwe().filter { it.nextDue != null && !it.nextDue.isAfter(today.plusDays(1)) }
        if (collect.isEmpty() && pay.isEmpty()) {
            return reply(
                lang, KaiMood.NEUTRAL,
                "$gTa இன்னைக்கு அவசரம் எதுவும் இல்ல.",
                "$gTl Innaikku avasaram edhuvum illa.",
                "$gEn Nothing urgent today.",
            )
        }
        val c = collect.take(2)
        val first = collect.firstOrNull()
        val payTotal = pay.sumOf { it.pending }
        return reply(
            lang, if (s.overdue(today).isNotEmpty()) KaiMood.CONCERNED else KaiMood.EXPLAINING,
            buildString {
                append(gTa)
                if (collect.isNotEmpty()) append(" ${collect.size} முக்கிய வசூல் இருக்கு. " + c.joinToString(". ") { "${it.name} ${ta(it.pending)}${dueTa(it, today)}" } + ".")
                if (pay.isNotEmpty()) append(" சப்ளையர் பேமெண்ட் ${ta(payTotal)} பாக்கி.")
                first?.let { append(" முதல்ல ${it.name} கிட்ட கேளுங்க.") }
            },
            buildString {
                append(gTl)
                if (collect.isNotEmpty()) append(" Innaikku ${collect.size} important collection irukku. " + c.joinToString(". ") { "${it.name} ${rupees(it.pending)}${dueTl(it, today)}" } + ".")
                if (pay.isNotEmpty()) append(" Supplier payment ${rupees(payTotal)} pending.")
                first?.let { append(" First ${it.name}-kitta follow-up pannunga.") }
            },
            buildString {
                append(gEn)
                if (collect.isNotEmpty()) append(" ${collect.size} important collection(s) today. " + c.joinToString(". ") { "${it.name} ${rupees(it.pending)}${dueEn(it, today)}" } + ".")
                if (pay.isNotEmpty()) append(" Supplier payments ${rupees(payTotal)} pending.")
                first?.let { append(" Follow up with ${it.name} first.") }
            },
        )
    }

    /** An answer from the server's Ask-my-business, shown and spoken as it came. */
    fun serverAnswer(answer: String, lang: KaiLang): KaiReply = KaiReply(
        display = answer,
        speech = answer,
        speechLanguage = if (lang == KaiLang.ENGLISH && answer.none { it in '஀'..'௿' }) "en-IN" else "ta-IN",
        mood = KaiMood.EXPLAINING,
    )

    fun didNotUnderstand(lang: KaiLang): KaiReply = reply(
        lang, KaiMood.CLARIFY,
        "சரியா புரியல ஓனர், இன்னொரு தடவை சொல்லுங்க.",
        "Sariya puriyala Owner, innoru thadava sollunga.",
        "Sorry Owner, I didn't catch that. Please say it again.",
    )

    fun couldNotLoad(lang: KaiLang): KaiReply = reply(
        lang, KaiMood.ERROR,
        "ஓனர், உங்க கணக்கை இப்போ பார்க்க முடியல. நெட் செக் பண்ணுங்க.",
        "Owner, unga kanakku ippo paakka mudiyala. Net check pannunga.",
        "Owner, I can't reach your accounts right now. Please check the internet.",
    )

    // ------------------------------------------------------------- photos

    /** After reading a printed bill: what Kai found, and what to check. */
    fun billRead(shop: String?, total: Double?, totalSure: Boolean, lang: KaiLang): KaiReply = when {
        total == null -> reply(
            lang, KaiMood.CLARIFY,
            "ஓனர், பில்லில் மொத்த தொகை தெளிவா இல்ல. தொகையை டைப் பண்ணுங்க.",
            "Owner, bill-la total clear-ah illa. Amount type pannunga.",
            "Owner, I couldn't read the bill total. Please type the amount.",
        )
        !totalSure -> reply(
            lang, KaiMood.CLARIFY,
            "ஓனர், மொத்தம் ${ta(total)}-ஆ? சரிபாருங்க.",
            "Owner, total ${rupees(total)}-aa? Confirm pannunga.",
            "Owner, is the total ${rupees(total)}? Please confirm.",
        )
        else -> reply(
            lang, KaiMood.EXPLAINING,
            "ஓனர், ${shop ?: "பில்"} — மொத்தம் ${ta(total)}. சரிபார்த்து சேவ் பண்ணுங்க.",
            "Owner, ${shop ?: "bill"} — total ${rupees(total)}. Check panni save pannunga.",
            "Owner, ${shop ?: "the bill"} — total ${rupees(total)}. Check and save.",
        )
    }

    /** After reading a handwritten note: how many entries are ready and how many need a check. */
    fun noteRead(ready: Int, needCheck: Int, notRead: Int, lang: KaiLang): KaiReply = when {
        ready == 0 && needCheck == 0 -> reply(
            lang, KaiMood.CLARIFY,
            "ஓனர், குறிப்பை தெளிவா படிக்க முடியல. ஒவ்வொரு வரியையும் பார்த்து டைப் பண்ணுங்க.",
            "Owner, note clear-ah read aagala. Ovvoru line-aiyum paathu type pannunga.",
            "Owner, I couldn't read the note clearly. Please check each line and type it.",
        )
        needCheck > 0 -> reply(
            lang, KaiMood.CLARIFY,
            "ஓனர், ${ready + needCheck} entry கிடைச்சுது. $needCheck-ல ஏதோ தெளிவா இல்ல — சரிபாருங்க.",
            "Owner, ${ready + needCheck} entry kidaichuchu. $needCheck-la edho clear-ah illa — confirm pannunga.",
            "Owner, I found ${ready + needCheck} entries. $needCheck need a quick check.",
        )
        else -> reply(
            lang, KaiMood.SUCCESS,
            "ஓனர், $ready entry ரெடி." + if (notRead > 0) " $notRead வரி படிக்க முடியல." else "",
            "Owner, $ready entry ready." + if (notRead > 0) " $notRead line read aagala." else "",
            "Owner, $ready entries are ready." + if (notRead > 0) " $notRead line(s) couldn't be read." else "",
        )
    }

    /** After photo entries are saved. */
    fun photoSaved(count: Int, lang: KaiLang): KaiReply = reply(
        lang, KaiMood.SUCCESS,
        "ஓனர், $count entry சேவ் பண்ணிட்டேன்.",
        "Owner, $count entry save panniten.",
        "Owner, saved $count entr${if (count == 1) "y" else "ies"}.",
    )

    private fun dueTa(p: PartyFacts, today: LocalDate) = p.nextDue?.let { " — ${date(it, KaiLang.TAMIL, today)}" }.orEmpty()
    private fun dueTl(p: PartyFacts, today: LocalDate) = p.nextDue?.let { " — ${date(it, KaiLang.TANGLISH, today)}" }.orEmpty()
    private fun dueEn(p: PartyFacts, today: LocalDate) = p.nextDue?.let { " (due ${date(it, KaiLang.ENGLISH, today)})" }.orEmpty()
}
