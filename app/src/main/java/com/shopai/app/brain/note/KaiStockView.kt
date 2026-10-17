package com.shopai.app.brain.note

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.tools.StockFact
import java.math.BigDecimal
import java.math.RoundingMode

/** How a product's stock stands against the reorder level the owner set: out, low, fine, or no level set. */
enum class KaiStockLevel { OUT, LOW, OK, NO_LEVEL }

/**
 * One product in Kai's stock card (owner, 18 Oct 2026: "stock evlo iruku card-um digital-aa"): the name, the stock as Kai says it
 * ("1150 PCS"), the reorder level when set, how full it is against that level (for the bar), and whether it is out / low / fine.
 * Read only from the books' stock facts — the same numbers as the card's lines.
 */
data class KaiStockRow(
    val name: String,
    val qty: String,
    val reorderAt: String?,
    /** 0..1 against three times the reorder level (the bar); null when no level is set. */
    val fill: Float?,
    val level: KaiStockLevel,
)

object KaiStockView {
    fun rows(facts: List<StockFact>, qtyText: (BigDecimal, String) -> String): List<KaiStockRow> = facts.map { f ->
        val at = f.reorderAt?.takeIf { it.signum() > 0 }
        val level = when {
            f.qty.signum() <= 0 -> KaiStockLevel.OUT
            at == null -> KaiStockLevel.NO_LEVEL
            f.qty <= at -> KaiStockLevel.LOW
            else -> KaiStockLevel.OK
        }
        val fill = at?.let { f.qty.max(BigDecimal.ZERO).divide(it.multiply(BigDecimal(3)), 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) }
        KaiStockRow(f.name, qtyText(f.qty, f.unit), at?.let { qtyText(it, f.unit) }, fill, level)
    }

    /** The line under a product's name. */
    fun label(row: KaiStockRow, lang: KaiLang): String = when (row.level) {
        KaiStockLevel.OUT -> pick(lang, "ஸ்டாக் இல்ல", "Stock illa", "Out of stock")
        KaiStockLevel.LOW -> pick(lang, "குறைவு · reorder ${row.reorderAt}", "Low · reorder ${row.reorderAt}", "Low · reorder at ${row.reorderAt}")
        KaiStockLevel.OK -> pick(lang, "நல்லா இருக்கு", "Nalla irukku", "In stock")
        KaiStockLevel.NO_LEVEL -> pick(lang, "இருப்பு", "Irukku", "In stock")
    }

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) {
        KaiLang.TAMIL -> ta
        KaiLang.TANGLISH -> tl
        KaiLang.ENGLISH -> en
    }
}
