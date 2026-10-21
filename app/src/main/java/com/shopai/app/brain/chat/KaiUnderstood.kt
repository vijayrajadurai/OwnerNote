package com.shopai.app.brain.chat

/**
 * How well Kai understood one line the owner said (owner, 21 Oct 2026: "sari puriyalana shop owners app ah use panna matanga"),
 * counted on the phone — never sent anywhere. The share understood the first time is the number to watch.
 */
enum class KaiUnderstood { UNDERSTOOD, ASKED, CORRECTED, CANCELLED }

/** The counts so far and the last lines Kai didn't get the first time (the owner's own words, on this phone only). */
data class KaiUnderstandingCounts(val counts: Map<KaiUnderstood, Int>, val missed: List<String>) {
    val total: Int get() = counts.values.sum()
    /** Understood the first time, of every line; null before any line. */
    val firstTimePercent: Int? get() = total.takeIf { it > 0 }?.let { (counts[KaiUnderstood.UNDERSTOOD] ?: 0) * 100 / it }
}
