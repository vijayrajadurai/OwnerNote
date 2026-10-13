package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.util.BillStock
import com.shopai.app.util.MyBusiness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The owner's check (13 Oct 2026), for six kinds of shop that keep stock — grocery, supermarket, hardware, automobile spare parts,
 * plywood, marble & tiles: purchase bills put their items into stock and sales bills take them out (scanned bill → the bill
 * screen's plan → Confirm), then every product's stock is asked of Kai typed and as the mic writes it (Tamil script) and must
 * be exactly what the bills left. Then stock is added by chat — typed ("Asian Paints 100 litre add pannu") and spoken
 * ("ஏசியன் பெயிண்ட்ஸ் 50 லிட்டர் ஆட் பண்ணு") — Confirmed, and asked again both ways.
 */
class KaiShopBillsStockTest {
    private val now = LocalDateTime.of(2026, 10, 13, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private fun r(s: String) = BigDecimal(s)

    /**
     * A product of the shop: its unit and second unit, how the mic writes its name, and how a bill writes it (name, unit,
     * how many of the product's unit one bill unit is, rate); [addSaid] / [addMic] add stock by chat in [addUnit]s.
     */
    private data class P(
        val id: String, val name: String, val unit: String, val stock: String, val conv: Map<String, String>, val mic: String,
        val billName: String, val billUnit: String, val perUnit: String, val rate: String,
    )

    private data class Kind(val shop: String, val products: List<P>, val suppliers: List<String>, val customers: List<String>,
        val addTyped: Triple<String, String, String>, val addMic: Triple<String, String, String>)

    private val grocery = Kind("Murugan Grocery", listOf(
        P("g1", "Ponni Rice", "KG", "500", mapOf("BAG" to "25"), "பொன்னி ரைஸ்", "Ponni Rice", "bag", "25", "1250.00"),
        P("g2", "Toor Dal", "KG", "200", emptyMap(), "டூர் தால்", "Toor Dal", "kg", "1", "120.00"),
        P("g3", "Sugar", "KG", "300", emptyMap(), "சுகர்", "Sugar", "kg", "1", "44.00"),
        P("g4", "Groundnut Oil", "LITRE", "150", emptyMap(), "கிரவுண்ட்நட் ஆயில்", "Groundnut Oil", "ltr", "1", "180.00"),
        P("g5", "Tata Salt", "PCS", "400", emptyMap(), "டாடா சால்ட்", "Tata Salt 1kg", "pcs", "1", "28.00"),
        P("g6", "Bru Coffee", "PCS", "120", emptyMap(), "ப்ரூ காபி", "Bru Coffee 100g", "pcs", "1", "95.00"),
    ), listOf("Sri Balaji Traders", "Annai Wholesale", "Kaveri Agencies"), listOf("Ravi", "Priya", "Arun"),
        Triple("Toor Dal 25 kg add pannu", "Toor Dal", "25"), Triple("சுகர் 50 கிலோ ஆட் பண்ணு", "Sugar", "50"))

    private val supermarket = Kind("Sri Lakshmi Supermarket", listOf(
        P("s1", "Colgate", "PCS", "600", mapOf("BOX" to "48"), "கோல்கேட்", "Colgate 200g", "box", "48", "1344.00"),
        P("s2", "Surf Excel", "PCS", "300", emptyMap(), "சர்ஃப் எக்ஸல்", "Surf Excel 1kg", "pcs", "1", "120.00"),
        P("s3", "Britannia Biscuit", "PCS", "500", mapOf("BOX" to "24"), "பிரிட்டானியா பிஸ்கட்", "Britannia Biscuit", "box", "24", "240.00"),
        P("s4", "Aashirvaad Atta", "KG", "400", mapOf("BAG" to "10"), "ஆசிர்வாத் ஆட்டா", "Aashirvaad Atta", "bag", "10", "450.00"),
        P("s5", "Dettol Soap", "PCS", "240", emptyMap(), "டெட்டால் சோப்", "Dettol Soap", "doz", "12", "480.00"),
        P("s6", "Amul Butter", "PCS", "100", emptyMap(), "அமுல் பட்டர்", "Amul Butter 100g", "pcs", "1", "56.00"),
    ), listOf("Hindustan Distributors", "Metro Cash Agencies", "Siva Sakthi Stores"), listOf("Deepa", "Mohan", "Kavitha"),
        Triple("Colgate 2 box add pannu", "Colgate", "96"), Triple("அமுல் பட்டர் 20 பீஸ் ஆட் பண்ணு", "Amul Butter", "20"))

    private val hardware = Kind("Vel Hardwares", listOf(
        P("h1", "Asian Paints", "LITRE", "200", emptyMap(), "ஏசியன் பெயிண்ட்ஸ்", "Asian Paints Apex", "ltr", "1", "320.00"),
        P("h2", "Cement", "BAG", "300", emptyMap(), "சிமெண்ட்", "Ramco Cement", "bag", "1", "410.00"),
        P("h3", "Wire", "METER", "1000", mapOf("COIL" to "90"), "ஒயர்", "Finolex Wire", "coil", "90", "1850.00"),
        P("h4", "Screw", "PCS", "2000", mapOf("BOX" to "100"), "ஸ்க்ரூ", "Screw 1 inch", "box", "100", "150.00"),
        P("h5", "Door Hinge", "PCS", "300", mapOf("PAIR" to "2"), "டோர் ஹிஞ்ச்", "Door Hinge 4in", "pair", "2", "90.00"),
        P("h6", "Paint Brush", "PCS", "150", emptyMap(), "பெயிண்ட் பிரஷ்", "Paint Brush 2in", "pcs", "1", "45.00"),
    ), listOf("Asian Paints Depot", "Ramco Agencies", "Finolex Distributors"), listOf("Ganesh", "Lakshmi", "Prakash"),
        Triple("Asian Paints 100 litre add pannu", "Asian Paints", "100"), Triple("ஏசியன் பெயிண்ட்ஸ் 50 லிட்டர் ஆட் பண்ணு", "Asian Paints", "50"))

    private val autoParts = Kind("Kumar Auto Spares", listOf(
        P("a1", "Engine Oil", "LITRE", "120", mapOf("CAN" to "5"), "இன்ஜின் ஆயில்", "Castrol Engine Oil", "can", "5", "1750.00"),
        P("a2", "Brake Pad", "SET", "80", emptyMap(), "பிரேக் பேட்", "Brake Pad Front", "set", "1", "650.00"),
        P("a3", "Spark Plug", "PCS", "200", mapOf("BOX" to "10"), "ஸ்பார்க் பிளக்", "Bosch Spark Plug", "box", "10", "1200.00"),
        P("a4", "Clutch Cable", "PCS", "60", emptyMap(), "கிளட்ச் கேபிள்", "Clutch Cable Splendor", "pcs", "1", "180.00"),
        P("a5", "Air Filter", "PCS", "70", emptyMap(), "ஏர் ஃபில்டர்", "Air Filter", "pcs", "1", "250.00"),
        P("a6", "Chain Sprocket", "SET", "40", emptyMap(), "செயின் ஸ்ப்ராக்கெட்", "Chain Sprocket Kit", "set", "1", "1450.00"),
    ), listOf("Bosch Auto Agencies", "TVS Spares Distributors", "Castrol Lubricants"), listOf("Senthil", "Meena", "Karthik"),
        Triple("Brake Pad 10 set add pannu", "Brake Pad", "10"), Triple("ஸ்பார்க் பிளக் 1 பாக்ஸ் ஆட் பண்ணு", "Spark Plug", "10"))

    private val plywood = Kind("Annai Plywoods", listOf(
        P("p1", "Plywood 18mm", "SHEET", "150", emptyMap(), "பிளைவுட் 18mm", "Plywood 18mm", "sheet", "1", "2400.00"),
        P("p2", "Laminate Sheet", "SHEET", "200", emptyMap(), "லேமினேட் ஷீட்", "Merino Laminate Sheet", "sheet", "1", "950.00"),
        P("p3", "Fevicol", "KG", "80", emptyMap(), "ஃபெவிகால்", "Fevicol SH", "kg", "1", "260.00"),
        P("p4", "Block Board", "SHEET", "60", emptyMap(), "பிளாக் போர்டு", "Block Board 19mm", "sheet", "1", "2100.00"),
        P("p5", "Teak Beading", "METER", "500", emptyMap(), "டீக் பீடிங்", "Teak Beading", "mtr", "1", "35.00"),
        P("p6", "Hinges", "PCS", "400", emptyMap(), "ஹிஞ்சஸ்", "SS Hinges 3in", "pcs", "1", "40.00"),
    ), listOf("Greenply Distributors", "Century Ply Agencies", "Merino Traders"), listOf("Divya", "Bala", "Sangeetha"),
        Triple("Laminate Sheet 20 sheet add pannu", "Laminate Sheet", "20"), Triple("ஃபெவிகால் 10 கிலோ ஆட் பண்ணு", "Fevicol", "10"))

    private val tiles = Kind("Sakthi Marbles & Tiles", listOf(
        P("t1", "Floor Tiles", "BOX", "400", emptyMap(), "ஃப்ளோர் டைல்ஸ்", "Kajaria Floor Tiles", "box", "1", "780.00"),
        P("t2", "Granite", "SQFT", "3000", emptyMap(), "கிரானைட்", "Black Granite", "sqft", "1", "160.00"),
        P("t3", "Italian Marble", "SQFT", "2000", emptyMap(), "இட்டாலியன் மார்பிள்", "Italian Marble", "sqft", "1", "350.00"),
        P("t4", "Tile Adhesive", "BAG", "150", emptyMap(), "டைல் அடெசிவ்", "Tile Adhesive 20kg", "bag", "1", "520.00"),
        P("t5", "Grout", "KG", "100", emptyMap(), "க்ரௌட்", "Grout White", "kg", "1", "90.00"),
        P("t6", "Wall Tiles", "BOX", "300", emptyMap(), "வால் டைல்ஸ்", "Somany Wall Tiles", "box", "1", "690.00"),
    ), listOf("Kajaria Ceramics", "Somany Agencies", "Rajasthan Marbles"), listOf("Murali", "Uma", "Rajesh"),
        Triple("Granite 500 sqft add pannu", "Granite", "500"), Triple("வால் டைல்ஸ் 40 பாக்ஸ் ஆட் பண்ணு", "Wall Tiles", "40"))

    private fun money(a: BigDecimal) = a.setScale(2, RoundingMode.HALF_UP).toPlainString()
    private fun plain(q: BigDecimal) = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
    /** How a reply may write a stock figure: 2000, 2,000. */
    private fun forms(q: BigDecimal): List<String> = listOf(plain(q)) +
        (if (q.stripTrailingZeros().scale() <= 0 && q >= BigDecimal(1000)) listOf("%,d".format(q.toLong())) else emptyList())

    /** A bill: the products [pick]ed, one quantity each, written in one of five layouts; a sales bill is the shop's own. */
    private fun billText(k: Kind, n: Int, sale: Boolean, pick: List<P>, qty: (Int) -> Int): String {
        val rows = pick.mapIndexed { i, p ->
            val q = qty(i)
            val amount = BigDecimal(q).multiply(r(p.rate))
            when ((n + i) % 5) {
                0 -> "${p.billName} 9999 $q ${p.billUnit} ${money(r(p.rate))} ${money(amount)}"
                1 -> "${p.billName} $q ${p.billUnit} ${money(r(p.rate))} ${money(amount)}"
                2 -> "${i + 1}. ${p.billName} $q ${p.billUnit} ${money(r(p.rate))} ${money(amount)}"
                3 -> "${p.billName.uppercase()} $q ${p.billUnit.uppercase()} ${money(r(p.rate))} ${money(amount)}"
                else -> "${p.billName} $q ${p.billUnit} Rs.${money(r(p.rate))} Rs.${money(amount)}"
            }
        }
        val sum = pick.indices.fold(BigDecimal.ZERO) { a, i -> a + BigDecimal(qty(i)).multiply(r(pick[i].rate)) }
        val head = if (sale) listOf(k.shop.uppercase(), "Ph: 9876500000", "TAX INVOICE", "Invoice No: S-${n + 1}", "Date: 1${n % 9}/10/2026", "Bill To: ${k.customers[n % 3]}")
        else listOf(k.suppliers[n % 3].uppercase(), "TAX INVOICE", "Invoice No: P-${n + 1}", "Date: 0${1 + n % 9}/10/2026", "Bill To: ${k.shop}")
        return (head + "Item Qty Rate Amount" + rows + "Grand Total ${money(sum)}").joinToString("\n")
    }

    private fun shopCheck(k: Kind): List<String> {
        val fails = mutableListOf<String>()
        val inv = Inventory()
        for (p in k.products) inv.items += Inventory.Item(p.id, p.name, p.unit, r(p.stock), p.conv.mapValues { r(it.value) }, null, null, "Shop", null)
        val expected = k.products.associate { it.id to r(it.stock) }.toMutableMap()
        val me = MyBusiness(k.shop, null, "9876500000")
        // 3 purchase bills (stock in), then 3 sales bills (stock out): three products each, rotating through the shop's six.
        for (n in 0 until 6) {
            val sale = n >= 3
            val pick = (0 until 3).map { k.products[(n * 2 + it) % k.products.size] }
            val qty = { i: Int -> 1 + (n * 3 + i * 2) % 7 }
            val text = billText(k, n, sale, pick, qty)
            val products = runBlocking { inv.products() }
            val plan = BillStock.plan(text, products, me)
            val what = "${k.shop} ${if (sale) "sales" else "purchase"} bill ${n % 3 + 1}"
            if (plan.credit != sale || plan.stockIn == sale) fails += "[$what] entry ${if (plan.credit) "credit" else "debit"}, stock ${if (plan.stockIn) "in" else "out"}"
            val want = pick.mapIndexed { i, p -> p.id to BigDecimal(qty(i)).multiply(r(p.perUnit)) }.toMap()
            val got = plan.moves.associate { (p, q) -> p.id to q }
            if (got.keys != want.keys || got.any { (id, q) -> q.compareTo(want.getValue(id)) != 0 })
                fails += "[$what] moves $got, want $want\n" + plan.lines.joinToString("\n") { "   ${it.itemName} | ${it.billQty} ${it.billUnit} → ${it.product?.name} ${it.qty} ${it.problem ?: ""}" } +
                    "\n" + text.prependIndent("      ")
            // Confirm on the bill screen: each ticked item in / out, as the save does.
            for ((p, q) in plan.moves) runBlocking { inv.changeStock(p, q, plan.stockIn, what) }
            for ((id, q) in want) expected[id] = expected.getValue(id) + if (sale) q.negate() else q
        }
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
        fun ask(said: String, p: P, want: BigDecimal) {
            val t = runBlocking { kai.ask(said) }
            if (forms(want).none { t.reply.text.contains(it) } || !t.reply.text.contains(p.name)) fails += "[${k.shop} ask] \"$said\" → ${t.reply.text}  (want ${plain(want)})"
        }
        // The stock the bills left, asked typed and spoken, for every product.
        for (p in k.products) {
            val left = inv.item(p.name).stock
            if (left.compareTo(expected.getValue(p.id)) != 0) fails += "[${k.shop}] ${p.name} stock $left, want ${expected[p.id]}"
            ask("${p.name} stock evlo", p, expected.getValue(p.id))
            ask("${p.name} evlo irukku", p, expected.getValue(p.id))
            ask("${p.mic} எவ்வளவு இருக்கு", p, expected.getValue(p.id))
            ask("${p.mic} ஸ்டாக் எவ்வளவு", p, expected.getValue(p.id))
        }
        // Stock added by chat — typed, then said into the mic — Confirmed, then asked both ways.
        for ((said, name, added) in listOf(k.addTyped, k.addMic)) {
            val p = k.products.first { it.name == name }
            val before = inv.item(name).stock
            val draft = runBlocking { kai.ask(said) }
            if (inv.item(name).stock.compareTo(before) != 0) fails += "[${k.shop} add] \"$said\" changed stock before Confirm"
            val confirm = draft.card?.buttons?.firstOrNull { it.primary && (it.label.contains("Confirm") || it.label.contains("உறுதி")) }
            if (confirm == null) {
                fails += "[${k.shop} add] \"$said\" → no Confirm: ${draft.reply.text} ${draft.card?.lines}"
                continue
            }
            val done = runBlocking { kai.act(confirm.action, if (said.any { it in '஀'..'௿' }) KaiLang.TAMIL else KaiLang.TANGLISH) }
            val after = inv.item(name).stock
            if (after.compareTo(before + r(added)) != 0) fails += "[${k.shop} add] \"$said\" → stock ${plain(after)}, want ${plain(before + r(added))} (${done?.reply?.text})"
            ask("$name evlo", p, before + r(added))
            ask("${p.mic} எவ்வளவு", p, before + r(added))
        }
        return fails
    }

    private fun check(k: Kind) {
        val fails = shopCheck(k)
        assertTrue("${fails.size} wrong:\n\n" + fails.joinToString("\n\n"), fails.isEmpty())
    }

    @Test fun grocery() = check(grocery)
    @Test fun supermarket() = check(supermarket)
    @Test fun hardware() = check(hardware)
    @Test fun automobileSpareParts() = check(autoParts)
    @Test fun plywood() = check(plywood)
    @Test fun marbleAndTiles() = check(tiles)
}
