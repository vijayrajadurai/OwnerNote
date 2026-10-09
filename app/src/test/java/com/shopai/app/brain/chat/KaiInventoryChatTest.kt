package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductChange
import com.shopai.app.brain.tools.StockBill
import com.shopai.app.brain.tools.ItemField
import com.shopai.app.brain.tools.ItemSpec
import com.shopai.app.brain.tools.KaiInventory
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.NewProduct
import com.shopai.app.brain.tools.ProductKind
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.StockFact
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Inventory by chat (owner's request, 9 Oct 2026): a new product is added in the conversation — Kai asks only
 * the missing detail for that kind of product, one at a time, shows the totals and saves ONE product with ONE
 * opening movement on Confirm. Checked on the inventory's state (products, units, prices, movements), not only
 * on Kai's words.
 */
class KaiInventoryChatTest {
    private val now = LocalDateTime.of(2026, 10, 9, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c1", "Ramesh", "9000000001", 1000.0, null)),
            suppliers = listOf(PartySummary("s1", "ABC Traders", null, 0.0, null)),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** An in-memory inventory: products with units / prices / minimum, and every movement written. */
    class Inventory : KaiTools {
        data class Item(
            val id: String, val name: String, val unit: String, var stock: BigDecimal, val conversions: Map<String, BigDecimal>,
            var purchase: BigDecimal?, var selling: BigDecimal?, val category: String, val size: String?, var minimum: BigDecimal? = null,
        )
        data class Move(val itemId: String, val qty: BigDecimal, val type: String, val said: String)

        val items = mutableListOf<Item>()
        val moves = mutableListOf<Move>()
        /** Customers / suppliers with their balance (receivable / payable). */
        val parties = mutableListOf(PartyMatch("c1", "Ramesh", true, null, BigDecimal("1000")), PartyMatch("s1", "ABC Traders", false, null, BigDecimal.ZERO))
        val bills = mutableListOf<StockBill>()
        val payments = mutableListOf<ActionPlan>()
        var failBill = false
        val created = mutableListOf<NewProduct>()
        val logs = mutableListOf<Pair<String, ActionStatus>>()
        var failCreate = false

        fun item(name: String) = items.single { it.name.equals(name, true) }

        override suspend fun products() = items.map { ProductRef(it.id, it.name, it.unit, it.stock, it.conversions, it.purchase, it.selling, it.minimum, it.category) }
        override suspend fun parties(name: String) = parties.filter { it.name.contains(name, true) }
        override suspend fun updateProduct(productId: String, change: ProductChange): ActionOutcome {
            val i = items.first { it.id == productId }
            change.purchasePrice?.let { i.purchase = it }
            change.sellingPrice?.let { i.selling = it }
            change.minStock?.let { i.minimum = it }
            return ActionOutcome.Done(i.name, null)
        }
        /** One bill = one write: the stock and the party's balance change together, or nothing changes. */
        override suspend fun stockBill(bill: StockBill): ActionOutcome {
            val i = items.first { it.id == bill.product.id }
            if (failBill) return ActionOutcome.Failed("rejected")
            if (!bill.purchase && i.stock < bill.qty) return ActionOutcome.Failed("only ${i.stock} in stock")
            i.stock = if (bill.purchase) i.stock + bill.qty else i.stock - bill.qty
            moves += Move(i.id, if (bill.purchase) bill.qty else bill.qty.negate(), if (bill.purchase) "PURCHASE" else "SALE", bill.said)
            if (bill.credit) parties.replaceAll { if (it.id == bill.partyId) it.copy(balance = it.balance + bill.total) else it }
            bills += bill
            return ActionOutcome.Done("BILL-${bills.size}", i.stock)
        }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("pay${payments.size}", kind, partyName, partyId, amount, mode, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            payments += plan
            parties.replaceAll { if (it.id == plan.partyId) it.copy(balance = it.balance - plan.amount) else it }
            return ActionOutcome.Done("PAY-${payments.size}", parties.first { it.id == plan.partyId }.balance)
        }
        override suspend fun stock(product: String?) = items.filter { product == null || it.name.equals(product, true) }
            .map { StockFact(it.name, it.stock, it.unit, it.minimum) }
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            val i = items.first { it.id == product.id }
            if (!incoming && i.stock < qty) return ActionOutcome.Failed("only ${i.stock.toPlainString()} ${i.unit} in stock")
            i.stock = if (incoming) i.stock + qty else i.stock - qty
            moves += Move(i.id, if (incoming) qty else qty.negate(), if (incoming) "IN" else "OUT", said)
            return ActionOutcome.Done(i.name, i.stock)
        }
        override suspend fun createProduct(product: NewProduct): ProductRef? {
            // The books reject a second product with the same name; a rejected write saves nothing.
            if (failCreate || items.any { it.name.equals(product.name, true) }) return null
            created += product
            val conv = if (product.secondaryUnit != null && product.perSecondary != null) mapOf(product.secondaryUnit!! to product.perSecondary!!) else emptyMap()
            val i = Item("n${items.size + 1}", product.name, product.unit, product.openingQty ?: BigDecimal.ZERO, conv,
                product.purchasePrice, product.sellingPrice, product.category, product.weight ?: product.packSize)
            items += i
            if ((product.openingQty?.signum() ?: 0) > 0) moves += Move(i.id, product.openingQty!!, "OPENING", "")
            return ProductRef(i.id, i.name, i.unit, i.stock, conv)
        }
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?): String {
            logs += intent to status
            return "K-${logs.size}"
        }
    }

    private val inv = Inventory()
    private val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
    private fun say(text: String) = runBlocking { kai.ask(text) }

    private fun near(a: BigDecimal?, b: String) = a != null && a.compareTo(BigDecimal(b)) == 0
    private fun tap(t: KaiTurn) = runBlocking { kai.act(t.card!!.buttons.first { it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmPlan }.action, KaiLang.TANGLISH) }!!
    private fun balance(name: String) = inv.parties.single { it.name == name }.balance

    /** The owner's shop from the request (section 27): Colgate 5 boxes, Rice 10 bags, Oil 20 boxes. */
    private fun stocked() {
        inv.items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), BigDecimal("28"), BigDecimal("35"), "FMCG", "200 g")
        inv.items += Inventory.Item("p2", "Rice", "KG", BigDecimal("250"), mapOf("BAG" to BigDecimal("25")), BigDecimal("56"), BigDecimal("65"), "Grocery", null)
        inv.items += Inventory.Item("p3", "Oil", "BOTTLE", BigDecimal("240"), mapOf("BOX" to BigDecimal("12")), null, null, "Liquids", "1 litre")
    }

    // ------------------------------------------------------------------ the owner's own example, one question at a time

    @Test
    fun colgateFiveBoxesAskedOneDetailAtATime() {
        assertEquals("Seri Owner 👍 1 box-la evlo pieces irukku?", say("Colgate 5 box add pannu").reply.text)
        assertEquals("Oru piece evlo gram? — theriyalana 'skip'", say("48").reply.text)
        assertEquals("Oru piece purchase rate evlo?", say("200 gram").reply.text)
        assertEquals("Oru piece selling rate evlo?", say("28 rupees").reply.text)
        val summary = say("35")
        assertEquals("Seri Owner. Colgate — 5 boxes = 240 pieces. Purchase ₹6,720. Selling value ₹8,400. Stock add pannattuma?", summary.reply.text)
        assertEquals(listOf("Colgate", "5 boxes = 240 pieces", "Ovvonnum 200 g (48 kg)", "Purchase ₹28/piece → ₹6,720", "Selling ₹35/piece → ₹8,400", "Margin ₹1,680"),
            summary.card!!.lines)
        assertTrue("nothing saved before Confirm", inv.items.isEmpty() && inv.moves.isEmpty())
        val done = say("aama")
        assertEquals("Done Owner ✅ Colgate — 5 boxes (240 pieces) stock-la add panniten. Ippo stock 240 pieces.", done.reply.text)
        val c = inv.item("Colgate")
        assertEquals("PCS", c.unit)
        assertTrue(near(c.stock, "240"))
        assertTrue(near(c.conversions["BOX"], "48"))
        assertTrue("purchase kept apart from selling", near(c.purchase, "28") && near(c.selling, "35"))
        assertEquals("FMCG", c.category)
        assertEquals("200 g", c.size)
        assertEquals("exactly one movement", listOf(Inventory.Move(c.id, BigDecimal("240"), "OPENING", "")), inv.moves)
        // Saying yes again saves nothing more.
        say("aama")
        assertEquals(1, inv.items.size)
        assertEquals(1, inv.moves.size)
    }

    @Test
    fun everythingInOneMessageIsNotAskedAgain() {
        // The owner's own sentence (Tamil "க்கு" in it → Kai answers in Tamil).
        val t = say("Colgate 5 box, boxக்கு 48 pieces, piece 200 gram, purchase 28, selling 35")
        assertEquals("சரி Owner. Colgate — 5 boxes = 240 pieces. Purchase ₹6,720. Selling value ₹8,400. Stock add பண்ணட்டுமா?", t.reply.text)
        assertTrue(t.card!!.lines.contains("ஒவ்வொன்றும் 200 g (48 kg)"))
        assertTrue(t.card!!.lines.contains("லாபம் ₹1,680"))
        assertTrue(inv.items.isEmpty())
        runBlocking { kai.act(t.card!!.buttons.first { it.action is KaiAction.ConfirmStock }.action, KaiLang.TAMIL) }
        assertTrue(near(inv.item("Colgate").stock, "240"))
        assertEquals(1, inv.moves.size)
    }

    @Test
    fun everythingInOneTanglishMessage() {
        val t = say("Colgate 5 box, boxku 48 pieces, piece 200 gram, purchase 28, selling 35")
        assertEquals("Seri Owner. Colgate — 5 boxes = 240 pieces. Purchase ₹6,720. Selling value ₹8,400. Stock add pannattuma?", t.reply.text)
        assertTrue(t.card!!.lines.contains("Ovvonnum 200 g (48 kg)"))
        assertTrue(t.card!!.lines.contains("Margin ₹1,680"))
        say("aama")
        val c = inv.item("Colgate")
        assertTrue(near(c.stock, "240") && near(c.purchase, "28") && near(c.selling, "35") && near(c.conversions["BOX"], "48"))
    }

    // ------------------------------------------------------------------ category decides the questions

    @Test
    fun riceIsBoughtByTheBagAndSoldByTheKg() {
        assertEquals("Oru bag purchase rate evlo?", say("Rice 10 bags, 25kg each").reply.text)
        assertEquals("Oru kg selling rate evlo?", say("1400").reply.text)
        val s = say("65")
        assertEquals("Seri Owner. Rice — 10 bags = 250 kg. Purchase ₹14,000. Selling value ₹16,250. Stock add pannattuma?", s.reply.text)
        say("seri")
        val r = inv.item("Rice")
        assertEquals("KG", r.unit)
        assertTrue(near(r.stock, "250") && near(r.conversions["BAG"], "25"))
        assertTrue("₹1,400 a bag = ₹56 a kg", near(r.purchase, "56") && near(r.selling, "65"))
        assertEquals("Grocery", r.category)
    }

    @Test
    fun riceBagSizeIsAskedInKg() {
        assertEquals("Seri Owner 👍 1 bag evlo kg?", say("Rice 10 bag vandhiruku").reply.text)
        assertEquals("Oru bag purchase rate evlo?", say("25 kg").reply.text)
    }

    @Test
    fun oilBoxesOfLitreBottles() {
        assertEquals("Oru bottle purchase rate evlo?", say("Oil 2 boxes, 12 bottles each, 1 litre bottle").reply.text)
        say("150"); val s = say("180")
        assertEquals("Seri Owner. Oil — 2 boxes = 24 bottles. Purchase ₹3,600. Selling value ₹4,320. Stock add pannattuma?", s.reply.text)
        assertTrue(s.card!!.lines.contains("Ovvonnum 1 litre (24 litre)"))
        say("aama")
        assertEquals("BOTTLE", inv.item("Oil").unit)
        assertEquals("Liquids", inv.item("Oil").category)
    }

    @Test
    fun shirtsAreNeverAskedForGrams() {
        val t = say("Shirt 20 pieces, M 8 L 7 XL 5")
        assertEquals("Oru piece purchase rate evlo?", t.reply.text)
        say("250"); val s = say("400")
        assertTrue(s.card!!.lines.contains("Size: M 8, L 7, XL 5"))
        assertTrue(s.reply.text.contains("Shirt — 20 pieces."))
        say("aama")
        assertEquals("Garments", inv.item("Shirt").category)
        assertEquals("M 8, L 7, XL 5", inv.item("Shirt").size)
    }

    @Test
    fun shirtSizeIsAskedAsSizesNotWeight() {
        assertEquals("Size enna Owner? (eg: M 8, L 7, XL 5) — theriyalana 'skip'", say("Shirt 20 pieces add pannu").reply.text)
        assertEquals("Oru piece purchase rate evlo?", say("skip").reply.text)
    }

    @Test
    fun cokeCasesAskBottlesThenMl() {
        assertEquals("1 bottle எத்தனை ml? — தெரியலனா 'skip'", say("Coke 3 case, caseக்கு 24 bottles").reply.text)
        assertEquals("ஒரு bottle purchase rate எவ்வளவு?", say("300 ml").reply.text)
        say("18"); val s = say("20")
        assertTrue(s.reply.text, s.reply.text.contains("Coke — 3 cases = 72 bottles."))
        assertTrue(s.card!!.lines.toString(), s.card!!.lines.contains("ஒவ்வொன்றும் 300 ml (21.6 litre)"))
    }

    @Test
    fun soapBoxesOfAHundred() {
        assertEquals("ஒரு piece எத்தனை gram? — தெரியலனா 'skip'", say("Soap 5 box, boxக்கு 100 pieces").reply.text)
        say("skip"); say("skip"); val s = say("skip")
        assertEquals("சரி Owner. Soap — 5 boxes = 500 pieces. Stock add பண்ணட்டுமா?", s.reply.text)
        say("aama")
        val soap = inv.item("Soap")
        assertTrue(near(soap.stock, "500") && soap.purchase == null && soap.selling == null)
    }

    @Test
    fun slippersArePairs() {
        assertEquals("Size enna Owner? (eg: 7, 8, 9) — theriyalana 'skip'", say("Slippers 10 pairs add pannu").reply.text)
        assertEquals("Oru pair purchase rate evlo?", say("7 8 9").reply.text)
    }

    @Test
    fun chargersAreAskedTheirModelNotWeight() {
        assertEquals("Seri Owner 👍 1 box-la evlo pieces irukku?", say("Charger 2 box add pannu").reply.text)
        assertEquals("Model / spec enna Owner? — theriyalana 'skip'", say("10").reply.text)
    }

    // ------------------------------------------------------------------ unit safety, corrections, cancel, failure

    @Test
    fun aNumberWithoutAUnitIsAsked() {
        assertEquals("Colgate 5 — 5 pieces-aa, 5 boxes-aa Owner?", say("Colgate 5 add pannu").reply.text)
        assertEquals("Seri Owner 👍 1 box-la evlo pieces irukku?", say("box").reply.text)
        assertEquals("Rice 10 — 10 bags-aa, 10 kg-aa Owner?", KaiAgent(KaiBusinessBrain(Books()), Books(), Inventory(), { now }).let { k -> runBlocking { k.ask("Rice 10 add pannu") } }.reply.text)
    }

    @Test
    fun aCorrectionChangesTheSameEntry() {
        say("Colgate 5 box, boxக்கு 48 pieces, piece 200 gram, purchase 28, selling 35")
        val fixed = say("illa 3 box dhaan")
        assertEquals("சரி Owner. Colgate — 3 boxes = 144 pieces. Purchase ₹4,032. Selling value ₹5,040. Stock add பண்ணட்டுமா?", fixed.reply.text)
        say("aama")
        assertTrue(near(inv.item("Colgate").stock, "144"))
        assertEquals("+3 only, never +5 and +3", 1, inv.moves.size)
    }

    @Test
    fun aCorrectionWhileAskingKeepsAsking() {
        say("Colgate 5 box add pannu")
        assertEquals("Seri Owner 👍 1 box-la evlo pieces irukku?", say("illa 3 box dhaan").reply.text)
        say("48"); say("skip"); say("28")
        assertTrue(say("35").reply.text.contains("Colgate — 3 boxes = 144 pieces."))
    }

    @Test
    fun cancelSavesNothing() {
        say("Colgate 5 box add pannu")
        assertEquals("Seri Owner, cancel pannitten. Edhuvum save aagala.", say("venam").reply.text)
        assertTrue(inv.items.isEmpty() && inv.moves.isEmpty())
        // The next number is not taken as an answer any more.
        assertTrue(inv.created.isEmpty())
    }

    @Test
    fun aRejectedSaveLeavesNothingHalfDone() {
        inv.failCreate = true
        say("Colgate 5 box, boxக்கு 48 pieces, piece 200 gram, purchase 28, selling 35")
        assertEquals("ஓனர், Colgate சேமிக்க முடியல — எதுவும் சேமிக்கல.", say("aama").reply.text)
        assertTrue(inv.items.isEmpty() && inv.moves.isEmpty())
    }

    @Test
    fun tamilScriptAndEnglishWork() {
        assertEquals("சரி Owner 👍 1 box-ல எத்தனை pieces இருக்கு?", say("Colgate 5 பாக்ஸ் ஆட் பண்ணு").reply.text)
        val en = KaiAgent(KaiBusinessBrain(Books()), Books(), Inventory(), { now })
        assertEquals("Sure Owner 👍 How many pieces are in 1 box?", runBlocking { en.ask("Add 5 boxes of Colgate") }.reply.text)
    }

    @Test
    fun newStockWithNoProductAsksWhichAndNeverOpensTheCamera() {
        val t = say("new stock add pannu")
        assertEquals("Seri Owner 👍 Enna product, evlo vandhirukku? (eg: Colgate 5 box)", t.reply.text)
        assertNull(t.direct)
        assertEquals("Seri Owner 👍 1 box-la evlo pieces irukku?", say("Colgate 5 box").reply.text)
    }

    // ------------------------------------------------------------------ a product the books already have

    @Test
    fun anExistingProductIsNeverCreatedAgainNorAskedAgain() {
        inv.items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), BigDecimal("28"), BigDecimal("35"), "FMCG", "200 g")
        val t = say("Colgate 2 box add pannu")
        assertTrue(t.reply.text, t.reply.text.contains("Colgate") && t.reply.text.contains("96 pieces"))
        runBlocking { kai.act(t.card!!.buttons.first { it.label.contains("Confirm") }.action, KaiLang.TANGLISH) }
        assertEquals(1, inv.items.size)
        assertTrue(near(inv.item("Colgate").stock, "336"))
        assertEquals("2 boxes = 96 pieces, one movement", listOf(BigDecimal("96")), inv.moves.map { it.qty })
    }

    // ------------------------------------------------------------------ the pure rules

    @Test
    fun kindsAndNextQuestions() {
        assertEquals(ProductKind.FMCG, KaiInventory.kindOf("Colgate", "BOX"))
        assertEquals(ProductKind.GROCERY, KaiInventory.kindOf("Rice", "BAG"))
        assertEquals(ProductKind.BEVERAGE, KaiInventory.kindOf("Coke", "CASE"))
        assertEquals(ProductKind.LIQUID, KaiInventory.kindOf("Sunflower Oil", "BOX"))
        assertEquals(ProductKind.GARMENT, KaiInventory.kindOf("Shirt", "PCS"))
        assertEquals(ProductKind.FOOTWEAR, KaiInventory.kindOf("Slippers", "PAIR"))
        assertEquals(ProductKind.HARDWARE, KaiInventory.kindOf("Screws", "BOX"))
        assertEquals(ProductKind.ELECTRONICS, KaiInventory.kindOf("Charger", "BOX"))
        assertEquals(ProductKind.GROCERY, KaiInventory.kindOf("Ponni", "BAG"))
        val shirt = ItemSpec("Shirt", ProductKind.GARMENT, BigDecimal(20), "PCS")
        assertEquals(ItemField.SIZE, KaiInventory.next(shirt))
        val rice = ItemSpec("Rice", ProductKind.GROCERY, BigDecimal(10), "KG")
        assertEquals("no size for loose kg", ItemField.PURCHASE, KaiInventory.next(rice))
        val totals = KaiInventory.totals(ItemSpec("Colgate", ProductKind.FMCG, BigDecimal(5), "BOX", BigDecimal(48), "PCS", "200 g", BigDecimal(200), "GRAM",
            BigDecimal(28), null, BigDecimal(35), null))!!
        assertTrue(near(totals.baseQty, "240") && totals.weight == "48 kg")
        assertTrue(near(totals.purchaseValue, "6720") && near(totals.sellingValue, "8400") && near(totals.margin, "1680"))
    }

    // ------------------------------------------------------------------ movements with a reason

    @Test
    fun damageWastageReturnsAndSales() {
        stocked()
        val d = say("Oil 3 bottle damage")
        assertEquals("Seri Owner. Oil — 3 bottles stock-out (Damage). Remaining: 237 bottles.", d.reply.text)
        assertTrue(d.card!!.lines.contains("Reason: Damage"))
        assertTrue("nothing before Confirm", near(inv.item("Oil").stock, "240"))
        tap(d)
        assertTrue(near(inv.item("Oil").stock, "237"))
        assertTrue("the reason travels with the movement", inv.moves.single().said.contains("damage"))

        val w = say("Rice 2 kg wastage")
        assertTrue(w.reply.text, w.reply.text.contains("(Wastage)")); tap(w)
        assertTrue(near(inv.item("Rice").stock, "248"))

        val r = say("Oil 3 bottles customer return")
        assertEquals("Seri Owner. Oil — 3 bottles stock-in (Customer return). Ippo stock: 240 bottles.", r.reply.text); tap(r)
        assertTrue(near(inv.item("Oil").stock, "240"))

        val sr = say("Colgate 1 box supplier return")
        assertTrue(sr.reply.text, sr.reply.text.contains("(Supplier return)") && sr.reply.text.contains("Remaining: 192 pieces")); tap(sr)
        // In Tanglish the shelf count answers in Tanglish.
        val tl = say("Colgate actual 190 pieces dhaan, enniten")
        assertEquals("Owner, records-la Colgate 192 pieces, neenga ennunadhu 190 pieces. Adjustment −2 pieces (physical count) — confirm pannava?", tl.reply.text)
        say("venam")

        val sale = say("Rice 2 bag sale")
        assertTrue(sale.reply.text, sale.reply.text.contains("2 bags stock-out = 50 kg")); tap(sale)
        assertTrue(near(inv.item("Rice").stock, "198"))
        assertEquals(5, inv.moves.size)
    }

    @Test
    fun aShelfCountBecomesAnAdjustmentNeverAnOverwrite() {
        stocked()
        val t = say("Colgate actual stock 230 pieces, system 240")
        // The owner wrote it in English → Kai answers in English.
        assertEquals("Owner, the records show Colgate at 240 pieces; you counted 230 pieces. Adjustment −10 pieces (physical count) — confirm?", t.reply.text)
        assertTrue(t.card!!.lines.contains("Reason: Physical count"))
        tap(t)
        assertTrue(near(inv.item("Colgate").stock, "230"))
        assertEquals("one ADJUSTMENT_OUT of 10", listOf(BigDecimal("-10")), inv.moves.map { it.qty })
        assertTrue(inv.moves.single().said.startsWith("Physical count"))
        val rice = say("Rice actual 255 kg")
        assertTrue(rice.reply.text, rice.reply.text.contains("Adjustment +5 kg")); tap(rice)
        assertTrue(near(inv.item("Rice").stock, "255"))
        assertEquals("same count again: nothing to adjust", "Seri Owner, records-la-um Rice 255 kg dhaan — adjustment thevai illa.", say("Rice actual 255 kg").reply.text)
    }

    // ------------------------------------------------------------------ prices, minimum, details, summary

    @Test
    fun pricesChangeOnlyOnConfirmAndStayApart() {
        stocked()
        assertEquals("Colgate selling price: ₹35/piece → ₹38/piece — maathava?", say("Colgate selling price 38 aakku").reply.text)
        assertTrue("not before Confirm", near(inv.item("Colgate").selling, "35"))
        assertEquals("Done Owner ✅ Colgate update panniten.", say("aama").reply.text)
        assertTrue(near(inv.item("Colgate").selling, "38") && near(inv.item("Colgate").purchase, "28"))
        assertEquals("Rice purchase rate: ₹56/kg → ₹1,400/bag (₹56/kg) — maathava?", say("Rice purchase rate 1400 per bag").reply.text)
        say("seri")
        assertTrue(near(inv.item("Rice").purchase, "56") && near(inv.item("Rice").selling, "65"))
        assertTrue("stock untouched", inv.moves.isEmpty())
    }

    @Test
    fun minimumStockAndTheLowStockWarning() {
        stocked()
        val m = say("Colgate 2 box-ku keela pona remind pannu")
        assertEquals("Colgate minimum stock: 2 boxes (96 pieces). Adhukku keezha pona low stock-nu solluven — set pannava?", m.reply.text)
        say("aama")
        assertTrue(near(inv.item("Colgate").minimum, "96"))
        assertEquals("Rice minimum stock: 5 bags (125 kg). Adhukku keezha pona low stock-nu solluven — set pannava?", say("Rice minimum stock 5 bags aakku").reply.text)
        say("aama")
        assertTrue(near(inv.item("Rice").minimum, "125"))
        // Selling down to the minimum warns, right with the save.
        val out = say("Colgate 3 box sale")
        val done = tap(out)
        assertTrue(done.reply.text, done.reply.text.contains("⚠️ Colgate low stock: 2 boxes (96 pieces) (minimum 2 boxes)."))
    }

    @Test
    fun detailsSummaryAndStockInPacks() {
        stocked()
        assertEquals("Owner, Colgate stock 240 PCS (5 boxes) irukku.", say("Colgate evlo irukku?").reply.text)
        val d = say("Colgate details kaattu")
        assertEquals("Owner, Colgate details:", d.reply.text)
        assertEquals(listOf("Stock: 240 pieces (5 boxes)", "1 box = 48 pieces", "Purchase ₹28/piece", "Selling ₹35/piece", "Stock value ₹6,720", "Category: FMCG"), d.card!!.lines)
        val sum = say("en inventory summary kaattu")
        assertEquals("Owner, 3 items. Stock value ₹20,720 (1 items-ku purchase rate illa). Low stock: 0.", sum.reply.text)
        assertEquals(listOf("Colgate — 240 pieces (5 boxes)", "Oil — 240 bottles (20 boxes)", "Rice — 250 kg (10 bags)"), sum.card!!.lines)
    }

    // ------------------------------------------------------------------ goods with a supplier / customer: one bill

    @Test
    fun aPurchaseOnCreditIsStockAndPayableTogether() {
        stocked()
        assertEquals("Credit-aa, cash-aa Owner?", say("ABC Traders kitta 5 box Colgate vaanginen").reply.text)
        val s1 = say("credit")
        assertEquals("ABC Traders kitta Colgate 5 boxes (240 pieces) purchase — ₹6,720 (credit). Stock +240 pieces, ABC Traders-ku ₹6,720 kudukkanum. Confirm pannava?", s1.reply.text)
        assertTrue("nothing before Confirm", near(inv.item("Colgate").stock, "240") && near(balance("ABC Traders"), "0"))
        val done = say("aama")
        assertEquals("Done Owner ✅ Purchase save aagiduchu (BILL-1). Colgate stock 480 pieces. ABC Traders-ku ippo ₹6,720 kudukkanum.", done.reply.text)
        assertTrue(near(inv.item("Colgate").stock, "480") && near(balance("ABC Traders"), "6720"))
        assertEquals("one bill, one movement", 1, inv.moves.size)
        say("aama")
        assertEquals("no second bill", 1, inv.bills.size)
    }

    @Test
    fun aPurchaseWithItsTotalSaid() {
        stocked()
        val t = say("ABC Traders kitta 10 box Colgate vaanginen, ₹6,000 credit")
        assertTrue(t.reply.text, t.reply.text.contains("10 boxes (480 pieces) purchase — ₹6,000 (credit)"))
        tap(t)
        assertTrue(near(balance("ABC Traders"), "6000") && near(inv.item("Colgate").stock, "720"))
    }

    @Test
    fun aCreditSaleIsStockOutAndReceivableTogether() {
        stocked()
        assertEquals("Colgate — 5 pieces-aa, 5 boxes-aa Owner?", say("Ramesh-ku 5 Colgate credit sale").reply.text)
        val s1 = say("pieces")
        assertEquals("Ramesh-ku Colgate 5 pieces sale — ₹175 (credit). Stock −5 pieces, Ramesh ₹175 tharanum. Confirm pannava?", s1.reply.text)
        val done = tap(s1)
        assertEquals("Done Owner ✅ Sale save aagiduchu (BILL-1). Colgate stock 235 pieces. Ramesh ippo ₹1,175 tharanum.", done.reply.text)
        assertTrue(near(inv.item("Colgate").stock, "235") && near(balance("Ramesh"), "1175"))
        assertEquals(1, inv.moves.size)
    }

    @Test
    fun aRejectedBillChangesNothing() {
        stocked()
        inv.failBill = true
        say("ABC Traders kitta 5 box Colgate vaanginen credit")
        assertEquals("Owner, save aagala: rejected. Edhuvum save aagala.", say("aama").reply.text)
        assertTrue(near(inv.item("Colgate").stock, "240") && near(balance("ABC Traders"), "0") && inv.moves.isEmpty())
    }

    @Test
    fun freeGoodsAreStockOnly() {
        stocked()
        val t = say("Ramesh-ku 2 pieces Colgate free-a kuduthen")
        assertTrue(t.reply.text, t.reply.text.contains("2 pieces stock-out (Free)"))
        tap(t)
        assertTrue(near(inv.item("Colgate").stock, "238") && near(balance("Ramesh"), "1000") && inv.bills.isEmpty())
    }

    @Test
    fun anInventoryCheckReminderIsAReminderNotStock() {
        stocked()
        val t = say("Every Sunday inventory check remind pannu")
        assertTrue(t.reply.text, t.reply.text.contains("remind", ignoreCase = true) || t.reply.text.contains("Sunday", ignoreCase = true))
        assertTrue(inv.moves.isEmpty() && inv.items.all { it.minimum == null })
    }

    // ------------------------------------------------------------------ the owner's final scenario (section 27)

    @Test
    fun finalScenarioFromTheRequest() {
        stocked()
        tap(say("Colgate 2 box add pannu"))                                   // +96 → 336
        tap(say("Rice 2 bag sale"))                                           // −50 kg → 200
        tap(say("Oil 3 bottle damage"))                                       // −3 → 237
        say("Ramesh-ku 5 Colgate credit sale"); tap(say("pieces"))            // −5 → 331, Ramesh +₹175
        say("ABC Traders kitta 5 box Colgate vaanginen"); tap(say("credit"))  // +240 → 571, ABC +₹6,720
        val pay = say("ABC Traders-ku 2000 payment pannitten"); tap(pay)      // ABC −₹2,000
        say("Colgate 2 box-ku keela pona remind pannu"); say("aama")          // minimum 96
        val summary = say("en inventory summary kaattu")

        assertTrue(near(inv.item("Colgate").stock, "571"))
        assertTrue(near(inv.item("Rice").stock, "200"))
        assertTrue(near(inv.item("Oil").stock, "237"))
        assertTrue(near(balance("Ramesh"), "1175"))
        assertTrue(near(balance("ABC Traders"), "4720"))
        assertTrue(near(inv.item("Colgate").minimum, "96"))
        // Stock value at purchase price: Colgate 571 × ₹28 + Rice 200 × ₹56 (Oil has no purchase rate).
        assertEquals("Owner, 3 items. Stock value ₹27,188 (1 items-ku purchase rate illa). Low stock: 0.", summary.reply.text)
        assertEquals("one movement per action", 5, inv.moves.size)
        assertEquals("one purchase + one sale bill", 2, inv.bills.size)
        assertEquals(1, inv.payments.size)
    }

    // ------------------------------------------------------------------ combinations: kind × phrasing × language

    @Test
    fun newProductCombinationsAskTheRightFirstDetail() {
        // product, quantity said, the word the first question must contain (what one pack holds / the size kind)
        val goods = listOf(
            Triple("Colgate", "5 box", "pieces"), Triple("Rice", "10 bag", "kg"), Triple("Coke", "3 case", "bottles"),
            Triple("Oil", "2 box", "bottles"), Triple("Shirt", "20 pieces", "M 8"), Triple("Slippers", "10 pairs", "7, 8, 9"),
            Triple("Screws", "4 box", "pieces"), Triple("Charger", "2 box", "pieces"), Triple("Biscuit", "6 box", "pieces"),
            Triple("Sugar", "5 bag", "kg"),
        )
        val phrasings = listOf("{p} {q} add pannu", "{p} {q} vandhirukku", "{q} {p} pudhu stock vandhuchu", "{p} {q} ஆட் பண்ணு", "Add {q} of {p}")
        val failures = mutableListOf<String>()
        var cases = 0
        for ((p, q, expect) in goods) for (ph in phrasings) {
            val said = ph.replace("{p}", p).replace("{q}", q)
            cases++
            val shop = Inventory()
            val k = KaiAgent(KaiBusinessBrain(Books()), Books(), shop, { now })
            val t = runBlocking { k.ask(said) }
            val ok = t.reply.text.contains(expect) && t.direct == null && shop.items.isEmpty() && shop.moves.isEmpty()
            if (!ok) failures += "$said → ${t.reply.text}"
        }
        println("MATRIX inventory-first-question ${cases - failures.size}/$cases")
        assertEquals(50, cases)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun answerSpellingsAreUnderstood() {
        // Each answer spelling to "1 box-la evlo pieces irukku?" gives 48 pieces a box.
        for (ans in listOf("48", "48 pieces", "naarpathettu", "1 box = 48 pieces", "48 pcs")) {
            val shop = Inventory()
            val k = KaiAgent(KaiBusinessBrain(Books()), Books(), shop, { now })
            runBlocking { k.ask("Colgate 5 box add pannu") }
            val t = runBlocking { k.ask(ans) }
            assertEquals("$ans → ${t.reply.text}", "Oru piece evlo gram? — theriyalana 'skip'", t.reply.text)
        }
        // Rates said with ₹ / rs / rupees / Tamil words.
        for (ans in listOf("28", "₹28", "28 rs", "28 rupees", "28 ரூபாய்")) {
            val shop = Inventory()
            val k = KaiAgent(KaiBusinessBrain(Books()), Books(), shop, { now })
            runBlocking { k.ask("Colgate 5 box add pannu"); k.ask("48"); k.ask("skip") }
            assertEquals(ans, "Oru piece selling rate evlo?", runBlocking { k.ask(ans) }.reply.text)
            runBlocking { k.ask("35"); k.ask("aama") }
            assertTrue(ans, near(shop.item("Colgate").purchase, "28") && near(shop.item("Colgate").selling, "35"))
        }
    }
}
