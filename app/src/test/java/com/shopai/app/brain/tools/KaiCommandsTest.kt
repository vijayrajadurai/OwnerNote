package com.shopai.app.brain.tools

import com.shopai.app.books.model.PaymentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/** What the owner wants Kai to do — Tamil, Tanglish and English — decided by rules. */
class KaiCommandsTest {
    private val now = LocalDateTime.of(2026, 10, 3, 16, 20)
    private val people = listOf("Kumar", "Ramesh", "ABC Traders")
    private fun route(text: String) = KaiCommands.route(text, now, people)

    @Test
    fun calculations() {
        assertTrue(route("10*3") is KaiCommand.Calculate)
        assertTrue(route("25000 la 18% GST evlo?") is KaiCommand.Calculate)
        assertTrue(route("50 kg × ₹82") is KaiCommand.Calculate)
    }

    @Test
    fun moneyGivenAndReceived() {
        val out = route("Ramesh ku 5000 kuduthen") as KaiCommand.Payment
        assertEquals("Ramesh", out.name)
        assertEquals(0, BigDecimal("5000").compareTo(out.amount))
        assertTrue(out.outgoing)
        assertEquals(PaymentMode.CASH, out.mode)
        val inn = route("Kumar kitta 10000 vanginen") as KaiCommand.Payment
        assertEquals("Kumar", inn.name)
        assertTrue(!inn.outgoing)
        val upi = route("Ramesh kitta 2000 gpay la vanginen") as KaiCommand.Payment
        assertEquals(PaymentMode.UPI, upi.mode)
        // A due, a question, or buying goods is not a payment.
        assertEquals(KaiCommand.Question, route("Kumar kita 5000 pending"))
        assertEquals(KaiCommand.Question, route("Kumar 5000 kuduthana?"))
        assertTrue(route("Kumar kitta 10 kg rice vanginen") !is KaiCommand.Payment)
    }

    @Test
    fun reminders() {
        val call = route("Kumar ku 10 minutes kalichi call pannanum") as KaiCommand.Remind
        assertEquals("Kumar", call.callName)
        assertEquals(now.plusMinutes(10), call.time!!.at)
        val call2 = route("10 minutes kalichi Kumar-ku call panna remind pannu") as KaiCommand.Remind
        assertEquals("Kumar", call2.callName)
        val keys = route("Daily kaalaila 10 maniku saavi eduthuka remind pannu") as KaiCommand.Remind
        assertEquals(Repeat.DAILY, keys.time!!.repeat)
        assertEquals("saavi eduthuka", keys.task)
        assertEquals(null, keys.callName)
        val supplier = route("Naalaiku supplier-ku payment remind pannu") as KaiCommand.Remind
        assertEquals(now.toLocalDate().plusDays(1), supplier.time!!.at.toLocalDate())
        val noTime = route("Kumar ku call panna remind pannu") as KaiCommand.Remind
        assertEquals(null, noTime.time)
        assertEquals(KaiCommand.ListReminders, route("En reminders enna?"))
        assertTrue(route("Kumar reminder cancel pannu") is KaiCommand.CancelReminder)
    }

    @Test
    fun callScanStockMoney() {
        assertEquals(KaiCommand.Call("Kumar"), route("Kumar-ku call pannu"))
        assertEquals(KaiCommand.ScanBill(false), route("Indha bill add pannu"))
        assertEquals(KaiCommand.ScanBill(true), route("Indha bill purchase ah?"))
        assertEquals(KaiCommand.Stock("Rice"), route("Rice stock evlo?"))
        assertEquals(KaiCommand.LowStock, route("Which stock is low?"))
        assertEquals(KaiCommand.LowStock, route("Enna reorder pannanum?"))
        assertEquals(KaiCommand.MoneyBalance(MoneyKind.CASH), route("Enakku evlo cash iruku?"))
        assertEquals(KaiCommand.MoneyBalance(MoneyKind.BANK), route("Bank la evlo balance iruku?"))
        assertEquals(KaiCommand.TopProducts, route("Indha month highest selling product enna?"))
        assertEquals(KaiCommand.Question, route("Inniku evlo sales?"))
        assertEquals(KaiCommand.Question, route("Kumar kita evlo pending?"))
    }
}
