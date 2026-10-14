package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Supermarket names (owner's stock master, 14 Oct 2026), typed and said: a shorter product inside a longer name is never taken for it
 * ("Ragi flour 7" is not Ragi, "Aashirvaad Wheat flour 7" is not Wheat — Kai asks which pack), a long brand name without its pack is
 * still stock (Kai asks which), and a goods name the owner says in Tamil ("அப்பளம்", "தேன்") is the shop's product typed or spoken.
 */
class KaiSupermarketNamesTest {
    private val shop = listOf("Ragi", "Ragi flour 500 g", "Ragi flour 1 kg", "Wheat", "Aashirvaad Wheat flour 1 kg", "Aashirvaad Wheat flour 5 kg",
        "India Gate Sona masoori rice 1 kg", "India Gate Sona masoori rice 5 kg", "Papad", "Honey", "Hing")
        .mapIndexed { i, n -> ProductRef("p$i", n, "PCS", BigDecimal("50")) }

    private fun typed(line: String) = KaiStock.understand(line, shop)
    private fun spoken(line: String) = KaiStock.understand(KaiSpokenWords.normalize(KaiProductSound.withProducts(line, shop.map { it.name })), shop)

    @Test fun aShorterProductInsideALongerNameIsNotIt() {
        for ((line, r) in listOf("Ragi flour 7 add pannu" to typed("Ragi flour 7 add pannu"), "ராகி ஃப்ளோர் 7 ஆட் பண்ணு" to spoken("ராகி ஃப்ளோர் 7 ஆட் பண்ணு"))) {
            assertNotNull(line, r)
            assertNull("$line → ${r!!.product?.name}", r.product)
        }
        assertNull(typed("Aashirvaad Wheat flour 7 add pannu")?.product)
        // The short name said alone is still that product.
        assertEquals("Ragi", typed("Ragi 7 add pannu")?.product?.name)
        assertEquals("Ragi", spoken("ராகி 7 ஆட் பண்ணு")?.product?.name)
    }

    @Test fun aLongBrandNameWithoutItsPackIsStillStock() {
        val r = typed("India Gate Sona masoori rice 7 add pannu")
        assertNotNull(r)
        assertNull(r!!.product)
        assertEquals(BigDecimal("7"), r.qty)
        // A long name the shop doesn't have at all is not taken as stock from the words alone.
        assertNull(typed("India Gate Golden Basmati special rice 7 add pannu")?.product)
    }

    @Test fun aPackNamedByItsSizeWord() {
        // The expanded catalogue (14 Oct 2026): "Small pack" said "small", "chinna pack", or as the mic writes it ("சின்ன பேக்" = chinna bag).
        val cat = listOf("Fruit juice Small pack", "Fruit juice Medium pack", "Mixed fruit juice Small pack", "Cola soft drink Large pack", "Cola soft drink Small pack")
            .mapIndexed { i, n -> ProductRef("c$i", n, "PCS", BigDecimal("50")) }
        for ((line, want) in listOf("Fruit juice small 7 add pannu" to "Fruit juice Small pack", "Fruit juice chinna pack 7 add pannu" to "Fruit juice Small pack",
                "Fruit juice chinna bag 7 add pannu" to "Fruit juice Small pack", "Cola soft drink periya pack 7 add pannu" to "Cola soft drink Large pack",
                "Mixed fruit juice small pack 7 add pannu" to "Mixed fruit juice Small pack")) {
            val r = KaiStock.understand(line, cat)
            assertEquals(line, want, r?.product?.name)
            assertEquals(line, BigDecimal("7"), r?.qty)
        }
        // Without its pack: no product taken (Kai asks which pack).
        assertNull(KaiStock.understand("Fruit juice 7 add pannu", cat)?.product)
    }

    @Test fun aGoodsNameOfAThingKeptOnlyInPacks() {
        // Catalogue 11-15 (14 Oct 2026): "nandu" = crab, with only Crab Small / Medium pack kept — the pack said is that pack; a goods
        // word already inside the name said ("Whole wheat bread", "Zip lock bag") is never read again; "Bucket 7" names the packs.
        val cat = listOf("Crab Small pack", "Crab Medium pack", "Whole wheat bread Small pack", "Whole wheat bread Medium pack", "Zip lock bag Small pack",
            "Zip lock bag Medium pack", "Bucket Small pack", "Bucket Medium pack").mapIndexed { i, n -> ProductRef("k$i", n, "PCS", BigDecimal("50")) }
        assertEquals("Crab Small pack", KaiStock.understand("nandu small pack 7 add pannu", cat)?.product?.name)
        assertEquals("Whole wheat bread Small pack", KaiStock.understand("Whole wheat bread small pack 7 add pannu", cat)?.product?.name)
        assertEquals("Zip lock bag Medium pack", KaiStock.understand("Zip lock bag medium pack 7 add pannu", cat)?.product?.name)
        assertEquals("crab 7 add pannu", KaiStock.withGoodsFamilies("nandu 7 add pannu", cat))
        KaiStock.understand("Bucket 7 add pannu", cat).let { r -> assertNull(r?.product); assertEquals("Bucket", r?.spokenName) }
        // "mezhuguvarthi" = candle, with Candle and Handmade candle packs kept: the thing of that very name.
        val candles = listOf("Candle Small pack", "Candle Medium pack", "Handmade candle Small pack", "Handmade candle Medium pack")
        assertEquals("Candle", KaiStock.familyOf(listOf("candle"), candles))
        // A word the shop's own names have is never read as a goods name: "Mustard oil 500 ml" with Mustard seeds packs kept stays Mustard oil.
        val oils = listOf("Mustard oil 500 ml", "Mustard oil 1 L", "Mustard seeds 100 g", "Mustard seeds 200 g").mapIndexed { i, n -> ProductRef("m$i", n, "PCS", BigDecimal("50")) }
        assertEquals("Mustard oil 500 ml 7 add pannu", KaiStock.withGoodsFamilies("Mustard oil 500 ml 7 add pannu", oils))
        assertEquals("Mustard oil 500 ml", KaiStock.understand("Mustard oil 500 ml 7 add pannu", oils)?.product?.name)
        // A goods name the shop has nothing for stays as said.
        assertEquals("nookal 7 add pannu", KaiStock.withGoodsFamilies("nookal 7 add pannu", cat))
    }

    @Test fun aStockWordInTheShopsOwnName() {
        // Catalogue 21-25 (14 Oct 2026): "Pet waste scoop 7 add pannu" with only its packs kept is stock in — "waste" is the name's own
        // word (Kai asks which pack); "waste aachu" after it is still stock going out.
        val cat = listOf("Pet waste scoop Small pack", "Pet waste scoop Medium pack", "Pet shampoo Small pack").mapIndexed { i, n -> ProductRef("w$i", n, "PCS", BigDecimal("50")) }
        KaiStock.understand("Pet waste scoop 7 add pannu", cat).let { r ->
            assertNotNull(r); assertNull(r!!.product); assertEquals(true, r.incoming); assertEquals("Pet Waste Scoop", r.spokenName); assertEquals(BigDecimal("7"), r.qty)
        }
        assertEquals(false, KaiStock.understand("Pet waste scoop 2 waste aachu", cat)?.incoming)
        assertEquals("Pet waste scoop Small pack", KaiStock.understand("Pet waste scoop small 7 add pannu", cat)?.product?.name)
    }

    @Test fun aMoneyWordInTheShopsOwnName() {
        // Catalogue 06-10 (14 Oct 2026): "Power bank" is the shop's product — its "bank" is not money (read as one word, "Powerbank", as
        // KaiInventory.joinProductWords does); "bank la 500 pottuten" still is.
        val cat = listOf("Power bank Small pack", "Power bank Medium pack", "Chalk Small pack", "Chalk Medium pack").mapIndexed { i, n -> ProductRef("b$i", n, "PCS", BigDecimal("50")) }
        assertEquals("Power bank Small pack", KaiStock.understand("Power bank small 7 add pannu", cat)?.product?.name)
        KaiStock.understand("Power bank 7 add pannu", cat).let { r -> assertNotNull(r); assertNull(r!!.product); assertEquals("Powerbank", r.spokenName) }
        assertNull(KaiStock.understand("bank la 500 pottuten", cat))
        assertNull(KaiStock.understand("Power bank 500 rupees", cat))
        // "chalk piece" = Chalk: the goods name holds the product's own name — read, the pack said after it kept.
        assertEquals("Chalk Small pack", KaiStock.understand("chalk piece small pack 7 add pannu", cat)?.product?.name)
    }

    @Test fun goodsByTheirTamilName() {
        assertEquals("Papad", typed("appalam 7 add pannu")?.product?.name)
        assertEquals("Papad", spoken("அப்பளம் 7 ஆட் பண்ணு")?.product?.name)
        assertEquals("Honey", spoken("தேன் 7 ஆட் பண்ணு")?.product?.name)
        // Said as the English name in Tamil letters: "ஹனி" is Honey (its a heard), never Hing.
        assertEquals("Honey", spoken("ஹனி 7 ஆட் பண்ணு")?.product?.name)
    }
}
