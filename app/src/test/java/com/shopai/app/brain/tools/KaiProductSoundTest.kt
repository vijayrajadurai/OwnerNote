package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product names as the phone's ta-IN mic writes them (owner, 11 Oct 2026: lakhs of shops, any product name — the mic must
 * read them as the text box does). One big shop with many look-alike names, so a match is never luck.
 */
class KaiProductSoundTest {

    private val shop = listOf(
        "Britannia Biscuit", "Good Day", "Parle G", "Sunfeast Marie", "Toor Dal", "Moong Dal", "Dettol", "Dettol Soap", "Surf Excel",
        "Tea Powder", "Hamam Soap", "Clinic Plus", "Sunsilk Shampoo", "Vim Bar", "Rin Powder", "Ariel", "Tide", "Horlicks", "Boost",
        "Bru Coffee", "Aashirvaad Atta", "Fortune Oil", "Gold Winner", "Maggi Noodles", "Kissan Jam", "Lifebuoy", "Colgate",
        "Colgate Paste", "Close Up", "Pears", "Santoor", "Medimix", "Vicks", "Amul Butter", "Pepsi", "Sprite", "Lays Chips",
        "Kurkure", "Dairy Milk", "Perk", "Bournvita", "Complan", "Harpic", "Lizol", "Odonil", "Good Knight", "All Out", "Pepsodent",
        "Maida", "Sugar", "Rice", "Coca Cola",
    )
    private val people = listOf("Kumar", "Ramesh", "Selvam", "Lakshmi", "Priya")

    /** As Kai reads a mic line: product names first, then the Tamil words in Tanglish. */
    private fun heard(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, shop, people))

    /**
     * A hardware shop (owner, 13 Oct 2026): the mic writes an English "w" before a vowel as "ஒய" ("ஒயர்" = Wire, "ஒயிட்" = White),
     * and a short name is taken right before its quantity or a stock question about it — never "அவர்" (he) for Wire.
     */
    @Test fun wireAndWhiteInAHardwareShop() {
        val hardware = listOf("Wire", "White Cement", "Washer", "Asian Paints", "Cement")
        fun h(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, hardware, people))
        for ((mic, product) in listOf("ஒயர் 10 மீட்டர்" to "Wire", "ஒயர் எவ்வளவு இருக்கு" to "Wire", "ஒயர் ஸ்டாக் எவ்வளவு" to "Wire",
                "வயர் எவ்வளவு" to "Wire", "ஒயிட் சிமெண்ட் 2 மூட்டை" to "White Cement", "ஒயிட் சிமெண்ட் எவ்வளவு இருக்கு" to "White Cement",
                "வாஷர் எவ்வளவு" to "Washer")) {
            val got = h(mic)
            assertTrue("$mic → $got", got.contains(product))
        }
        // Not a product: a pronoun before "how much", and a short name with no quantity or stock word after it.
        assertTrue(h("அவர் எவ்வளவு தரணும்").let { !it.contains("Wire") })
        assertTrue(h("ஒயர் கட் ஆயிடுச்சு").let { !it.contains("Wire") })
    }

    /** "Switch" / "Watch" (the mic writes "ஸ்விட்ச்" / "வாட்ச்"), "Cough Syrup" ("காஃப் சிரப்" — the gh of cough is an f). */
    @Test fun tchAndOughSaidIntoTheMic() {
        val shop2 = listOf("Switch", "Watch", "Cough Syrup", "Socket", "Sugar")
        fun h(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, shop2, people))
        for ((mic, product) in listOf("ஸ்விட்ச் 10 பீஸ்" to "Switch", "ஸ்விட்ச் எவ்வளவு இருக்கு" to "Switch", "வாட்ச் 2 வித்துச்சு" to "Watch",
                "காஃப் சிரப் 5 பாட்டில்" to "Cough Syrup", "காஃப் சிரப் எவ்வளவு இருக்கு" to "Cough Syrup")) {
            val got = h(mic)
            assertTrue("$mic → $got", got.contains(product))
        }
        assertTrue(h("சுகர் 5 கிலோ").contains("Sugar") && !h("சுகர் 5 கிலோ").contains("Switch"))
    }

    /**
     * Medical shop names as the mic writes them (owner, 13 Oct 2026): "Syringe" / "Bandage" (a final ge is a j), "Ointment" (its t
     * isn't heard, the oi written with ய), "Eye Drops" (eye = ai) — and what kind of product a spoken name is, as typed.
     */
    @Test fun medicalNamesSaidIntoTheMic() {
        val shop3 = listOf("Syringe", "Cotton Bandage", "Betadine Ointment", "Eye Drops", "Surgical Gloves", "Benadryl Syrup")
        fun h(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, shop3, people))
        for ((mic, product) in listOf("சிரிஞ்ச் 100 ஆட் பண்ணு" to "Syringe", "சிரிஞ்ச் எவ்வளவு ஸ்டாக் இருக்கு" to "Syringe",
                "காட்டன் பேண்டேஜ் 10 ரோல்" to "Cotton Bandage", "பீட்டாடின் ஆயின்மென்ட் 5 டியூப்" to "Betadine Ointment",
                "ஐ டிராப்ஸ் எவ்வளவு ஸ்டாக் இருக்கு" to "Eye Drops", "சர்ஜிக்கல் கிளவுஸ் 2 பாக்ஸ்" to "Surgical Gloves")) {
            val got = h(mic)
            assertTrue("$mic → $got", got.contains(product))
        }
        val inv = com.shopai.app.brain.tools.KaiInventory
        for (name in listOf("பெனட்ரில் சிரப்", "பீட்டாடின் ஆயின்மென்ட்", "காட்டன் பேண்டேஜ்", "இன்சுலின் இன்ஜெக்ஷன்", "சர்ஜிக்கல் கிளவுஸ்", "ஐ டிராப்ஸ்"))
            assertEquals(name, com.shopai.app.brain.tools.ProductKind.PHARMACY, inv.kindOf(name, null))
        // Sounding like a word of another kind is not enough: syrup is not "cheruppu" (footwear) or "surf"; gloves is not glass.
        assertTrue(KaiProductSound.wordGap("சிரப்", "surf") == null)
        assertTrue(KaiProductSound.wordGap("கிளவுஸ்", "gloves")!! < KaiProductSound.wordGap("கிளவுஸ்", "glass")!!)
        // Not a medicine word at all: still a grocery item.
        assertEquals(com.shopai.app.brain.tools.ProductKind.GROCERY, inv.kindOf("பாசி பருப்பு", "KG"))
    }

    @Test fun productNamesSaidIntoTheMic() {
        val said = mapOf(
            "பிரிட்டானியா பிஸ்கட்" to "Britannia Biscuit", "குட் டே" to "Good Day", "பார்லே ஜி" to "Parle G", "சன்ஃபீஸ்ட் மேரி" to "Sunfeast Marie",
            "டூர் தால்" to "Toor Dal", "மூங் தால்" to "Moong Dal", "டெட்டால்" to "Dettol", "டெட்டால் சோப்" to "Dettol Soap",
            "சர்ஃப் எக்செல்" to "Surf Excel", "டீ பவுடர்" to "Tea Powder", "ஹமாம் சோப்" to "Hamam Soap", "கிளினிக் பிளஸ்" to "Clinic Plus",
            "சன்சில்க் ஷாம்பு" to "Sunsilk Shampoo", "விம் பார்" to "Vim Bar", "ரின் பவுடர்" to "Rin Powder", "ஏரியல்" to "Ariel",
            "டைட்" to "Tide", "ஹார்லிக்ஸ்" to "Horlicks", "பூஸ்ட்" to "Boost", "ப்ரூ காபி" to "Bru Coffee", "ஆசீர்வாத் ஆட்டா" to "Aashirvaad Atta",
            "ஃபார்ச்சூன் ஆயில்" to "Fortune Oil", "கோல்ட் வின்னர்" to "Gold Winner", "மேகி நூடுல்ஸ்" to "Maggi Noodles", "கிசான் ஜாம்" to "Kissan Jam",
            "லைஃப்பாய்" to "Lifebuoy", "கோல்கேட்" to "Colgate", "கோல்கேட் பேஸ்ட்" to "Colgate Paste", "க்ளோஸ் அப்" to "Close Up",
            "பியர்ஸ்" to "Pears", "சந்தூர்" to "Santoor", "மெடிமிக்ஸ்" to "Medimix", "விக்ஸ்" to "Vicks", "அமுல் பட்டர்" to "Amul Butter",
            "பெப்சி" to "Pepsi", "ஸ்ப்ரைட்" to "Sprite", "லேஸ் சிப்ஸ்" to "Lays Chips", "குர்குரே" to "Kurkure", "டெய்ரி மில்க்" to "Dairy Milk",
            "பெர்க்" to "Perk", "போர்ன்விட்டா" to "Bournvita", "காம்ப்ளான்" to "Complan", "ஹார்பிக்" to "Harpic", "லைசால்" to "Lizol",
            "ஓடோனில்" to "Odonil", "குட் நைட்" to "Good Knight", "ஆல் அவுட்" to "All Out", "பெப்சோடென்ட்" to "Pepsodent", "மைதா" to "Maida",
            "கோகோ கோலா" to "Coca Cola",
        )
        val wrong = said.mapNotNull { (mic, product) ->
            val got = heard("$mic 2 பாக்ஸ் வந்துச்சு")
            if (got.startsWith("$product 2")) null else "$mic → $got (want $product)"
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test fun twoProductsInOneLine() {
        assertEquals("Britannia Biscuit 2 box Toor Dal 25 kg", heard("பிரிட்டானியா பிஸ்கட் 2 பாக்ஸ் டூர் தால் 25 கிலோ"))
    }

    @Test fun neverGuessed() {
        // A person in the books stays a person; the shop's common Tamil words and money lines are not products.
        assertEquals("குமார் 2000", heard("குமார் 2000"))
        for (line in listOf("கடைக்கு சாமான் வந்திருக்கு", "அவ்ளோ தான்", "இன்னைக்கு வியாபாரம் எப்படி")) {
            val got = heard(line)
            assertEquals(line, KaiSpokenWords.normalize(line), got)
        }
        // "தால்" alone sounds like Toor Dal / Moong Dal / Dettol: two alike → nothing replaced (Kai asks).
        assertEquals(KaiSpokenWords.normalize("தால் 2 கிலோ"), heard("தால் 2 கிலோ"))
    }

    @Test fun aShopWithOnlyTamilNamesIsLeftAlone() {
        assertEquals("அரிசி 2 மூட்டை", KaiProductSound.withProducts("அரிசி 2 மூட்டை", listOf("அரிசி"), emptyList()))
    }

    @Test fun goodsByTheirOwnNameAndLookAlikeNames() {
        // The owner's 8-shop stock list (12 Oct 2026): native names (KaiFeed.goodsNames), names that sound alike, hyphens, dotted letters.
        fun heardIn(shop: List<String>, mic: String) = KaiProductSound.withProducts(mic, shop, people)
        val hardware = listOf("Nut", "Bolt", "Measuring tape", "Door hinge", "Door lock", "Wire")
        val auto = listOf("Headlight", "Tail light", "Inner tube", "Engine oil", "Oil filter")
        val fashion = listOf("Shirt", "T-shirt", "Lungi", "Zipper", "Necklace / chain")
        val plywood = listOf("Plywood", "MDF board", "HDHMR board", "Particle board")
        val building = listOf("Steel rod", "Binding wire", "TMT steel bar", "Blue metal aggregate", "6 mm aggregate", "20 mm aggregate")
        val said = listOf(
            hardware to "அளவு நாடா" to "Measuring tape", hardware to "கதவு கீல்" to "Door hinge", hardware to "கதவு பூட்டு" to "Door lock",
            auto to "ஹெட்லைட்" to "Headlight", auto to "டெயில் லைட்" to "Tail light",
            fashion to "டி-ஷர்ட்" to "T-shirt", fashion to "லுங்கி" to "Lungi", fashion to "சட்டை" to "Shirt", fashion to "ஜிப்" to "Zipper",
            plywood to "எச்.டி.எச்.எம்.ஆர். போர்டு" to "HDHMR board", plywood to "எம்.டி.எஃப். பலகை" to "MDF board",
            building to "கம்பி" to "Steel rod", building to "ஜல்லி" to "Blue metal aggregate", building to "6 எம்.எம். ஜல்லி" to "6 mm aggregate",
            building to "20 எம்.எம். ஜல்லி" to "20 mm aggregate", building to "டி.எம்.டி. கம்பி" to "TMT steel bar",
        )
        val wrong = said.mapNotNull { (shopAndMic, product) ->
            val (shop, mic) = shopAndMic
            val got = heardIn(shop, "$mic 5 வந்துச்சு")
            if (got.startsWith("$product 5")) null else "$mic → $got (want $product)"
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
        // "கம்பி" is wire in a hardware shop. A goods name the shop doesn't keep is never turned into another product: no Measuring
        // tape → "அளவு நாடா" is not Nut; "ஸ்பா" (not in any list) stays as said.
        assertEquals("Wire 5", heardIn(hardware, "கம்பி 5"))
        assertEquals("அளவு நாடா 5", heardIn(listOf("Nut", "Bolt"), "அளவு நாடா 5"))
        assertEquals("ஸ்பா 5", heardIn(hardware, "ஸ்பா 5"))
    }
}
