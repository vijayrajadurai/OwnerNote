package com.shopai.app.books.engine

import com.shopai.app.books.model.AdjustmentReason

/**
 * Maps a typed stock-change reason ("damaged", "expiry", "count correction",
 * Tamil words too) to an adjustment reason. Anything else is OTHER — the
 * owner's own words are always kept as the note.
 */
object AdjustmentReasonText {
    private val rules = listOf(
        AdjustmentReason.DAMAGED to listOf("damage", "broken", "break", "leak", "சேதம்", "உடைந்"),
        AdjustmentReason.EXPIRED to listOf("expir", "expiry", "காலாவதி"),
        AdjustmentReason.MISSING to listOf("missing", "lost", "theft", "stolen", "காணவில்லை", "காணோம்"),
        AdjustmentReason.WASTAGE to listOf("wastage", "waste", "spoil", "rotten", "வீண்"),
        AdjustmentReason.PHYSICAL_COUNT to listOf("count", "physical", "stock check", "audit", "எண்ணிக்கை"),
        AdjustmentReason.OPENING_CORRECTION to listOf("opening"),
    )

    fun from(text: String): AdjustmentReason {
        val t = text.lowercase()
        return rules.firstOrNull { (_, words) -> words.any { t.contains(it) } }?.first ?: AdjustmentReason.OTHER
    }
}
