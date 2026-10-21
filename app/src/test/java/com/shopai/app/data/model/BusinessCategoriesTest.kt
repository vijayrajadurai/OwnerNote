package com.shopai.app.data.model

import com.shopai.app.books.model.UnitMaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's setup categories (20 Oct 2026): 40 product business categories (9 popular as cards), search, several
 * picks, "Other Product Businesses" with its own name, subcategories kept with each, and the older single category
 * the rest of the app (Kai's services, the shop scene, the server) still reads.
 */
class BusinessCategoriesTest {
    private val owner40 = listOf(
        "Grocery & FMCG", "Fruits & Vegetables", "Pharmacy & Medical", "Fashion & Garments", "Electronics & Mobiles",
        "Hardware & Building Materials", "Automobile Spares", "Home & Lifestyle", "Agriculture Supplies", "Stationery & Office Supplies",
        "Bakery & Confectionery", "Meat, Fish & Poultry", "Dairy & Frozen Foods", "Beverages & Water", "Beauty, Cosmetics & Personal Care",
        "Footwear", "Bags, Luggage & Accessories", "Jewellery, Watches & Imitation Jewellery", "Sports & Fitness Goods", "Toys & Baby Products",
        "Books & Educational Materials", "Gifts, Crafts & Party Supplies", "Electrical & Lighting Products", "Sanitaryware & Bathroom Fittings",
        "Paints, Chemicals & Adhesives", "Industrial Machinery & Equipment", "Packaging Materials", "Plastic Products & Housewares",
        "Paper & Printing Materials", "Textile Raw Materials & Accessories", "Safety Equipment & Workwear", "Solar & Renewable Energy Products",
        "Pet Supplies", "Musical Instruments & Audio Equipment", "Optical Products & Eyewear", "Medical & Laboratory Equipment",
        "Scrap & Recyclable Materials", "Wholesale & General Merchandise", "Kitchen & Commercial Catering Equipment", "Other Product Businesses",
    )

    @Test fun theOwnersFortyProductCategoriesInOrderEachWithTamilAndSubcategories() {
        val products = BusinessCategories.all.filter { !it.service }
        assertEquals(owner40, products.map { it.en })
        assertEquals(BusinessCategories.all.size, BusinessCategories.all.map { it.code }.toSet().size)
        BusinessCategories.all.forEach { c ->
            assertTrue(c.code, Regex("^[A-Z][A-Z0-9_]{1,59}$").matches(c.code))   // what the server accepts
            assertTrue(c.code, c.ta.isNotBlank() && c.icon.isNotBlank() && c.subcategories.isNotEmpty())
            assertTrue(c.code, BusinessCategory.entries.any { it.apiValue == c.legacy })
        }
        // The cards: the owner's first nine.
        assertEquals(owner40.take(9), BusinessCategories.popular.map { it.en })
        // A hotel and a salon can still pick themselves (the app's Daily Sales by chat serves them); the other service shops
        // (owner, 21 Oct 2026: business types) are OTHER to the server, ending with "Other Services".
        val services = BusinessCategories.all.filter { it.service }
        assertEquals(listOf("RESTAURANT_FOOD", "BEAUTY_SALON"), services.take(2).map { it.legacy })
        assertTrue(services.drop(2).all { it.legacy == "OTHER" })
        assertEquals(BusinessCategories.OTHER_SERVICES, services.last().code)
    }

    @Test fun searchFindsByNameTamilOrWhatTheShopSells() {
        assertEquals("BAKERY_CONFECTIONERY", BusinessCategories.search("bakery").first().code)
        assertEquals("HARDWARE_BUILDING", BusinessCategories.search("cement").first().code)
        assertEquals("AGRICULTURE_SUPPLIES", BusinessCategories.search("seeds").first().code)
        assertEquals("PHARMACY_MEDICAL", BusinessCategories.search("மருந்தகம்").first().code)
        assertEquals("OPTICAL_EYEWEAR", BusinessCategories.search("Sunglasses").first().code)
        // A name match comes before a shop that only sells it ("mobile": Electronics & Mobiles before Musical / Audio).
        assertEquals("ELECTRONICS_MOBILES", BusinessCategories.search("mobile").first().code)
        // A word in no category: nothing (the owner is pointed to "Other Product Businesses").
        assertTrue(BusinessCategories.search("zzqx").isEmpty())
        assertTrue(BusinessCategories.search("   ").isEmpty())
    }

    @Test fun severalPicksAndTheOlderCategoryFollowsTheFirstOrTheServiceShop() {
        assertEquals("GROCERY", BusinessCategories.legacyFor(listOf("BAKERY_CONFECTIONERY", "FOOTWEAR")))
        assertEquals("FOOTWEAR", BusinessCategories.legacyFor(listOf("FOOTWEAR", "BAKERY_CONFECTIONERY")))
        // A tea shop that also sells snacks packets: Kai's hotel sales need RESTAURANT_FOOD.
        assertEquals("RESTAURANT_FOOD", BusinessCategories.legacyFor(listOf("GROCERY_FMCG", "HOTEL_FOOD_SERVICE")))
        // A mobile shop with repairs keeps its own (repairs are OTHER to the server); a tailor alone is OTHER.
        assertEquals("MOBILE_ACCESSORIES", BusinessCategories.legacyFor(listOf("MOBILE_REPAIR", "ELECTRONICS_MOBILES")))
        assertEquals("OTHER", BusinessCategories.legacyFor(listOf("TAILORING_BOUTIQUE")))
        // Nothing known picked: OTHER, never another shop's category.
        assertEquals("OTHER", BusinessCategories.legacyFor(listOf("NOT_A_CODE")))
        assertEquals("OTHER", BusinessCategories.legacyFor(emptyList()))
    }

    @Test fun otherNeedsItsOwnName() {
        assertFalse(BusinessCategories.complete(emptyList(), ""))
        assertTrue(BusinessCategories.complete(listOf("FOOTWEAR"), ""))
        assertFalse(BusinessCategories.complete(listOf("FOOTWEAR", BusinessCategories.OTHER), " "))
        assertFalse(BusinessCategories.complete(listOf(BusinessCategories.OTHER), "a"))
        assertTrue(BusinessCategories.complete(listOf(BusinessCategories.OTHER), "Pooja items"))
        // "Other Services" too (a service shop's own kind).
        assertFalse(BusinessCategories.complete(listOf(BusinessCategories.OTHER_SERVICES), ""))
        assertTrue(BusinessCategories.complete(listOf(BusinessCategories.OTHER_SERVICES), "Pest control"))
        assertEquals(BusinessCategories.OTHER_SERVICES, BusinessCategories.namedOther(listOf("GYM_FITNESS", BusinessCategories.OTHER_SERVICES)))
        assertNull(BusinessCategories.namedOther(listOf("GYM_FITNESS")))
    }

    @Test fun aBusinessSetUpBeforeIsReadIntoTheList() {
        fun biz(category: String, categories: List<String>? = null) = Business("b", "u", "O", "Shop", category, "Chennai", null, null, categories = categories)
        assertEquals(listOf("FASHION_GARMENTS"), BusinessCategories.picked(biz("TEXTILE")))
        assertEquals(listOf("SALON_BEAUTY_SERVICE"), BusinessCategories.picked(biz("BEAUTY_SALON")))
        assertEquals(listOf("GROCERY_FMCG", "DAIRY_FROZEN"), BusinessCategories.picked(biz("GROCERY", listOf("GROCERY_FMCG", "DAIRY_FROZEN"))))
        // An empty list (or codes this app does not know) falls back to the older category.
        assertEquals(listOf("PHARMACY_MEDICAL"), BusinessCategories.picked(biz("PHARMACY", emptyList())))
        assertEquals(listOf("PHARMACY_MEDICAL"), BusinessCategories.picked(biz("PHARMACY", listOf("FUTURE_CODE"))))
        assertTrue(BusinessCategories.picked(null).isEmpty())
        // Every older category round-trips: read into the list and saved back, it is the same older value.
        BusinessCategory.entries.forEach { old ->
            assertEquals(old.apiValue, BusinessCategories.legacyFor(BusinessCategories.fromLegacy(old.apiValue)))
        }
    }

    @Test fun theFirstProductCategoryPicksTheUnitsAndItsSubcategoriesAreSuggested() {
        assertEquals(UnitMaster.Kind.FRESH, BusinessCategories.unitKindFor(listOf("HOTEL_FOOD_SERVICE", "FRUITS_VEGETABLES")))
        assertEquals(UnitMaster.Kind.RESTAURANT, BusinessCategories.unitKindFor(listOf("HOTEL_FOOD_SERVICE")))
        assertEquals(UnitMaster.Kind.HARDWARE, BusinessCategories.unitKindFor(listOf(BusinessCategories.OTHER, "SANITARYWARE_BATH")))
        assertNull(BusinessCategories.unitKindFor(emptyList()))
        val subs = BusinessCategories.subcategoriesFor(listOf("GROCERY_FMCG", "DAIRY_FROZEN"))
        assertEquals("Rice & Grains", subs.first())
        assertTrue("Paneer & Cheese" in subs)
    }
}
