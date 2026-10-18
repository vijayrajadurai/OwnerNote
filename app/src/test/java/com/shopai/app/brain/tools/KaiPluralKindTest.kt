package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A plural product name is read as its one word (owner, 18 Oct 2026: typed "Asian Paints 100 litre" was taken for a drink —
 * the litre's kind — while by mic "ஏசியன் பெயிண்ட்ஸ்" was paint). Typed and spoken give the same kind.
 */
class KaiPluralKindTest {
    @Test fun aPluralNameIsItsOneWord() {
        assertEquals(ProductKind.HARDWARE, KaiInventory.kindOf("Asian Paints", "LITRE"))
        assertEquals(KaiInventory.kindOf("ஏசியன் பெயிண்ட்ஸ்", "LITRE"), KaiInventory.kindOf("Asian Paints", "LITRE"))
        assertEquals(KaiInventory.kindOf("Asian Paint", "LITRE"), KaiInventory.kindOf("Asian Paints", "LITRE"))
        assertEquals(ProductKind.HARDWARE, KaiInventory.kindOf("Berger Primers", null))
    }

    @Test fun aWordNotAPluralIsNotCut() {
        // "Brass": not a plural — no kind made up from "bras"; the unit decides, as before.
        assertEquals(KaiInventory.kindByUnit("PCS"), KaiInventory.kindOf("Zorba Brass", "PCS"))
        // A name with no kind word at all: the unit decides, as before.
        assertEquals(KaiInventory.kindByUnit("LITRE"), KaiInventory.kindOf("Xyzzies", "LITRE"))
    }
}
