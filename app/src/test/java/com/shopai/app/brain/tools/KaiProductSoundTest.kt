package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
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
}
