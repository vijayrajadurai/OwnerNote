package com.shopai.app.brain.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which goods keep an expiry (owner, 17 Oct 2026): fruits & vegetables, dairy & beverages, bakery, meat & fish, grocery & staples,
 * snacks, personal care, cleaning liquids, baby care, medicines, seeds / fertiliser / pesticide, pooja (ghee, oil) — in any shop.
 * Clothes, hardware, electricals, mobiles, stationery, footwear, kitchenware, toys and auto parts never do. By the product's
 * category, else by its name; a product neither tells about keeps the expiry the owner said.
 */
class KaiExpiryKindsTest {
    @Test fun goodsThatCanExpire() {
        val fails = mutableListOf<String>()
        val byCategory = listOf("Fruits & Vegetables", "Beverages & Dairy", "Bakery & Fresh Foods", "Meat & Fish", "Grocery & Staples", "Grocery",
            "Snacks & Confectionery", "FMCG & Personal Care", "FMCG", "Home Care & Cleaning", "Baby Care & Hygiene", "Pharmacy & Medical",
            "Agriculture & Gardening", "Pooja & Religious Items")
        for (c in byCategory) if (!KaiExpiry.canExpire(c, "Item")) fails += "category $c: no expiry"
        val never = listOf("Garments & Textiles", "Hardware & Plumbing", "Electricals & Lighting", "Mobile & Electronics", "Stationery & Office Supplies",
            "Footwear & Accessories", "Kitchenware & Household", "Toys, Gifts & Party", "Automobile Spare Parts")
        for (c in never) if (KaiExpiry.canExpire(c, "Item")) fails += "category $c: expiry"
        // By name when the category says nothing ("General", none).
        for (n in listOf("Tomato", "Milk", "Bread", "Paracetamol")) if (!KaiExpiry.canExpire("General", n)) fails += "name $n: no expiry"
        // A product neither its category nor its name tells about: the owner's word is kept.
        if (!KaiExpiry.canExpire(null, "Zxq item")) fails += "unknown: no expiry"
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
