package com.shopai.app.books.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Which batch a sale / stock change uses when no batch was chosen: first expiry first out, split; in to the newest batch. */
class BatchPickTest {
    private fun b(no: String, expiry: Int?, created: Long, qty: Long) = BatchStock(no, no, expiry, created, qty)

    @Test fun outTakesTheFirstExpiryFirstAndSplits() {
        val bs = listOf(b("LATE", 500, 1, 60_000), b("SOON", 100, 2, 40_000), b("NOEXP", null, 0, 99_000), b("EMPTY", 50, 3, 0))
        val out = BatchPick.out(bs, 50_000)
        assertEquals(listOf("SOON" to 40_000L, "LATE" to 10_000L), out.parts.map { it.first.batchNo to it.second })
        assertEquals(0L, out.shortMilli)
        // More than all batches have: what's short is reported, the no-expiry batch goes last.
        val all = BatchPick.out(bs, 250_000)
        assertEquals(listOf("SOON", "LATE", "NOEXP"), all.parts.map { it.first.batchNo })
        assertEquals(51_000L, all.shortMilli)
    }

    @Test fun aLineSplitsInItsOwnUnitWithItsDiscount() {
        // 5 strips (strip = 10 tablets) over 40 + 10 tablets: 4 strips from SOON, 1 from LATE; ₹10 discount 8 + 2.
        val out = BatchPick.out(listOf(b("SOON", 100, 1, 40_000), b("LATE", 200, 2, 60_000)), 50_000)
        assertEquals(listOf(BatchPick.LinePart("SOON", 4_000, 800), BatchPick.LinePart("LATE", 1_000, 200)), BatchPick.splitLine(5_000, 10_000, 1_000, out))
        // One batch enough: the line as it is.
        val one = BatchPick.out(listOf(b("SOON", 100, 1, 40_000)), 20_000)
        assertEquals(listOf(BatchPick.LinePart("SOON", 2_000, 0)), BatchPick.splitLine(2_000, 10_000, 0, one))
        // No batch at all: nothing to split (the books ask for the batch).
        assertEquals(emptyList<BatchPick.LinePart>(), BatchPick.splitLine(2_000, 1_000, 0, BatchPick.out(emptyList(), 2_000)))
    }

    @Test fun inGoesToTheNewestBatchOrANewDatedOne() {
        assertEquals("NEW", BatchPick.into(listOf(b("OLD", 100, 1, 5), b("NEW", 50, 9, 0)))?.batchNo)
        assertEquals(null, BatchPick.into(emptyList()))
        assertEquals("B-261013", BatchPick.newNo(LocalDate.of(2026, 10, 13)))
    }
}
