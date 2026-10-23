package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.UpiCredit
import com.shopai.app.util.NameSound
import java.math.BigDecimal
import java.util.Locale

/**
 * Kai வசூல் (owner, 23 Oct 2026): money came in by UPI → Kai says it at once and asks whose account it goes to.
 *
 * - The payer's name is one customer of the books (the same name, or the same name in Tamil letters): the payment-in draft
 *   for them straight away — the owner's own money flow, so Confirm / Edit / Cancel, balance before → after, nothing saved
 *   before Confirm (principle 5).
 * - Otherwise (no name, a name not in the books, a name like several): "யார் கணக்கு?" with the likely customers as buttons —
 *   a name like the payer's first, then whoever owes exactly that amount, then the biggest dues. A tap drafts that customer's
 *   payment (still Confirm first).
 * - Only customers: money from a supplier is not guessed as anything (it is never a "debit" by surprise — principle 2).
 *
 * The draft is made by saying the owner's own line to Kai ("Kumar 2000 gpay-la kuduthaan" / "Kumar 2000 gpay-ல குடுத்தான்"),
 * so it is exactly what the owner would get by saying it — typed or by mic.
 */
internal class KaiVasool(private val tools: KaiTools, private val books: KaiBooks) {

    private class Customer(val id: String, val name: String, val owes: BigDecimal)

    suspend fun received(c: UpiCredit, lang: KaiLang, say: suspend (String) -> KaiTurn): KaiTurn {
        val all = customers()
        val sender = c.sender
        val same = if (sender == null) emptyList() else all.filter { it.name.equals(sender, ignoreCase = true) || NameSound.same(it.name, sender) }
            .ifEmpty {
                // The books' own search (spelling / Tamil letters), customers only, the whole name.
                runCatching { tools.parties(sender) }.getOrNull().orEmpty()
                    .filter { p -> p.customer && (p.name.equals(sender, ignoreCase = true) || NameSound.same(p.name, sender)) }
                    .map { Customer(it.id, it.name, it.balance) }
            }.distinctBy { it.id }
        tools.log("upi received", "payment notification", "${c.app} ${c.amount.toPlainString()} ${sender ?: "-"} → ${same.size} same", ActionStatus.ANSWERED)
        val head = head(c, lang)
        if (same.size == 1) {
            val turn = say(line(same.single().name, c, lang))
            return turn.copy(reply = turn.reply.copy(text = head + "\n" + turn.reply.text))
        }
        val choices = (same + likely(all, sender, c.amount, exclude = same.map { it.id }.toSet())).distinctBy { it.id }.take(MAX_CHOICES)
        if (choices.isEmpty()) return KaiTurn(ChatReply(head + "\n" + pick(lang,
            ta = "கணக்குல customer யாரும் இல்ல. யார் கொடுத்தாங்கனு சொல்லுங்க: “Kumar ${amt(c)} ${c.modeWord}-ல குடுத்தான்”.",
            tl = "Kanakku-la customer yaarum illa. Yaar kuduthaanga-nu sollunga: “Kumar ${amt(c)} ${c.modeWord}-la kuduthaan”.",
            en = "There are no customers in your books yet. Say who paid: “Kumar paid ${amt(c)} on ${c.app}”."), KaiMood.CLARIFY, ChatIntent.UNKNOWN))
        val question = pick(lang, ta = "யார் கணக்குல போடணும்?", tl = "Yaar kanakku-la podanum?", en = "Whose account does it go to?")
        return KaiTurn(
            ChatReply("$head\n$question", KaiMood.CLARIFY, ChatIntent.UNKNOWN),
            KaiCard(
                emptyList(),
                choices.map { p ->
                    KaiButton(p.name + if (p.owes.signum() > 0) " · " + pick(lang, ta = "பாக்கி ", tl = "baaki ", en = "owes ") + rupees(p.owes) else "",
                        KaiAction.Say(line(p.name, c, lang)))
                },
                headline = pick(lang, ta = "${rupees(c.amount)} ${c.app}-ல வந்துச்சு", tl = "${rupees(c.amount)} ${c.app}-la vandhuchu", en = "${rupees(c.amount)} on ${c.app}"),
            ),
        )
    }

    private suspend fun customers(): List<Customer> = runCatching { books.snapshot()?.customers.orEmpty() }.getOrDefault(emptyList())
        .map { Customer(it.id, it.name, BigDecimal.valueOf(it.pendingTotal).max(BigDecimal.ZERO)) }

    /** A name like the payer's (its first word), then whoever owes exactly this amount, then the biggest dues. */
    private fun likely(all: List<Customer>, sender: String?, amount: BigDecimal, exclude: Set<String>): List<Customer> {
        val rest = all.filter { it.id !in exclude }
        val first = sender?.split(' ')?.firstOrNull()?.takeIf { it.length >= 2 }
        val alike = if (first == null) emptyList() else rest.filter { p ->
            val words = p.name.split(' ').filter { it.isNotBlank() }
            words.any { w -> w.equals(first, ignoreCase = true) || NameSound.same(w, first) } ||
                sender.orEmpty().split(' ').any { w -> w.length >= 3 && w.equals(words.firstOrNull(), ignoreCase = true) }
        }
        val exact = rest.filter { it.owes.compareTo(amount) == 0 }
        val owing = rest.filter { it.owes.signum() > 0 }.sortedByDescending { it.owes }
        return alike + exact + owing
    }

    /** The owner's own line for this payment, in the chat's language — the draft is what saying it would give. */
    private fun line(name: String, c: UpiCredit, lang: KaiLang): String =
        if (lang == KaiLang.TAMIL) "$name ${amt(c)} ${c.modeWord}-ல குடுத்தான்" else "$name ${amt(c)} ${c.modeWord}-la kuduthaan"

    private fun head(c: UpiCredit, lang: KaiLang): String {
        val r = rupees(c.amount)
        val who = c.sender
        return if (who != null) pick(lang,
            ta = "ஓனர், $r ${c.app}-ல வந்துச்சு — $who-கிட்ட இருந்து.",
            tl = "Owner, $r ${c.app}-la vandhuchu — $who kitta irundhu.",
            en = "Owner, $r came in on ${c.app} — from $who.")
        else pick(lang,
            ta = "ஓனர், $r ${c.app}-ல வந்துச்சு (அனுப்பினவர் பேர் வரல).",
            tl = "Owner, $r ${c.app}-la vandhuchu (anuppinavar per varala).",
            en = "Owner, $r came in on ${c.app} (the payer's name wasn't shown).")
    }

    private fun amt(c: UpiCredit): String = c.amount.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
    private fun rupees(a: BigDecimal) = KaiFormat.rupees(a.toDouble())
    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }

    private companion object {
        const val MAX_CHOICES = 4
    }
}
