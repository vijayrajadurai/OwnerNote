package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Kai வசூல் (owner, 23 Oct 2026): a payment app's notification → money in, its amount and who paid — or nothing. Principle 3:
 * never a wrong entry — the owner's own payments, requests, cashback, failed payments and anything unclear are ignored.
 *
 * The lines here are the usual English shapes. Before release, add the owner's own phone's notifications (GPay, PhonePe, Paytm,
 * BHIM screenshots) to [ownersPhone] — a shape Kai doesn't read is ignored, never misread.
 */
class KaiUpiNoticeTest {
    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val phonepe = "com.phonepe.app"
    private val paytm = "net.one97.paytm"
    private val bhim = "in.org.npci.upiapp"

    private fun read(pkg: String, title: String?, text: String?, big: String? = null) = KaiUpiNotice.read(pkg, title, text, big, 1_000L)

    private fun assertIn(c: UpiCredit?, amount: String, sender: String?, app: String) {
        requireNotNull(c) { "not read" }
        assertEquals(0, c.amount.compareTo(BigDecimal(amount)))
        assertEquals(sender, c.sender)
        assertEquals(app, c.app)
    }

    @Test fun moneyInIsRead() {
        assertIn(read(gpay, "₹1 received", "Praba paid you ₹1"), "1", "Praba", "GPay")
        assertIn(read(gpay, "Kumar", "Paid you ₹500"), "500", "Kumar", "GPay")
        assertIn(read(phonepe, "Payment received", "Received ₹2,000 from KUMAR SELVAM"), "2000", "Kumar Selvam", "PhonePe")
        assertIn(read(phonepe, "Money received", "You have received ₹ 1,500.50 from Lakshmi on PhonePe"), "1500.5", "Lakshmi", "PhonePe")
        assertIn(read(paytm, "Payment Received", "Rs.750 received from MR. RAJ KUMAR via UPI"), "750", "Raj Kumar", "Paytm")
        assertIn(read(bhim, "Credit alert", "INR 1,00,000 credited to your account from Sri Balaji Agencies"), "100000", "Sri Balaji Agencies", "BHIM")
        assertIn(read(gpay, null, "Selvi sent you ₹10"), "10", "Selvi", "GPay")
    }

    @Test fun aUpiIdOrNumberIsNoName() {
        assertIn(read(gpay, "Payment received", "₹300 received from kumar123@okaxis"), "300", null, "GPay")
        assertIn(read(phonepe, "Payment received", "Received ₹300 from 98xxxxxx10"), "300", null, "PhonePe")
    }

    @Test fun theOwnersOwnPaymentsAreIgnored() {
        assertNull(read(gpay, "Payment successful", "You paid ₹500 to Kumar"))
        assertNull(read(phonepe, "Paid", "₹500 sent to ABC Traders"))
        assertNull(read(paytm, "Paid to Murugan Stores", "Rs.2000 debited from your account"))
    }

    @Test fun requestsCashbackFailuresAreIgnored() {
        assertNull(read(gpay, "Kumar requested ₹500", "Payment request from Kumar"))
        assertNull(read(phonepe, "Cashback received", "You have received ₹10 cashback"))
        assertNull(read(gpay, "You won ₹21", "Reward received in your scratch card"))
        assertNull(read(paytm, "Payment failed", "Rs.500 from Kumar could not be received"))
        assertNull(read(phonepe, "Payment pending", "₹500 from Kumar is pending"))
        assertNull(read(gpay, "Refund received", "₹99 refund credited for your order"))
        assertNull(read(paytm, "Offer", "Get ₹100 off on your next recharge"))
        assertNull(read(gpay, "Electricity bill", "Your bill of ₹1,200 is due on 30 Oct"))
    }

    @Test fun unclearIsIgnored() {
        // No money-in word.
        assertNull(read(gpay, "Kumar", "₹500"))
        // Two different amounts.
        assertNull(read(phonepe, "Received", "Received ₹500 from Kumar and ₹300 from Ravi"))
        // No amount.
        assertNull(read(gpay, "Payment received", "Kumar paid you"))
        // Not a payment app.
        assertNull(KaiUpiNotice.read("com.whatsapp", "Kumar", "Received ₹500 from Kumar", null, 0))
        assertNull(KaiUpiNotice.read(null, "Kumar", "Received ₹500 from Kumar", null, 0))
    }

    @Test fun aBalanceAfterThePaymentIsNotTheAmount() {
        assertIn(read(bhim, "Credited", "₹500 credited from Kumar. Avl bal ₹12,340.00"), "500", "Kumar", "BHIM")
    }

    @Test fun aNameThatHoldsAPromoWordIsStillAName() {
        // "Billa" is a name, not a bill.
        assertIn(read(gpay, "Payment received", "₹200 received from Billa"), "200", "Billa", "GPay")
    }

    @Test fun bigTextIsReadToo() {
        assertIn(read(gpay, "Payment received", "₹1 received", "₹1 received from Praba via UPI"), "1", "Praba", "GPay")
    }

    @Test fun sameRupeeAmountShapes() {
        assertEquals(BigDecimal("1"), KaiUpiNotice.amountIn("₹1"))
        assertEquals(BigDecimal("2500.5"), KaiUpiNotice.amountIn("₹ 2,500.50"))
        assertEquals(BigDecimal("500"), KaiUpiNotice.amountIn("Rs.500"))
        assertEquals(BigDecimal("100000"), KaiUpiNotice.amountIn("Rs 1,00,000"))
        assertEquals(BigDecimal("75"), KaiUpiNotice.amountIn("INR 75"))
        // The same amount twice is one amount.
        assertEquals(BigDecimal("500"), KaiUpiNotice.amountIn("₹500 received. ₹500 from Kumar"))
        assertNull(KaiUpiNotice.amountIn("₹0"))
    }

    @Test fun samePaymentTwiceIsOne() {
        val seen = KaiUpiSeen()
        val c = UpiCredit(BigDecimal("500"), "Kumar", "GPay", 10_000)
        assertTrue(seen.firstTime(c))
        // The app updates its notification; a second app says the same payment.
        assertFalse(seen.firstTime(c.copy(at = 12_000)))
        assertFalse(seen.firstTime(c.copy(app = "PhonePe", at = 40_000)))
        // Another person, or another amount: a new payment.
        assertTrue(seen.firstTime(c.copy(sender = "Ravi", at = 41_000)))
        assertTrue(seen.firstTime(c.copy(amount = BigDecimal("300"), at = 42_000)))
        // The same again, long after: a new payment.
        assertTrue(seen.firstTime(c.copy(at = 10_000 + KaiUpiSeen.WINDOW_MS + 60_000)))
    }

    @Test fun modeWords() {
        assertEquals("gpay", UpiCredit(BigDecimal.ONE, null, "GPay", 0).modeWord)
        assertEquals("phonepe", UpiCredit(BigDecimal.ONE, null, "PhonePe", 0).modeWord)
        assertEquals("paytm", UpiCredit(BigDecimal.ONE, null, "Paytm Business", 0).modeWord)
        assertEquals("upi", UpiCredit(BigDecimal.ONE, null, "BHIM", 0).modeWord)
    }

    /** Only customers' money gets Kai's alert; a friend's or family's stays quiet (owner, 23 Oct 2026). */
    @Test fun customersOnly() {
        val books = listOf("Kumar", "Selvi", "முருகன்", "Sri Balaji Agencies")
        fun d(sender: String?, personal: Set<String> = emptySet(), ask: Boolean = false) =
            KaiUpiFilter.decide(UpiCredit(BigDecimal("500"), sender, "GPay", 0), books, personal, ask)
        assertEquals(KaiUpiFilter.Decision.CUSTOMER, d("Kumar"))
        assertEquals(KaiUpiFilter.Decision.CUSTOMER, d("KUMAR".lowercase().replaceFirstChar { it.uppercase() }))
        assertEquals(KaiUpiFilter.Decision.CUSTOMER, d("Murugan"))
        // A full name with a customer's first name: may be them — Kai asks (never drafts).
        assertEquals(KaiUpiFilter.Decision.CUSTOMER, d("Kumar Selvam"))
        // A friend: quiet. No name: quiet.
        assertEquals(KaiUpiFilter.Decision.QUIET, d("Praba"))
        assertEquals(KaiUpiFilter.Decision.QUIET, d(null))
        // The owner asked about everyone (a demo): asked.
        assertEquals(KaiUpiFilter.Decision.UNKNOWN, d("Praba", ask = true))
        assertEquals(KaiUpiFilter.Decision.UNKNOWN, d(null, ask = true))
        // Marked personal: never — even a customer's name, even with "ask about everyone" on.
        assertEquals(KaiUpiFilter.Decision.PERSONAL, d("Praba", setOf("praba"), ask = true))
        assertEquals(KaiUpiFilter.Decision.PERSONAL, d("PRABA ", setOf(KaiUpiFilter.key("Praba"))))
        // Two letters of a first name are not a match ("Sr" is not Sri Balaji).
        assertEquals(KaiUpiFilter.Decision.QUIET, d("Sr Kannan"))
    }

    /** The owner's phone's own notifications go here (from screenshots) before release. */
    @Test fun ownersPhone() {
        // GPay, 23 Oct 2026 (owner's screenshots): collapsed, and expanded with the payer's app under it.
        assertIn(read(gpay, "Prabhakaran paid you ₹1.00", "Tap to view."), "1", "Prabhakaran", "GPay")
        assertIn(read(gpay, "Prabhakaran paid you ₹1.00", "Payment from PhonePe"), "1", "Prabhakaran", "GPay")
        assertIn(read(gpay, "Prabhakaran paid you ₹1.00", "Tap to view.", "Payment from PhonePe"), "1", "Prabhakaran", "GPay")
        // The same line as the text, no title.
        assertIn(read(gpay, null, "Prabhakaran paid you ₹1.00"), "1", "Prabhakaran", "GPay")
        // Another payer's app under it is never the payer.
        listOf("Payment from Paytm", "Payment from Google Pay", "Payment from BHIM", "Payment from Amazon Pay", "Payment from WhatsApp").forEach { under ->
            assertIn(read(gpay, "Selvi paid you ₹250.00", under), "250", "Selvi", "GPay")
        }
        // Payer's app only, no person: no name (asked), never "PhonePe".
        assertIn(read(gpay, "₹1.00 received", "Payment from PhonePe"), "1", null, "GPay")
    }
}
