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

    @Test fun goodsByTheirTamilName() {
        assertEquals("Papad", typed("appalam 7 add pannu")?.product?.name)
        assertEquals("Papad", spoken("அப்பளம் 7 ஆட் பண்ணு")?.product?.name)
        assertEquals("Honey", spoken("தேன் 7 ஆட் பண்ணு")?.product?.name)
        // Said as the English name in Tamil letters: "ஹனி" is Honey (its a heard), never Hing.
        assertEquals("Honey", spoken("ஹனி 7 ஆட் பண்ணு")?.product?.name)
    }
}
