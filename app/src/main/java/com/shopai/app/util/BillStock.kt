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
        /** The bill's rate for one [billUnit] (null: not printed) — a new product's purchase price. */
        val rate: BigDecimal? = null,
        /** The row's batch and expiry (medical / grocery bills) — the stock comes in as that batch. */
        val batchNo: String? = null,
        val expiry: java.time.LocalDate? = null,
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
        // The scan's slips for "Pcs"; a lorry of sand, a drum of paint (owner's bills, 17 Oct 2026).
        "pes" to "pcs", "pce" to "pcs", "lorry" to "lorry", "lorries" to "lorry", "drum" to "drum", "drums" to "drum",
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
    fun lines(items: List<BillLineItem>, products: List<ProductRef>): List<Line> {
        val kept = items.filter { it.description.any(Char::isLetter) && !addressLine(it.description) && !gstinPiece.containsMatchIn(it.description) }
            // "33AAJFS6789K1Z": a code the scan cut from the header — a name has a word of letters.
            .filter { it.description.split(' ').any { w -> w.count(Char::isLetter) >= 3 && w.count(Char::isLetter) > w.count(Char::isDigit) } }
        // "1 Cotton Saree", "2 Silk Saree", "3 Chudi Material": a row number with no mark after it — taken off only when the
        // rows are numbered (two rows or more start with their own row number), so "7 Up" / "3 Roses" keep their number.
        val numbered = kept.withIndex().count { (i, it) -> serialOf(it.description) == i + 1 } >= 2
        return kept.withIndex().map { (i, it) ->
            val d = if (numbered && serialOf(it.description).let { n -> n != null && n in (i + 1 - 2)..(i + 1 + 2) })
                it.description.replaceFirst(Regex("""^\s*\d{1,3}\s+"""), "") else it.description
            it.copy(description = cleanName(d), unit = it.unit?.let { u -> if (u == "pes" || u == "pce") "pcs" else u })
        }
            .filter { it.description.count(Char::isLetter) >= 2 }
            .map { line(it, products) }
    }

    private fun serialOf(text: String): Int? = Regex("""^\s*(\d{1,3})\s+\S""").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private val gstinPiece = Regex("""\b\d{2}[A-Z]{5}\d{4}[A-Z]""")

    fun line(item: BillLineItem, products: List<ProductRef>): Line {
        val product = match(item.description, products)
        val qty = item.quantity?.takeIf { it.signum() > 0 }
        val rate = item.unitPrice?.takeIf { it.signum() > 0 }
        val b = item.batchNo
        val e = item.expiry
        if (product == null) return Line(item.description, qty, item.unit, null, null, Problem.NO_PRODUCT, rate, b, e)
        if (qty == null) return Line(item.description, null, item.unit, product, null, Problem.NO_QUANTITY, rate, b, e)
        val unit = key(item.unit) ?: return Line(item.description, qty, null, product, qty, Problem.NO_UNIT, rate, b, e)
        val inUnit = inProductUnit(qty, unit, product)
        return Line(item.description, qty, item.unit, product, inUnit, if (inUnit == null) Problem.UNKNOWN_UNIT else null, rate, b, e)
    }

    /**
     * The shop's address, phone or reference read as an item (owner's bill, 16 Oct 2026: "123, Main Road, Gandhi Nagar,
     * Coimbatore - 641 012, Tamil Nadu | Supplier Ref ST" was an item): a PIN code, or a street / place / reference word.
     */
    fun addressLine(text: String): Boolean =
        Regex("""(?<!\d)\d{3}\s?\d{3}(?!\d)""").containsMatchIn(text) && Regex("""[\p{L}]""").containsMatchIn(text) && !Regex("""(?i)\b\d{3}\s?\d{3}\s*(?:g|gm|kg|ml|l|ltr|pcs)\b""").containsMatchIn(text) ||
            Regex("""(?i)\b(road|rd\.|street|st\.|nagar|salai|main\s+road|cross|layout|colony|district|dist\.|taluk|tamil\s*nadu|kerala|karnataka|pin\s*code|pincode|gstin|gst\s*no|phone|ph\s*[:.]|mobile|mob\s*[:.]|email|e-mail|www\.|supplier\s+ref|ref\s*no|invoice\s*no|bill\s*no|state\s*code)\b""").containsMatchIn(text)

    /**
     * An item's name as printed, without a row number or table marks the scan kept ("6 _| Groundnut Oil idhayam" → "Groundnut
     * Oil idhayam", "10 | Maida Pillsbury" → "Maida Pillsbury", "Tea Powder _ Tata Tea" → "Tea Powder Tata Tea").
     */
    fun cleanName(text: String): String = withoutComposition(withoutRepeat(withoutNoise(withoutNoise(text
        .replace(Regex("""^\s*(?:s\.?\s*no\.?\s*)?\d{1,3}\s*[_|.)\]:\-]+\s*"""), "")
        .replace(Regex("""^\s*[_|.)\]:\-]+\s*"""), ""))
        .replace(Regex("""\s*[_|]+\s*"""), " ")
        // A pack-size column ("10x10", "1 × 4") is not part of the name.
        .replace(Regex("""(?i)(?<![\p{L}\d])\d+\s*[x×]\s*\d+(?![\p{L}\d])"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', ':', '.', '|', '_', '*'))))

    /**
     * Specks the scan read before a name — a product photo column, a smudged row number ("th Plastic Toy Car", "6 [I Colgate
     * Toothpaste", "9 JEBl_ Maggi Noodles", "at is Sresta Sunflower Oil") — and after it ("Face Mask 50pcs ?"). A word that
     * starts in lower case and is three letters or fewer, or one mixing letters with marks, is not the start of a name.
     */
    private fun withoutNoise(name: String): String {
        val w = name.split(' ').filter { it.isNotEmpty() }.toMutableList()
        fun noise(t: String): Boolean {
            val core = t.trim('‘', '’', '\'', '"', '“', '”')
            if (core.isEmpty() || core.none(Char::isLetterOrDigit)) return true
            if (core.all(Char::isDigit) && core.length <= 3) return true
            if (core.first().isLowerCase() && core.length <= 3 && core.all { it.isLetter() || it == ')' }) return true
            return core.any { !it.isLetterOrDigit() && it !in "()&'.-+/%" } && core.first() != '('
        }
        // "B F] Daawat Basmati Rice": a speck a little way in takes the letters before it with it.
        val last = (0 until minOf(3, w.size - 2).coerceAtLeast(0)).lastOrNull { k ->
            w[k].length <= 3 && w[k].any { !it.isLetterOrDigit() } && noise(w[k]) && (0 until k).all { w[it].length <= 2 }
        }
        if (last != null && last > 0) repeat(last) { w.removeAt(0) }
        var dropped = 0
        while (w.size > 1 && dropped < 4 && noise(w.first())) { w.removeAt(0); dropped++ }
        while (w.size > 1 && w.last().none(Char::isLetterOrDigit)) w.removeAt(w.size - 1)
        return w.joinToString(" ") { it.trim('‘', '’', '“', '”') }
    }

    /**
     * A medicine / FMCG row prints the composition after the name: "Vitamin D3 Capsule Cholecalciferol 60,000 IU", "ORS Powder
     * ORS 21gm", "Surgical Mask (3 Ply) Disposable Face Mask 50pcs". The name ends at its form word (tablet, capsule, powder…)
     * when what follows carries a strength and a word of its own, or says the name again — a plain size ("Ponds Powder 100g")
     * stays, and a form word Kai doesn't know keeps the whole name.
     */
    private fun withoutComposition(name: String): String {
        val m = Regex("""(?i)^(.*?\p{L}.*?\b(?:tablets?|tab|capsules?|syrup|susp|suspension|gel|cream|ointment|powder|drops|injection|spray|lotion|mask|sanitizer)\b(?:\s*\([^)]*\))?)\s+(.+)$""")
            .find(name) ?: return name
        val head = m.groupValues[1]
        val rest = m.groupValues[2]
        val words = head.lowercase().split(' ').filter { it.count(Char::isLetter) >= 3 }.toSet()
        val strength = rest.any(Char::isDigit)
        val ownWord = rest.split(' ').any { it.count(Char::isLetter) >= 4 }
        val again = rest.lowercase().split(' ').any { it in words }
        return if (strength && (ownWord || again)) head else name
    }

    /**
     * The brand / composition column printed after the name, starting with the name's own first word again: "Aashirvaad Atta
     * (10 Kg) Aashirvaad" → "Aashirvaad Atta (10 Kg)", "Paracetamol Tablet Paracetamol 650 mg" → "Paracetamol Tablet". Only when
     * the name's start comes again right after its pack size "(…)", or — after a word of four letters or more — with a strength.
     */
    private fun withoutRepeat(name: String): String {
        val w = name.split(' ')
        val first = w.firstOrNull()?.takeIf { it.count(Char::isLetter) >= 3 } ?: return name
        for (i in 2 until w.size) {
            if (!w[i].equals(first, ignoreCase = true)) continue
            val rest = w.drop(i)
            val repeatsStart = rest.size <= w.size - rest.size && rest.indices.all { k -> rest[k].equals(w[k], ignoreCase = true) }
            // The brand said again after the pack size ("Bru Coffee Powder (100 g) Bru") — "Tea Powder Tata Tea" is a name.
            if (repeatsStart && w[i - 1].endsWith(")") || first.count(Char::isLetter) >= 4 && rest.any { r -> r.any(Char::isDigit) })
                return w.take(i).joinToString(" ")
        }
        return name
    }

    /** A bill unit as the shop's products name it, for a new product ("kgs" → KG, "pkt" → PACKET); PCS when none was printed. */
    fun productUnit(billUnit: String?): String = when (key(billUnit)) {
        null, "pcs" -> "PCS"
        "kg" -> "KG"; "g" -> "GRAM"; "ltr" -> "LITRE"; "ml" -> "ML"; "packet" -> "PACKET"; "box" -> "BOX"; "bag" -> "BAG"
        "bottle" -> "BOTTLE"; "dozen" -> "DOZEN"; "mtr" -> "METER"; "set" -> "SET"; "can" -> "CAN"; "roll" -> "ROLL"; "pair" -> "PAIR"
        else -> key(billUnit)!!.uppercase(Locale.ROOT)
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
