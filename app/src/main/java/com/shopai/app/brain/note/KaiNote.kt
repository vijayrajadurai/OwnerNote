package com.shopai.app.brain.note

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.PlanKind
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Kai's hand-note (owner, 17 Oct 2026): while a draft waits for Confirm, Kai writes it into a notebook and a digital card follows
 * line by line. Both read this one note, built from the real draft — the money plan, the stock draft, the sale — never from
 * example text. The note never decides or saves anything; [KaiNoteFlow] only follows what Kai and the books really did.
 */

/** What Kai is doing, as the scene shows it. Only the state the app has really reached. */
enum class KaiNoteState {
    IDLE, LISTENING, UNDERSTANDING, CLARIFICATION_REQUIRED, DRAFT_READY, REVIEWING, SAVING, SAVE_SUCCESS, SAVE_ERROR, CANCELLED
}

/** The kind of entry a note is: its notebook title and icons. */
enum class KaiNoteKind { CREDIT, DEBIT, PAYMENT_IN, PAYMENT_OUT, STOCK_IN, STOCK_OUT, SALE, OTHER }

/** One notebook line / card row: "Kumar — ₹5,000 · Cash", "Rice — 5 kg · 40 kg → 45 kg". */
data class KaiNoteRow(val name: String, val value: String, val detail: String? = null)

/** Where the draft behind a note lives, so the note knows when it was saved or dropped. */
data class KaiNoteKey(val source: Source, val key: String) {
    enum class Source { PLAN, STOCK, GOODS, SALE }
}

data class KaiNote(
    /** The chat message whose card this note is. */
    val messageId: Long,
    val kind: KaiNoteKind,
    val title: String,
    val rows: List<KaiNoteRow>,
    val date: LocalDate,
    val lang: KaiLang,
    val keys: List<KaiNoteKey>,
)

/** A stock line of a draft, as the notebook writes it (read from the draft — nothing computed here but the after-stock). */
data class KaiStockNoteLine(
    val name: String,
    /** As the owner said it ("5 BOX"). */
    val qty: BigDecimal,
    val unit: String,
    val incoming: Boolean,
    /** The product's stock now and after this line, in its own unit — null when either isn't known from the records. */
    val before: BigDecimal? = null,
    val after: BigDecimal? = null,
    val baseUnit: String? = null,
    val batchNo: String? = null,
    val expiry: LocalDate? = null,
)

object KaiNotes {

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) {
        KaiLang.TAMIL -> ta
        KaiLang.TANGLISH -> tl
        KaiLang.ENGLISH -> en
    }

    fun title(kind: KaiNoteKind, lang: KaiLang): String = when (kind) {
        KaiNoteKind.CREDIT -> pick(lang, "வாங்க வேண்டியது (Credit)", "Vaanga vendiyadhu (Credit)", "To collect (Credit)")
        KaiNoteKind.DEBIT -> pick(lang, "கொடுக்க வேண்டியது (Debit)", "Kudukka vendiyadhu (Debit)", "To pay (Debit)")
        KaiNoteKind.PAYMENT_IN -> pick(lang, "பணம் வந்தது", "Panam vandhadhu", "Payment received")
        KaiNoteKind.PAYMENT_OUT -> pick(lang, "பணம் கொடுத்தது", "Panam kuduthadhu", "Payment paid")
        KaiNoteKind.STOCK_IN -> pick(lang, "ஸ்டாக் வரவு", "Stock Add", "Stock in")
        KaiNoteKind.STOCK_OUT -> pick(lang, "ஸ்டாக் போனது", "Stock Out", "Stock out")
        KaiNoteKind.SALE -> pick(lang, "இன்னைக்கு Sales", "Innaikku Sales", "Today's sales")
        KaiNoteKind.OTHER -> pick(lang, "Kai குறிப்பு", "Kai Note", "Kai's note")
    }

    /** What the card says under the rows, for the note's own state. */
    fun status(state: KaiNoteState, lang: KaiLang): String = when (state) {
        KaiNoteState.IDLE -> ""
        KaiNoteState.LISTENING -> pick(lang, "கேக்குறேன்…", "Kekkuren…", "Listening…")
        KaiNoteState.UNDERSTANDING -> pick(lang, "யோசிக்கிறேன்…", "Yosikkuren…", "Thinking…")
        KaiNoteState.CLARIFICATION_REQUIRED -> pick(lang, "ஒரு கேள்வி…", "Oru kelvi…", "One question…")
        KaiNoteState.DRAFT_READY -> pick(lang, "எழுதுறேன்…", "Ezhudhuren…", "Writing…")
        KaiNoteState.REVIEWING -> pick(lang, "தயார் · உறுதி செய்யுங்க", "Ready · Confirm pannunga", "Ready · please confirm")
        KaiNoteState.SAVING -> pick(lang, "சேமிக்குது…", "Save aagudhu…", "Saving…")
        KaiNoteState.SAVE_SUCCESS -> pick(lang, "சேமிச்சாச்சு ✓", "Save aagiduchu ✓", "Saved ✓")
        KaiNoteState.SAVE_ERROR -> pick(lang, "சேமிக்கல — மறுபடி சொல்லுங்க", "Save aagala — thirumba sollunga", "Not saved — please say it again")
        KaiNoteState.CANCELLED -> pick(lang, "ரத்து — எதுவும் சேமிக்கல", "Cancel — edhuvum save aagala", "Cancelled — nothing saved")
    }

    private fun qty(q: BigDecimal): String = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    private fun unit(u: String, lang: KaiLang): String = when (u.uppercase()) {
        "PCS", "PC", "NOS" -> pick(lang, "pcs", "pcs", "pcs")
        "KG" -> "kg"
        "LITRE", "LTR", "L" -> pick(lang, "லிட்டர்", "ltr", "ltr")
        else -> u.lowercase()
    }

    /** A money draft: who, how much, how, and when it is due. */
    fun fromPlan(messageId: Long, plan: ActionPlan, lang: KaiLang, today: LocalDate): KaiNote {
        val kind = when (plan.kind) {
            PlanKind.CREDIT_GIVEN -> KaiNoteKind.CREDIT
            PlanKind.DEBIT_TAKEN -> KaiNoteKind.DEBIT
            PlanKind.PAYMENT_IN -> KaiNoteKind.PAYMENT_IN
            PlanKind.PAYMENT_OUT -> KaiNoteKind.PAYMENT_OUT
        }
        val pending: String? = when (kind) {
            KaiNoteKind.CREDIT -> pick(lang, "வாங்கணும்", "Vaanganum", "To collect")
            KaiNoteKind.DEBIT -> pick(lang, "கொடுக்கணும்", "Kudukkanum", "To pay")
            else -> when (plan.mode) {
                com.shopai.app.books.model.PaymentMode.CASH -> "Cash"
                com.shopai.app.books.model.PaymentMode.UPI -> "UPI"
                com.shopai.app.books.model.PaymentMode.BANK_TRANSFER -> "Bank"
                com.shopai.app.books.model.PaymentMode.CARD -> "Card"
                com.shopai.app.books.model.PaymentMode.CHEQUE -> "Cheque"
                else -> null
            }
        }
        val due = plan.dueDate?.let { pick(lang, "கடைசி தேதி ", "Due ", "Due ") + KaiFormat.date(it, lang, today) }
        val row = KaiNoteRow(plan.partyName, KaiFormat.rupees(plan.amount.toDouble()), listOfNotNull(pending, due).joinToString(" · "))
        return KaiNote(messageId, kind, title(kind, lang), listOf(row), today, lang, listOf(KaiNoteKey(KaiNoteKey.Source.PLAN, plan.key)))
    }

    /** Stock lines (one draft, or several items said in one line): each product, its quantity, and its stock before → after. */
    fun fromStock(messageId: Long, lines: List<KaiStockNoteLine>, keys: List<KaiNoteKey>, lang: KaiLang, today: LocalDate): KaiNote? {
        if (lines.isEmpty()) return null
        val kind = if (lines.all { it.incoming }) KaiNoteKind.STOCK_IN else if (lines.none { it.incoming }) KaiNoteKind.STOCK_OUT else KaiNoteKind.OTHER
        val rows = lines.map { l ->
            val change = if (l.before != null && l.after != null && l.baseUnit != null)
                "${qty(l.before)} → ${qty(l.after)} ${unit(l.baseUnit, lang)}" else null
            val batch = listOfNotNull(l.batchNo?.let { "Batch $it" }, l.expiry?.let { "Exp ${"%02d".format(it.monthValue)}/${it.year}" }).joinToString(" · ").ifEmpty { null }
            KaiNoteRow(l.name, (if (kind == KaiNoteKind.OTHER) (if (l.incoming) "+" else "−") else "") + "${qty(l.qty)} ${unit(l.unit, lang)}",
                listOfNotNull(change, batch).joinToString(" · ").ifEmpty { null })
        }
        val title = if (kind == KaiNoteKind.OTHER) pick(lang, "ஸ்டாக்", "Stock", "Stock") else title(kind, lang)
        return KaiNote(messageId, kind, title, rows, today, lang, keys)
    }

    /**
     * Any other draft (a service sale, a new product): the card's own lines, as Kai wrote them — "Hair cutting ×3 — ₹450" is
     * the name and its value; a line without " — " is a note under the rows.
     */
    fun fromCard(messageId: Long, kind: KaiNoteKind, lines: List<String>, keys: List<KaiNoteKey>, lang: KaiLang, today: LocalDate): KaiNote? {
        val rows = lines.filter { it.isNotBlank() }.map { line ->
            val parts = line.split(" — ", limit = 2)
            if (parts.size == 2) KaiNoteRow(parts[0].trim(), parts[1].trim()) else KaiNoteRow(line.trim(), "")
        }
        if (rows.isEmpty()) return null
        return KaiNote(messageId, kind, title(kind, lang), rows, today, lang, keys)
    }
}

/**
 * The scene's state, moved only by what really happened: the mic on / off, Kai working, Kai's answer, the draft being spent.
 * [state] is Kai's (what he is doing now); [noteState] is the open note's own (writing, waiting for Confirm, saving, saved…).
 * SAVE_SUCCESS comes only from a spent draft whose answer was a save (Kai says "Save aagiduchu" only after the books did).
 */
data class KaiNoteFlow(
    val state: KaiNoteState = KaiNoteState.IDLE,
    val note: KaiNote? = null,
    val noteState: KaiNoteState = KaiNoteState.IDLE,
) {
    val noteOpen: Boolean get() = note != null && noteState in setOf(KaiNoteState.DRAFT_READY, KaiNoteState.REVIEWING, KaiNoteState.SAVING)

    fun listening(on: Boolean): KaiNoteFlow = when {
        on && state != KaiNoteState.SAVING -> copy(state = KaiNoteState.LISTENING)
        !on && state == KaiNoteState.LISTENING -> copy(state = if (noteOpen) KaiNoteState.REVIEWING else KaiNoteState.IDLE)
        else -> this
    }

    /** The owner's line went to Kai. */
    fun understanding(): KaiNoteFlow = copy(state = KaiNoteState.UNDERSTANDING)

    /** Confirm was tapped on the open note: saving until Kai's answer comes back. */
    fun saving(): KaiNoteFlow = if (noteOpen) copy(state = KaiNoteState.SAVING, noteState = KaiNoteState.SAVING) else this

    /**
     * Kai answered. [spent]: the open note's draft is gone (saved, dropped, or failed). [newNote]: this answer is a new draft.
     * [mood]: how Kai answered — a save is SUCCESS / HAPPY, a failure ERROR / CONCERNED, anything else (Cancel) drops it.
     */
    fun answered(mood: KaiMood, spent: Boolean, newNote: KaiNote?): KaiNoteFlow {
        var f = this
        if (noteOpen && spent) {
            val end = when (mood) {
                KaiMood.SUCCESS, KaiMood.HAPPY -> KaiNoteState.SAVE_SUCCESS
                KaiMood.ERROR, KaiMood.CONCERNED -> KaiNoteState.SAVE_ERROR
                else -> KaiNoteState.CANCELLED
            }
            f = f.copy(noteState = end, state = end)
        }
        if (newNote != null) return f.copy(note = newNote, noteState = KaiNoteState.DRAFT_READY, state = KaiNoteState.DRAFT_READY)
        // A saving that came back without spending the draft (nothing happened): the note waits again.
        if (f.noteState == KaiNoteState.SAVING) f = f.copy(noteState = KaiNoteState.REVIEWING)
        if (f.state in setOf(KaiNoteState.SAVE_SUCCESS, KaiNoteState.SAVE_ERROR, KaiNoteState.CANCELLED)) return f
        return f.copy(state = when {
            mood == KaiMood.CLARIFY -> KaiNoteState.CLARIFICATION_REQUIRED
            f.noteOpen -> KaiNoteState.REVIEWING
            else -> KaiNoteState.IDLE
        })
    }

    /** The notebook finished writing the draft: Edit / Confirm / Cancel are shown. */
    fun written(): KaiNoteFlow = if (noteState == KaiNoteState.DRAFT_READY) copy(noteState = KaiNoteState.REVIEWING,
        state = if (state == KaiNoteState.DRAFT_READY) KaiNoteState.REVIEWING else state) else this

    fun cleared(): KaiNoteFlow = KaiNoteFlow()
}
