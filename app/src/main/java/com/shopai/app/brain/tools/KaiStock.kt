package com.shopai.app.brain.tools

import java.math.BigDecimal
import java.util.Locale

/** A product of the business (for stock in / out). */
data class ProductRef(
    val id: String,
    val name: String,
    /** The unit stock is kept in (the product's primary / base unit). */
    val unit: String,
    val stock: BigDecimal,
    /** This product's own conversions: unit → base units in 1 (e.g. BOX → 12 for 1 box = 12 pieces). */
    val conversions: Map<String, BigDecimal> = emptyMap(),
    /** Per [unit], from the books; null = not set. Purchase and selling price are always kept apart. */
    val purchasePrice: BigDecimal? = null,
    val sellingPrice: BigDecimal? = null,
    /** The minimum (low-stock) level in [unit]; null = not set. */
    val minStock: BigDecimal? = null,
    val category: String? = null,
)

/**
 * "Colgate 20 stock in pannu" → what to change. [product] null = the name was
 * not found in the products (Kai offers to create it); [qty] null = no
 * quantity was said (Kai asks, or the camera / form fills it).
 */
data class StockRequest(
    val incoming: Boolean,
    val product: ProductRef?,
    val spokenName: String,
    val qty: BigDecimal?,
    val unit: String?,
    /** Every quantity said, in order ("1 box 3 pieces" → [1 BOX, 3 PCS]); the stock flow converts them. */
    val parts: List<QtyPart> = emptyList(),
    /** A size said as part of the name ("5 inch" in "5inch tape 10"): the product's size, never the quantity. */
    val size: String? = null,
)

/**
 * Global stock in / out understanding (Tamil, Tanglish, English) — part of
 * the Kai core, the same for every owner. A shop's own words ("pottudu")
 * reach it only after the owner's private memory turned them into these
 * standard words. Nothing is written here: the agent makes a draft and the
 * owner confirms before the inventory engine is called.
 */
object KaiStock {
    /** Stock coming in: "add stock", "new stock", "stock vandhiruku", "pudhu stock", "pudhusa vandhuruku", "inventory-ku podu". */
    private val inWords = listOf(
        "stock in", "stock add", "add stock", "add pannu", "add panniten", "add pannunga", "add panni", "add", "serthu", "sethu", "serthudu",
        "vandhirukku", "vanthirukku", "vandhiruku", "vanthiruku", "vandhurukku", "vandhuruku", "vanthuruku", "vandhuchu", "vanthuchu",
        "new stock", "pudhu stock", "puthu stock", "pudhusa", "puthusa", "pudhusaa", "fresh stock", "stock vandhachu", "stock vanthachu",
        "inventory ku podu", "inventory-ku podu", "inventoryku podu", "stock la podu", "stock-la podu", "stockla podu", "inventory la podu",
        "purchase panninen", "purchase pannen", "received", "in pannu", "inward", "restock", "restocked",
        "vandhuduchu", "vanthuduchu", "vandhachu", "vanthachu", "vandhurukku", "vandhudhu", "vanthudhu", "vanthuthu", "vandhadhu", "vanthathu",
        "ஸ்டாக் உள்ளே", "சேர்", "வந்திருக்கு", "புது ஸ்டாக்",
        "சேர்த்துடு", "சேர்த்திடு", "சேர்த்து", "சேர்த்தேன்", "சேருங்க", "வந்துருக்கு", "வந்தது", "arrived", "came in", "has come",
        // A customer brought goods back: they come into stock again.
        "customer return", "customer returned", "sales return", "sale return", "return vandhuchu", "return vanthuchu", "thirumbi vandhuchu",
        "thiruppi kuduthaanga", "thiruppi kuduthaan", "thiruppi kuduthutaanga",
    )
    /** Stock going out: "Colgate 2 out pannu", "2 Colgate pochu", "rendu sale aachu", "2 pieces sold", "eduthutanga". */
    private val outWords = listOf(
        "stock out", "stock remove", "remove pannu", "remove panniten", "sale panniten", "sale pannen", "sold", "out pannu", "out panniten",
        "out aachu", "out aagiduchu", "poiduchu", "poiduchchu", "pochu", "poachu", "pochi", "pochchu", "sale aachu", "sale achu", "sale aagiduchu",
        "vithuduchu", "vithachu", "vithuten", "vitthuten", "vithuchu", "vithuchchu", "vitthuchu", "vithiruchu", "vithutten", "vithen", "vitten", "vikkapattadhu", "eduthutanga", "eduthuttanga", "eduthaanga", "eduthanga", "eduthuttaanga", "outward",
        "sell panniten", "sell pannen", "sell aachu", "sell achu", "sell panni", "sold out", "out",
        "ஸ்டாக் வெளியே", "விற்றுவிட்டேன்", "போயிடுச்சு", "போச்சு", "விற்றேன்", "வித்தேன்", "வித்துட்டேன்", "விற்றோம்", "விற்பனை ஆச்சு",
        "வித்துச்சு", "வித்துடுச்சு", "விற்றுச்சு", "வித்தாச்சு", "விற்பனை", "vithuduchu",
        "sale", "sales", "sale ayiduchu", "sale aayiduchu", "sale aayidichu",
        // Lost from stock: damage, wastage, expiry, missing — and goods sent back to the supplier.
        "damage", "damaged", "damage aachu", "odanjiduchu", "udanjiduchu", "odanju pochu", "broken", "wastage", "waste", "waste aachu",
        "kettupochu", "kettu pochu", "spoil", "spoiled", "rotten", "expiry", "expired", "expire aachu", "missing", "kaanom", "kanom", "wasted",
        "supplier return", "purchase return", "supplier ku return", "supplier-ku return", "return anuppitten", "thiruppi anuppitten",
        "to supplier", "to the supplier", "back to supplier",
        "kurainjirukku", "kuranjirukku", "korainjirukku", "kammiyaa irukku",
        "சேதம்", "உடைஞ்சு", "வீணா", "கெட்டுப்போச்சு", "குறைஞ்சிருக்கு",
    )
    /** "2 pieces Colgate kuduthuten": stock out only when a known product is named (otherwise it is money). */
    private val gaveWords = listOf("kuduthen", "kuduthuten", "kuduthutten", "koduthen", "koduthuten", "kuduthachu", "kuduthaachu", "gave", "given")
    /** "New Colgate stock", "Colgate pudhu stock": new / fresh with a stock word is stock coming in. */
    private val newWords = Regex("""(?i)(?<![\p{L}])(new|pudhu|puthu|pudhusa|puthusa|fresh)(?![\p{L}])""")
    private val photoWords = Regex("""(?i)(?<![\p{L}])(photo|foto|camera|cam|pic|picture|scan|click)(?![\p{L}])|போட்டோ|கேமரா""")
    private val billWords = Regex("""(?i)(?<![\p{L}])(bill|bills|invoice|receipt)(?![\p{L}])|பில்""")

    private val units = mapOf(
        "pcs" to "PCS", "pc" to "PCS", "piece" to "PCS", "pieces" to "PCS", "peice" to "PCS", "peices" to "PCS", "nos" to "PCS",
        "kg" to "KG", "kgs" to "KG", "kilo" to "KG", "bag" to "BAG", "bags" to "BAG", "mootai" to "BAG", "moottai" to "BAG", "moota" to "BAG", "mootta" to "BAG",
        "sack" to "BAG", "sacks" to "BAG", "மூட்டை" to "BAG",
        "box" to "BOX", "boxes" to "BOX", "packet" to "PACK", "packets" to "PACK", "pack" to "PACK", "packs" to "PACK",
        "litre" to "LITRE", "litres" to "LITRE", "liter" to "LITRE", "ltr" to "LITRE", "dozen" to "DOZEN", "bottle" to "BOTTLE", "bottles" to "BOTTLE",
        "carton" to "CARTON", "cartons" to "CARTON", "case" to "CASE", "cases" to "CASE", "bundle" to "BUNDLE", "bundles" to "BUNDLE",
        "strip" to "STRIP", "strips" to "STRIP", "pair" to "PAIR", "pairs" to "PAIR", "jodi" to "PAIR", "tray" to "TRAY", "trays" to "TRAY", "ட்ரே" to "TRAY",
        // Tamil Nadu shop units: kattu = bundle, crate = case, dabba / tin = can; seepu / thaar (bananas), churul (wire),
        // muzham (flowers), bucket (paint) are kept as their own units. ("petti" stays a word each owner teaches Kai — private memory.)
        "kattu" to "BUNDLE", "கட்டு" to "BUNDLE", "crate" to "CASE", "crates" to "CASE",
        "dabba" to "CAN", "tin" to "CAN", "tins" to "CAN", "டப்பா" to "CAN", "seepu" to "SEEPU", "சீப்பு" to "SEEPU", "thaar" to "THAAR", "தார்" to "THAAR",
        "churul" to "COIL", "coil" to "COIL", "coils" to "COIL", "சுருள்" to "COIL", "muzham" to "MUZHAM", "mulam" to "MUZHAM", "முழம்" to "MUZHAM",
        "bucket" to "BUCKET", "buckets" to "BUCKET", "பக்கெட்" to "BUCKET",
        // The owner's 20 shop categories: tube (toothpaste) = piece; set, roll, ream, basket / koodai, tablet / maathirai.
        "tube" to "PCS", "tubes" to "PCS", "pkt" to "PACK", "pkts" to "PACK", "set" to "SET", "sets" to "SET", "roll" to "ROLL", "rolls" to "ROLL",
        "ream" to "REAM", "reams" to "REAM", "basket" to "BASKET", "baskets" to "BASKET", "koodai" to "BASKET", "kooda" to "BASKET", "கூடை" to "BASKET",
        "tablet" to "TABLET", "tablets" to "TABLET", "tab" to "TABLET", "tabs" to "TABLET", "capsule" to "TABLET", "capsules" to "TABLET",
        "maathirai" to "TABLET", "mathirai" to "TABLET", "மாத்திரை" to "TABLET", "கிலோ" to "KG", "பாக்கெட்" to "PACK", "meter" to "METER", "metre" to "METER", "ஜோடி" to "PAIR", "can" to "CAN", "cans" to "CAN",
        "gram" to "GRAM", "grams" to "GRAM", "gm" to "GRAM", "gms" to "GRAM", "g" to "GRAM", "ml" to "ML", "dozens" to "DOZEN",
    )

    private val reasons = listOf(
        "Customer return" to Regex("""(?i)customer\s+return|sales?\s+return|return\s+vand|return\s+vanth|thirumbi\s+vand|thiruppi\s+kuduth"""),
        "Supplier return" to Regex("""(?i)supplier\s*-?\s*(?:ku\s+)?return|purchase\s+return|return\s+anupp|thiruppi\s+anupp|returned\b.*\bto\s+(?:the\s+)?supplier"""),
        "Damage" to Regex("""(?i)damage|odanj|udanj|broken|சேதம்|உடைஞ்சு"""),
        "Expired" to Regex("""(?i)expir"""),
        "Wastage" to Regex("""(?i)wastage|waste|kettu\s*poch|spoil|rotten|வீணா|கெட்டுப்போச்சு"""),
        "Missing" to Regex("""(?i)missing|kaanom|kanom"""),
        "Free" to Regex("""(?i)^free\s·|(?<![\p{L}])(?:free|summa|ilavasam|sample)(?![\p{L}])"""),
    )

    /** Why the stock changed, when the owner said it ("Damage", "Wastage", "Customer return" …); null for a plain in / out. */
    fun reasonOf(text: String): String? = reasons.firstOrNull { it.second.containsMatchIn(text) }?.first

    /** The unit words Kai knows ("box", "packet", …) — a shop's own word for one is taught as a unit. */
    fun unitOf(word: String): String? = units[word.trim().lowercase(Locale.ROOT)]

    /** "2 mani pochu", "10 minutes la": a time, never a stock quantity. */
    private val timeAfterNumber = Regex("""(?i)(?:\d+|oru|rendu|moonu|naalu|anju|pathu)\s*(?:mani|manikku|maniku|nimisham|nimishathula|minutes?|mins?|hours?|neram|o'?clock)(?![\p{L}])""")
    private val numberWords = mapOf(
        "oru" to 1, "onnu" to 1, "one" to 1, "rendu" to 2, "irandu" to 2, "two" to 2, "moonu" to 3, "three" to 3, "naalu" to 4, "four" to 4,
        "anju" to 5, "aindhu" to 5, "five" to 5, "aaru" to 6, "six" to 6, "ezhu" to 7, "seven" to 7, "ettu" to 8, "eight" to 8,
        "onbadhu" to 9, "nine" to 9, "pathu" to 10, "padhu" to 10, "ten" to 10, "pannendu" to 12, "twelve" to 12,
        "irupathu" to 20, "twenty" to 20, "aimbadhu" to 50, "fifty" to 50, "nooru" to 100, "hundred" to 100,
    )
    private val fillers = setOf(
        "stock", "in", "out", "add", "remove", "pannu", "panniten", "pannunga", "pannen", "panninen", "panni", "purchase", "sale", "sold", "received",
        "serthu", "sethu", "serthudu", "inward", "outward", "owner", "kai", "please", "pls", "ah", "aa", "la", "ku", "kku", "na", "new", "pudhu",
        "puthu", "pudhusa", "puthusa", "pudhusaa", "fresh", "inventory", "inventoryku", "inventory-ku", "podu", "stockla", "stock-la", "aachu", "achu",
        "aagiduchu", "vandhachu", "vanthachu", "restock", "restocked", "the", "my", "of", "some", "konjam", "indha", "intha", "innaiku", "inniku", "today",
        "kadaiku", "kadaila", "shop", "to", "into", "got", "irukku", "iruku", "vandhu", "vanthu", "mattum", "ellam", "items", "photo", "edu", "edunga", "camera", "open",
        "sell", "kuduthen", "kuduthuten", "kuduthutten", "koduthen", "koduthuten", "gave", "given", "vandhuduchu", "vanthuduchu",
        "damage", "damaged", "wastage", "waste", "expiry", "expired", "missing", "customer", "supplier", "return", "returned", "sales",
        "kuraivu", "kuraichu", "kuraichi", "kuraichudu", "kuraichiru",
    )
    /** Words that make it money or a question, not a stock change ("cash 500 pochu", "sale evlo aachu?"). */
    private val notStock = Regex(
        """(?i)(?<![\p{L}])(cash|panam|kaasu|rupees?|rs|money|amount|balance|salary|rent|upi|gpay|bank|payment|evlo|evvalavu|enna|how|what|which|why|""" +
            """eppo|remind|reminder|bill|loss|profit|low|reorder)(?![\p{L}])|₹|\?""",
    )
    /** "EB bill 500 kattu", "rent 5000", "₹500" — money or a question, never a stock line. */
    fun moneyOrQuestion(text: String): Boolean = notStock.containsMatchIn(lowerOf(text))

    private val stockContext = Regex("""(?i)(?<![\p{L}])(stock|inventory|iruppu|product)(?![\p{L}])""")

    private fun lowerOf(text: String) = " " + text.lowercase(Locale.ROOT).replace(Regex("""[!,;.]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "

    private fun has(lower: String, list: List<String>) = list.any { w -> Regex("""(?<![\p{L}])${Regex.escape(w)}(?![\p{L}])""").containsMatchIn(lower) }

    /**
     * The longest real product name in the words (names may contain numbers: "Colgate 200g") — else the one product that
     * sounds the same as the words said ("bhaniyan" for Baniyan, "kolgate" for Colgate): names of five letters or more only,
     * and only when exactly one product sounds like that.
     */
    private fun productIn(lower: String, products: List<ProductRef>): ProductRef? {
        val named = products.filter { it.name.isNotBlank() }
        fun exact(words: String) = named.sortedByDescending { it.name.length }
            .firstOrNull { p -> Regex("""(?<![\p{L}\p{N}])${Regex.escape(p.name.lowercase(Locale.ROOT))}(?![\p{L}])""").containsMatchIn(words) }
        exact(lower)?.let { return it }
        // "Arisi 2 moota", "ennai 5 bottle": the Tamil name of a product the shop keeps in English (and back).
        val aliased = lower.split(' ').joinToString(" ") { w -> tamilNames[w] ?: w }
        if (aliased != lower) exact(aliased)?.let { return it }
        val words = lower.split(' ').filter { it.isNotEmpty() }
        // Only a name said right before its quantity ("bhaniyan 20", "Lux 12 vandhuchu") — "collect in total" or "colgate paste 10"
        // (a longer name Kai doesn't have) are never taken for a product by sound or by its first word.
        fun beforeQty(end: Int) = words.getOrNull(end)?.let { n -> n.first().isDigit() || n in numberWords } == true
        val sounds = named.filter { p ->
            val pw = p.name.lowercase(Locale.ROOT).split(Regex("""\s+"""))
            pw.size <= 3 && p.name.count(Char::isLetter) >= 5 && words.windowed(pw.size).withIndex().any { (i, w) ->
                beforeQty(i + pw.size) && w.none { it in units || it in fillers || it in numberWords || it.first().isDigit() } &&
                    w.zip(pw).all { (a, b) -> a == b || (a.length >= 4 && b.length >= 4 && soundsSame(a, b)) }
            }
        }
        sounds.singleOrNull()?.let { return it }
        // "Lux 12 vandhuchu" with only "Lux Soap" in the shop: the one product whose name starts with the word said
        // (three letters or more, not a unit / number / filler). Several such products: none — Kai asks as before.
        val first = named.filter { p ->
            val head = p.name.lowercase(Locale.ROOT).split(Regex("""\s+""")).first()
            head.length >= 3 && head.first().isLetter() && head !in units && head !in fillers &&
                words.withIndex().any { (i, w) -> w == head && beforeQty(i + 1) }
        }
        return first.singleOrNull()
    }

    /** The common Tamil names of grocery goods, for a shop that keeps them in English ("arisi" = Rice). */
    private val tamilNames = mapOf(
        "arisi" to "rice", "ennai" to "oil", "ennei" to "oil", "sakkarai" to "sugar", "sarkarai" to "sugar", "seeni" to "sugar",
        "uppu" to "salt", "paal" to "milk", "paruppu" to "dal", "maavu" to "atta", "godhumai" to "wheat", "muttai" to "egg",
        "vengayam" to "onion", "thakkali" to "tomato", "urulai" to "potato", "pori" to "puffed rice", "kadalai" to "groundnut", "sopu" to "soap",
        "theeppetti" to "matchbox", "agarbathi" to "agarbatti", "kalkandu" to "sugar candy", "vellam" to "jaggery", "puli" to "tamarind",
        "milagai" to "chilli", "milagu" to "pepper", "manjal" to "turmeric", "kadugu" to "mustard", "seeragam" to "jeera",
        "அரிசி" to "rice", "எண்ணெய்" to "oil", "எண்ணை" to "oil", "சர்க்கரை" to "sugar", "சீனி" to "sugar", "உப்பு" to "salt", "பால்" to "milk",
        "பருப்பு" to "dal", "மாவு" to "atta", "முட்டை" to "egg", "வெங்காயம்" to "onion", "தக்காளி" to "tomato", "சோப்பு" to "soap",
        "வெல்லம்" to "jaggery", "புளி" to "tamarind", "மிளகாய்" to "chilli", "மஞ்சள்" to "turmeric",
    )

    /** The product named in [text] — exactly, by its Tamil name, by sound, or by the one name it starts — or null. */
    fun productNamedIn(text: String, products: List<ProductRef>): ProductRef? = productIn(lowerOf(KaiSpokenWords.normalize(text)), products)

    /** Two spellings of one word ("bhaniyan" / "baniyan", "kolgate" / "colgate"): the same consonant skeleton, three or more of them. */
    private fun soundsSame(a: String, b: String): Boolean =
        com.shopai.app.util.NameSound.same(a, b) ||
            com.shopai.app.util.NameSound.key(a).let { k -> k.length >= 3 && k == com.shopai.app.util.NameSound.key(b) } && editDistance(a, b) <= 2

    private fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    /** The words left once stock words, numbers and units are taken out — the product's name as said. */
    private fun spokenName(rest: String): String {
        val words = rest.split(' ').filter { it.isNotEmpty() }
        return words.filterIndexed { i, w ->
            fun isNumber(x: String?) = x != null && (x.first().isDigit() || x in numberWords)
            // A unit word is the unit right after a number, or right before one with no unit after it ("arisi moota 50");
            // otherwise it is part of the name ("Bucket 10 piece", "Parle G 10 box"). A one-letter unit only after a number.
            val afterNumber = isNumber(words.getOrNull(i - 1))
            val beforeNumber = isNumber(words.getOrNull(i + 1)) && words.getOrNull(i + 2)?.let { it in units } != true
            val unit = w in units && (afterNumber || (w.length > 1 && (beforeNumber || words.none(::isNumber))))
            // "Return gift" keeps its "return" (a gift, not goods coming back).
            val returnGift = w == "return" && words.getOrNull(i + 1)?.startsWith("gift") == true
            // A word that starts with a letter keeps its digits ("A4 paper"); a number or "200g" is not part of the name.
            returnGift || (w !in fillers && !unit && w !in numberWords && !w.first().isDigit() &&
                (inWords + outWords).none { k -> k.split(' ').contains(w) })
        }.joinToString(" ").trim().trim('-')
    }

    private fun direction(lower: String, product: ProductRef?): Boolean? {
        val isIn = has(lower, inWords) || (newWords.containsMatchIn(lower) && stockContext.containsMatchIn(lower)) || gotGoods(lower)
        // "out of stock" is a question about stock, not stock going out.
        val outText = lower.replace(Regex("""(?<![\p{L}])out\s+of(?![\p{L}])"""), " ")
        // "kuduthen" is money unless a known product is named and nobody is given it ("Selvam-ku … kuduthen" is a payment).
        val toSomeone = Regex("""(?<![\p{L}])[\p{L}]+\s*-?\s*(?:ku|kku|kitta)(?![\p{L}])""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        val isOut = has(outText, outWords) || (product != null && !toSomeone && has(lower, gaveWords))
        return if (isIn == isOut) null else isIn
    }

    /** "Got 4 box of Dove soap": "got" is goods coming in only with a stock unit right after a number (else it may be money). */
    private fun gotGoods(lower: String): Boolean {
        if (!Regex("""(?<![\p{L}])got(?![\p{L}])""").containsMatchIn(lower)) return false
        return Regex("""(?<![\p{L}\d])(?:\d+(?:\.\d+)?)\s*([\p{L}]+)""").findAll(lower).any { units.containsKey(it.groupValues[1]) }
    }

    /**
     * SCAN_STOCK: "Colgate photo edu", "stock photo edu", "new stock add pannu" (no name) → the product
     * camera, with the product's name when one was said ("" when not). Null: not a stock photo request.
     */
    fun scanRequest(text: String, products: List<ProductRef>): String? {
        val lower = lowerOf(text)
        if (billWords.containsMatchIn(lower) || notStock.containsMatchIn(lower)) return null
        val product = productIn(lower, products)
        if (photoWords.containsMatchIn(lower)) {
            if (product != null) return product.name
            if (stockContext.containsMatchIn(lower) || newWords.containsMatchIn(lower)) return spokenName(lower).takeIf { it.split(' ').size <= 3 }.orEmpty().let(::displayName)
            return null
        }
        // A stock-in sentence with no product at all ("new stock add pannu", "pudhu stock vandhiruku").
        if (product == null && direction(lower, null) == true && stockContext.containsMatchIn(lower) && spokenName(lower).isEmpty() && quantityIn(lower) == null) return ""
        return null
    }

    /** "5inch", "10 inch", "2.5 mm", "4 sqmm", "9 watt": a size number with its size word. */
    private val specSize = Regex("""(?<![\p{L}\d.])(\d+(?:\.\d+)?)\s*(inch|inches|mm|cm|ft|feet|sqmm|sq\s*mm|watt|watts|volt|volts|amp|amps|hp|gauge)(?![\p{L}])""")

    /**
     * "5inch tape 10 add pannu", "10 inch pipe 90": the size is part of the product ("5 inch Tape") and the other number is
     * the quantity. Only when another number is said — "pipe 20 feet" alone keeps 20 feet as what came in.
     */
    private fun sizeInName(rest: String): Pair<String, String>? {
        val specs = specSize.findAll(rest).toList().takeIf { it.isNotEmpty() } ?: return null
        val left = specSize.replace(rest, " ")
        if (Regex("""(?<![\p{L}\d.])\d+(?:\.\d+)?(?![\d])""").find(left) == null && left.split(' ').none { it in numberWords }) return null
        val shown = specs.joinToString(" ") { m -> "${m.groupValues[1]} ${m.groupValues[2].replace(Regex("""\s+"""), "")}" }
        return shown to left.replace(Regex("""\s+"""), " ")
    }

    fun understand(text: String, products: List<ProductRef>): StockRequest? {
        // "5inch tape" is said and written "5 inch tape": the same product.
        val lower = lowerOf(text).replace(Regex("""(?<![\p{L}\d.])(\d+(?:\.\d+)?)(inch|inches|mm|cm|ft|feet|sqmm|watt|watts|volt|volts|amp|amps|hp)(?![\p{L}])"""), "$1 $2")
        if (notStock.containsMatchIn(lower) || timeAfterNumber.containsMatchIn(lower)) return null
        val product = productIn(lower, products)
        val isIn = direction(lower, product) ?: return null
        val named = product?.let { lower.replace(it.name.lowercase(Locale.ROOT), " ") } ?: lower
        val sized = if (product == null) sizeInName(named) else null
        val rest = sized?.second ?: named
        val words = rest.split(' ').filter { it.isNotEmpty() }
        val (qty, saidUnit) = quantityIn(rest) ?: (null to words.firstNotNullOfOrNull { units[it] })
        val spoken = product?.name ?: spokenName(rest).let { n -> if (sized != null && n.isNotEmpty()) "${sized.first} $n" else n }
        if (spoken.isEmpty()) return null
        if (product == null) {
            // A name Kai doesn't have: only when it clearly is stock ("Pepsodent stock vandhiruku", "2 Pepsodent box pochu").
            if (!stockContext.containsMatchIn(lower) && saidUnit == null && qty == null) return null
            if (spoken.split(' ').size > 4) return null
            // A big number with no unit is money, not pieces.
            if (qty != null && saidUnit == null && qty > BigDecimal(999) && !stockContext.containsMatchIn(lower)) return null
        }
        val shownName = if (product == null && sized != null) "${sized.first} ${displayName(spoken.removePrefix(sized.first).trim())}" else displayName(spoken)
        return StockRequest(incoming = isIn, product = product, spokenName = shownName, qty = qty, unit = saidUnit ?: product?.unit, parts = partsIn(rest), size = sized?.first)
    }

    /**
     * "Colgate 2 petti vandhiruku": a known product, a quantity, and right after
     * it a word Kai doesn't know as a unit or a stock word → that word (Kai asks
     * what it is — box? packet? — instead of guessing). Null when there is none.
     */
    fun unknownUnitWord(text: String, products: List<ProductRef>): String? {
        val lower = lowerOf(text)
        if (notStock.containsMatchIn(lower) || timeAfterNumber.containsMatchIn(lower)) return null
        val product = productIn(lower, products) ?: return null
        if (direction(lower, product) == null) return null
        val rest = lower.replace(product.name.lowercase(Locale.ROOT), " ")
        val m = Regex("""(?<![\p{L}\p{N}])(\d+(?:\.\d+)?|${numberWords.keys.joinToString("|")})\s+([\p{L}\p{M}]+)""").find(rest) ?: return null
        val word = m.groupValues[2]
        if (word in units || word in fillers || word in numberWords || word.length < 2) return null
        if ((inWords + outWords + gaveWords).any { k -> k.split(' ').contains(word) }) return null
        return word
    }

    /**
     * Every quantity in [text] with its unit: "1 box 3 pieces" → [1 BOX, 3 PCS], "2kg" → [2 KG],
     * "rendu box" → [2 BOX], "12" → [12 (product unit)]. Empty when there is no number.
     */
    /** Weights / volumes: the size of a pack ("25 kg moota"), not a pack. */
    private val sizeUnits = setOf("KG", "GRAM", "LITRE", "ML")

    fun partsIn(text: String): List<QtyPart> {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[!,;+]"""), " ").replace(Regex("""(\d)([a-z]+)"""), "$1 $2").replace(Regex("""\s+"""), " ").trim() + " "
        val words = lower.split(' ').filter { it.isNotEmpty() }
        val out = mutableListOf<QtyPart>()
        var i = 0
        var usedUnitAt = -1
        while (i < words.size) {
            val w = words[i]
            val n = w.toBigDecimalOrNull()?.takeIf { w.first().isDigit() } ?: numberWords[w]?.toBigDecimal()
            if (n != null && n.signum() > 0) {
                // "25 kg moota 2", "1 litre bottle 12": the pack's size, then the pack and how many — 2 bags, 12 bottles.
                val packAfterSize = words.getOrNull(i + 1)?.let { units[it] }?.takeIf { it in sizeUnits }
                    ?.let { words.getOrNull(i + 2)?.let { u -> units[u] }?.takeIf { it !in sizeUnits } }
                val packCount = words.getOrNull(i + 3)?.let { c -> c.toBigDecimalOrNull()?.takeIf { c.first().isDigit() } ?: numberWords[c]?.toBigDecimal() }
                if (packAfterSize != null && packCount != null && packCount.signum() > 0) {
                    out += QtyPart(packCount, packAfterSize)
                    usedUnitAt = i + 2
                    i += 4
                    continue
                }
                val after = words.getOrNull(i + 1)?.let { units[it] }
                // "arisi moota 50", "Colgate box 5": the unit said just before the number (when none follows it).
                val before = if (after == null && i - 1 > usedUnitAt) words.getOrNull(i - 1)?.let { units[it] } else null
                val unit = after ?: before
                out += QtyPart(n, unit)
                if (after != null) usedUnitAt = i + 1
                i += if (after != null) 2 else 1
            } else {
                i++
            }
        }
        return out
    }

    /** A number said in words or digits ("12", "rendu", "12 pieces"), with its unit if said. */
    fun quantityIn(text: String): Pair<BigDecimal, String?>? {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[!,;]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        val words = lower.split(' ').filter { it.isNotEmpty() }
        val digits = Regex("""(?<![\p{L}\d.])(\d+(?:\.\d+)?)(?![\d])""").find(lower)?.groupValues?.get(1)?.toBigDecimalOrNull()
        val qty = (digits ?: words.firstNotNullOfOrNull { numberWords[it] }?.toBigDecimal())?.takeIf { it.signum() > 0 } ?: return null
        return qty to words.firstNotNullOfOrNull { units[it] }
    }

    /** "box" / "boxes", "piece" / "pieces" — the unit for [qty] (one or many). */
    fun unitWord(unit: String?, qty: BigDecimal): String {
        val one = qty.compareTo(BigDecimal.ONE) == 0
        return when (KaiUnits.canon(unit)) {
            null, "PCS" -> if (one) "piece" else "pieces"
            "BOX" -> if (one) "box" else "boxes"
            "PACK" -> if (one) "packet" else "packets"
            "BAG" -> if (one) "bag" else "bags"
            "BOTTLE" -> if (one) "bottle" else "bottles"
            "CARTON" -> if (one) "carton" else "cartons"
            "CASE" -> if (one) "case" else "cases"
            "BUNDLE" -> if (one) "bundle" else "bundles"
            "STRIP" -> if (one) "strip" else "strips"
            "PAIR" -> if (one) "pair" else "pairs"
            "CAN" -> if (one) "can" else "cans"
            "TRAY" -> if (one) "tray" else "trays"
            "COIL" -> if (one) "coil" else "coils"
            "TABLET" -> if (one) "tablet" else "tablets"
            "SET" -> if (one) "set" else "sets"
            "ROLL" -> if (one) "roll" else "rolls"
            "REAM" -> if (one) "ream" else "reams"
            "BASKET" -> if (one) "basket" else "baskets"
            "BUCKET" -> if (one) "bucket" else "buckets"
            "SEEPU" -> "seepu"
            "THAAR" -> "thaar"
            "MUZHAM" -> "muzham"
            "METER" -> "meter"
            "LITRE" -> "litre"
            "KG" -> "kg"
            "GRAM" -> if (one) "gram" else "grams"
            "ML" -> "ml"
            else -> unitWord(unit)
        }
    }

    /** "pieces" / "kg" … as Kai says it to the owner. */
    fun unitWord(unit: String?): String = when (unit?.uppercase(Locale.ROOT)) {
        null, "", "PCS", "NOS" -> "pieces"
        "KG" -> "kg"
        "BAG" -> "bags"
        "BOX" -> "box"
        "PACK" -> "packets"
        "LITRE" -> "litre"
        "DOZEN" -> "dozen"
        "BOTTLE" -> "bottles"
        "CARTON" -> "cartons"
        "CASE" -> "cases"
        "BUNDLE" -> "bundles"
        "STRIP" -> "strips"
        "PAIR" -> "pairs"
        "CAN" -> "cans"
        "TRAY" -> "trays"
        "TABLET" -> "tablets"
        "SET" -> "sets"
        "ROLL" -> "rolls"
        "REAM" -> "reams"
        "BASKET" -> "baskets"
        "GRAM" -> "grams"
        "ML" -> "ml"
        else -> unit.lowercase(Locale.ROOT)
    }

    /** "12 pieces", "2 kg" — how Kai shows a quantity. */
    fun shown(q: BigDecimal, unit: String?): String {
        val n = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        return "$n ${unitWord(unit, q)}"
    }

    private fun displayName(s: String) = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}
