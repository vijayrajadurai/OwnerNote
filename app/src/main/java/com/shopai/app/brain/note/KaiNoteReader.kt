package com.shopai.app.brain.note

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiAgent
import com.shopai.app.brain.chat.KaiCard
import java.time.LocalDate

/**
 * Reads Kai's own drafts for the notebook — read only: the money plan, the stock draft(s), the goods said together, the service
 * sale — and tells when a note's draft is gone (saved, dropped, failed). The same calls Kai Chat makes, so tests check exactly
 * what the screen shows.
 */
object KaiNoteReader {

    /** The note for a draft card, from the draft itself — null when the card is no draft (a question, a list, a report). */
    fun noteFor(agent: KaiAgent, messageId: Long, card: KaiCard?, lang: KaiLang, today: LocalDate): KaiNote? {
        val actions = card?.buttons?.map { it.action }.orEmpty()
        actions.firstNotNullOfOrNull { (it as? KaiAction.ConfirmPlan)?.key?.let(agent::plan) }?.let { return KaiNotes.fromPlan(messageId, it, lang, today) }
        val stockKeys = actions.mapNotNull { (it as? KaiAction.ConfirmStock)?.key }
        if (stockKeys.isNotEmpty()) {
            return KaiNotes.fromStock(messageId, stockKeys.mapNotNull(agent::stockNoteOf), stockKeys.map { KaiNoteKey(KaiNoteKey.Source.STOCK, it) }, lang, today)
        }
        if (KaiAction.ConfirmGoods in actions) return KaiNotes.fromStock(messageId, agent.goodsNote(), listOf(KaiNoteKey(KaiNoteKey.Source.GOODS, "goods")), lang, today)
        actions.firstNotNullOfOrNull { (it as? KaiAction.ConfirmSale)?.key }?.let { key ->
            return KaiNotes.fromCard(messageId, KaiNoteKind.SALE, card!!.lines, listOf(KaiNoteKey(KaiNoteKey.Source.SALE, key)), lang, today)
        }
        return null
    }

    /** The stock draft behind [note] is now in Kai's goods list (kept to save with the list) — not in the books. */
    fun listed(agent: KaiAgent, note: KaiNote): Boolean {
        if (note.keys.none { it.source == KaiNoteKey.Source.STOCK }) return false
        val kept = agent.goodsNote().map { it.name.trim().lowercase() }.toSet()
        return kept.isNotEmpty() && note.rows.all { it.name.trim().lowercase() in kept }
    }

    /** The draft behind [note] is gone from Kai: saved, dropped, or failed (Kai's answer tells which). */
    fun spent(agent: KaiAgent, note: KaiNote): Boolean = note.keys.all { k ->
        when (k.source) {
            KaiNoteKey.Source.PLAN -> agent.plan(k.key) == null
            KaiNoteKey.Source.STOCK -> agent.stockNoteOf(k.key) == null
            KaiNoteKey.Source.GOODS -> agent.goodsNote().isEmpty()
            KaiNoteKey.Source.SALE -> !agent.saleDraftOpen(k.key)
        }
    }
}
