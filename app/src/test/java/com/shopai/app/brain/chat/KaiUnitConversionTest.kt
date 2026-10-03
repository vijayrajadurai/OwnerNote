package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.ConversionSave
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.KaiUnits
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.QtyPart
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.brain.tools.UnitResolution
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Stock in / out in another unit: converted with the PRODUCT's own conversion
 * (the books' secondary unit), asked when unknown, never "2 boxes = 2 pieces";
 * the inventory gets exactly the total shown on the draft, only after Confirm.
 */
class KaiUnitConversionTest {
    private val now = LocalDateTime.of(2026, 10, 3, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** One business's products; a conversion is kept like the books do: one second unit per product. */
    private class Tools(products: List<ProductRef>) : KaiTools {
        val productList = products.toMutableList()
        val stockChanges = mutableListOf<Triple<String, BigDecimal, Boolean>>()
        val notes = mutableListOf<String>()
        override suspend fun parties(name: String) = emptyList<com.shopai.app.brain.tools.PartyMatch>()
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("x", kind, partyName, partyId, amount, mode, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome = ActionOutcome.Done("X", null)
        override suspend fun products() = productList.toList()
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            stockChanges += Triple(product.id, qty, incoming)
            notes += said
            val i = productList.indexOfFirst { it.id == product.id }
            val now = productList[i].stock + if (incoming) qty else qty.negate()
            productList[i] = productList[i].copy(stock = now)
            return ActionOutcome.Done(product.name, now)
        }
        override suspend fun saveUnitConversion(productId: String, unit: String, perUnit: BigDecimal): ConversionSave {
            val i = productList.indexOfFirst { it.id == productId }
            val p = productList[i]
            if (p.conversions.isNotEmpty() && unit !in p.conversions) return ConversionSave.OTHER_UNIT_SET
            productList[i] = p.copy(conversions = mapOf(unit to perUnit))
            return ConversionSave.SAVED
        }
        override fun createReminder(reminder: KaiReminder) = ReminderSaved(reminder, false, ScheduleResult.EXACT)
        override fun reminders(): List<KaiReminder> = emptyList()
        override fun zone(): String = "Asia/Kolkata"
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K"
    }

    private class Access(store: InMemoryKaiMemoryStore, val tools: Tools) : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1L })
        override suspend fun current() = memory.also { it.open("biz-A", "owner-A") }
        override suspend fun entities() = tools.productList.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) }
    }

    private fun colgate(stock: Int = 0, box: Int? = 12) =
        ProductRef("p1", "Colgate", "PCS", BigDecimal(stock), box?.let { mapOf("BOX" to BigDecimal(it)) }.orEmpty())
    private fun pepsodent(box: Int? = null) = ProductRef("p2", "Pepsodent", "PCS", BigDecimal.ZERO, box?.let { mapOf("BOX" to BigDecimal(it)) }.orEmpty())
    private val rice = ProductRef("p3", "Rice", "KG", BigDecimal(10))

    private fun agent(tools: Tools, memory: KaiMemoryAccess? = null) = KaiAgent(KaiBusinessBrain(Books()), Books(), tools, { now }, memory)
    private fun KaiTurn.actions() = card?.buttons.orEmpty().map { it.action }
    private fun KaiTurn.labels() = card?.buttons.orEmpty().map { it.label }
    private suspend fun KaiAgent.confirmFrom(t: KaiTurn) = act(t.actions().filterIsInstance<KaiAction.ConfirmStock>().single(), KaiLang.TANGLISH)!!

    // TEST 1: 1 box = 12 pieces → "Colgate 2 box add" → 24 pieces.
    @Test
    fun knownConversionStockIn() = runBlocking {
        val tools = Tools(listOf(colgate()))
        val kai = agent(tools)
        val d = kai.ask("Colgate 2 box add pannu")
        assertEquals("Colgate — 2 boxes = 24 pieces stock-in draft ready. Confirm pannunga.", d.reply.text)
        assertEquals(listOf("Colgate", "Input: 2 boxes", "Conversion: 1 box = 12 pieces", "Total: 24 pieces", "Inventory: +24 pieces (0 pieces → 24 pieces)"), d.card!!.lines)
        assertEquals(listOf("Confirm Stock In", "Edit", "Cancel"), d.labels())
        assertTrue("nothing before Confirm", tools.stockChanges.isEmpty())
        val done = kai.confirmFrom(d)
        assertEquals(Triple("p1", BigDecimal("24"), true), tools.stockChanges.single())
        assertTrue(done.reply.text, done.reply.text.startsWith("Done Owner ✅ Colgate — 2 boxes added. Inventory +24 pieces."))
        // The history keeps what the owner said and the conversion.
        assertTrue(tools.notes.single(), tools.notes.single().startsWith("2 boxes = 24 pieces · "))
    }

    // TEST 2: base unit — no conversion.
    @Test
    fun baseUnitNeedsNoConversion() = runBlocking {
        val tools = Tools(listOf(colgate()))
        val kai = agent(tools)
        val d = kai.ask("Colgate 5 pieces add pannu")
        assertEquals("Colgate — 5 pieces stock-in draft ready. Confirm pannunga.", d.reply.text)
        assertEquals(listOf("Confirm", "Edit", "Cancel"), d.labels())
        kai.confirmFrom(d)
        assertEquals(Triple("p1", BigDecimal("5"), true), tools.stockChanges.single())
    }

    // TEST 3 + section 4: unknown conversion → asked, nothing drafted or changed; the answer gives the draft.
    @Test
    fun unknownConversionIsAsked() = runBlocking {
        val tools = Tools(listOf(pepsodent()))
        val kai = agent(tools)
        val ask = kai.ask("Pepsodent 5 box add pannu")
        assertEquals("Owner, 1 box-la evlo pieces irukku?", ask.reply.text)
        assertFalse(ask.actions().any { it is KaiAction.ConfirmStock })
        assertTrue(tools.stockChanges.isEmpty())
        val d = kai.ask("12")
        assertTrue(d.reply.text, d.reply.text.startsWith("Seri Owner 👍\n1 box = 12 pieces.\n5 boxes = 60 pieces.\nStock-in draft ready."))
        assertEquals(listOf("Confirm Stock In", "Edit", "Cancel", "Save: 1 box = 12 pieces"), d.labels())
        assertTrue(tools.stockChanges.isEmpty())
        kai.confirmFrom(d)
        assertEquals(Triple("p2", BigDecimal("60"), true), tools.stockChanges.single())
        // Not saved for the product unless the owner taps Save: a new conversation asks again.
        assertEquals("Owner, 1 box-la evlo pieces irukku?", agent(tools).ask("Pepsodent 1 box add pannu").reply.text)
    }

    // TEST 4 + sections 6/7/16: personal word → unit → product conversion → draft (memory never sets the quantity).
    @Test
    fun personalWordResolvesToUnitThenConverts() = runBlocking {
        val tools = Tools(listOf(colgate()))
        val access = Access(InMemoryKaiMemoryStore(), tools)
        val kai = agent(tools, access)
        kai.ask("'potti' na box")
        kai.ask("aama")
        val d = kai.ask("Colgate 2 potti add pannu")
        assertEquals("Colgate — 2 boxes = 24 pieces stock-in draft ready. Confirm pannunga.", d.reply.text)
        assertTrue(tools.stockChanges.isEmpty())
        kai.confirmFrom(d)
        assertEquals(Triple("p1", BigDecimal("24"), true), tools.stockChanges.single())
    }

    // TEST 5: stock-out converts too.
    @Test
    fun stockOutConverts() = runBlocking {
        val tools = Tools(listOf(colgate(stock = 24)))
        val kai = agent(tools)
        val d = kai.ask("2 box Colgate pochu")
        assertEquals("Seri Owner. Colgate — 2 boxes stock-out = 24 pieces. Remaining: 0 pieces.", d.reply.text)
        assertTrue(d.card!!.lines.contains("Inventory: −24 pieces (24 pieces → 0 pieces)"))
        assertEquals("Confirm Stock Out", d.labels().first())
        val done = kai.confirmFrom(d)
        assertEquals(Triple("p1", BigDecimal("24"), false), tools.stockChanges.single())
        assertTrue(done.reply.text, done.reply.text.startsWith("Done Owner ✅ Colgate — 2 boxes out. Inventory −24 pieces."))
        // More boxes than the stock: Confirm is disabled.
        val tooMany = kai.ask("3 box Colgate pochu")
        assertFalse(tooMany.card!!.buttons.first().enabled)
    }

    // TEST 6: mixed units keep the owner's words.
    @Test
    fun mixedUnits() = runBlocking {
        val tools = Tools(listOf(colgate()))
        val kai = agent(tools)
        val d = kai.ask("1 box 3 pieces Colgate add pannu")
        assertEquals("Colgate — 1 box + 3 pieces = 15 pieces stock-in draft ready. Confirm pannunga.", d.reply.text)
        kai.confirmFrom(d)
        assertEquals(Triple("p1", BigDecimal("15"), true), tools.stockChanges.single())
    }

    // TEST 7: product-specific conversion.
    @Test
    fun productSpecificConversion() = runBlocking {
        val tools = Tools(listOf(colgate(), pepsodent(box = 24)))
        val kai = agent(tools)
        assertTrue(kai.ask("Colgate 2 box add pannu").reply.text.contains("2 boxes = 24 pieces"))
        assertTrue(kai.ask("Pepsodent 2 box add pannu").reply.text.contains("2 boxes = 48 pieces"))
    }

    // TEST 8: edit recalculates (never the old total).
    @Test
    fun editRecalculates() = runBlocking {
        val tools = Tools(listOf(colgate()))
        val kai = agent(tools)
        val d = kai.ask("Colgate 5 box add pannu")
        val key = d.actions().filterIsInstance<KaiAction.EditStock>().single().key
        assertEquals(Triple("Colgate", BigDecimal("5"), "BOX"), kai.stockDraftOf(key)) // the form shows what was said
        val edited = kai.reviseStock(key, BigDecimal("4"), "BOX", KaiLang.TANGLISH)!!
        assertTrue(edited.reply.text, edited.reply.text.contains("4 boxes = 48 pieces"))
        kai.confirmFrom(edited)
        assertEquals(Triple("p1", BigDecimal("48"), true), tools.stockChanges.single())
        // A conversion corrected in chat recalculates the open draft too.
        kai.ask("Colgate 5 box add pannu")
        val fixed = kai.ask("1 box = 10 pieces")
        assertTrue(fixed.reply.text, fixed.reply.text.contains("5 boxes = 50 pieces"))
    }

    // TEST 9: cancel → nothing changes.
    @Test
    fun cancelChangesNothing() = runBlocking {
        val tools = Tools(listOf(colgate()))
        val kai = agent(tools)
        val d = kai.ask("Colgate 5 box add pannu")
        kai.act(d.actions().filterIsInstance<KaiAction.CancelStock>().single(), KaiLang.TANGLISH)
        // Cancelling the conversion question also changes nothing.
        val tools2 = Tools(listOf(pepsodent()))
        val kai2 = agent(tools2)
        val q = kai2.ask("Pepsodent 5 box add pannu")
        kai2.act(q.actions().filterIsInstance<KaiAction.CancelStock>().single(), KaiLang.TANGLISH)
        kai2.ask("12")
        assertTrue(tools.stockChanges.isEmpty() && tools2.stockChanges.isEmpty())
    }

    // TEST 10 + section 17: each business has its own products and conversions.
    @Test
    fun businessesNeverShareConversions() = runBlocking {
        val a = Tools(listOf(colgate(box = 12), pepsodent()))
        val b = Tools(listOf(colgate(box = 24), pepsodent()))
        assertTrue(agent(a).ask("Colgate 1 box add pannu").reply.text.contains("1 box = 12 pieces"))
        assertTrue(agent(b).ask("Colgate 1 box add pannu").reply.text.contains("1 box = 24 pieces"))
        // A conversion saved in A stays in A.
        val kaiA = agent(a)
        kaiA.ask("Pepsodent 1 box add pannu")
        val d = kaiA.ask("6")
        val save = kaiA.act(d.actions().filterIsInstance<KaiAction.SaveUnitConversion>().single(), KaiLang.TANGLISH)!!
        assertEquals("Done Owner 👍 Pepsodent-ku 1 box = 6 pieces save panniten. Inime kekka maatten.", save.reply.text)
        assertTrue(agent(a).ask("Pepsodent 2 box add pannu").reply.text.contains("2 boxes = 12 pieces"))
        assertEquals("Owner, 1 box-la evlo pieces irukku?", agent(b).ask("Pepsodent 2 box add pannu").reply.text)
    }

    // Section 9: an unknown unit for the product — "1 packet = 3 pieces", saved only on Save.
    @Test
    fun packetConversionSavedOnlyOnSave() = runBlocking {
        val tools = Tools(listOf(pepsodent(), colgate()))
        val kai = agent(tools)
        assertEquals("Owner, 1 packet-la evlo pieces irukku?", kai.ask("Pepsodent 5 packet add pannu").reply.text)
        val d = kai.ask("1 packet = 3 pieces")
        assertTrue(d.reply.text, d.reply.text.contains("5 packets = 15 pieces"))
        assertTrue(tools.productList.first { it.id == "p2" }.conversions.isEmpty())
        kai.act(d.actions().filterIsInstance<KaiAction.SaveUnitConversion>().single(), KaiLang.TANGLISH)
        assertEquals(BigDecimal(3), tools.productList.first { it.id == "p2" }.conversions["PACK"])
        // Colgate already has its box: a packet size can't take the same slot — said, and used only in this chat.
        kai.ask("Colgate 2 packet add pannu")
        val c = kai.ask("4")
        val res = kai.act(c.actions().filterIsInstance<KaiAction.SaveUnitConversion>().single(), KaiLang.TANGLISH)!!
        assertTrue(res.reply.text, res.reply.text.contains("indha conversation-ku mattum"))
        assertEquals(setOf("BOX"), tools.productList.first { it.id == "p1" }.conversions.keys)
    }

    // Section 13: weight units convert only where the conversion is fixed; dozen is 12.
    @Test
    fun weightsAndDozens() = runBlocking {
        val tools = Tools(listOf(rice, colgate(box = null)))
        val kai = agent(tools)
        assertEquals("Rice — 2 kg stock-in draft ready. Confirm pannunga.", kai.ask("Rice 2 kg add pannu").reply.text)
        val g = kai.ask("Rice 500 gram add pannu")
        assertTrue(g.reply.text, g.reply.text.contains("500 grams = 0.5 kg"))
        kai.confirmFrom(g)
        assertEquals(Triple("p3", BigDecimal("0.5"), true), tools.stockChanges.single())
        assertTrue(kai.ask("Colgate 2 dozen add pannu").reply.text.contains("2 dozen = 24 pieces"))
        // A unit that doesn't convert (litre for rice in kg) is asked, never guessed.
        assertEquals("Owner, 1 litre-la evlo kg irukku?", kai.ask("Rice 3 litre add pannu").reply.text)
    }

    @Test
    fun resolverIsPure() {
        val c = colgate()
        val ok = KaiUnits.resolve(c, listOf(QtyPart(BigDecimal(1), "BOX"), QtyPart(BigDecimal(3), null))) as UnitResolution.Ok
        assertEquals(BigDecimal(15), ok.baseQty)
        assertEquals(mapOf("BOX" to BigDecimal(12)), ok.rules)
        assertEquals(UnitResolution.Missing("PACK", listOf(QtyPart(BigDecimal(2), "PACK"))), KaiUnits.resolve(c, listOf(QtyPart(BigDecimal(2), "PACK"))))
        assertEquals(listOf(QtyPart(BigDecimal(1), "BOX"), QtyPart(BigDecimal(3), "PCS")), com.shopai.app.brain.tools.KaiStock.partsIn("1 box 3 pieces"))
        assertEquals(listOf(QtyPart(BigDecimal(2), "KG")), com.shopai.app.brain.tools.KaiStock.partsIn("2kg"))
        assertEquals(listOf(QtyPart(BigDecimal(2), "BOX")), com.shopai.app.brain.tools.KaiStock.partsIn("rendu box"))
    }
}
