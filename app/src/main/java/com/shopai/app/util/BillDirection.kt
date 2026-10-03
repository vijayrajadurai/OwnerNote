package com.shopai.app.util

import java.util.Locale

/** The owner's own business, as printed on bills. */
data class MyBusiness(val name: String?, val gstin: String?, val phone: String?)

enum class BillDirectionReason {
    /** My GSTIN / name / phone is the seller's (top of the bill): I sold → customer owes me. */
    MY_BILL_TO_CUSTOMER,
    /** My GSTIN / name is under "Bill to / Buyer": I bought → I owe the shop. */
    ADDRESSED_TO_ME,
    /** My details are not on it: a bill from another shop → I owe the shop. */
    FROM_ANOTHER_SHOP,
}

data class BillDirectionGuess(
    /** CREDIT = money to receive, DEBIT = money to pay. */
    val direction: TxnDirection,
    val reason: BillDirectionReason,
    /** False when decided only because my details were absent (the owner should glance at it). */
    val confident: Boolean,
)

/**
 * Credit (money to receive) or Debit (money to pay) for a printed bill, from
 * who issued it: the seller is printed at the top, the buyer under
 * "Bill to / Buyer / Customer / M/s". Nothing is guessed from amounts.
 */
object BillDirection {
    private val buyerHeading = Regex("""(?i)\b(buyer|consignee|bill(?:ed)?\s*to|sold\s*to|customer|ship\s*to|m/s|party)\b""")

    fun decide(text: String, bill: ExtractedBill, me: MyBusiness): BillDirectionGuess {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val buyerAt = lines.indexOfFirst { buyerHeading.containsMatchIn(it) }.takeIf { it >= 0 }

        // 1. GSTIN: the strongest signal — where on the bill is mine printed?
        me.gstin?.let(::alnum)?.takeIf { it.length == 15 }?.let { gst ->
            val at = lines.indexOfFirst { alnum(it).contains(gst) }
            if (at >= 0) {
                val sellerSide = if (buyerAt != null) at < buyerAt else at < 8
                return guess(sellerSide)
            }
        }
        // 2. My shop name as the seller (header) or as the buyer.
        val myName = me.name?.let(::key)?.takeIf { it.length >= 4 }
        if (myName != null) {
            if (bill.merchantName?.let { sameName(key(it), myName) } == true) return guess(sellerSide = true)
            if (bill.customerName?.let { sameName(key(it), myName) } == true) return guess(sellerSide = false)
            val at = lines.indexOfFirst { sameName(key(it), myName) }
            if (at >= 0) return guess(if (buyerAt != null) at < buyerAt else at < 4)
        }
        // 3. My phone number in the header.
        me.phone?.filter(Char::isDigit)?.takeLast(10)?.takeIf { it.length == 10 }?.let { phone ->
            val at = lines.indexOfFirst { it.filter(Char::isDigit).contains(phone) }
            if (at >= 0) return guess(if (buyerAt != null) at < buyerAt else at < 6)
        }
        // 4. Not mine anywhere: a bill from another shop.
        return BillDirectionGuess(TxnDirection.DEBIT, BillDirectionReason.FROM_ANOTHER_SHOP, confident = false)
    }

    private fun guess(sellerSide: Boolean) =
        if (sellerSide) BillDirectionGuess(TxnDirection.CREDIT, BillDirectionReason.MY_BILL_TO_CUSTOMER, confident = true)
        else BillDirectionGuess(TxnDirection.DEBIT, BillDirectionReason.ADDRESSED_TO_ME, confident = true)

    private fun alnum(s: String) = s.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun key(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** "Anbu Traders" vs "ANBU TRADERS, CHENNAI": one contains the other. */
    private fun sameName(a: String, b: String) = a.length >= 4 && b.length >= 4 && (a.contains(b) || b.contains(a))
}
