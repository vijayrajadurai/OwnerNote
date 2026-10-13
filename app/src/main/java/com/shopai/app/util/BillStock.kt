package com.shopai.app.util

import com.shopai.app.brain.tools.KaiStock
import com.shopai.app.brain.tools.ProductRef
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * The stock a scanned bill moves (owner, 13 Oct 2026: "purchase bill / sales bill photo → stock increase / less").
 *
 * Each item line read from the bill ([BillLineItem]) is matched to one of the shop's products by name (the same matcher Kai
 * uses for spoken / typed product names), and its quantity is turned into the product's own unit (1 BOX = 12 PCS from the
 * product's conversion, 500 G = 0.5 KG). Nothing is guessed: a line with no product, no quantity, or a unit that can't be
 * turned into the product's unit is shown for the owner to fill in and is not ticked. Nothing moves before the bill is saved.
 */
object BillStock {

    /** Which way a bill's items move the stock: in (a purchase bill) or out (a sales bill). */
    enum class Way { IN, OUT }

    /**
     * What the owner asked when Kai opened the scanner ("intha bill la irukura stock ah add pannu" → IN, "… kammi pannu" →
     * OUT; "bill scan pannu" → null: by the bill — purchase in, sale out). Read once by the scan that follows, within 15 minutes.
     */
    object Request {
        @Volatile private var asked: Pair<Way?, Long>? = null
        fun ask(way: Way?, now: Long = System.currentTimeMillis()) { asked = way to now }
        fun take(now: Long = System.currentTimeMillis()): Way? = asked?.takeIf { now - it.second in 0..15 * 60_000L }?.first.also { asked = null }
    }

    enum class Problem {
        /** No product of the shop has this name — added to stock only once the owner picks / creates it in Inventory. */
        NO_PRODUCT,
        /** The bill row has no quantity Kai could read (qty × rate didn't match the amount). */
        NO_QUANTITY,
        /** The bill row has no unit: the quantity is shown in the product's unit for the owner to check. */
        NO_UNIT,
        /** A unit the product doesn't have ("BOX" for a product with no box size). */
        UNKNOWN_UNIT,
    }

    data class Line(
        /** The item as printed on the bill. */
        val itemName: String,
        val billQty: BigDecimal?,
        val billUnit: String?,
        val product: ProductRef?,
        /** The quantity in [product]'s own unit; null when it can't be worked out. */
        val qty: BigDecimal?,
        val problem: Problem?,
    ) {
        /** Ticked by itself only when product and quantity are both sure. */
        val sure: Boolean get() = product != null && qty != null && qty.signum() > 0 && problem == null
    }

    /** Bill units, written many ways, as one key. */
    private val unitKeys = mapOf(
        "nos" to "pcs", "no" to "pcs", "pcs" to "pcs", "pc" to "pcs", "piece" to "pcs", "pieces" to "pcs", "unit" to "pcs", "units" to "pcs",
        "each" to "pcs", "ea" to "pcs", "set" to "set",
        "kg" to "kg", "kgs" to "kg", "kilo" to "kg", "g" to "g", "gm" to "g", "gms" to "g", "gram" to "g", "grams" to "g",
        "l" to "ltr", "ltr" to "ltr", "litre" to "ltr", "liter" to "ltr", "ml" to "ml",
        "btl" to "bottle", "bottle" to "bottle", "bottles" to "bottle", "pkt" to "packet", "packet" to "packet", "packets" to "packet",
        "box" to "box", "boxes" to "box", "bag" to "bag", "bags" to "bag", "dozen" to "dozen", "doz" to "dozen", "mtr" to "mtr", "m" to "mtr",
        // As the shop's products name their units (LITRE, METER, PACK, GRAM …) and the hardware / plywood / tiles / spare-parts bills.
        "litres" to "ltr", "ltrs" to "ltr", "meter" to "mtr", "metre" to "mtr", "meters" to "mtr", "mtrs" to "mtr", "pack" to "packet", "packs" to "packet",
        "pkts" to "packet", "dozens" to "dozen", "sets" to "set", "tube" to "pcs", "tubes" to "pcs", "sheet" to "sheet", "sheets" to "sheet",
        "sqft" to "sqft", "sft" to "sqft", "can" to "can", "cans" to "can", "tin" to "can", "tins" to "can", "roll" to "roll", "rolls" to "roll",
        "pair" to "pair", "pairs" to "pair", "bundle" to "bundle", "bundles" to "bundle", "coil" to "coil", "coils" to "coil",
        "bucket" to "bucket", "buckets" to "bucket", "carton" to "carton", "cartons" to "carton",
        // Rice mandi, building materials, medical, spare parts (owner's 10-category guide, 13 Oct 2026).
        "quintal" to "quintal", "quintals" to "quintal", "qtl" to "quintal", "tonne" to "tonne", "tonnes" to "tonne", "ton" to "tonne",
        "tons" to "tonne", "cft" to "cft", "strip" to "strip", "strips" to "strip", "vial" to "vial", "vials" to "vial", "tablet" to "tablet",
        "tablets" to "tablet", "tab" to "tablet", "tabs" to "tablet", "capsule" to "tablet", "capsules" to "tablet", "sachet" to "packet",
        "sachets" to "packet", "kit" to "kit", "kits" to "kit", "load" to "load", "loads" to "load", "length" to "length",
        "lengths" to "length", "board" to "board", "boards" to "board", "ft" to "feet", "feet" to "feet",
    )

    private fun key(unit: String?): String? = unit?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { unitKeys[it] ?: it }

    /** What saving a scanned bill does, as the bill screen decides it before the owner changes anything. */
    data class Plan(
        /** true: a credit entry (my sales bill — the customer owes me); false: a debit entry (a purchase bill — I owe the shop). */
        val credit: Boolean,
        /** The bill total written as "Total / Grand total" (null: not read — the owner types it). */
        val total: BigDecimal?,
        val party: String?,
        /** The items go into stock (true) or out of it (false). */
        val stockIn: Boolean,
        /** The lines ticked by themselves: each product and its quantity in the product's unit. */
        val moves: List<Pair<ProductRef, BigDecimal>>,
        val lines: List<Line>,
    )

    /**
     * The scanned bill [text] → the credit / debit entry and the stock it moves: the same parser, direction and matching the
     * bill screen uses ([BillTextParser], [BillDirection], [lines]); [asked] is what the owner told Kai ("stock ah add pannu").
     */
    fun plan(text: String, products: List<ProductRef>, me: MyBusiness, asked: Way? = null): Plan {
        val bill = BillTextParser.parse(text)
        val credit = BillDirection.decide(text, bill, me).direction == TxnDirection.CREDIT
        val lines = lines(bill.items, products)
        return Plan(
            credit = credit,
            total = bill.total?.takeIf { bill.totalFromLabel },
            party = if (credit) bill.customerName else bill.merchantName,
            stockIn = asked?.let { it == Way.IN } ?: !credit,
            moves = lines.filter { it.sure }.map { it.product!! to it.qty!! },
            lines = lines,
        )
    }

    /** Every bill line, matched to a product where one has its name. */
    fun lines(items: List<BillLineItem>, products: List<ProductRef>): List<Line> =
        items.filter { it.description.any(Char::isLetter) }.map { line(it, products) }

    fun line(item: BillLineItem, products: List<ProductRef>): Line {
        val product = match(item.description, products)
        val qty = item.quantity?.takeIf { it.signum() > 0 }
        if (product == null) return Line(item.description, qty, item.unit, null, null, Problem.NO_PRODUCT)
        if (qty == null) return Line(item.description, null, item.unit, product, null, Problem.NO_QUANTITY)
        val unit = key(item.unit) ?: return Line(item.description, qty, null, product, qty, Problem.NO_UNIT)
        val inUnit = inProductUnit(qty, unit, product)
        return Line(item.description, qty, item.unit, product, inUnit, if (inUnit == null) Problem.UNKNOWN_UNIT else null)
    }

    /** The shop's product this bill line names, or null. */
    fun match(description: String, products: List<ProductRef>): ProductRef? {
        // Pack sizes and codes ("200G", "1L", "x12") are not part of the name.
        val name = description.replace(Regex("""(?i)\b\d+(?:\.\d+)?\s*(?:g|gm|gms|kg|ml|l|ltr|pcs|x)\b|\bx\s*\d+\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if (name.none(Char::isLetter)) return null
        return KaiStock.productNamedIn(name, products)
    }

    /** [qty] [unit] in [product]'s own unit, from its conversion or the standard weights and measures; null when it can't be. */
    fun inProductUnit(qty: BigDecimal, unit: String, product: ProductRef): BigDecimal? {
        val base = key(product.unit)
        val u = key(unit) ?: return null
        if (u == base) return qty
        // The product's own second unit ("1 BOX = 12 PCS").
        product.conversions.entries.firstOrNull { key(it.key) == u }?.let { return qty.multiply(it.value) }
        return when {
            u == "g" && base == "kg" -> qty.divide(BigDecimal(1000), 3, RoundingMode.HALF_UP).stripTrailingZeros()
            u == "kg" && base == "g" -> qty.multiply(BigDecimal(1000))
            u == "ml" && base == "ltr" -> qty.divide(BigDecimal(1000), 3, RoundingMode.HALF_UP).stripTrailingZeros()
            u == "ltr" && base == "ml" -> qty.multiply(BigDecimal(1000))
            u == "dozen" && base == "pcs" -> qty.multiply(BigDecimal(12))
            (u == "sheet" || u == "board") && base == "pcs" -> qty
            // A quintal is 100 kg and a tonne 1,000 kg everywhere; a load, a length, a bag is not (the product's own conversion, else asked).
            u == "quintal" && base == "kg" -> qty.multiply(BigDecimal(100))
            u == "tonne" && base == "kg" -> qty.multiply(BigDecimal(1000))
            u == "tonne" && base == "quintal" -> qty.multiply(BigDecimal(10))
            else -> null
        }
    }
}
