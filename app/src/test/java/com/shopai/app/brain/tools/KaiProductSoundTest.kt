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

    /**
     * Pharmacy brands (owner's stock master, 13 Oct 2026): "Dr" is doctor ("டாக்டர்"), "&" is and, gy = jy (Flagyl), nc = ng
     * (Zincovit), a real "tz" / "dc" ("செட்சின்", "குட்செஃப்"), capitals said as a word ("ஈனோ" = ENO), a short brand before its
     * quantity ("டோலோ 650" is Dolo 650, not Dettol) and the strength said once ("மாக்ஸ் 500" is Mox 500, not Mox 500 500).
     */
    @Test fun pharmacyBrandsSaidIntoTheMic() {
        val shop4 = listOf("Dr Trust Thermometer", "Dr Morepen", "Head & Shoulders", "Flagyl 400", "Zincovit", "Cetzine 10", "Gudcef 200", "ENO",
            "Dolo 650", "Dettol", "Mox 500", "Uprise D3", "BPL", "Telma 40", "Telmikind 40")
        fun h(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, shop4, people))
        for ((mic, want) in listOf("டாக்டர் டிரஸ்ட் தெர்மாமீட்டர் 2 பீஸ்" to "Dr Trust Thermometer 2", "டாக்டர் மோர்பென் 2 பீஸ்" to "Dr Morepen 2",
                "ஹெட் அண்ட் ஷோல்டர்ஸ் 5 பாட்டில்" to "Head & Shoulders 5", "ஃபிளாஜில் 400 5 ஸ்ட்ரிப்" to "Flagyl 400 5", "சிங்கோவிட் 5 ஸ்ட்ரிப்" to "Zincovit 5",
                "செட்சின் 10 5 ஸ்ட்ரிப்" to "Cetzine 10 5", "குட்செஃப் 200 5 ஸ்ட்ரிப்" to "Gudcef 200 5", "ஈனோ 5 பாக்கெட்" to "ENO 5",
                "டோலோ 650 5 ஸ்ட்ரிப்" to "Dolo 650 5", "டெட்டால் 5 பாட்டில்" to "Dettol 5", "மாக்ஸ் 500 5 ஸ்ட்ரிப்" to "Mox 500 5",
                "அப்ரைஸ் டி3 5 பாக்கெட்" to "Uprise D3 5", "டெல்மா 40 5 ஸ்ட்ரிப்" to "Telma 40 5",
                "டெல்மிகைண்ட் 40 5 ஸ்ட்ரிப்" to "Telmikind 40 5")) {
            val got = h(mic)
            assertTrue("$mic → $got", got.startsWith(want) && !got.startsWith("$want 0"))
        }
        // A word that sounds like nothing in the shop stays as the mic wrote it.
        h("ஸ்பா 5 பீஸ்").let { got -> assertTrue(got, shop4.none { got.contains(it) }) }
    }

    /**
     * Hardware names (owner's stock master, 13 Oct 2026): "U-bolt" = yu bolt, "Union" = yunion, "Chemical" = kemical, "GI wire" in two
     * words, "House" with its ou (வு), "Wedge" (ட்ஜ), "Anchor" / "Angle" with the n-k of ங்க — and "ஸ்விட்ச்" is always Switch, never
     * Jigsaw; "எம்எஸ் ஆங்கிள்" is MS angle, not MS channel; "பைண்டிங் வயர்" is Binding wire, not Paint thinner.
     */
    @Test fun hardwareNamesSaidIntoTheMic() {
        val shop5 = listOf("U-bolt", "Eye bolt", "PVC union", "Universal socket", "Chemical anchor", "Wedge anchor", "Wood screw", "Coach screw", "Hex screw",
            "GI wire", "GI pipe", "House wire", "Washer", "MS angle", "MS channel", "Binding wire", "Paint thinner", "Jigsaw", "Switch box", "1-way switch", "Multi-plug",
            "Wood chisel", "Chisel", "Bench vice", "Piano hinge", "Hacksaw blade", "Hex bolt", "Pipe wrench", "Hand saw", "Chalk line",
            "Flush bolt", "Flush plate", "Hydraulic hinge", "Door lock", "Drawer lock", "Silica gel", "Mop", "Ear muff", "Ear plug", "Watering can",
            "Two-piece closet", "One-piece closet", "WD-type penetrating spray", "2-way switch", "Emergency sign", "Emergency light")
        fun h(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, shop5, people))
        for ((mic, want) in listOf("யூ போல்ட் 7" to "U-bolt 7", "ஐ போல்ட் 7" to "Eye bolt 7", "பிவிசி யூனியன் 7" to "PVC union 7",
                "யுனிவர்சல் சாக்கெட் 7" to "Universal socket 7", "கெமிக்கல் ஆங்கர் 7" to "Chemical anchor 7", "வெட்ஜ் ஆங்கர் 7" to "Wedge anchor 7",
                "கோச் ஸ்க்ரூ 7" to "Coach screw 7", "ஹெக்ஸ் ஸ்க்ரூ 7" to "Hex screw 7", "ஜிஐ வயர் 7" to "GI wire 7", "ஹவுஸ் வயர் 7" to "House wire 7",
                "எம்எஸ் ஆங்கிள் 7" to "MS angle 7", "எம்எஸ் சேனல் 7" to "MS channel 7", "பைண்டிங் வயர் 7" to "Binding wire 7", "பெயிண்ட் தின்னர் 7" to "Paint thinner 7",
                "ஸ்விட்ச் பாக்ஸ் 7" to "Switch box 7", "ஒன் வே ஸ்விட்ச் 7" to "1-way switch 7", "மல்டி பிளக் 7" to "Multi-plug 7", "ஜிக்சா 2" to "Jigsaw 2",
                // Two words said together stay two words ("வுட் சிசல்" is not "Chisel"); "Bench" keeps its nch, "Chalk" loses its l.
                "வுட் சிசல் 7" to "Wood chisel 7", "சிசல் 7" to "Chisel 7", "பெஞ்ச் வைஸ் 7" to "Bench vice 7", "பியானோ ஹிஞ்ச் 7" to "Piano hinge 7",
                "ஹேக்சா பிளேடு 7" to "Hacksaw blade 7", "ஹெக்ஸ் போல்ட் 7" to "Hex bolt 7", "பைப் ரெஞ்ச் 7" to "Pipe wrench 7", "ஹேண்ட் சா 7" to "Hand saw 7",
                "சாக் லைன் 7" to "Chalk line 7",
                // Every word's own vowel (bolt / plate), a y between consonants (hydraulic), a soft g (gel), a long ா (Mop, not muff).
                "ஃப்ளஷ் போல்ட் 7" to "Flush bolt 7", "ஃப்ளஷ் பிளேட் 7" to "Flush plate 7", "ஹைட்ராலிக் ஹிஞ்ச் 7" to "Hydraulic hinge 7",
                "சிலிக்கா ஜெல் 7" to "Silica gel 7", "மாப் 7" to "Mop 7", "இயர் மஃப் 7" to "Ear muff 7", "இயர் பிளக் 7" to "Ear plug 7",
                "வாட்டரிங் கேன் 7" to "Watering can 7", "டூ பீஸ் க்ளோசெட் 7" to "Two-piece closet 7", "டபிள்யூடி டைப் பெனட்ரேட்டிங் ஸ்ப்ரே 7" to "WD-type penetrating spray 7",
                "டூ வே ஸ்விட்ச் 7" to "2-way switch 7", "எமர்ஜென்சி சைன் 7" to "Emergency sign 7")) {
            val got = h(mic)
            assertTrue("$mic → $got", got.startsWith(want))
        }
        assertTrue(h("ஸ்விட்ச் 7").let { !it.contains("Jigsaw") })
        // One word that only sounds like part of another name is never that product ("ஹைட்ராலிக்" alone is not Door lock / Drawer lock).
        assertTrue(h("ஹைட்ராலிக் 7").let { !it.contains("lock") })
        // RCBO / RCCB by their vowels in order; "ஸ்க்ரூ" is Screw (ew = oo); a unit said alone stays the unit ("பாக்கெட்" is never Bucket).
        val shop6 = listOf("RCBO", "RCCB", "Screw", "Bucket", "Hex bolt M4", "Hex bolt M8")
        fun h6(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, shop6, people))
        assertTrue(h6("ஆர்சிபிஓ 7").startsWith("RCBO 7") && h6("ஆர்சிசிபி 7").startsWith("RCCB 7") && h6("ஸ்க்ரூ 7").startsWith("Screw 7"))
        assertTrue(h6("10 பாக்கெட்").let { !it.contains("Bucket") } && h6("பக்கெட் 2").startsWith("Bucket 2"))
        // Letters said one by one, one syllable each: "எம்சிபி" (MCB) is never MCCB; DP / TP MCB sound the same — neither is picked.
        val shop7 = listOf("MCCB 6A", "SP MCB 6A", "DP MCB 6A", "TP MCB 6A", "Gypsum board")
        fun h7(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, shop7, people))
        assertTrue(h7("டிபி எம்சிபி 6 ஆம்ப் 7").let { !it.contains("MCCB") && !it.contains("DP MCB") && !it.contains("TP MCB") })
        assertTrue(h7("எம்சிசிபி 6 ஆம்ப் 7").startsWith("MCCB") && h7("எஸ்பி எம்சிபி 6 ஆம்ப் 7").startsWith("SP MCB") && h7("ஜிப்சம் போர்டு 7").startsWith("Gypsum board"))
        // A letter before a number is a size only where the shop's names have it: "எம் 4" = M4; "டி 10 பாக்கெட்" stays D, 10 packets.
        val known = setOf("M4", "M8")
        assertEquals("Hex bolt M4 7", KaiSpokenWords.code("Hex bolt எம் 4 7", known))
        assertEquals("Glucon டி 10 packet", KaiSpokenWords.code("Glucon டி 10 packet", known))
    }

    /**
     * Supermarket names (owner's stock master, 14 Oct 2026): "கீ" is ghee (gh said at the start of a word), "மிக்சர்" is Mixture (sch is
     * sk only at the start), "பொன்னி ரா ரைஸ்" in three words is Ponni Raw rice (not Ponni rice), "சாமை" is the shop's own Samai before the
     * Little millet it also means, "பாமோலின்" is Palmolein (the l isn't heard), "அயோடைஸ்டு" is Iodized, and a name led by its code
     * ("24 Mantra", "A1 Chips") takes the code said before it once — never as a quantity.
     */
    @Test fun supermarketNamesSaidIntoTheMic() {
        val shop8 = listOf("Amul Buffalo ghee 200 ml", "Mixture 100 g", "Ponni rice 1 kg", "Ponni Raw rice 1 kg", "Samai", "Little millet",
            "Fortune Palmolein oil 1 L", "Iodized salt", "24 Mantra Red rice 500 g", "A1 Chips Banana chips 50 g", "Cooking oil", "Semolina")
        fun h(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, shop8, people))
        for ((mic, want) in listOf("அமுல் பஃபலோ கீ 200 எம்எல் 7" to "Amul Buffalo ghee 200 ml 7", "மிக்சர் 100 கிராம் 7" to "Mixture 100",
                "பொன்னி ரா ரைஸ் 1 கிலோ 7" to "Ponni Raw rice 1", "பொன்னி ரைஸ் 1 கிலோ 7" to "Ponni rice 1", "சாமை 7" to "Samai 7",
                "லிட்டில் மில்லட் 7" to "Little millet 7", "பார்ச்சூன் பாமோலின் ஆயில் 1 லிட்டர் 7" to "Fortune Palmolein oil 1",
                "அயோடைஸ்டு சால்ட் 7" to "Iodized salt 7", "24 மந்த்ரா ரெட் ரைஸ் 500 கிராம் 7" to "24 Mantra Red rice 500",
                "ஏ1 சிப்ஸ் பனானா சிப்ஸ் 50 கிராம் 7" to "A1 Chips Banana chips 50", "மந்த்ரா ரெட் ரைஸ் 500 கிராம் 7" to "24 Mantra Red rice 500",
                // A goods name said in Tamil is its product unless a kept name sounds exactly like it: "சமையல் எண்ணெய்" is Cooking oil, not Semolina.
                "சமையல் எண்ணெய் 5 லிட்டர்" to "Cooking oil 5")) {
            val got = h(mic)
            assertTrue("$mic → $got", got.startsWith(want))
        }
        // A word Kai can't place is left as said, never turned into the nearest name ("ஸ்பா" is not Samai).
        assertTrue(h("ஸ்பா 7").let { it.startsWith("spa 7") && !it.contains("Samai") && !it.contains("millet") })
    }

    /**
     * Vegetable names (owner's stock master, 14 Oct 2026): a product the shop writes with two names ("Spinach / Palak 250 g") is
     * either name said alone; "Ginger (tender)" is also said "டெண்டர் ஜிஞ்சர்"; "சுக்கினி" is Zucchini (cch = k), "ஜிஞ்சர்" is Ginger
     * (soft g); a goods name in Tamil goes to the loose produce, never to its packs or another produce that holds the word.
     */
    @Test fun vegetableNamesSaidIntoTheMic() {
        val shop9 = listOf("Spinach / Palak", "Spinach / Palak 250 g", "Brinjal / Eggplant", "Brinjal / Eggplant 250 g", "Native brinjal varieties",
            "Ginger", "Ginger (tender)", "Zucchini", "Zucchini 250 g", "Radish", "Radish (red)", "Red radish", "Okra / Ladies finger", "Lettuce")
        fun h(mic: String) = KaiSpokenWords.normalize(KaiProductSound.withProducts(mic, shop9, people))
        for ((mic, want) in listOf("பாலக் 7 கிலோ" to "Spinach / Palak 7", "ஸ்பினாச் 250 கிராம் 7" to "Spinach / Palak 250", "கத்தரிக்காய் 5 கிலோ" to "Brinjal / Eggplant 5",
                "ஜிஞ்சர் 7 கிலோ" to "Ginger 7", "ஜிஞ்சர் டெண்டர் 7 கிலோ" to "Ginger (tender) 7", "டெண்டர் ஜிஞ்சர் 7 கிலோ" to "Ginger (tender) 7",
                "சுக்கினி 7 கிலோ" to "Zucchini 7", "லேடீஸ் ஃபிங்கர் 7 கிலோ" to "Okra / Ladies finger 7", "ரெட் ராடிஷ் 7 கிலோ" to "Red radish 7")) {
            val got = h(mic)
            assertTrue("$mic → $got", got.startsWith(want))
        }
        // A vegetable the shop doesn't keep stays as said ("நூக்கல்", knol-khol, is not Lettuce or Okra).
        assertTrue(h("நூக்கல் 5 கிலோ").startsWith("நூக்கல் 5"))
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
