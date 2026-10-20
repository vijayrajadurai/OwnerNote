package com.shopai.app.books.model

import java.util.Locale

/**
 * Product categories offered for each kind of business (owner, 20 Oct 2026: "Product Category — Required; category-wise list").
 * They are suggestions — a category the owner types that is not here is created for the shop, as before.
 */
object ProductCategories {
    private val byKind: Map<UnitMaster.Kind, List<String>> = mapOf(
        UnitMaster.Kind.HARDWARE to listOf("Cement", "Steel & TMT", "Pipes & Fittings", "Sanitary", "Electrical", "Wires & Cables", "Switches",
            "Lighting", "Paints", "Tools", "Fasteners", "Plywood & Boards", "Tiles", "Adhesives", "Other"),
        UnitMaster.Kind.GROCERY to listOf("Rice & Grains", "Pulses & Dal", "Flour & Atta", "Oil & Ghee", "Spices & Masala", "Sugar & Salt",
            "Snacks & Biscuits", "Beverages", "Dairy", "Personal Care", "Household & Cleaning", "Baby Care", "Packaged Food", "Other"),
        UnitMaster.Kind.TEXTILE to listOf("Sarees", "Shirts", "Pants", "Dress Materials", "Kids Wear", "Innerwear", "Dhotis & Lungis",
            "Fabric", "Threads & Accessories", "Bedsheets & Towels", "Other"),
        UnitMaster.Kind.PHARMACY to listOf("Tablets", "Capsules", "Syrups", "Injections", "Ointments & Creams", "Drops", "Surgical",
            "Ayurvedic", "Baby Care", "Health Supplements", "Devices", "Other"),
        UnitMaster.Kind.AGRICULTURE to listOf("Seeds", "Fertilizers", "Pesticides", "Insecticides", "Fungicides", "Plant Growth",
            "Tools & Sprayers", "Animal Feed", "Irrigation", "Other"),
        UnitMaster.Kind.AUTOMOBILE to listOf("Engine Parts", "Brake Parts", "Electrical & Lights", "Filters", "Oils & Lubricants",
            "Tyres & Tubes", "Body Parts", "Bearings", "Belts & Hoses", "Accessories", "Other"),
        UnitMaster.Kind.FRESH to listOf("Vegetables", "Leafy Greens", "Fruits", "Chicken", "Mutton", "Fish & Seafood", "Eggs", "Flowers", "Other"),
        UnitMaster.Kind.STATIONERY to listOf("Notebooks", "Pens & Pencils", "Paper", "Files & Folders", "Art Supplies", "Office Supplies",
            "School Supplies", "Other"),
        UnitMaster.Kind.PAINTS to listOf("Interior Paint", "Exterior Paint", "Enamel", "Primer", "Putty", "Brushes & Rollers", "Thinner", "Other"),
        UnitMaster.Kind.CHEMICALS to listOf("Industrial Chemicals", "Cleaning Chemicals", "Solvents", "Acids", "Lab Chemicals", "Other"),
        UnitMaster.Kind.COSMETICS to listOf("Skin Care", "Hair Care", "Makeup", "Fragrance", "Bath & Body", "Bangles & Fancy", "Other"),
        UnitMaster.Kind.FOOTWEAR to listOf("Men", "Women", "Kids", "Sports Shoes", "Sandals & Slippers", "School Shoes", "Accessories", "Other"),
        UnitMaster.Kind.FURNITURE to listOf("Sofas", "Beds", "Tables", "Chairs", "Wardrobes", "Office Furniture", "Mattresses", "Other"),
        UnitMaster.Kind.PET to listOf("Dog Food", "Cat Food", "Fish & Bird Food", "Pet Care", "Toys & Accessories", "Other"),
        UnitMaster.Kind.BEVERAGES to listOf("Soft Drinks", "Juices", "Water", "Energy Drinks", "Tea & Coffee", "Other"),
        UnitMaster.Kind.MOBILE to listOf("Mobiles", "Chargers & Cables", "Earphones", "Covers & Guards", "Batteries", "Memory Cards", "Other"),
        UnitMaster.Kind.RESTAURANT to listOf("Raw Materials", "Vegetables", "Dairy", "Beverages", "Packaging", "Ready Items", "Other"),
        UnitMaster.Kind.OTHER to listOf("General", "Other"),
    )

    fun suggestions(kind: UnitMaster.Kind): List<String> = byKind.getValue(kind)
}

/**
 * Product Code / SKU, generated when the owner taps Auto (owner, 20 Oct 2026: "Optional; auto-generate option"):
 * 3 letters of the category, 3 of the name, then the next free number — "RIC-PON-001" for Ponni Rice in Rice & Grains.
 * Never one already [taken] by another product of the shop.
 */
object ProductCode {
    private fun letters(text: String?, n: Int): String =
        text.orEmpty().uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }.take(n)

    fun generate(name: String, category: String?, taken: Collection<String>): String {
        val used = taken.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        // (a Tamil-only name has no Latin letters: "PRD")
        val head = listOf(letters(category, 3), letters(name.split(Regex("\\s+")).firstOrNull { letters(it, 1).isNotEmpty() }, 3))
            .filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "PRD" }
        var n = 1
        while (true) {
            val code = "$head-${n.toString().padStart(3, '0')}"
            if (code !in used) return code
            n++
        }
    }
}
