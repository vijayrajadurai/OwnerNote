package com.shopai.app.util

import com.shopai.app.brain.tools.ProductRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The owner's five purchase invoices (17 Oct 2026) as the scan reads them — a fancy store's GST bill, a medical bill with pack
 * size / batch / expiry, a supermarket bill with batch / expiry and a product-photo column, a hardware bill (lorry, drum) and a
 * textile bill ("20 | Pcs", "Pes"). Every row: its name, quantity, unit and rate — and the batch and expiry where printed — so
 * the stock comes in right. Both page reads Tesseract gives (auto layout, one column) are checked where the rows survive both.
 */
class BillOwnerInvoicesTest {
    private fun text(bill: Int, psm: Int) = javaClass.getResource("/bills/owner-17oct/bill${bill}_psm$psm.txt")!!.readText()

    /** name | qty unit | rate [| batch expiry(yyyy-mm)]. */
    private fun rows(bill: Int, psm: Int, products: List<ProductRef> = emptyList()) =
        BillStock.lines(BillTextParser.parse(text(bill, psm)).items, products).map { l ->
            listOfNotNull(l.itemName, "${l.billQty?.toPlainString()} ${l.billUnit}", l.rate?.toPlainString(),
                l.expiry?.let { "${l.batchNo} ${it.toString().take(7)}" }).joinToString(" | ")
        }

    private fun check(bill: Int, psm: Int, want: List<String>) {
        val got = rows(bill, psm)
        val diff = (0 until maxOf(got.size, want.size)).filter { got.getOrNull(it) != want.getOrNull(it) }
            .map { "row ${it + 1}: got «${got.getOrNull(it)}» want «${want.getOrNull(it)}»" }
        assertTrue("bill $bill PSM$psm:\n" + diff.joinToString("\n"), diff.isEmpty())
    }

    private val fancy = listOf(
        "Gift Box (Medium) Premium | 50 pcs | 40.00", "Water Bottle (Steel) Milton | 30 pcs | 250.00", "Key Chain Fancy | 100 pcs | 25.00",
        "Hair Band Set Kids Zone | 200 pcs | 15.00", "Wall Clock Ajanta | 10 pcs | 450.00", "Photo Frame Decor | 25 pcs | 120.00",
        "Plastic Toy Car Toy Story | 50 pcs | 80.00", "Decorative Lamp Sparkle | 15 pcs | 320.00",
    )

    private val medical = listOf(
        "Paracetamol Tablet | 50 strip | 12.00 | PT2501 2026-12", "Amoxicillin Capsule | 30 strip | 45.00 | AM2408 2026-08",
        "Pantoprazole Tablet | 20 strip | 38.00 | PT2411 2026-11", "Azithromycin Oral Susp | 10 bottle | 85.00 | null 2026-05",
        "Cetirizine Tablet | 50 strip | 18.00 | CT2502 2027-02", "Domperidone Tablet | 30 strip | 22.00 | P2409 2026-09",
        "Diclofenac Gel | 20 tube | 58.00 | DG2410 2026-10", "ORS Powder | 50 sachet | 8.00 | ORS2501 2027-01",
        "Vitamin D3 Capsule | 20 strip | 72.00 | VD2412 2026-12", "Surgical Mask (3 Ply) | 10 box | 95.00 | SM2503 2027-03",
        "Hand Sanitizer | 10 bottle | 120.00 | S2502 2027-02", "Gauze Roll Sterile | 50 roll | 16.00 | GR2407 2026-07",
    )

    private val grocery = listOf(
        "Aashirvaad Atta (10 Kg) | 10 bag | 320.00 | AA2501 2025-12", "Daawat Basmati Rice (5 Kg) | 8 bag | 450.00 | DR2408 2025-11",
        "Sresta Sunflower Oil (1 Ltr) | 20 btl | 145.00 | S02503 2026-03", "Tata Salt (1 Kg) | 25 pcs | 22.00 | TS2412 2026-12",
        "Surf Excel Detergent (1 Kg) | 12 pcs | 165.00 | SE2502 2027-02", "Colgate Toothpaste (200 g) | 24 pcs | 98.00 | CL2501 2027-01",
        "Bru Coffee Powder (100 g) | 12 pcs | 125.00 | BC2501 2025-10", "Amul Butter (500 g) | 10 pcs | 310.00 | AB2404 2026-04",
        "Maggi Noodles (70 g) | 30 pcs | 16.00 | MG2502 2026-02", "Fresh Onion (Loose) Local | 50 kg | 28.00",
        "Fresh Tomato (Loose) Local | 40 kg | 24.00", "Banana (Loose) Local | 30 kg | 32.00",
    )

    private val hardware = listOf(
        "OPC Cement 53 Grade (50 kg) UltraTech | 100 bags | 420.00", "TMT Steel 12 mm TATA Tiscon | 50 nos | 580.00",
        "Red Bricks (1st Quality) Local | 1000 nos | 8.00", "M-Sand Local | 2 lorry | 6500.00", "PVC Pipe 1 inch (3 mtr) Supreme | 50 nos | 120.00",
        "Electrical Wire 1.5 sq mm (90 mtr) Finolex | 10 coil | 850.00", "Modular Switch 6A (10 pcs box) Anchor | 5 box | 420.00",
        "LED Bulb 12W Havells | 20 nos | 110.00", "Brass Tap 15 mm Jaguar | 10 nos | 320.00",
        "Apex Exterior Emulsion 20 Ltr Asian Paints | 4 drum | 2850.00", "Tk GI Nails 2 inch (1 kg) Local | 10 kg | 90.00",
        "Se Teflon Tape Astral | 50 nos | 15.00",
    )

    private val textile = listOf(
        "Cotton Saree Lakshmi | 20 pcs | 650.00", "Silk Saree (Kanchipuram Type) Sri Devi | 10 pcs | 1850.00",
        "Chudi Material (3 Pcs Set) Trendy | 30 set | 420.00", "Shirting Fabric (White) Raymond | 50 mtr | 120.00",
        "Suiting Fabric (Dark) Donear | 40 mtr | 180.00", "Printed Kurti Fabric Aashirvad | 25 mtr | 90.00",
        "Leggings (Assorted Colours) Lyra | 100 pcs | 95.00", "Inner Wear Vest (Men) Rupa | 120 pcs | 65.00",
        "Kids Cotton Frock Fabric Cute Kids | 30 mtr | 110.00", "Dupatta (Chiffon) FC | 50 pcs | 150.00",
        "Nighty Material Jeeva | 40 mtr | 75.00", "Blouse Piece (Mixed Colours) Lakshmi | 100 pcs | 35.00",
    )

    // GST columns after the amount: qty × rate is the taxable amount (50 × 40 = 2,000), not the row's last number (2,240).
    @Test fun fancyStoreGstBill() { check(1, 3, fancy); check(1, 4, fancy) }

    // Pack size (10x10), batch and expiry leave the name; the composition column ("Paracetamol 650 mg") too.
    @Test fun medicalBillWithBatchAndExpiry() { check(2, 4, medical); check(2, 3, medical.map { it.replace("CT2502", "T2502") }) }

    // The column header "Rate (₹) Amount (₹) Total (₹)" is no total; the shop's banner above it is no item; the photo column's specks
    // ("6 [I", "9 JEBl_", "at is") leave the names; a repeated brand ("Bru … Bru", "Surf Excel … Surf Excel") too.
    @Test fun supermarketBillWithBatchAndExpiry() {
        check(3, 4, grocery)
        val bill = BillTextParser.parse(text(3, 4))
        assertTrue("total ${bill.total}", bill.total!! > BigDecimal("1000"))
    }

    // A lorry of sand, a drum of paint; the GSTIN piece the column read keeps is no item.
    @Test fun hardwareBill() {
        check(4, 4, hardware)
        assertTrue(rows(4, 3).none { it.contains("33AAJFS") })
    }

    // "20 | Pcs" (a table line), "5208 i 50" (a speck), "Pes" (Pcs): every row keeps its quantity and unit; the row numbers go.
    @Test fun textileBill() { check(5, 3, textile); check(5, 4, textile) }

    /** The pass with the rows is kept over a pass that lost them, even when the bill's own totals don't add up. */
    @Test fun thePassWithTheRowsIsChosen() {
        for (b in 1..5) {
            val texts = listOf(text(b, 3), text(b, 4))
            val chosen = texts[OcrResultChooser.best(texts)]
            val n = BillTextParser.parse(chosen).items.count { it.quantity != null }
            val most = texts.maxOf { t -> BillTextParser.parse(t).items.count { it.quantity != null } }
            assertEquals("bill $b", most, n)
        }
    }

    /** The shop's own products: each row lands on it, in its unit, with the row's batch / expiry for the stock. */
    @Test fun rowsLandOnTheShopsProducts() {
        val shop = listOf(
            ProductRef("m1", "Paracetamol Tablet", "STRIP", BigDecimal("10"), category = "Pharmacy & Medical"),
            ProductRef("g1", "Aashirvaad Atta", "BAG", BigDecimal("2"), category = "Grocery & Staples"),
            ProductRef("g2", "Tata Salt", "PCS", BigDecimal("0"), mapOf("BOX" to BigDecimal("25"))),
            ProductRef("h1", "M-Sand", "LORRY", BigDecimal("0")),
            ProductRef("t1", "Leggings", "PCS", BigDecimal("5")),
        )
        val med = BillStock.lines(BillTextParser.parse(text(2, 4)).items, shop).first()
        assertEquals("m1", med.product?.id)
        assertEquals(0, BigDecimal("50").compareTo(med.qty))
        assertEquals("PT2501", med.batchNo)
        assertEquals(LocalDate.of(2026, 12, 31), med.expiry)
        val atta = BillStock.lines(BillTextParser.parse(text(3, 4)).items, shop)
        assertEquals("g1", atta[0].product?.id)
        assertEquals(0, BigDecimal("10").compareTo(atta[0].qty))
        assertEquals("g2", atta[3].product?.id)
        assertEquals(0, BigDecimal("25").compareTo(atta[3].qty))
        // Loose onions have no batch / expiry printed: none made up.
        assertNull(atta[9].expiry)
        val sand = BillStock.lines(BillTextParser.parse(text(4, 4)).items, shop).single { it.itemName.startsWith("M-Sand") }
        assertEquals("h1", sand.product?.id)
        assertEquals(0, BigDecimal("2").compareTo(sand.qty))
        val leg = BillStock.lines(BillTextParser.parse(text(5, 4)).items, shop).single { it.itemName.startsWith("Leggings") }
        assertEquals("t1", leg.product?.id)
        assertEquals(0, BigDecimal("100").compareTo(leg.qty))
    }

    /** Words not in Kai's lists: a unit it doesn't know is asked (never the default unit), a form word it doesn't know keeps the name. */
    @Test fun wordsNotInTheListsAreNotGuessed() {
        val bill = """
            Description Qty Unit Rate Amount
            Alphonso Mango 3 Crate 600.00 1,800.00
            Betadine Solution Povidone 5% 100ml 10 Btl 95.00 950.00
            Glucon D Powder 100g 12 Pcs 40.00 480.00
            Cough Syrup 13/2026 5 Btl 60.00 300.00
            Grand Total 3,530.00
        """.trimIndent()
        val shop = listOf(ProductRef("x1", "Alphonso Mango", "KG", BigDecimal("0")))
        val l = BillStock.lines(BillTextParser.parse(bill).items, shop)
        assertEquals("x1", l[0].product?.id)
        // "Crate" isn't a unit Kai knows: the line waits for the owner — never 3 kg.
        assertTrue("${l[0]}", !l[0].sure)
        assertEquals("Betadine Solution Povidone 5% 100ml", l[1].itemName)
        // A plain pack size stays in the name (it tells one product from another).
        assertEquals("Glucon D Powder 100g", l[2].itemName)
        // Month 13 is no expiry.
        assertNull(l[3].expiry)
    }
}
