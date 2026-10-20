package com.shopai.app.data.model

import com.shopai.app.books.model.UnitMaster
import java.util.Locale

/**
 * The master list of product business categories an owner picks at setup (owner, 20 Oct 2026: 40 main categories,
 * subcategories kept with each, several may be picked, "Other Product Businesses" takes the owner's own name).
 *
 * A new category or subcategory is a line here — the backend keeps the codes as given (`Business.categories`). Each one
 * also names the older single [legacy] value (`Business.category`) that Kai's services, the shop scene and the backend's
 * own features read, and the [unitKind] whose units and product categories the Add Product form suggests.
 */
object BusinessCategories {
    data class Category(
        val code: String,
        val en: String,
        val ta: String,
        /** Shown on the category card. */
        val icon: String,
        /** The older single category this one is read as ([BusinessCategory.apiValue]). */
        val legacy: String,
        val unitKind: UnitMaster.Kind,
        /** What the shop sells under it — suggested as product categories, and searched. */
        val subcategories: List<String>,
        /** A service shop (hotel, salon): listed apart from the product businesses. */
        val service: Boolean = false,
    )

    const val OTHER = "OTHER_PRODUCTS"

    private fun c(code: String, en: String, ta: String, icon: String, legacy: String, kind: UnitMaster.Kind, subs: String, service: Boolean = false) =
        Category(code, en, ta, icon, legacy, kind, subs.split(", ").map(String::trim), service)

    val all: List<Category> = listOf(
        c("GROCERY_FMCG", "Grocery & FMCG", "மளிகை & FMCG", "🛒", "GROCERY", UnitMaster.Kind.GROCERY,
            "Rice & Grains, Spices, Snacks, Packaged Foods, Cooking Oil, Household Cleaning, Personal Care"),
        c("FRUITS_VEGETABLES", "Fruits & Vegetables", "பழங்கள் & காய்கறிகள்", "🥦", "GROCERY", UnitMaster.Kind.FRESH,
            "Fruits, Vegetables, Greens, Roots, Mushrooms, Fresh Produce"),
        c("PHARMACY_MEDICAL", "Pharmacy & Medical", "மருந்தகம் & மெடிக்கல்", "💊", "PHARMACY", UnitMaster.Kind.PHARMACY,
            "Medicines, Medical Devices, Surgical Supplies, First Aid, Healthcare Products"),
        c("FASHION_GARMENTS", "Fashion & Garments", "ஆடைகள் & ஃபேஷன்", "👕", "TEXTILE", UnitMaster.Kind.TEXTILE,
            "Men's Wear, Women's Wear, Kidswear, Fabrics, Uniforms, Innerwear, Tailoring Materials"),
        c("ELECTRONICS_MOBILES", "Electronics & Mobiles", "எலக்ட்ரானிக்ஸ் & மொபைல்", "📱", "MOBILE_ACCESSORIES", UnitMaster.Kind.MOBILE,
            "Mobiles, Computers, Accessories, TVs, Appliances, Cameras, Networking Devices"),
        c("HARDWARE_BUILDING", "Hardware & Building Materials", "ஹார்டுவேர் & கட்டுமானப் பொருட்கள்", "🔨", "HARDWARE", UnitMaster.Kind.HARDWARE,
            "Tools, Fasteners, Pipes, Cement, Steel, Paints, Plumbing, Electrical Fittings, Construction Supplies"),
        c("AUTOMOBILE_SPARES", "Automobile Spares", "ஆட்டோமொபைல் ஸ்பேர்ஸ்", "🚗", "AUTO_PARTS", UnitMaster.Kind.AUTOMOBILE,
            "Two-wheeler Parts, Car Parts, Commercial Vehicle Parts, Lubricants, Tyres, Batteries, Accessories"),
        c("HOME_LIFESTYLE", "Home & Lifestyle", "வீடு & லைஃப்ஸ்டைல்", "🛋️", "FURNITURE", UnitMaster.Kind.FURNITURE,
            "Furniture, Kitchenware, Home Décor, Lighting, Mattresses, Storage, Household Utilities"),
        c("AGRICULTURE_SUPPLIES", "Agriculture Supplies", "விவசாயப் பொருட்கள்", "🌾", "OTHER", UnitMaster.Kind.AGRICULTURE,
            "Seeds, Fertilizers, Crop Protection, Irrigation Items, Farming Tools, Animal Feed"),
        c("STATIONERY_OFFICE", "Stationery & Office Supplies", "ஸ்டேஷனரி & அலுவலகப் பொருட்கள்", "✏️", "STATIONERY", UnitMaster.Kind.STATIONERY,
            "Notebooks, Pens, Paper, Office Equipment, School Supplies, Printer Consumables"),
        c("BAKERY_CONFECTIONERY", "Bakery & Confectionery", "பேக்கரி & இனிப்பு", "🥐", "GROCERY", UnitMaster.Kind.GROCERY,
            "Bread & Buns, Cakes, Biscuits & Cookies, Sweets, Chocolates, Savouries"),
        c("MEAT_FISH_POULTRY", "Meat, Fish & Poultry", "இறைச்சி, மீன் & கோழி", "🍗", "GROCERY", UnitMaster.Kind.FRESH,
            "Chicken, Mutton, Fish, Seafood, Eggs, Frozen Meat"),
        c("DAIRY_FROZEN", "Dairy & Frozen Foods", "பால் பொருட்கள் & ஃப்ரோசன்", "🥛", "GROCERY", UnitMaster.Kind.GROCERY,
            "Milk, Curd, Butter & Ghee, Paneer & Cheese, Ice Cream, Frozen Foods"),
        c("BEVERAGES_WATER", "Beverages & Water", "பானங்கள் & தண்ணீர்", "🥤", "GROCERY", UnitMaster.Kind.BEVERAGES,
            "Soft Drinks, Juices, Packaged Water, Water Cans, Energy Drinks, Tea & Coffee"),
        c("BEAUTY_COSMETICS", "Beauty, Cosmetics & Personal Care", "அழகு சாதனம் & பர்சனல் கேர்", "💄", "OTHER", UnitMaster.Kind.COSMETICS,
            "Skin Care, Hair Care, Makeup, Fragrance, Bath & Body, Grooming"),
        c("FOOTWEAR", "Footwear", "காலணி", "👟", "FOOTWEAR", UnitMaster.Kind.FOOTWEAR,
            "Men's Footwear, Women's Footwear, Kids' Footwear, Sports Shoes, Sandals & Slippers, School Shoes"),
        c("BAGS_LUGGAGE", "Bags, Luggage & Accessories", "பைகள், லக்கேஜ் & ஆக்சஸரீஸ்", "👜", "OTHER", UnitMaster.Kind.OTHER,
            "Handbags, School Bags, Travel Bags, Suitcases, Wallets & Belts"),
        c("JEWELLERY_WATCHES", "Jewellery, Watches & Imitation Jewellery", "நகைகள், வாட்ச் & கவரிங்", "💍", "OTHER", UnitMaster.Kind.OTHER,
            "Gold, Silver, Imitation Jewellery, Watches, Bangles"),
        c("SPORTS_FITNESS", "Sports & Fitness Goods", "விளையாட்டு & ஃபிட்னஸ்", "⚽", "OTHER", UnitMaster.Kind.OTHER,
            "Cricket, Football, Gym Equipment, Sportswear, Indoor Games"),
        c("TOYS_BABY", "Toys & Baby Products", "பொம்மைகள் & குழந்தைப் பொருட்கள்", "🧸", "OTHER", UnitMaster.Kind.OTHER,
            "Toys, Baby Care, Baby Food, Diapers, Prams & Walkers"),
        c("BOOKS_EDUCATION", "Books & Educational Materials", "புத்தகங்கள் & கல்விப் பொருட்கள்", "📚", "STATIONERY", UnitMaster.Kind.STATIONERY,
            "School Books, Guides, Novels, Children's Books, Educational Kits"),
        c("GIFTS_CRAFTS_PARTY", "Gifts, Crafts & Party Supplies", "பரிசுப் பொருட்கள் & பார்ட்டி", "🎁", "OTHER", UnitMaster.Kind.OTHER,
            "Gift Items, Handicrafts, Party Decorations, Greeting Cards, Pooja Items"),
        c("ELECTRICAL_LIGHTING", "Electrical & Lighting Products", "எலக்ட்ரிகல் & லைட்டிங்", "💡", "ELECTRICAL", UnitMaster.Kind.HARDWARE,
            "Wires & Cables, Switches, Lights, Fans, MCBs, Motors"),
        c("SANITARYWARE_BATH", "Sanitaryware & Bathroom Fittings", "சானிட்டரி & பாத்ரூம் ஃபிட்டிங்ஸ்", "🚿", "HARDWARE", UnitMaster.Kind.HARDWARE,
            "Taps & Faucets, Closets, Wash Basins, Showers, Pipes & Fittings, Tanks"),
        c("PAINTS_CHEMICALS", "Paints, Chemicals & Adhesives", "பெயிண்ட், கெமிக்கல் & பசை", "🎨", "HARDWARE", UnitMaster.Kind.PAINTS,
            "Paints, Primers & Putty, Thinners, Adhesives, Industrial Chemicals, Cleaning Chemicals"),
        c("INDUSTRIAL_MACHINERY", "Industrial Machinery & Equipment", "தொழிற்சாலை இயந்திரங்கள்", "⚙️", "HARDWARE", UnitMaster.Kind.AUTOMOBILE,
            "Machines, Power Tools, Pumps, Motors, Spare Parts, Bearings"),
        c("PACKAGING_MATERIALS", "Packaging Materials", "பேக்கேஜிங் பொருட்கள்", "📦", "OTHER", UnitMaster.Kind.STATIONERY,
            "Boxes & Cartons, Carry Bags, Tapes, Pouches, Containers, Wrapping"),
        c("PLASTIC_HOUSEWARES", "Plastic Products & Housewares", "பிளாஸ்டிக் & வீட்டுப் பொருட்கள்", "🪣", "OTHER", UnitMaster.Kind.OTHER,
            "Buckets & Mugs, Containers, Chairs, Kitchen Plastics, Storage Boxes"),
        c("PAPER_PRINTING", "Paper & Printing Materials", "காகிதம் & அச்சுப் பொருட்கள்", "🖨️", "STATIONERY", UnitMaster.Kind.STATIONERY,
            "Paper Reams, Printing Ink, Visiting Cards, Labels, Flex & Vinyl"),
        c("TEXTILE_RAW_MATERIALS", "Textile Raw Materials & Accessories", "ஜவுளி மூலப்பொருட்கள்", "🧵", "TEXTILE", UnitMaster.Kind.TEXTILE,
            "Yarn & Thread, Fabric Rolls, Buttons & Zips, Laces, Dyes"),
        c("SAFETY_WORKWEAR", "Safety Equipment & Workwear", "பாதுகாப்பு உபகரணங்கள்", "🦺", "HARDWARE", UnitMaster.Kind.HARDWARE,
            "Helmets, Gloves, Safety Shoes, Reflective Jackets, Fire Safety"),
        c("SOLAR_RENEWABLE", "Solar & Renewable Energy Products", "சோலார் & மாற்று எரிசக்தி", "☀️", "ELECTRICAL", UnitMaster.Kind.HARDWARE,
            "Solar Panels, Inverters, Batteries, Solar Lights, Water Heaters"),
        c("PET_SUPPLIES", "Pet Supplies", "செல்லப்பிராணி பொருட்கள்", "🐾", "OTHER", UnitMaster.Kind.PET,
            "Dog Food, Cat Food, Fish & Bird Food, Pet Care, Toys & Accessories"),
        c("MUSICAL_AUDIO", "Musical Instruments & Audio Equipment", "இசைக்கருவிகள் & ஆடியோ", "🎸", "OTHER", UnitMaster.Kind.MOBILE,
            "Guitars, Keyboards, Traditional Instruments, Speakers, Microphones"),
        c("OPTICAL_EYEWEAR", "Optical Products & Eyewear", "கண்ணாடி & ஆப்டிகல்", "👓", "OTHER", UnitMaster.Kind.OTHER,
            "Spectacle Frames, Lenses, Sunglasses, Contact Lenses, Accessories"),
        c("MEDICAL_LAB_EQUIPMENT", "Medical & Laboratory Equipment", "மருத்துவ & ஆய்வக உபகரணங்கள்", "🔬", "PHARMACY", UnitMaster.Kind.PHARMACY,
            "Diagnostic Devices, Lab Instruments, Hospital Furniture, Consumables, Glassware"),
        c("SCRAP_RECYCLABLE", "Scrap & Recyclable Materials", "பழைய பொருட்கள் & மறுசுழற்சி", "♻️", "OTHER", UnitMaster.Kind.HARDWARE,
            "Metal Scrap, Paper Scrap, Plastic Scrap, E-waste, Old Batteries"),
        c("WHOLESALE_GENERAL", "Wholesale & General Merchandise", "மொத்த விற்பனை & பொது வணிகம்", "🏬", "OTHER", UnitMaster.Kind.GROCERY,
            "General Goods, Bulk Grocery, Household Items, FMCG Distribution"),
        c("KITCHEN_CATERING_EQUIPMENT", "Kitchen & Commercial Catering Equipment", "சமையலறை & கேட்டரிங் உபகரணங்கள்", "🍳", "OTHER", UnitMaster.Kind.FURNITURE,
            "Vessels, Stoves & Burners, Commercial Kitchen Equipment, Mixers & Grinders, Storage"),
        c(OTHER, "Other Product Businesses", "மற்ற பொருள் வணிகம்", "➕", "OTHER", UnitMaster.Kind.OTHER, "General, Other"),
        // Service shops the app already serves (Daily Sales / Cash Note by chat) — kept so a hotel or a salon can still pick itself.
        c("HOTEL_FOOD_SERVICE", "Hotel, Restaurant & Tea Shop", "ஹோட்டல், உணவகம் & டீ கடை", "🍽️", "RESTAURANT_FOOD", UnitMaster.Kind.RESTAURANT,
            "Meals, Tiffin, Tea & Coffee, Snacks, Juices, Parcels", service = true),
        c("SALON_BEAUTY_SERVICE", "Salon & Beauty Parlour", "சலூன் & பியூட்டி பார்லர்", "✂️", "BEAUTY_SALON", UnitMaster.Kind.COSMETICS,
            "Haircut, Shaving, Facial, Waxing, Bridal Makeup, Beauty Products", service = true),
    )

    private val byCode = all.associateBy { it.code }

    /** The 9 shown first as cards (the owner's first nine). */
    val popular: List<Category> = all.take(9)

    fun of(code: String?): Category? = code?.let { byCode[it.trim().uppercase(Locale.ROOT)] }

    /**
     * Categories matching what is typed — the English or Tamil name or one of their subcategories ("cement" finds Hardware);
     * a name match comes before a subcategory match. Nothing typed → nothing (the cards and "View all" show the list).
     */
    fun search(query: String): List<Category> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+")).filter { it.length >= 2 }.ifEmpty { listOf(q) }
        fun hits(text: String) = text.lowercase(Locale.ROOT).let { t -> t.contains(q) || words.all { t.contains(it) } }
        val byName = all.filter { hits(it.en) || hits(it.ta) || hits(it.code.replace('_', ' ')) }
        val bySub = all.filter { it !in byName && it.subcategories.any(::hits) }
        return byName + bySub
    }

    /**
     * The older single category for what the owner picked: a service shop's when one is picked (Kai's hotel / salon sales
     * read it), else the first pick's; OTHER when nothing known is picked.
     */
    fun legacyFor(codes: List<String>): String {
        val picked = codes.mapNotNull(::of)
        return (picked.firstOrNull { it.service } ?: picked.firstOrNull())?.legacy ?: BusinessCategory.OTHER.apiValue
    }

    /** A business set up before (one older category, no list): the category it stands for, picked again in the new list. */
    fun fromLegacy(category: String?): List<String> = when (category?.trim()?.uppercase(Locale.ROOT)) {
        "TEXTILE" -> listOf("FASHION_GARMENTS")
        "GROCERY" -> listOf("GROCERY_FMCG")
        "HARDWARE" -> listOf("HARDWARE_BUILDING")
        "ELECTRICAL" -> listOf("ELECTRICAL_LIGHTING")
        "MOBILE_ACCESSORIES" -> listOf("ELECTRONICS_MOBILES")
        "AUTO_PARTS" -> listOf("AUTOMOBILE_SPARES")
        "FURNITURE" -> listOf("HOME_LIFESTYLE")
        "FOOTWEAR" -> listOf("FOOTWEAR")
        "PHARMACY" -> listOf("PHARMACY_MEDICAL")
        "STATIONERY" -> listOf("STATIONERY_OFFICE")
        "RESTAURANT_FOOD" -> listOf("HOTEL_FOOD_SERVICE")
        "BEAUTY_SALON" -> listOf("SALON_BEAUTY_SERVICE")
        "OTHER" -> listOf(OTHER)
        else -> emptyList()
    }

    /** What the business picked: its list, or (set up before the list) its older category read into the list. */
    fun picked(business: Business?): List<String> =
        business?.categories?.filter { of(it) != null }?.takeIf { it.isNotEmpty() } ?: fromLegacy(business?.category)

    /** The units and product categories the Add Product form suggests: the first product category picked (else a service one's). */
    fun unitKindFor(codes: List<String>): UnitMaster.Kind? {
        val picked = codes.mapNotNull(::of)
        return (picked.firstOrNull { !it.service && it.code != OTHER } ?: picked.firstOrNull())?.unitKind
    }

    /** Product categories the picked business categories suggest, in order, once each. */
    fun subcategoriesFor(codes: List<String>): List<String> =
        codes.mapNotNull(::of).filter { it.code != OTHER }.flatMap { it.subcategories }.distinct()

    /** The picks are complete: at least one, and a name (2+ letters) when "Other Product Businesses" is one of them. */
    fun complete(codes: List<String>, otherName: String): Boolean =
        codes.any { of(it) != null } && (OTHER !in codes || otherName.trim().length >= 2)
}
