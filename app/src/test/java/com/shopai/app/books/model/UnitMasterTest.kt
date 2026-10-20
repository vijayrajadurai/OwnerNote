package com.shopai.app.books.model

import com.shopai.app.books.engine.AdjustmentReasonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The owner's inventory fields (20 Oct 2026): a searchable Unit Master for every kind of business, product-wise purchase /
 * stock / sales units with configured conversions (never assumed), decimal quantities, SKU auto-generate, category lists.
 */
class UnitMasterTest {
    private fun dec(s: String) = BigDecimal(s)

    @Test fun everyKindOfBusinessHasTheUnitsTheOwnerListed() {
        val owner = mapOf(
            UnitMaster.Kind.HARDWARE to "mm cm metre km inch foot yard sq.ft sq.m cu.ft cu.m kg gram tonne litre ml piece length roll coil bundle bar sheet pipe box bag set pair",
            UnitMaster.Kind.GROCERY to "piece packet pack box carton case bottle can jar pouch sachet tin bag kg gram quintal tonne litre ml dozen tray bundle",
            UnitMaster.Kind.TEXTILE to "piece pair set dozen metre cm yard roll bundle pack kg cone spool reel bale",
            UnitMaster.Kind.PHARMACY to "tablet capsule strip box bottle vial ampoule sachet tube pack piece pair ml litre gram mg microgram dose kit",
            UnitMaster.Kind.AGRICULTURE to "kg gram quintal tonne bag packet sachet litre ml bottle can piece bundle acre hectare",
            UnitMaster.Kind.AUTOMOBILE to "piece set pair kit unit box pack mm cm inch metre kg gram litre can drum roll",
            UnitMaster.Kind.FRESH to "kg gram quintal tonne piece dozen bunch bundle box crate basket tray packet litre ml",
        )
        for ((kind, words) in owner) {
            val offered = UnitMaster.suggestions(kind).map { it.code }
            for (w in words.split(" ")) {
                val code = UnitMaster.canon(w)
                assertTrue("$kind: '$w' → $code not offered in $offered", code in offered)
            }
        }
    }

    @Test fun searchFindsAUnitByCodeNameTamilOrShortFormWithTheKindsOwnFirst() {
        assertEquals("KG", UnitMaster.search("kilo", UnitMaster.Kind.GROCERY).first())
        assertEquals("KG", UnitMaster.search("கிலோ", UnitMaster.Kind.OTHER).first())
        assertEquals("METER", UnitMaster.search("metre", UnitMaster.Kind.TEXTILE).first())
        assertEquals("STRIP", UnitMaster.search("str", UnitMaster.Kind.PHARMACY).first())
        assertEquals("CARTON", UnitMaster.search("ctn", UnitMaster.Kind.GROCERY).first())
        // "m" starts many units: the shop's own kind ranks its units first (metre for textile, ml for pharmacy).
        assertTrue(UnitMaster.search("m", UnitMaster.Kind.PHARMACY).take(3).containsAll(listOf("ML", "MG")))
        // Empty search: the kind's suggestions, then the shop's own custom units.
        assertEquals(UnitMaster.suggestions(UnitMaster.Kind.FRESH).map { it.code } + "GUNNY", UnitMaster.search("", UnitMaster.Kind.FRESH, listOf("KG", "gunny")))
        // A unit not in the master is found among the shop's custom ones; one nobody has gives nothing (offered as a new unit).
        assertEquals(listOf("GUNNY"), UnitMaster.search("gun", UnitMaster.Kind.FRESH, listOf("GUNNY")))
        assertTrue(UnitMaster.search("zzz", UnitMaster.Kind.OTHER).isEmpty())
    }

    @Test fun aNewUnitIsKeptInLettersTheBooksCanStore() {
        assertEquals("GUNNY", UnitMaster.customCode("Gunny"))
        assertEquals("KG", UnitMaster.customCode("கிலோ"))
        assertEquals("SQFT", UnitMaster.customCode("sq.ft"))
        // Tamil letters of a unit not in the master, or a leading digit: cannot be kept — the owner is asked to type it in English.
        assertNull(UnitMaster.customCode("மூட்டை"))
        assertNull(UnitMaster.customCode("2kg"))
    }

    @Test fun theShopsCategoryPicksItsKindAndAnUnknownOneIsOther() {
        assertEquals(UnitMaster.Kind.HARDWARE, UnitMaster.kindOf("HARDWARE"))
        assertEquals(UnitMaster.Kind.HARDWARE, UnitMaster.kindOf("ELECTRICAL"))
        assertEquals(UnitMaster.Kind.AUTOMOBILE, UnitMaster.kindOf("AUTO_PARTS"))
        assertEquals(UnitMaster.Kind.MOBILE, UnitMaster.kindOf("MOBILE_ACCESSORIES"))
        assertEquals(UnitMaster.Kind.RESTAURANT, UnitMaster.kindOf("RESTAURANT_FOOD"))
        assertEquals(UnitMaster.Kind.COSMETICS, UnitMaster.kindOf("BEAUTY_SALON"))
        assertEquals(UnitMaster.Kind.FRESH, UnitMaster.kindOf("Fruits & Vegetables"))
        // Not in any list: OTHER — never a different shop's units.
        assertEquals(UnitMaster.Kind.OTHER, UnitMaster.kindOf("spa"))
        assertEquals(UnitMaster.Kind.OTHER, UnitMaster.kindOf("carpet"))
        assertEquals(UnitMaster.Kind.OTHER, UnitMaster.kindOf(null))
    }

    @Test fun onlyConversionsTrueForEveryProductAreFixed() {
        assertEquals(0, dec("1000").compareTo(UnitMaster.fixedFactor("METER", "MM")))
        assertEquals(0, dec("100").compareTo(UnitMaster.fixedFactor("QUINTAL", "KG")))
        assertEquals(0, dec("1000").compareTo(UnitMaster.fixedFactor("LITRE", "ML")))
        assertEquals(0, dec("12").compareTo(UnitMaster.fixedFactor("DOZEN", "PCS")))
        assertEquals(0, dec("0.001").compareTo(UnitMaster.fixedFactor("GRAM", "KG")))
        assertEquals(0, dec("12").compareTo(UnitMaster.fixedFactor("FEET", "INCH")))
        // A carton, box, strip, coil, ream or bag has no fixed size; kg and litre never convert to each other.
        for ((a, b) in listOf("CARTON" to "PCS", "BOX" to "PCS", "STRIP" to "TABLET", "COIL" to "METER", "REAM" to "SHEET", "BAG" to "KG", "KG" to "LITRE")) {
            assertNull("$a → $b", UnitMaster.fixedFactor(a, b))
        }
    }

    @Test fun aProductCanHaveAPurchaseAStockAndASalesUnitEachConfigured() {
        // Stock in PCS, bought by the CARTON (144), sold by the PACK (6).
        val u = ProductUnits("PCS", "CARTON", Qty.of(144), mapOf("PACK" to Qty.of(6)))
        assertEquals(Qty.of(288), u.toPrimary(Qty.of(2), "CARTON"))
        assertEquals(Qty.of(15), u.toPrimary(Qty.of("2.5"), "PACK"))
        assertEquals(listOf("PCS", "CARTON", "PACK"), u.all)
        // A unit with no conversion set is refused — never taken as 1.
        assertFalse(u.accepts("BOX"))
        assertThrows(IllegalArgumentException::class.java) { u.toPrimary(Qty.of(1), "BOX") }
        // Decimals: 1.75 metre of a cloth kept in metres, 2.5 kg of rice kept in kg sold by the 25 kg bag.
        assertEquals(Qty.of("1.75"), ProductUnits("METER").toPrimary(Qty.of("1.75"), "METER"))
        assertEquals(Qty.of("62.5"), ProductUnits("KG", "BAG", Qty.of(25)).toPrimary(Qty.of("2.5"), "BAG"))
        // The same unit twice, or a zero conversion, is refused.
        assertThrows(IllegalArgumentException::class.java) { ProductUnits("PCS", "BOX", Qty.of(12), mapOf("BOX" to Qty.of(10))) }
        assertThrows(IllegalArgumentException::class.java) { ProductUnits("PCS", more = mapOf("PACK" to 0L)) }
    }

    @Test fun moreUnitsAreStoredAndReadBack() {
        val more = mapOf("CARTON" to Qty.of(144), "PACK" to Qty.of("6"))
        val text = ProductUnits.formatMore(more)
        assertEquals("CARTON=144000;PACK=6000", text)
        assertEquals(more, ProductUnits.parseMore(text))
        assertNull(ProductUnits.formatMore(emptyMap()))
        assertEquals(emptyMap<String, Long>(), ProductUnits.parseMore(null))
        // A broken part is skipped, never read as a size.
        assertEquals(mapOf("PACK" to 6000L), ProductUnits.parseMore("PACK=6000;BOX=;=5;TIN=-3;JUNK"))
    }

    @Test fun theUnitsNeedingAConversionAreThePurchaseSalesAndOtherUnitsOnce() {
        assertEquals(listOf("CARTON", "PACK"), UnitMaster.unitsNeedingConversion("PCS", "CARTON", "PACK", listOf("pack")))
        assertEquals(emptyList<String>(), UnitMaster.unitsNeedingConversion("KG", "kg", null, emptyList()))
        assertEquals(listOf("BOX"), UnitMaster.unitsNeedingConversion("PCS", null, null, listOf("BOX")))
    }

    @Test fun skuIsGeneratedFromCategoryAndNameAndNeverRepeats() {
        assertEquals("RIC-PON-001", ProductCode.generate("Ponni Rice", "Rice & Grains", emptyList()))
        assertEquals("RIC-PON-003", ProductCode.generate("Ponni Rice", "Rice & Grains", listOf("ric-pon-001", "RIC-PON-002")))
        assertEquals("PON-001", ProductCode.generate("Ponni Rice", null, emptyList()))
        // A Tamil-only name: still a code.
        assertEquals("PRD-001", ProductCode.generate("பொன்னி அரிசி", "", emptyList()))
    }

    @Test fun eachKindHasItsOwnProductCategories() {
        assertTrue("Cement" in ProductCategories.suggestions(UnitMaster.Kind.HARDWARE))
        assertTrue("Tablets" in ProductCategories.suggestions(UnitMaster.Kind.PHARMACY))
        assertTrue("Seeds" in ProductCategories.suggestions(UnitMaster.Kind.AGRICULTURE))
        UnitMaster.Kind.entries.forEach { assertTrue(it.name, ProductCategories.suggestions(it).isNotEmpty()) }
    }

    @Test fun oldStockWithNoBillIsAnOpeningCorrection() {
        assertEquals(AdjustmentReason.OPENING_CORRECTION, AdjustmentReasonText.from("Old stock (no bill)"))
        assertEquals(AdjustmentReason.OPENING_CORRECTION, AdjustmentReasonText.from("பழைய ஸ்டாக் (பில் இல்லை)"))
        assertEquals(AdjustmentReason.PHYSICAL_COUNT, AdjustmentReasonText.from("எண்ணிக்கை திருத்தம்"))
        assertEquals(AdjustmentReason.DAMAGED, AdjustmentReasonText.from("சேதம்"))
        // A reason not in the list stays OTHER, with the owner's words kept as the note.
        assertEquals(AdjustmentReason.OTHER, AdjustmentReasonText.from("Customer return"))
    }
}
