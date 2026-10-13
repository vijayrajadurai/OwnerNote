package com.shopai.app.books.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One batch of a product and the stock it still has (thousandths of the primary unit). */
data class BatchStock(
    val id: String,
    val batchNo: String,
    val expiryDay: Int?,
    val createdAt: Long,
    val qtyMilli: Long,
)

/**
 * Which batch a stock movement uses when the screen or Kai didn't name one (owner, 13 Oct 2026: the batch / expiry said in chat
 * must be the books' own batch record, so the expiry list and the daily alert have it — and the product must still sell, buy
 * and move in / out from every screen that doesn't ask for a batch).
 *
 * Out (a sale, stock out): first expiry, first out — the batch that expires first goes first (no expiry date last, then the
 * oldest), split across batches when one isn't enough. In with no batch said: the newest batch; none yet → a new one named by
 * the date ([newNo]), with no expiry (an expiry is never guessed).
 */
object BatchPick {
    data class Out(val parts: List<Pair<BatchStock, Long>>, val shortMilli: Long)

    private val fefo = compareBy<BatchStock>({ it.expiryDay ?: Int.MAX_VALUE }, { it.createdAt }, { it.batchNo })

    /** [needMilli] taken from [batches] first-expiry-first; [Out.shortMilli] is what the batches don't have. */
    fun out(batches: List<BatchStock>, needMilli: Long): Out {
        var left = needMilli
        val parts = mutableListOf<Pair<BatchStock, Long>>()
        for (b in batches.filter { it.qtyMilli > 0 }.sortedWith(fefo)) {
            if (left <= 0) break
            val take = minOf(left, b.qtyMilli)
            parts += b to take
            left -= take
        }
        return Out(parts, left.coerceAtLeast(0))
    }

    /** One part of a split item line: its batch, its quantity in the line's own unit and its share of the line's discount. */
    data class LinePart(val batchNo: String, val qtyMilli: Long, val discountPaise: Long)

    /**
     * An item line of [qtyMilli] in its own unit (1 of it = [factorMilli] thousandths of the primary unit) spread over [out]'s
     * batches: each part in the line's unit with its share of [discountPaise]; the last part takes what rounding leaves and what
     * the batches don't have ([Out.shortMilli]), so the books still say "only N in stock". Empty when there is no batch at all.
     */
    fun splitLine(qtyMilli: Long, factorMilli: Long, discountPaise: Long, out: Out): List<LinePart> {
        if (out.parts.isEmpty()) return emptyList()
        val whole = listOf(LinePart(out.parts.last().first.batchNo, qtyMilli, discountPaise))
        if (out.parts.size == 1) return whole
        val parts = mutableListOf<LinePart>()
        var qtyLeft = qtyMilli
        var discountLeft = discountPaise
        for ((b, primary) in out.parts.dropLast(1)) {
            val q = BigDecimal.valueOf(primary).multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(factorMilli), 0, RoundingMode.HALF_UP).toLong()
            if (q <= 0) continue
            val d = share(discountPaise, q, qtyMilli)
            parts += LinePart(b.batchNo, q, d)
            qtyLeft -= q
            discountLeft -= d
        }
        if (qtyLeft <= 0) return whole
        return parts + LinePart(out.parts.last().first.batchNo, qtyLeft, discountLeft)
    }

    /** [total] × [part] / [whole], rounded. */
    fun share(total: Long, part: Long, whole: Long): Long =
        if (whole == 0L) 0 else BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part)).divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).toLong()

    /** The batch stock comes into when none was named: the newest one; null when the product has none yet. */
    fun into(batches: List<BatchStock>): BatchStock? = batches.maxWithOrNull(compareBy({ it.createdAt }, { it.batchNo }))

    /** A batch number for stock that came in without one: "B-261013". */
    fun newNo(date: LocalDate): String = "B-" + date.format(DateTimeFormatter.ofPattern("yyMMdd"))
}
