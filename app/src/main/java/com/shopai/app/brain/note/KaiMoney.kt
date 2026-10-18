package com.shopai.app.brain.note

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.PlanKind
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A money draft as Kai's digital card shows it (owner, 18 Oct 2026: "intha digital draft maadhiri credit and debit draft-ku
 * venum"): who, the amount, what it is, when it is due, and the person's balance before → after. Read from Kai's own draft
 * (the [ActionPlan]) when the note is made — the same numbers Kai saves on Confirm, nothing guessed.
 */
data class KaiMoneyDraft(
    /** "CREDIT · VAANGANUM", "DEBIT · KUDUKKANUM", "PAYMENT · VANDHADHU", "PAYMENT · KUDUTHADHU". */
    val heading: String,
    val name: String,
    val amount: String,
    /** The money comes to the shop (credit to collect, a payment received) — green; else it goes out — red. */
    val toShop: Boolean,
    /** "Credit kuduthadhu · Cash". */
    val detail: String,
    /** "Due 25 Oct", or null. */
    val due: String?,
    /** "Kumar baaki" / "ABC Traders-ku naan": what the balance line says, with before → after; null when the books gave none. */
    val balanceLabel: String?,
    val before: String?,
    val after: String?,
    /** The bar: the part that stays (0..1) and the part this entry changes (0..1), against the larger of before / after. */
    val kept: Float?,
    val changed: Float?,
)

object KaiMoney {
    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) {
        KaiLang.TAMIL -> ta
        KaiLang.TANGLISH -> tl
        KaiLang.ENGLISH -> en
    }

    private fun rupees(v: BigDecimal) = KaiFormat.rupees(v.toDouble())

    fun of(plan: ActionPlan, lang: KaiLang, today: LocalDate): KaiMoneyDraft {
        val mode = when (plan.mode) {
            com.shopai.app.books.model.PaymentMode.CASH -> "Cash"
            com.shopai.app.books.model.PaymentMode.UPI -> "UPI"
            com.shopai.app.books.model.PaymentMode.BANK_TRANSFER -> "Bank"
            com.shopai.app.books.model.PaymentMode.CARD -> "Card"
            com.shopai.app.books.model.PaymentMode.CHEQUE -> "Cheque"
            else -> null
        }
        val (heading, what) = when (plan.kind) {
            PlanKind.CREDIT_GIVEN -> pick(lang, "கிரெடிட் · வாங்கணும்", "CREDIT · VAANGANUM", "CREDIT · TO COLLECT") to
                pick(lang, "கிரெடிட் குடுத்தது", "Credit kuduthadhu", "Credit given")
            PlanKind.DEBIT_TAKEN -> pick(lang, "டெபிட் · கொடுக்கணும்", "DEBIT · KUDUKKANUM", "DEBIT · TO PAY") to
                pick(lang, "நான் கொடுக்கணும்", "Naan kudukkanum", "I have to pay")
            PlanKind.PAYMENT_IN -> pick(lang, "பணம் · வந்தது", "PAYMENT · VANDHADHU", "PAYMENT · RECEIVED") to
                pick(lang, "பணம் வந்தது", "Panam vandhadhu", "Money received")
            PlanKind.PAYMENT_OUT -> pick(lang, "பணம் · கொடுத்தது", "PAYMENT · KUDUTHADHU", "PAYMENT · PAID") to
                pick(lang, "பணம் கொடுத்தது", "Panam kuduthadhu", "Money paid")
        }
        // Credit / payment in: what they owe the shop; debit / payment out: what the shop owes them.
        val theyOwe = plan.kind == PlanKind.CREDIT_GIVEN || plan.kind == PlanKind.PAYMENT_IN
        val label = pick(lang,
            ta = if (theyOwe) "${plan.partyName} பாக்கி" else "${plan.partyName}-க்கு நான்",
            tl = if (theyOwe) "${plan.partyName} baaki" else "${plan.partyName}-ku naan",
            en = if (theyOwe) "${plan.partyName} owes" else "I owe ${plan.partyName}")
        val b = plan.balanceBefore
        val a = plan.balanceAfter
        val top = if (b != null && a != null) b.max(a).takeIf { it.signum() > 0 } else null
        return KaiMoneyDraft(
            heading = heading,
            name = plan.partyName,
            amount = rupees(plan.amount),
            toShop = theyOwe,
            detail = listOfNotNull(what, mode.takeIf { plan.kind == PlanKind.PAYMENT_IN || plan.kind == PlanKind.PAYMENT_OUT }).joinToString(" · "),
            due = plan.dueDate?.let { pick(lang, "கடைசி தேதி ", "Due ", "Due ") + KaiFormat.date(it, lang, today) },
            balanceLabel = if (b != null && a != null) label else null,
            before = b?.let(::rupees),
            after = a?.let(::rupees),
            kept = top?.let { b!!.min(a!!).max(BigDecimal.ZERO).toFloat() / it.toFloat() },
            changed = top?.let { (a!! - b!!).abs().toFloat() / it.toFloat() },
        )
    }
}
