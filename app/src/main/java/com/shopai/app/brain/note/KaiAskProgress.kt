package com.shopai.app.brain.note

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.tools.ItemField
import com.shopai.app.brain.tools.ItemSpec
import java.math.BigDecimal

/**
 * What Kai already has and what he is asking now, while he gathers a new product / a stock line (owner, 17 Oct 2026: "kekura
 * question-um digital-aa"): rows of what the owner said so far ("Product · Noodles", "Quantity · 100 pcs", "Purchase · ₹50"), the
 * one row being asked ("Selling"), and how far along the usual questions are. Read from Kai's own pending item — nothing guessed.
 */
data class KaiAskProgress(
    /** (label, value) of what is known, in order. */
    val known: List<Pair<String, String>>,
    /** The label of the one thing asked now. */
    val asking: String,
    /** This question's place (1-based) and the questions in all, counting the usual ones still to come. */
    val step: Int,
    val steps: Int,
)

object KaiAsks {
    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) {
        KaiLang.TAMIL -> ta
        KaiLang.TANGLISH -> tl
        KaiLang.ENGLISH -> en
    }

    private fun n(q: BigDecimal) = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    private fun unit(u: String?) = u?.lowercase()?.let { if (it == "pcs" || it == "piece" || it == "pieces") "pcs" else it } ?: ""

    fun label(field: ItemField?, lang: KaiLang): String = when (field) {
        null -> pick(lang, "பொருள்", "Product", "Product")
        ItemField.QTY -> pick(lang, "அளவு", "Quantity", "Quantity")
        ItemField.UNIT -> pick(lang, "Unit", "Unit", "Unit")
        ItemField.PER_PACK -> pick(lang, "ஒரு pack-ல", "Pack-la", "Per pack")
        ItemField.SIZE -> pick(lang, "Size", "Size", "Size")
        ItemField.BATCH -> "Batch"
        ItemField.EXPIRY -> "Expiry"
        ItemField.PURCHASE -> pick(lang, "வாங்கு விலை", "Purchase", "Purchase")
        ItemField.SELLING -> pick(lang, "விற்பனை விலை", "Selling", "Selling")
    }

    /** A new product being added: what is known, the field asked, and the usual price questions still to come. */
    fun ofItem(spec: ItemSpec, asked: ItemField, lang: KaiLang): KaiAskProgress {
        val known = buildList {
            add(label(null, lang) to spec.name)
            spec.qty?.let { add(label(ItemField.QTY, lang) to "${n(it)} ${unit(spec.unit)}".trim()) }
            spec.perPack?.let { add(label(ItemField.PER_PACK, lang) to "${n(it)} ${unit(spec.inner)}".trim()) }
            spec.size?.let { add(label(ItemField.SIZE, lang) to it) }
            spec.batch?.let { add(label(ItemField.BATCH, lang) to it) }
            spec.expiry?.let { add(label(ItemField.EXPIRY, lang) to "${"%02d".format(it.monthValue)}/${it.year}") }
            spec.purchase?.let { add(label(ItemField.PURCHASE, lang) to KaiFormat.rupees(it.toDouble())) }
            spec.selling?.let { add(label(ItemField.SELLING, lang) to KaiFormat.rupees(it.toDouble())) }
        }
        // Answered so far (the quantity, prices, size… already known), this one, and the usual price questions not yet reached.
        val answered = listOfNotNull(spec.qty, spec.perPack, spec.size, spec.batch, spec.expiry, spec.purchase, spec.selling).size
        val later = listOf(ItemField.PURCHASE to spec.purchase, ItemField.SELLING to spec.selling)
            .count { (f, v) -> v == null && f != asked && f !in spec.skipped && (asked != ItemField.SELLING || f != ItemField.PURCHASE) }
        val step = answered + 1
        return KaiAskProgress(known, label(asked, lang), step, step + later)
    }

    /** "1 box-la evlo pieces?" for a product the shop has: the product, the quantity said, and the pack size asked. */
    fun ofUnit(product: String, qty: BigDecimal?, unitSaid: String?, baseUnit: String, lang: KaiLang): KaiAskProgress = KaiAskProgress(
        known = listOfNotNull(label(null, lang) to product, qty?.let { label(ItemField.QTY, lang) to "${n(it)} ${unit(unitSaid)}".trim() }),
        asking = "1 ${unit(unitSaid)} = ? ${unit(baseUnit)}".replace("  ", " "),
        step = 1, steps = 1,
    )
}
