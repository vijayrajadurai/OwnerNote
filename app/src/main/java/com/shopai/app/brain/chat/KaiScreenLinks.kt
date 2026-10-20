package com.shopai.app.brain.chat

import com.shopai.app.brain.tools.ProductKind
import com.shopai.app.data.model.BusinessCategories

/**
 * The app's screens Kai points to when the owner asks for them by chat or by voice (owner, 20 Oct 2026: "ipo add paniruka
 * features ellam Kai-ku theriyanum, voice chat-laiyum link pannidu"). Kai chat doesn't fill these forms itself — it says what
 * the screen does and gives a button that opens it. Lines arrive here as Kai's rules read them (the mic's Tamil already turned
 * into Tanglish by [com.shopai.app.brain.tools.KaiSpokenWords]).
 */
object KaiScreenLinks {
    enum class Link { PURCHASE_BILL, DRAFT_BILLS, NEW_PRODUCT, SHOP_CATEGORY }

    private val billWord = """(?:bill|bills|invoice|பில்)"""
    private val doIt = """(?:podu|potu|podunga|podanum|podunum|poda|pannu|pannunga|pannanum|entry|add|create|make|venum|ready|ezhudhu|ezhuthu)"""

    /**
     * "purchase bill podu", "kolmudhal bill", "supplier bill entry pannanum", "vaangina bill podu". ("ABC Traders supplier invoice
     * kudu" is that supplier's PDF and "purchase bill scan pannu" the scanner — neither is this.)
     */
    private val purchaseBill = Regex("""(?i)(?<![\p{L}])(?:(?:purchase|parchase|kolmudhal|kolmuthal)\s*$billWord(?:\s*$doIt)?|(?:vaangina|vaangunadhu|vaangunathu|supplier)\s*$billWord\s*$doIt)(?![\p{L}])""")
    // ("Purchase invoice ah iruntha, inventory la stock add pannidu": the bill's items into stock — the bill scanner, not this.)
    private val scan = Regex("""(?i)scan|photo|camera|padam|snap|stock|inventory|irunth|irundh|irukura|போட்டோ|ஸ்கேன்|ஸ்டாக்""")

    /** "draft bill", "save panna bill", "pending bills kaattu". */
    private val draftBills = Regex("""(?i)(?<![\p{L}])(?:draft|drafts|save\s*panna|save\s*pannuna|paadhi|pathi\s*ezhudhuna)\s*$billWord(?![\p{L}])""")

    /** The whole line asks for the product form: "pudhu product add pannanum", "new product form", "product master". */
    private val newProduct = Regex("""(?i)^\s*(?:(?:pudhu|puthu|new|oru)\s+)?product\s+(?:master|form|screen|add\s*(?:pannu|pannanum|panna|pannunga|podu)?\s*(?:screen|form)?|serkkanum|serkanum)\s*[.!?]?\s*$""")

    /** "en kadai enna vagai", "business category enna", "kadai category maathanum", "shop type change". */
    private val shopCategory = Regex("""(?i)(?<![\p{L}])(?:kadai|kada|kadaiyoda|shop|business|vyabaram|viyabaram|vyaabaaram|vyabara|viyabara)(?:\s*oda)?(?:\s+[\p{L}]+){0,2}?\s+(?:category|categories|vagai|type|vagaigal)(?![\p{L}])""")

    fun of(text: String): Link? = when {
        purchaseBill.containsMatchIn(text) && !scan.containsMatchIn(text) -> Link.PURCHASE_BILL
        draftBills.containsMatchIn(text) -> Link.DRAFT_BILLS
        newProduct.matches(text) -> Link.NEW_PRODUCT
        shopCategory.containsMatchIn(text) -> Link.SHOP_CATEGORY
        else -> null
    }

    /** The kind of goods each setup category sells — a new product in a shop with no stock yet is read as that kind. */
    private val kinds: Map<String, ProductKind> = mapOf(
        "GROCERY_FMCG" to ProductKind.GROCERY, "FRUITS_VEGETABLES" to ProductKind.VEGETABLE, "PHARMACY_MEDICAL" to ProductKind.PHARMACY,
        "FASHION_GARMENTS" to ProductKind.GARMENT, "ELECTRONICS_MOBILES" to ProductKind.ELECTRONICS, "HARDWARE_BUILDING" to ProductKind.HARDWARE,
        "AUTOMOBILE_SPARES" to ProductKind.AUTO, "HOME_LIFESTYLE" to ProductKind.KITCHENWARE, "AGRICULTURE_SUPPLIES" to ProductKind.AGRI,
        "STATIONERY_OFFICE" to ProductKind.STATIONERY, "BAKERY_CONFECTIONERY" to ProductKind.BAKERY, "MEAT_FISH_POULTRY" to ProductKind.MEAT,
        "DAIRY_FROZEN" to ProductKind.BEVERAGE, "BEVERAGES_WATER" to ProductKind.BEVERAGE, "BEAUTY_COSMETICS" to ProductKind.FMCG,
        "FOOTWEAR" to ProductKind.FOOTWEAR, "TOYS_BABY" to ProductKind.TOYS, "BOOKS_EDUCATION" to ProductKind.STATIONERY,
        "GIFTS_CRAFTS_PARTY" to ProductKind.TOYS, "ELECTRICAL_LIGHTING" to ProductKind.ELECTRICAL, "SANITARYWARE_BATH" to ProductKind.HARDWARE,
        "PAINTS_CHEMICALS" to ProductKind.HARDWARE, "INDUSTRIAL_MACHINERY" to ProductKind.HARDWARE, "PLASTIC_HOUSEWARES" to ProductKind.KITCHENWARE,
        "PAPER_PRINTING" to ProductKind.STATIONERY, "TEXTILE_RAW_MATERIALS" to ProductKind.GARMENT, "SAFETY_WORKWEAR" to ProductKind.HARDWARE,
        "SOLAR_RENEWABLE" to ProductKind.ELECTRICAL, "KITCHEN_CATERING_EQUIPMENT" to ProductKind.KITCHENWARE,
    )

    /** The first picked category that names a kind of goods; null when none does (or nothing was picked). */
    fun kindOf(codes: List<String>): ProductKind? = codes.firstNotNullOfOrNull { kinds[it] }

    /** The picked categories as the owner reads them ("Grocery & FMCG, Pooja items"). */
    fun names(codes: List<String>, otherName: String?, tamil: Boolean): List<String> = codes.mapNotNull { code ->
        if (code == BusinessCategories.OTHER) otherName?.takeIf { it.isNotBlank() } ?: BusinessCategories.of(code)?.let { if (tamil) it.ta else it.en }
        else BusinessCategories.of(code)?.let { if (tamil) it.ta else it.en }
    }
}
