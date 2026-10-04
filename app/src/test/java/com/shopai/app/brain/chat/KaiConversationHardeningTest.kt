package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.tools.KaiCommand
import com.shopai.app.brain.tools.KaiCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/** Natural language safety and direction cases. Each row is an independent focused JUnit case. */
@RunWith(Parameterized::class)
class KaiConversationHardeningTest(
    private val area: String,
    private val input: String,
    private val expected: String,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}: {1}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("payment-in", "Kumar gave me 5000 cash today", "IN"),
            arrayOf("payment-in", "Kumar gave me 5k cash", "IN"),
            arrayOf("payment-in", "Kumar inniku 5k cash kuduthan", "IN"),
            arrayOf("payment-in", "Kumar enakku 5000 kuduthaar", "IN"),
            arrayOf("payment-in", "Kumar received by me 5000", "IN"),
            arrayOf("payment-in", "Kumar paid me 5000", "IN"),
            arrayOf("payment-in", "Kumar 5000 cash kuduthaan", "IN"),
            arrayOf("payment-in", "Kumar 5000 received today", "IN"),
            arrayOf("payment-out", "I gave Kumar 5000 cash", "OUT"),
            arrayOf("payment-out", "Kumar-ku 5000 kuduthen", "OUT"),
            arrayOf("payment-out", "I paid Kumar 5000", "OUT"),
            arrayOf("payment-out", "Ramesh ku 5000 anuppinen", "OUT"),

            arrayOf("correction", "Illai 500 dhaan", "500.00"),
            arrayOf("correction", "500 dhaan", "500.00"),
            arrayOf("correction", "Actually 500", "500.00"),
            arrayOf("correction", "No, 500", "500.00"),
            arrayOf("correction", "₹500 dhaan", "500.00"),
            arrayOf("correction", "Amount 500", "500.00"),
            arrayOf("correction", "500 received", "500.00"),
            arrayOf("correction", "5k only", "5000.00"),
            arrayOf("correction", "Actually ₹1,250", "1250.00"),

            arrayOf("cancel", "Venda", "true"),
            arrayOf("cancel", "Venaam", "true"),
            arrayOf("cancel", "Vendam", "true"),
            arrayOf("cancel", "Cancel", "true"),
            arrayOf("cancel", "Cancel pannu", "true"),
            arrayOf("cancel", "Skip", "true"),
            arrayOf("cancel", "Vidunga", "true"),
            arrayOf("cancel", "Don't add", "true"),
            arrayOf("cancel", "Add panna vendam", "true"),
            arrayOf("cancel", "Illa", "true"),
            arrayOf("not-cancel", "Illa 500 dhaan", "false"),
        )
    }

    @Test
    fun naturalPaymentCorrectionAndCancellationCases() {
        when (area) {
            "payment-in", "payment-out" -> {
                val command = KaiCommands.route(input, LocalDateTime.of(2026, 10, 3, 16, 20), listOf("Kumar", "Ramesh"))
                assertTrue("Expected a payment route for: $input, got $command", command is KaiCommand.Payment)
                val payment = command as KaiCommand.Payment
                assertEquals(expected, if (payment.outgoing) "OUT" else "IN")
                assertNotNull("Payment amount should be recognized: $input", payment.amount)
                assertEquals(PaymentMode.CASH, payment.mode)
            }
            "correction" -> assertEquals(BigDecimal(expected), KaiConversationSemantics.correctionAmount(input))
            "cancel" -> assertTrue("Expected cancellation: $input", KaiConversationSemantics.cancelsDraft(input))
            "not-cancel" -> assertFalse("Correction must not cancel: $input", KaiConversationSemantics.cancelsDraft(input))
            else -> throw AssertionError("Unknown case area: $area")
        }
    }
}
