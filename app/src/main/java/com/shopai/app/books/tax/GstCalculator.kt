package com.shopai.app.books.tax

import com.shopai.app.books.model.Money
import com.shopai.app.books.model.TaxType

/** One priced line before tax. [discountBp] and [discountPaise] are mutually exclusive. */
data class TaxLineInput(
    val qtyMilli: Long,
    val ratePaise: Long,
    val gstBp: Int,
    val taxType: TaxType = TaxType.GST,
    val discountPaise: Long = 0,
    val discountBp: Int = 0,
    val cessBp: Int = 0,
    /** Rate already includes GST (MRP-style pricing). */
    val priceIncludesTax: Boolean = false,
)

data class TaxLineResult(
    val grossPaise: Long,
    val discountPaise: Long,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val cessPaise: Long,
) {
    val taxPaise: Long get() = cgstPaise + sgstPaise + igstPaise + cessPaise
    val totalPaise: Long get() = taxablePaise + taxPaise
}

data class InvoiceTotals(
    val lines: List<TaxLineResult>,
    val grossPaise: Long,
    val discountPaise: Long,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val cessPaise: Long,
    val roundOffPaise: Long,
    val grandTotalPaise: Long,
) {
    val taxPaise: Long get() = cgstPaise + sgstPaise + igstPaise + cessPaise
}

class GstException(message: String) : IllegalArgumentException(message)

/**
 * Item-level GST, summed to the invoice. Intra-state lines get CGST + SGST
 * (each at half the rate); inter-state lines get IGST only — a line can never
 * carry both. Round-off is computed last and kept as its own figure.
 */
object GstCalculator {

    fun line(input: TaxLineInput, interState: Boolean): TaxLineResult {
        if (input.qtyMilli <= 0) throw GstException("Quantity must be more than zero")
        if (input.ratePaise < 0) throw GstException("Rate cannot be negative")
        if (input.gstBp < 0 || input.gstBp > 10_000) throw GstException("Invalid GST rate")
        if (input.cessBp < 0 || input.cessBp > 10_000) throw GstException("Invalid cess rate")
        if (input.taxType != TaxType.GST && (input.gstBp != 0 || input.cessBp != 0)) {
            throw GstException("${input.taxType} items cannot carry GST")
        }
        if (input.discountPaise < 0 || input.discountBp < 0 || input.discountBp > 10_000) throw GstException("Invalid discount")
        if (input.discountPaise > 0 && input.discountBp > 0) throw GstException("Use either a discount amount or a discount %")

        val gross = Money.times(input.ratePaise, input.qtyMilli)
        val discount = if (input.discountBp > 0) Money.percent(gross, input.discountBp) else input.discountPaise
        if (discount > gross) throw GstException("Discount is more than the line value")
        val net = gross - discount

        val totalRateBp = input.gstBp + input.cessBp
        if (input.priceIncludesTax && totalRateBp > 0) {
            // Back the tax out of the net amount; the line total stays exactly `net`.
            val taxable = java.math.BigDecimal.valueOf(net).multiply(java.math.BigDecimal.valueOf(10_000))
                .divide(java.math.BigDecimal.valueOf(10_000L + totalRateBp), 0, java.math.RoundingMode.HALF_UP).longValueExact()
            val tax = net - taxable
            val cess = if (input.cessBp == 0) 0 else Money.percent(taxable, input.cessBp).coerceAtMost(tax)
            val gst = tax - cess
            return if (interState) {
                TaxLineResult(gross, discount, taxable, 0, 0, gst, cess)
            } else {
                val cgst = gst / 2
                TaxLineResult(gross, discount, taxable, cgst, gst - cgst, 0, cess)
            }
        }

        val taxable = net
        val cess = Money.percent(taxable, input.cessBp)
        return if (interState) {
            TaxLineResult(gross, discount, taxable, 0, 0, Money.percent(taxable, input.gstBp), cess)
        } else {
            // CGST and SGST are each charged at half the rate (works for odd bp like 0.25%).
            val half = java.math.BigDecimal.valueOf(taxable).multiply(java.math.BigDecimal.valueOf(input.gstBp.toLong()))
                .divide(java.math.BigDecimal.valueOf(20_000), 0, java.math.RoundingMode.HALF_UP).longValueExact()
            TaxLineResult(gross, discount, taxable, half, half, 0, cess)
        }
    }

    fun invoice(lines: List<TaxLineInput>, interState: Boolean, roundOff: Boolean = true): InvoiceTotals {
        if (lines.isEmpty()) throw GstException("Add at least one item")
        val results = lines.map { line(it, interState) }
        val taxable = results.sumOf { it.taxablePaise }
        val cgst = results.sumOf { it.cgstPaise }
        val sgst = results.sumOf { it.sgstPaise }
        val igst = results.sumOf { it.igstPaise }
        val cess = results.sumOf { it.cessPaise }
        val raw = taxable + cgst + sgst + igst + cess
        val rounded = if (roundOff) Money.roundToRupee(raw) else raw
        return InvoiceTotals(
            lines = results,
            grossPaise = results.sumOf { it.grossPaise },
            discountPaise = results.sumOf { it.discountPaise },
            taxablePaise = taxable,
            cgstPaise = cgst,
            sgstPaise = sgst,
            igstPaise = igst,
            cessPaise = cess,
            roundOffPaise = rounded - raw,
            grandTotalPaise = rounded,
        )
    }

    /** Inter-state when both states are known and differ; unknown party state = local supply. */
    fun isInterState(businessStateCode: String?, placeOfSupplyCode: String?): Boolean =
        businessStateCode != null && placeOfSupplyCode != null && businessStateCode != placeOfSupplyCode
}
