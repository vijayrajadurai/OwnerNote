package com.shopai.app.util

import java.util.Locale

/** What a product photo's printed label says — only a starting point: every field is shown to the owner to edit. */
data class ProductLabel(
    val name: String?,
    val brand: String?,
    val variant: String?,
    val weight: String?,
    val category: String?,
    /** A count printed on a pack ("Pack of 12", "12 N"), if any. */
    val packCount: Int? = null,
)

/**
 * Reads a product's label text (from the phone's own OCR) into a product
 * form: brand, name, variant, net weight, category. Plain rules, no AI and
 * no network; anything it can't read stays empty for the owner to type.
 */
object ProductLabelReader {

    /** Known brands → their usual category (a brand only fills fields; the owner can change them). */
    private val brands: List<Pair<String, String>> = listOf(
        "Colgate" to "Personal Care", "Pepsodent" to "Personal Care", "Close Up" to "Personal Care", "Closeup" to "Personal Care",
        "Sensodyne" to "Personal Care", "Dabur" to "Personal Care", "Patanjali" to "Personal Care", "Himalaya" to "Personal Care",
        "Meswak" to "Personal Care", "Vicco" to "Personal Care", "Lux" to "Personal Care", "Lifebuoy" to "Personal Care",
        "Dettol" to "Personal Care", "Santoor" to "Personal Care", "Dove" to "Personal Care", "Pears" to "Personal Care",
        "Hamam" to "Personal Care", "Medimix" to "Personal Care", "Cinthol" to "Personal Care", "Mysore Sandal" to "Personal Care",
        "Clinic Plus" to "Personal Care", "Sunsilk" to "Personal Care", "Pantene" to "Personal Care", "Head & Shoulders" to "Personal Care",
        "Parachute" to "Personal Care", "Vaseline" to "Personal Care", "Ponds" to "Personal Care", "Nivea" to "Personal Care",
        "Surf Excel" to "Home Care", "Ariel" to "Home Care", "Tide" to "Home Care", "Rin" to "Home Care", "Wheel" to "Home Care",
        "Ghadi" to "Home Care", "Vim" to "Home Care", "Pril" to "Home Care", "Exo" to "Home Care", "Harpic" to "Home Care",
        "Lizol" to "Home Care", "Colin" to "Home Care", "Good Knight" to "Home Care", "All Out" to "Home Care", "Hit" to "Home Care",
        "Maggi" to "Packaged Food", "Yippee" to "Packaged Food", "Kissan" to "Packaged Food", "Knorr" to "Packaged Food",
        "Britannia" to "Biscuits & Snacks", "Parle" to "Biscuits & Snacks", "Sunfeast" to "Biscuits & Snacks", "Good Day" to "Biscuits & Snacks",
        "Marie Gold" to "Biscuits & Snacks", "Hide & Seek" to "Biscuits & Snacks", "Bourbon" to "Biscuits & Snacks", "Oreo" to "Biscuits & Snacks",
        "Lays" to "Biscuits & Snacks", "Lay's" to "Biscuits & Snacks", "Kurkure" to "Biscuits & Snacks", "Bingo" to "Biscuits & Snacks",
        "Haldiram" to "Biscuits & Snacks", "Cadbury" to "Chocolates", "Dairy Milk" to "Chocolates", "KitKat" to "Chocolates",
        "Munch" to "Chocolates", "5 Star" to "Chocolates", "Amul" to "Dairy", "Aavin" to "Dairy", "Arokya" to "Dairy", "Hatsun" to "Dairy",
        "Milky Mist" to "Dairy", "Nandini" to "Dairy", "Bru" to "Beverages", "Nescafe" to "Beverages", "Tata Tea" to "Beverages",
        "Red Label" to "Beverages", "3 Roses" to "Beverages", "Taj Mahal" to "Beverages", "Horlicks" to "Beverages", "Boost" to "Beverages",
        "Bournvita" to "Beverages", "Complan" to "Beverages", "Coca-Cola" to "Beverages", "Coca Cola" to "Beverages", "Pepsi" to "Beverages",
        "Sprite" to "Beverages", "Fanta" to "Beverages", "Thums Up" to "Beverages", "Frooti" to "Beverages", "Maaza" to "Beverages",
        "Slice" to "Beverages", "Bisleri" to "Beverages", "Kinley" to "Beverages", "Aquafina" to "Beverages",
        "Aashirvaad" to "Groceries", "Pillsbury" to "Groceries", "Fortune" to "Groceries", "Saffola" to "Groceries", "Gold Winner" to "Groceries",
        "Idhayam" to "Groceries", "Tata Salt" to "Groceries", "Tata Sampann" to "Groceries", "Everest" to "Groceries", "MDH" to "Groceries",
        "Sakthi" to "Groceries", "Aachi" to "Groceries", "Eastern" to "Groceries", "Catch" to "Groceries", "Annapoorna" to "Groceries",
        "India Gate" to "Groceries", "Daawat" to "Groceries",
    ).sortedByDescending { it.first.length }

    private val categoryWords: List<Pair<Regex, String>> = listOf(
        // Home care first: "detergent powder" is not a talc.
        Regex("""(?i)detergent|washing|dish\s*wash|cleaner|floor|toilet|mosquito""") to "Home Care",
        Regex("""(?i)tooth\s*paste|toothpaste|tooth\s*brush|shampoo|soap|hair\s*oil|face\s*wash|body\s*wash|cream|lotion|talc|deo""") to "Personal Care",
        Regex("""(?i)biscuit|cookies|cracker|chips|namkeen|wafers|snack""") to "Biscuits & Snacks",
        Regex("""(?i)chocolate|choco""") to "Chocolates",
        Regex("""(?i)noodles|pasta|ketchup|sauce|jam|soup""") to "Packaged Food",
        Regex("""(?i)\bmilk\b|curd|ghee|butter|paneer|cheese""") to "Dairy",
        Regex("""(?i)\btea\b|coffee|juice|drink|water|soda""") to "Beverages",
        Regex("""(?i)\brice\b|atta|flour|\bdal\b|sugar|salt|\boil\b|masala|spice|rava|sooji|maida""") to "Groceries",
    )

    private val weightRe = Regex("""(?i)(?<![\d.])(\d{1,4}(?:[.,]\d{1,3})?)\s*(kg|kgs|g|gm|gms|grams?|ml|l|ltr|litres?|liters?)(?![\p{L}])""")
    private val packRe = Regex("""(?i)(?:pack\s*of|\bx)\s*(\d{1,3})(?!\d)|(?<!\d)(\d{1,3})\s*(?:n|nos|pcs|pieces|units)(?![\p{L}])""")
    private val noise = Regex(
        """(?i)\b(mrp|rs\.?|₹|price|net\s*w(?:eigh)?t|net\s*qty|net\s*quantity|net|wt|mfd|mfg|exp|expiry|best\s*before|batch|b\.?\s*no|lot|inclusive|taxes|""" +
            """use\s*by|manufactured|marketed|customer\s*care|www|http|fssai|lic|veg|ingredients|nutrition|barcode|made\s*in|india|ltd|limited|pvt)\b""",
    )

    fun read(ocrText: String): ProductLabel {
        val lines = ocrText.lines().map { it.replace(Regex("""[|_~^*#<>{}\[\]]"""), " ").replace(Regex("""\s+"""), " ").trim() }.filter { it.length >= 2 }
        val all = lines.joinToString("\n")
        val lowerAll = all.lowercase(Locale.ROOT)

        val brandHit = brands.firstOrNull { (b, _) ->
            val words = b.split(' ').joinToString("""\s*""") { Regex.escape(it) }
            Regex("""(?i)(?<![\p{L}])$words(?![\p{L}])""").containsMatchIn(all)
        }
        val brand = brandHit?.first

        val weight = weightRe.find(all)?.let { m ->
            val n = m.groupValues[1].replace(',', '.')
            val u = when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "kg", "kgs" -> "kg"
                "ml" -> "ml"
                "l", "ltr", "litre", "litres", "liter", "liters" -> "L"
                else -> "g"
            }
            "$n $u"
        }
        val pack = packRe.find(all)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }?.takeIf { it in 2..500 }

        val category = categoryWords.firstOrNull { it.first.containsMatchIn(lowerAll) }?.second ?: brandHit?.second

        // The variant: a short words-only line that isn't the brand, a weight, a price or small print.
        val candidates = lines.filter { l ->
            val letters = l.count(Char::isLetter)
            letters >= 3 && letters >= l.length / 2 && l.split(' ').size <= 5 && !noise.containsMatchIn(l) && !weightRe.containsMatchIn(l) &&
                (brand == null || !l.contains(brand, ignoreCase = true) || l.length > brand.length + 3)
        }
        val variant = candidates.map { l -> brand?.let { l.replace(Regex("""(?i)${Regex.escape(it)}"""), " ").trim() } ?: l }
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .firstOrNull { it.count(Char::isLetter) >= 3 }
            ?.let(::tidy)

        val name = when {
            brand != null -> brand
            variant != null -> variant
            else -> null
        }
        return ProductLabel(
            name = name,
            brand = brand,
            variant = if (brand == null && variant == name) null else variant,
            weight = weight,
            category = category,
            packCount = pack,
        )
    }

    /** "STRONG TEETH" → "Strong Teeth". */
    private fun tidy(s: String): String =
        s.split(' ').joinToString(" ") { w -> if (w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() }) w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } else w }
}
