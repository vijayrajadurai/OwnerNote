package com.shopai.app.util

import com.shopai.app.brain.tools.ProductRef
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The owner's check (13 Oct 2026): 30 different purchase bills → a debit entry for each bill's total and their items into stock;
 * 30 different sales bills → a credit entry and their items out of stock. Different suppliers / customers, layouts (GST table,
 * plain, serial numbers, capitals, "Rs."), units (kg, g, box, bottle, dozen, litre), with and without tax, and items the shop
 * doesn't keep (never moved). At the end every product's stock must be exactly what the bills say.
 */
class BillStockSixtyBillsTest {

    private fun r(s: String) = BigDecimal(s)
    private fun money(a: BigDecimal) = a.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private val me = MyBusiness("Kumar Stores", null, "9876543210")

    private val products = listOf(
        ProductRef("p1", "Basmati Rice", "KG", r("500")),
        ProductRef("p2", "Sugar", "KG", r("400")),
        ProductRef("p3", "Colgate", "PCS", r("2000"), mapOf("BOX" to r("48"))),
        ProductRef("p4", "Sunflower Oil", "BOTTLE", r("600"), mapOf("BOX" to r("12"))),
        ProductRef("p5", "Marie Biscuit", "PCS", r("1000")),
        ProductRef("p6", "Toor Dal", "KG", r("300")),
        ProductRef("p7", "Lux Soap", "PCS", r("1000")),
        ProductRef("p8", "Tea Powder", "KG", r("100")),
        ProductRef("p9", "Milk", "LTR", r("200")),
        ProductRef("p10", "Coconut Oil", "BOTTLE", r("300")),
        ProductRef("p11", "Wheat Flour", "KG", r("400")),
        ProductRef("p12", "Maggi", "PCS", r("1500")),
    )

    /** How a product is written on a bill: its name there, the bill's unit, how many of the product's unit one bill unit is, a rate. */
    private data class Way(val pid: String, val name: String, val unit: String, val perUnit: BigDecimal, val rate: BigDecimal, val hsn: String)

    private val ways = listOf(
        Way("p1", "Basmati Rice", "kg", r("1"), r("85.00"), "1006"),
        Way("p2", "Sugar", "kg", r("1"), r("44.00"), "1701"),
        Way("p3", "Colgate 200g", "box", r("48"), r("1344.00"), "3306"),
        Way("p3", "Colgate", "pcs", r("1"), r("28.00"), "3306"),
        Way("p4", "Sunflower Oil 1L", "btl", r("1"), r("150.00"), "1512"),
        Way("p4", "Sunflower Oil", "box", r("12"), r("1800.00"), "1512"),
        Way("p5", "Marie Biscuit", "pcs", r("1"), r("10.00"), "1905"),
        Way("p6", "Toor Dal", "kg", r("1"), r("120.00"), "0713"),
        Way("p7", "Lux Soap", "doz", r("12"), r("360.00"), "3401"),
        Way("p8", "Tea Powder", "g", r("0.001"), r("0.50"), "0902"),
        Way("p9", "Milk", "ltr", r("1"), r("56.00"), "0401"),
        Way("p10", "Coconut Oil", "btl", r("1"), r("210.00"), "1513"),
        Way("p11", "Wheat Flour", "kg", r("1"), r("42.00"), "1101"),
        Way("p12", "Maggi", "pcs", r("1"), r("14.00"), "1902"),
    )

    private val suppliers = listOf("Sri Lakshmi Traders", "Murugan Agencies", "Balaji Distributors", "Annai Wholesale", "Ganesh Enterprises", "Vel Traders",
        "Kaveri Agencies", "Siva Sakthi Stores", "Ramco Distributors", "Selvam Wholesale", "Arul Traders", "Meenakshi Agencies", "Saravana Distributors",
        "Thangam Traders", "Bharath Agencies", "Kannan Wholesale", "Raja Enterprises", "Malar Traders", "Anbu Distributors", "Velan Agencies",
        "Sakthi Wholesale", "Ponni Traders", "Kumaran Agencies", "Vijaya Distributors", "Amman Traders", "Senthil Agencies", "Mani Wholesale",
        "Devi Distributors", "Karthik Traders", "Jaya Agencies")
    private val customers = listOf("Ravi", "Suresh", "Priya", "Arun", "Deepa", "Mohan", "Kavitha", "Ganesh", "Lakshmi", "Prakash", "Revathi", "Vijay",
        "Anitha", "Senthil", "Meena", "Karthik", "Divya", "Bala", "Sangeetha", "Murali", "Uma", "Rajesh", "Nithya", "Siva", "Geetha", "Hari", "Saranya",
        "Dinesh", "Malini", "Ashok")

    private data class Bill(val text: String, val credit: Boolean, val total: BigDecimal, val moves: Map<String, BigDecimal>)

    private fun bill(i: Int, sale: Boolean): Bill {
        val picks = (0 until 2 + i % 3).map { k -> ways[(i * 5 + k * 3 + if (sale) 7 else 0) % ways.size] }.distinctBy { it.pid }
        val rows = mutableListOf<String>()
        val moves = mutableMapOf<String, BigDecimal>()
        var sum = BigDecimal.ZERO
        picks.forEachIndexed { n, w ->
            val qty = if (w.unit == "g") BigDecimal(250 * (1 + (i + n) % 4)) else BigDecimal(1 + (i * 3 + n * 2) % 9)
            val amount = qty.multiply(w.rate).setScale(2, RoundingMode.HALF_UP)
            sum += amount
            moves.merge(w.pid, qty.multiply(w.perUnit).stripTrailingZeros()) { a, b -> a + b }
            rows += when (i % 5) {
                0 -> "${w.name} ${w.hsn} $qty ${w.unit} ${money(w.rate)} ${money(amount)}"
                1 -> "${w.name} $qty ${w.unit} ${money(w.rate)} ${money(amount)}"
                2 -> "${n + 1}. ${w.name} $qty ${w.unit} ${money(w.rate)} ${money(amount)}"
                3 -> "${w.name.uppercase()} $qty ${w.unit.uppercase()} ${money(w.rate)} ${money(amount)}"
                else -> "${w.name} $qty ${w.unit} Rs.${money(w.rate)} Rs.${money(amount)}"
            }
        }
        // Every fourth bill has an item the shop doesn't keep: entered in the total, never moved in stock.
        if (i % 4 == 1) {
            rows += "Carry Bag 2 nos 5.00 10.00"
            sum += r("10.00")
        }
        val taxed = i % 3 == 0
        val tax = if (taxed) sum.multiply(r("0.025")).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val total = sum + tax + tax
        val date = "%02d/09/2026".format(1 + i % 28)
        val head = if (sale) listOf("KUMAR STORES", "Ph: 9876543210", "TAX INVOICE", "Invoice No: S-${100 + i}", "Date: $date", "Bill To: ${customers[i]}")
        else listOfNotNull(suppliers[i].uppercase(), "TAX INVOICE", "Invoice No: P-${200 + i}", "Date: $date",
            // Half the purchase bills name my shop as the buyer; the rest don't mention me (a bill from another shop).
            "Bill To: Kumar Stores".takeIf { i % 2 == 0 })
        val foot = if (taxed) listOf("Taxable Value ${money(sum)}", "CGST @ 2.5% ${money(tax)}", "SGST @ 2.5% ${money(tax)}", "Grand Total ${money(total)}")
        else listOf("Grand Total ${money(total)}")
        val table = if (i % 5 == 0) "Description HSN Qty Rate Amount" else "Item Qty Rate Amount"
        return Bill((head + table + rows + foot).joinToString("\n"), sale, total, moves)
    }

    @Test fun thirtyPurchaseBillsInThirtySalesBillsOut() {
        val stock = products.associate { it.id to it.stock }.toMutableMap()
        val expected = stock.toMutableMap()
        val fails = mutableListOf<String>()
        val bills = (0 until 30).map { bill(it, sale = false) } + (0 until 30).map { bill(it, sale = true) }
        for ((n, b) in bills.withIndex()) {
            val shown = products.map { it.copy(stock = stock.getValue(it.id)) }
            val plan = BillStock.plan(b.text, shown, me)
            val what = if (b.credit) "sales bill ${n - 29}" else "purchase bill ${n + 1}"
            val got = plan.moves.groupBy({ it.first.id }, { it.second }).mapValues { (_, q) -> q.fold(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros() }
            val problems = buildList {
                if (plan.credit != b.credit) add("entry ${if (plan.credit) "credit" else "debit"}, want ${if (b.credit) "credit" else "debit"}")
                if (plan.total?.compareTo(b.total) != 0) add("total ${plan.total}, want ${b.total}")
                if (plan.stockIn == b.credit) add("stock ${if (plan.stockIn) "in" else "out"}")
                if (got.keys != b.moves.keys || got.any { (k, q) -> q.compareTo(b.moves.getValue(k)) != 0 }) add("moves $got, want ${b.moves}")
            }
            if (problems.isNotEmpty()) fails += "[$what] ${problems.joinToString("; ")}\n${plan.lines.joinToString("\n") { "   ${it.itemName} | ${it.billQty} ${it.billUnit} → ${it.product?.name} ${it.qty} ${it.problem ?: ""}" }}\n${b.text.prependIndent("      ")}"
            // Confirm: what the bill screen would move.
            for ((p, q) in plan.moves) stock[p.id] = stock.getValue(p.id) + if (plan.stockIn) q else q.negate()
            for ((pid, q) in b.moves) expected[pid] = expected.getValue(pid) + if (b.credit) q.negate() else q
        }
        val wrong = products.filter { stock.getValue(it.id).compareTo(expected.getValue(it.id)) != 0 }
            .map { "${it.name}: stock ${stock[it.id]}, want ${expected[it.id]}" }
        System.getProperty("bills.out")?.let { java.io.File(it).writeText("${fails.size} of 60 bills wrong\n\n" + fails.joinToString("\n\n") + "\n\n" + wrong.joinToString("\n")) }
        assertTrue("${fails.size} of 60 bills wrong:\n\n" + fails.joinToString("\n\n") + "\n\n" + wrong.joinToString("\n"), fails.isEmpty() && wrong.isEmpty())
    }
}
