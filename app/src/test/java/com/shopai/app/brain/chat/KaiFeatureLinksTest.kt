package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.KaiUnits
import com.shopai.app.brain.tools.ProductKind
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.ShopCategoryPicks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The features added to the app (owner, 20 Oct 2026: "ipo add paniruka features ellam Kai-ku theriyanum, voice chat-laiyum
 * link pannidu"), typed and as the ta-IN mic writes them: the screens Kai points to (purchase bill, draft bills, Add Product,
 * the shop's categories), the Unit Master's units, fixed conversions, and a new shop's setup categories. Nothing is saved by a link.
 */
class KaiFeatureLinksTest {
    private val now = LocalDateTime.of(2026, 10, 20, 11, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot()
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val inv: Inventory, val picks: ShopCategoryPicks?) : KaiTools by inv {
        override suspend fun businessCategoryPicks() = picks
    }

    private inner class Shop(picks: ShopCategoryPicks? = null, stocked: Boolean = true) {
        val inv = Inventory().apply {
            parties.clear()
            if (stocked) {
                items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), BigDecimal("28"), BigDecimal("35"), "FMCG", "200 g")
                items += Inventory.Item("p2", "Clinic Plus", "PCS", BigDecimal("100"), mapOf("SACHET" to BigDecimal("1")), BigDecimal("1"), BigDecimal("2"), "FMCG", null)
                items += Inventory.Item("p3", "Cotton cloth", "METER", BigDecimal("50"), emptyMap(), BigDecimal("80"), BigDecimal("120"), "Garments & Textiles", null)
            }
        }
        val kai = KaiAgent(KaiBusinessBrain(Books(), today = { now.toLocalDate() }), Books(), Tools(inv, picks), { now })
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }
        fun nothingSaved() = inv.moves.isEmpty() && inv.created.isEmpty()
    }

    private fun ways(typed: String): List<String> = listOfNotNull(typed, KaiMicForm.of(typed)).distinct()
    private fun opens(t: KaiTurn) = t.card?.buttons?.mapNotNull { (it.action as? KaiAction.OpenRecord)?.kind }.orEmpty()

    @Test fun eachScreenIsOpenedFromChatOrVoiceAndNothingIsSaved() {
        val asks = mapOf(
            "purchase bill podanum" to "PURCHASE_BILL", "supplier bill entry pannanum" to "PURCHASE_BILL", "kolmudhal bill podu" to "PURCHASE_BILL",
            "draft bill ellam kaattu" to "BILLING", "pudhu product add pannanum" to "NEW_PRODUCT", "product master" to "NEW_PRODUCT",
            "en kadai enna vagai" to "PROFILE", "kadai category maathanum" to "PROFILE", "business type enna" to "PROFILE",
            // A sales bill still opens the sales bill (and says its number and date fill in by themselves).
            "sales bill podu" to "SALE_BILL",
        )
        for ((typed, screen) in asks) for (line in ways(typed)) {
            val s = Shop()
            val t = s.say(line)
            assertEquals("$line → ${t.reply.text}", listOf(screen), opens(t))
            assertTrue(line, s.nothingSaved())
        }
        // Spoken in Tamil script as the mic writes it.
        assertEquals(listOf("PURCHASE_BILL"), opens(Shop().say("பர்சேஸ் பில் போடணும்")))
        assertEquals(listOf("PROFILE"), opens(Shop().say("என் கடை என்ன வகை")))
        assertTrue(Shop().say("sales bill podu").reply.text.contains("date"))
    }

    @Test fun aLineThatOnlyLooksLikeOneIsNotALink() {
        // A supplier's invoice asked for is their PDF, a bill to scan is the scanner, a product with a name is a stock line.
        assertFalse("PURCHASE_BILL" in opens(Shop().say("ABC Traders supplier invoice kudu")))
        assertFalse("PURCHASE_BILL" in opens(Shop().say("purchase bill scan pannu")))
        assertFalse("NEW_PRODUCT" in opens(Shop().say("pudhu product Colgate 5 box add pannu")))
        assertNull(KaiScreenLinks.of("Colgate category enna"))
        assertNull(KaiScreenLinks.of("kadaiku saraku vandhuruku"))
    }

    @Test fun theShopsCategoriesAreSaidFromTheSetup() {
        val picks = ShopCategoryPicks(listOf("GROCERY_FMCG", "DAIRY_FROZEN", "OTHER_PRODUCTS"), "Pooja items")
        for (line in ways("en kadai enna vagai")) {
            val t = Shop(picks).say(line)
            assertTrue("$line → ${t.reply.text}", t.reply.text.contains("Pooja items") &&
                (t.reply.text.contains("Grocery & FMCG") || t.reply.text.contains("மளிகை & FMCG")))
        }
        // Not read (an older setup): said plainly, with the way to see them — never a made-up category.
        assertTrue(Shop(null).say("en kadai enna vagai").reply.text.contains("Profile"))
    }

    @Test fun theUnitMastersPackUnitsAreReadTypedAndSpoken() {
        for (line in ways("Clinic Plus 10 sachet vandhuchu")) {
            val t = Shop().say(line)
            assertTrue("$line → ${t.reply.text}", t.reply.text.contains("10") && !t.reply.text.contains("evlo pieces", ignoreCase = true))
        }
        // A unit not known for the product is asked about — never taken as its own unit.
        for (line in ways("Colgate 2 jar vandhuchu")) {
            val t = Shop().say(line)
            assertTrue("$line → ${t.reply.text}", t.reply.text.contains("jar", ignoreCase = true) || t.reply.text.contains("ஜார்"))
        }
    }

    @Test fun fixedConversionsComeFromTheUnitMasterAndPacksNeverDo() {
        val cloth = ProductRef("p3", "Cotton cloth", "METER", BigDecimal("50"))
        assertEquals(0, BigDecimal("0.9144").compareTo(KaiUnits.factor(cloth, "YARD")))
        assertEquals(0, BigDecimal("0.3048").compareTo(KaiUnits.factor(cloth, "FEET")))
        val rice = ProductRef("p4", "Rice", "KG", BigDecimal("10"))
        assertEquals(0, BigDecimal("0.001").compareTo(KaiUnits.factor(rice, "GRAM")))
        assertNull(KaiUnits.factor(rice, "BAG"))
        assertNull(KaiUnits.factor(rice, "LITRE"))
        assertNull(KaiUnits.factor(ProductRef("p5", "Shampoo", "PCS", BigDecimal.ONE), "JAR"))
    }

    @Test fun aNewShopsFirstProductFollowsItsSetupCategories() {
        assertEquals(ProductKind.HARDWARE, KaiScreenLinks.kindOf(listOf("OTHER_PRODUCTS", "HARDWARE_BUILDING")))
        assertNull(KaiScreenLinks.kindOf(listOf("BAGS_LUGGAGE")))
        assertNull(KaiScreenLinks.kindOf(emptyList()))
        // An empty hardware shop: a new "Tube light" is never asked a medicine's expiry.
        for (line in ways("Tube light 10 pcs vandhuchu")) {
            val t = Shop(ShopCategoryPicks(listOf("HARDWARE_BUILDING")), stocked = false).say(line)
            assertFalse("$line → ${t.reply.text}", Regex("(?i)expiry|காலாவதி").containsMatchIn(t.reply.text))
        }
    }

    @Test fun aLinkKeepsTheChatsLanguage() {
        assertTrue(Shop().say("purchase bill podanum").reply.text.contains("podalaam"))
        assertTrue(Shop().say("பர்சேஸ் பில் போடணும்").reply.text.contains("போடலாம்"))
        assertTrue(Shop().say("I want to enter a purchase bill").reply.text.contains("Purchase bill screen"))
    }
}
