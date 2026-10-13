package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.BatchFact
import com.shopai.app.util.BillStock
import com.shopai.app.util.MyBusiness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The owner's 10-category stock guide (OwnerNote_10_Shop_Categories_Stock_Units, 13 Oct 2026): grocery, hardware, electrical,
 * textile, automobile spares, medical, rice mandi, plywood, paint, building materials — each with its own units and variants.
 * For every category, stock is moved by chat typed and as the mic writes it (Tamil script): a known conversion gives the exact
 * quantity on a draft that changes stock only on Confirm; an unknown one (a load, a length, a tin, a bag with no weight) is asked,
 * never guessed; a name that fits several products ("plywood", "paint") is asked and the answer goes on. Bills (OCR) put their
 * items in / out the same way, the medical shop gets its expiry list, and every product's stock is then asked.
 */
class KaiTenCategoriesTest {
    private val now = LocalDateTime.of(2026, 10, 13, 10, 0)
    private val today: LocalDate = now.toLocalDate()

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private fun r(s: String) = BigDecimal(s)

    private sealed interface Want
    /** A draft for [product] that, on Confirm, changes its stock by [delta]. */
    private data class Moves(val product: String, val delta: String) : Want
    /** Kai asks (one of [words] in the reply) and nothing changes. */
    private class Asks(vararg val words: String) : Want
    /** Kai answers with all of [words], nothing changes. */
    private class Says(vararg val words: String) : Want

    private data class Case(val lines: List<String>, val want: Want)
    private fun c(vararg lines: String, want: Want) = Case(lines.toList(), want)

    private data class Item(val name: String, val unit: String, val stock: String, val conv: Map<String, String> = emptyMap(), val mic: String? = null)

    /** A bill row: the name as printed, the quantity, the unit; [moves] = the product and its change in the product's unit, null = not moved. */
    private data class Row(val name: String, val qty: Int, val unit: String, val rate: String, val moves: Pair<String, String>?)

    private data class Category(
        val shop: String, val items: List<Item>, val cases: List<Case>,
        val purchase: List<Row>, val sale: List<Row>, val batches: List<BatchFact> = emptyList(),
    )

    private val grocery = Category("Murugan Grocery",
        listOf(Item("Ponni Rice", "KG", "250", mapOf("BAG" to "25"), "பொன்னி ரைஸ்"), Item("Sunflower Oil", "LITRE", "40"), Item("Biscuit", "PCS", "100", mapOf("BOX" to "24"), "பிஸ்கட்"),
            Item("Soap", "PCS", "60", mic = "சோப்"), Item("Sugar", "KG", "50", mic = "சுகர்"), Item("Toor Dal", "KG", "80", mic = "டூர் தால்")),
        listOf(
            c("Ponni Rice 2 bag add pannu", want = Moves("Ponni Rice", "50")), c("பொன்னி ரைஸ் 2 மூட்டை ஆட் பண்ணு", want = Moves("Ponni Rice", "50")),
            c("Biscuit 1 box vandhuchu", want = Moves("Biscuit", "24")), c("பிஸ்கட் 1 பாக்ஸ் வந்துச்சு", want = Moves("Biscuit", "24")),
            c("Sugar 500 g vithuchu", want = Moves("Sugar", "-0.5")), c("சுகர் 500 கிராம் வித்துச்சு", want = Moves("Sugar", "-0.5")),
            c("Soap 1 dozen add pannu", want = Moves("Soap", "12")), c("சோப் 1 டஜன் ஆட் பண்ணு", want = Moves("Soap", "12")),
            c("Sunflower Oil 5 litre add pannu", want = Moves("Sunflower Oil", "5")),
            // No bag weight set for Toor Dal: asked, never taken as 2 kg.
            c("Toor Dal 2 bag add pannu", want = Asks("bag")), c("டூர் தால் 2 மூட்டை ஆட் பண்ணு", want = Asks("bag")),
        ),
        purchase = listOf(Row("Ponni Rice", 4, "bag", "1250.00", "Ponni Rice" to "100"), Row("Sugar", 25, "kg", "44.00", "Sugar" to "25"), Row("Soap", 2, "doz", "360.00", "Soap" to "24")),
        sale = listOf(Row("Biscuit", 1, "box", "240.00", "Biscuit" to "-24"), Row("Sunflower Oil", 3, "ltr", "180.00", "Sunflower Oil" to "-3")))

    private val hardware = Category("Vel Hardwares",
        listOf(Item("Nail", "KG", "20"), Item("Screw", "PCS", "500", mapOf("BOX" to "100"), "ஸ்க்ரூ"), Item("PVC Pipe", "PCS", "40"), Item("Door Lock", "SET", "12", mic = "டோர் லாக்"),
            Item("Binding Wire", "KG", "30"), Item("Rope", "METER", "200")),
        listOf(
            c("Nail 5 kg add pannu", want = Moves("Nail", "5")), c("ஆணி 5 கிலோ ஆட் பண்ணு", want = Moves("Nail", "5")),
            c("Screw 2 box add pannu", want = Moves("Screw", "200")), c("ஸ்க்ரூ 2 பாக்ஸ் ஆட் பண்ணு", want = Moves("Screw", "200")),
            c("PVC Pipe 10 piece add pannu", want = Moves("PVC Pipe", "10")),
            c("Door Lock 2 set vithuchu", want = Moves("Door Lock", "-2")), c("டோர் லாக் 2 செட் வித்துச்சு", want = Moves("Door Lock", "-2")),
            c("Binding Wire 3 kg vithuchu", want = Moves("Binding Wire", "-3")),
            // Feet for a product kept in metres: asked.
            c("Rope 20 feet vithuchu", want = Asks("feet")),
        ),
        purchase = listOf(Row("Screw 1 inch", 3, "box", "150.00", "Screw" to "300"), Row("Nail 2in", 10, "kg", "90.00", "Nail" to "10"), Row("Door Lock Godrej", 4, "set", "650.00", "Door Lock" to "4")),
        sale = listOf(Row("PVC Pipe 1in", 6, "pcs", "220.00", "PVC Pipe" to "-6"), Row("Binding Wire", 2, "kg", "95.00", "Binding Wire" to "-2")))

    private val electrical = Category("Sri Electricals",
        listOf(Item("LED Bulb", "PCS", "50", mapOf("BOX" to "10"), "எல்இடி பல்ப்"), Item("Tube Light", "PCS", "20"), Item("Cable", "METER", "300", mapOf("COIL" to "90"), "கேபிள்"),
            Item("Switch", "PCS", "80", mic = "ஸ்விட்ச்"), Item("Ceiling Fan", "PCS", "6")),
        listOf(
            // The guide's example: "10 box bulb add pannu" — the box size is known for the bulb, so 100 pieces.
            c("10 box bulb add pannu", want = Moves("LED Bulb", "100")),
            c("LED Bulb 2 box add pannu", want = Moves("LED Bulb", "20")), c("எல்இடி பல்ப் 2 பாக்ஸ் ஆட் பண்ணு", want = Moves("LED Bulb", "20")),
            c("Tube Light 2 box add pannu", want = Asks("box")),
            c("Cable 1 coil add pannu", want = Moves("Cable", "90")),
            c("Switch 10 piece vithuchu", want = Moves("Switch", "-10")), c("ஸ்விட்ச் 10 பீஸ் வித்துச்சு", want = Moves("Switch", "-10")),
            c("Ceiling Fan 2 vithuchu", want = Moves("Ceiling Fan", "-2")),
        ),
        purchase = listOf(Row("LED Bulb 9W", 5, "box", "450.00", "LED Bulb" to "50"), Row("Cable 1.5 sqmm", 2, "coil", "1850.00", "Cable" to "180")),
        sale = listOf(Row("Switch", 12, "pcs", "35.00", "Switch" to "-12"), Row("Tube Light", 4, "pcs", "260.00", "Tube Light" to "-4")))

    private val textile = Category("Lakshmi Textiles",
        listOf(Item("Shirt L Blue", "PCS", "10"), Item("Shirt M Blue", "PCS", "8"), Item("Chappal", "PAIR", "15"), Item("Cotton Cloth", "METER", "200", mic = "காட்டன் கிளாத்"),
            Item("Saree", "PCS", "25", mic = "சாரி")),
        listOf(
            c("Shirt L Blue 5 piece add pannu", want = Moves("Shirt L Blue", "5")),
            // Size / colour not said, two variants: asked.
            c("Shirt 5 piece add pannu", want = Asks("Size", "size", "L", "M")),
            c("Chappal 3 pair vithuchu", want = Moves("Chappal", "-3")),
            c("Cotton Cloth 10 meter vithuchu", want = Moves("Cotton Cloth", "-10")), c("காட்டன் கிளாத் 10 மீட்டர் வித்துச்சு", want = Moves("Cotton Cloth", "-10")),
            c("Saree 2 vithuchu", want = Moves("Saree", "-2")), c("சாரி 2 வித்துச்சு", want = Moves("Saree", "-2")),
        ),
        purchase = listOf(Row("Shirt L Blue", 10, "pcs", "450.00", "Shirt L Blue" to "10"), Row("Cotton Cloth", 50, "mtr", "120.00", "Cotton Cloth" to "50")),
        sale = listOf(Row("Chappal", 2, "pair", "350.00", "Chappal" to "-2"), Row("Saree", 3, "pcs", "1500.00", "Saree" to "-3")))

    private val autoSpares = Category("Kumar Auto Spares",
        listOf(Item("Brake Pad", "SET", "10", mic = "பிரேக் பேட்"), Item("Spark Plug", "PCS", "40", mapOf("BOX" to "10")), Item("Engine Oil", "LITRE", "30"),
            Item("Bearing", "PCS", "25"), Item("Clutch Plate", "KIT", "5", mic = "கிளட்ச் பிளேட்")),
        listOf(
            c("Brake Pad 2 set add pannu", want = Moves("Brake Pad", "2")), c("பிரேக் பேட் 2 செட் ஆட் பண்ணு", want = Moves("Brake Pad", "2")),
            c("Clutch Plate 1 kit add pannu", want = Moves("Clutch Plate", "1")), c("கிளட்ச் பிளேட் 1 கிட் ஆட் பண்ணு", want = Moves("Clutch Plate", "1")),
            c("Engine Oil 5 litre vithuchu", want = Moves("Engine Oil", "-5")),
            c("Spark Plug 2 box add pannu", want = Moves("Spark Plug", "20")),
            c("Bearing 4 piece vithuchu", want = Moves("Bearing", "-4")),
        ),
        purchase = listOf(Row("Bosch Spark Plug", 3, "box", "1200.00", "Spark Plug" to "30"), Row("Clutch Plate Splendor", 2, "kit", "1450.00", "Clutch Plate" to "2")),
        sale = listOf(Row("Brake Pad Front", 2, "set", "650.00", "Brake Pad" to "-2"), Row("Engine Oil 20W40", 4, "ltr", "350.00", "Engine Oil" to "-4")))

    private val medical = Category("Amman Medicals",
        listOf(Item("Paracetamol", "STRIP", "100", mapOf("BOX" to "10"), "பாராசிட்டமால்"), Item("Cough Syrup", "BOTTLE", "20", mic = "காஃப் சிரப்"), Item("Ointment", "PCS", "15"),
            Item("Injection", "VIAL", "30")),
        listOf(
            c("Paracetamol 10 strip add pannu", want = Moves("Paracetamol", "10")), c("பாராசிட்டமால் 10 ஸ்ட்ரிப் ஆட் பண்ணு", want = Moves("Paracetamol", "10")),
            c("Paracetamol 2 box add pannu", want = Moves("Paracetamol", "20")),
            c("Cough Syrup 5 bottle vithuchu", want = Moves("Cough Syrup", "-5")), c("காஃப் சிரப் 5 பாட்டில் வித்துச்சு", want = Moves("Cough Syrup", "-5")),
            c("Injection 20 vial add pannu", want = Moves("Injection", "20")),
            c("Ointment 3 tube vithuchu", want = Moves("Ointment", "-3")),
            // The guide's example: a read-only expiry list from the batch records.
            c("Expiry aagura medicine list kudu", want = Says("Paracetamol", "B12", "Cough Syrup", "S07")),
            c("எக்ஸ்பைரி ஆகுற மெடிசின் லிஸ்ட் குடு", want = Says("Paracetamol", "B12", "Cough Syrup", "S07")),
        ),
        purchase = listOf(Row("Paracetamol 500mg", 3, "box", "250.00", "Paracetamol" to "30"), Row("Injection", 10, "vial", "45.00", "Injection" to "10")),
        sale = listOf(Row("Cough Syrup 100ml", 2, "bottle", "95.00", "Cough Syrup" to "-2"), Row("Paracetamol 500mg", 5, "strip", "25.00", "Paracetamol" to "-5")),
        batches = listOf(BatchFact("Paracetamol", "B12", LocalDate.of(2026, 10, 20), r("40"), "STRIP"), BatchFact("Cough Syrup", "S07", LocalDate.of(2026, 10, 10), r("6"), "BOTTLE"),
            BatchFact("Injection", "V33", LocalDate.of(2027, 3, 1), r("30"), "VIAL")))

    private val riceMandi = Category("Annai Rice Mandi",
        listOf(Item("Ponni Arisi", "KG", "5000", mapOf("BAG" to "50"), "பொன்னி அரிசி"), Item("Idli Arisi", "KG", "2000")),
        listOf(
            // The guide's example: "2 mootai arisi vandhurukku" — which rice / how much a bag holds is asked, never guessed into kg.
            c("2 mootai arisi vandhurukku", want = Asks("bag", "arisi", "Arisi")),
            c("Ponni Arisi 2 mootai vandhurukku", want = Moves("Ponni Arisi", "100")), c("பொன்னி அரிசி 10 மூட்டை வந்துருக்கு", want = Moves("Ponni Arisi", "500")),
            c("Idli Arisi 2 mootai vandhurukku", want = Asks("bag")),
            c("Ponni Arisi 2 quintal add pannu", want = Moves("Ponni Arisi", "200")), c("பொன்னி அரிசி 2 குவிண்டால் ஆட் பண்ணு", want = Moves("Ponni Arisi", "200")),
            c("Ponni Arisi 1 tonne add pannu", want = Moves("Ponni Arisi", "1000")), c("பொன்னி அரிசி 1 டன் ஆட் பண்ணு", want = Moves("Ponni Arisi", "1000")),
            c("Ponni Arisi 1 load add pannu", want = Asks("load")), c("பொன்னி அரிசி 1 லோடு ஆட் பண்ணு", want = Asks("load")),
        ),
        purchase = listOf(Row("Ponni Arisi", 20, "bag", "2400.00", "Ponni Arisi" to "1000"), Row("Ponni Arisi", 2, "tonne", "48000.00", "Ponni Arisi" to "2000"),
            Row("Idli Arisi", 3, "quintal", "3800.00", "Idli Arisi" to "300"), Row("Idli Arisi", 1, "load", "60000.00", null)),
        sale = listOf(Row("Ponni Arisi", 5, "bag", "2600.00", "Ponni Arisi" to "-250"), Row("Idli Arisi", 150, "kg", "40.00", "Idli Arisi" to "-150")))

    private val plywood = Category("Annai Plywoods",
        listOf(Item("Plywood 12mm", "SHEET", "40"), Item("Plywood 18mm", "SHEET", "30"), Item("Laminate", "SHEET", "60", mic = "லேமினேட்"), Item("Veneer", "SQFT", "500")),
        listOf(
            // The guide's example: the thickness picks the plywood.
            c("12 mm plywood 5 sheet add pannu", want = Moves("Plywood 12mm", "5")),
            c("Plywood 18mm 3 sheet vithuchu", want = Moves("Plywood 18mm", "-3")),
            c("Plywood 5 sheet add pannu", want = Asks("12mm")),
            c("Plywood 5 sheet add pannu", "12mm", want = Moves("Plywood 12mm", "5")),
            c("பிளைவுட் 5 ஷீட் ஆட் பண்ணு", "12 எம்எம்", want = Moves("Plywood 12mm", "5")),
            c("பிளைவுட் 12mm 5 ஷீட் ஆட் பண்ணு", want = Moves("Plywood 12mm", "5")),
            c("Veneer 100 sqft add pannu", want = Moves("Veneer", "100")),
            c("Laminate 10 sheet add pannu", want = Moves("Laminate", "10")), c("லேமினேட் 10 ஷீட் ஆட் பண்ணு", want = Moves("Laminate", "10")),
        ),
        purchase = listOf(Row("Plywood 12mm 8x4", 20, "sheet", "1650.00", "Plywood 12mm" to "20"), Row("Veneer Teak", 200, "sqft", "45.00", "Veneer" to "200")),
        sale = listOf(Row("Plywood 18mm", 4, "sheet", "2400.00", "Plywood 18mm" to "-4"), Row("Laminate 1mm", 6, "sheet", "950.00", "Laminate" to "-6")))

    private val paint = Category("Colour World Paints",
        listOf(Item("Emulsion Paint", "LITRE", "100", mic = "எமல்ஷன் பெயிண்ட்"), Item("Wall Putty", "KG", "400", mapOf("BAG" to "20")), Item("Primer", "LITRE", "80", mapOf("BUCKET" to "10"), "பிரைமர்"),
            Item("Spray Paint", "PCS", "30")),
        listOf(
            // The guide's example: "5 litre paint sale pannu" — which paint is asked, then the answer goes on.
            c("5 litre paint sale pannu", want = Asks("Emulsion Paint")),
            c("5 litre paint sale pannu", "Emulsion", want = Moves("Emulsion Paint", "-5")),
            c("Primer 2 bucket add pannu", want = Moves("Primer", "20")), c("பிரைமர் 2 பக்கெட் ஆட் பண்ணு", want = Moves("Primer", "20")),
            c("Wall Putty 3 bag vithuchu", want = Moves("Wall Putty", "-60")),
            // A tin's size isn't set: asked.
            c("Emulsion Paint 2 tin add pannu", want = Asks("can", "tin")),
            c("எமல்ஷன் பெயிண்ட் 5 லிட்டர் வித்துச்சு", want = Moves("Emulsion Paint", "-5")),
        ),
        purchase = listOf(Row("Emulsion Paint", 20, "ltr", "320.00", "Emulsion Paint" to "20"), Row("Primer", 2, "bucket", "1900.00", "Primer" to "20"), Row("Emulsion Paint", 2, "tin", "1200.00", null)),
        sale = listOf(Row("Wall Putty 20kg", 5, "bag", "780.00", "Wall Putty" to "-100"), Row("Spray Paint", 3, "pcs", "180.00", "Spray Paint" to "-3")))

    private val building = Category("Sakthi Building Materials",
        listOf(Item("Cement", "BAG", "200", mic = "சிமெண்ட்"), Item("Brick", "PCS", "5000", mic = "செங்கல்"), Item("Sand", "CFT", "800"), Item("Jelly", "CFT", "600"), Item("Steel Rod", "KG", "1000")),
        listOf(
            // The guide's example: bricks in pieces through the review / confirm flow.
            c("100 brick stock add pannu", want = Moves("Brick", "100")), c("செங்கல் 1000 ஆட் பண்ணு", want = Moves("Brick", "1000")),
            c("Cement 50 bag vandhuchu", want = Moves("Cement", "50")), c("சிமெண்ட் 50 மூட்டை வந்துச்சு", want = Moves("Cement", "50")),
            c("Sand 100 cft add pannu", want = Moves("Sand", "100")),
            // A load's cft, a length's kg, a tonne of jelly in cft: asked — the guide's main safety rule.
            c("Sand 1 load add pannu", want = Asks("load")),
            c("Steel Rod 10 length vithuchu", want = Asks("length")),
            c("Jelly 2 tonne add pannu", want = Asks("tonne")),
        ),
        purchase = listOf(Row("Ramco Cement", 100, "bag", "410.00", "Cement" to "100"), Row("Sand", 300, "cft", "55.00", "Sand" to "300"), Row("Sand", 1, "load", "18000.00", null)),
        sale = listOf(Row("Brick", 2000, "pcs", "9.00", "Brick" to "-2000"), Row("Steel Rod 12mm", 150, "kg", "68.00", "Steel Rod" to "-150")))

    private fun money(a: BigDecimal) = a.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    private fun plain(q: BigDecimal) = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    private fun inventory(cat: Category) = Inventory().apply {
        cat.items.forEachIndexed { i, it -> items += Inventory.Item("i$i", it.name, it.unit, r(it.stock), it.conv.mapValues { (_, v) -> r(v) }, null, null, "Shop", null) }
        batches += cat.batches
    }

    private fun billText(cat: Category, rows: List<Row>, sale: Boolean, n: Int): String {
        val lines = rows.mapIndexed { i, row ->
            val amount = BigDecimal(row.qty).multiply(r(row.rate))
            if ((n + i) % 2 == 0) "${row.name} ${row.qty} ${row.unit} ${money(r(row.rate))} ${money(amount)}"
            else "${i + 1}. ${row.name} ${row.qty} ${row.unit.uppercase()} ${money(r(row.rate))} ${money(amount)}"
        }
        val total = rows.fold(BigDecimal.ZERO) { a, row -> a + BigDecimal(row.qty).multiply(r(row.rate)) }
        val head = if (sale) listOf(cat.shop.uppercase(), "Ph: 9876500000", "TAX INVOICE", "Invoice No: S-$n", "Date: 12/10/2026", "Bill To: Ravi")
        else listOf("CITY WHOLESALE TRADERS", "TAX INVOICE", "Invoice No: P-$n", "Date: 11/10/2026", "Bill To: ${cat.shop}")
        return (head + "Item Qty Rate Amount" + lines + "Grand Total ${money(total)}").joinToString("\n")
    }

    private fun check(cat: Category) {
        val fails = mutableListOf<String>()
        // ---- chat, typed and spoken: each case on a fresh shop ----
        for (case in cat.cases) {
            val inv = inventory(cat)
            val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
            var t: KaiTurn? = null
            for (l in case.lines) t = runBlocking { kai.ask(l) }
            val turn = t!!
            val said = case.lines.joinToString(" → ")
            val text = turn.reply.text + " " + turn.card?.lines.orEmpty().joinToString(" ")
            val before = cat.items.associate { it.name to r(it.stock) }
            fun unchanged() = inv.items.all { it.stock.compareTo(before.getValue(it.name)) == 0 }
            when (val w = case.want) {
                is Moves -> {
                    if (!unchanged()) { fails += "[$said] stock changed before Confirm"; continue }
                    val confirm = turn.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") }
                    if (confirm == null) { fails += "[$said] no draft: ${turn.reply.text} ${turn.card?.lines}"; continue }
                    runBlocking { kai.act(confirm.action, if (said.any { it in '஀'..'௿' }) KaiLang.TAMIL else KaiLang.TANGLISH) }
                    val after = inv.item(w.product).stock
                    val want = before.getValue(w.product) + r(w.delta)
                    if (after.compareTo(want) != 0) fails += "[$said] ${w.product} stock ${plain(after)}, want ${plain(want)} — ${turn.reply.text}"
                    val others = inv.items.filter { it.name != w.product && it.stock.compareTo(before.getValue(it.name)) != 0 }
                    if (others.isNotEmpty()) fails += "[$said] also changed ${others.map { it.name }}"
                }
                is Asks -> {
                    if (!unchanged() || turn.card?.buttons?.any { it.label.contains("Confirm") || it.label.contains("உறுதி") } == true || w.words.none { text.contains(it) })
                        fails += "[$said] not asked: $text"
                }
                is Says -> if (!unchanged() || w.words.any { !text.contains(it) }) fails += "[$said] said: $text"
            }
        }
        // ---- bills (OCR text): purchase in, sale out; then every product's stock asked typed and spoken ----
        val inv = inventory(cat)
        val me = MyBusiness(cat.shop, null, "9876500000")
        val expected = cat.items.associate { it.name to r(it.stock) }.toMutableMap()
        for ((n, pair) in listOf(cat.purchase to false, cat.sale to true).withIndex()) {
            val (rows, sale) = pair
            val text = billText(cat, rows, sale, n)
            val plan = BillStock.plan(text, runBlocking { inv.products() }, me)
            val got = plan.moves.groupBy({ it.first.name }, { it.second }).mapValues { (_, q) -> q.fold(BigDecimal.ZERO, BigDecimal::add) }
            val want = rows.mapNotNull { it.moves }.groupBy({ it.first }, { r(it.second).abs() }).mapValues { (_, q) -> q.fold(BigDecimal.ZERO, BigDecimal::add) }
            if (plan.credit != sale || plan.stockIn == sale || got.keys != want.keys || got.any { (k, q) -> q.compareTo(want.getValue(k)) != 0 })
                fails += "[${cat.shop} ${if (sale) "sales" else "purchase"} bill] credit=${plan.credit} in=${plan.stockIn} moves $got, want $want\n" +
                    plan.lines.joinToString("\n") { "   ${it.itemName} | ${it.billQty} ${it.billUnit} → ${it.product?.name} ${it.qty} ${it.problem ?: ""}" }
            for ((p, q) in plan.moves) runBlocking { inv.changeStock(p, q, plan.stockIn, "bill") }
            for ((name, d) in rows.mapNotNull { it.moves }) expected[name] = expected.getValue(name) + r(d)
        }
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
        for (item in cat.items) {
            val want = expected.getValue(item.name)
            if (inv.item(item.name).stock.compareTo(want) != 0) fails += "[${cat.shop}] ${item.name} stock ${plain(inv.item(item.name).stock)} after the bills, want ${plain(want)}"
            for (q in listOfNotNull("${item.name} stock evlo", item.mic?.let { "$it எவ்வளவு இருக்கு" })) {
                val t = runBlocking { kai.ask(q) }
                if (!t.reply.text.contains(plain(want)) || !t.reply.text.contains(item.name)) fails += "[${cat.shop} ask] \"$q\" → ${t.reply.text}  (want ${plain(want)})"
            }
        }
        assertTrue("${fails.size} wrong:\n\n" + fails.joinToString("\n\n"), fails.isEmpty())
    }

    @Test fun groceryKirana() = check(grocery)
    @Test fun hardware() = check(hardware)
    @Test fun electrical() = check(electrical)
    @Test fun textileGarments() = check(textile)
    @Test fun automobileSpares() = check(autoSpares)
    @Test fun medicalShop() = check(medical)
    @Test fun riceMandi() = check(riceMandi)
    @Test fun plywood() = check(plywood)
    @Test fun paintShop() = check(paint)
    @Test fun buildingMaterials() = check(building)
}
