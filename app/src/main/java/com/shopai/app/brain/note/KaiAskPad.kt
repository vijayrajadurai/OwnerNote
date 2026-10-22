package com.shopai.app.brain.note

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The question card's number pad (owner, 22 Oct 2026: "stock question card innum user friendly"): the keys, the quick rates
 * above the cost, and the profit line shown while the owner types the selling rate. Screen help only — the answer goes to Kai as
 * the number typed ("60"), the same line as typing or saying it; nothing here is saved.
 */
object KaiAskPad {

    /** The value after a key: a digit, "." or "del" (at most 7 digits, one point, two decimals). */
    fun press(value: String, key: String): String = when (key) {
        "del" -> value.dropLast(1)
        "." -> if (value.contains('.')) value else value.ifEmpty { "0" } + "."
        else -> when {
            key.length != 1 || !key[0].isDigit() -> value
            value.substringAfter('.', "").length >= 2 && value.contains('.') -> value
            value.filter(Char::isDigit).length >= 7 -> value
            value == "0" -> key
            else -> value + key
        }
    }

    /** The typed value as an amount above zero, or null. */
    fun amount(value: String): BigDecimal? = value.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    /** Three selling rates to tap: the cost plus 10, 20 and 30 % (whole rupees; half rupees under ₹10), each above the cost. */
    fun quickRates(cost: BigDecimal?): List<BigDecimal> {
        if (cost == null || cost.signum() <= 0) return emptyList()
        return listOf("1.1", "1.2", "1.3").map { f ->
            val r = cost.multiply(BigDecimal(f))
            if (cost < BigDecimal.TEN) r.multiply(BigDecimal(2)).setScale(0, RoundingMode.HALF_UP).divide(BigDecimal(2)).stripTrailingZeros()
            else r.setScale(0, RoundingMode.HALF_UP)
        }.filter { it > cost }.distinct()
    }

    /** "labam ₹10" under a quick rate. */
    fun gain(rate: BigDecimal, cost: BigDecimal): String = KaiFormat.rupees(rate.subtract(cost).toDouble())

    /** What the typed selling rate makes: the profit for one and for all that came — or the loss, said plainly. */
    data class Profit(val text: String, val loss: Boolean)

    fun profit(value: String, cost: BigDecimal?, count: BigDecimal?, countLabel: String?, per: String?, lang: KaiLang): Profit? {
        val rate = amount(value) ?: return null
        cost ?: return null
        val one = per ?: "piece"
        val gain = rate.subtract(cost)
        fun r(x: BigDecimal) = KaiFormat.rupees(x.abs().toDouble())
        if (gain.signum() < 0) return Profit(when (lang) {
            KaiLang.TAMIL -> "நஷ்டம்! ஒரு $one-க்கு ${r(gain)} குறைவு — சரியா?"
            KaiLang.TANGLISH -> "Nashtam! Oru $one-ku ${r(gain)} kammi — sariyaa?"
            KaiLang.ENGLISH -> "Loss! ${r(gain)} less than the cost of 1 $one — right?"
        }, loss = true)
        val all = count?.let { gain.multiply(it) }
        val qty = countLabel ?: count?.stripTrailingZeros()?.toPlainString()
        return Profit(when (lang) {
            KaiLang.TAMIL -> "ஒரு $one-க்கு லாபம் ${r(gain)}" + (all?.let { " · $qty-க்கு ${r(it)}" } ?: "")
            KaiLang.TANGLISH -> "Oru $one-ku labam ${r(gain)}" + (all?.let { " · $qty-ku ${r(it)}" } ?: "")
            KaiLang.ENGLISH -> "Profit ${r(gain)} per $one" + (all?.let { " · ${r(it)} on $qty" } ?: "")
        }, loss = false)
    }
}
