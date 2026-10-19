package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Units in every kind of shop (owner, 19 Oct 2026: "units ah correct ah yeadukutha nu paru … grocery store na kg, gram, liter …
 * all categorie shops ku"): kg / gram / litre / ml / meter / dozen / tray / box / bag, the owner's fractions (arai, kaal, mukkaal,
 * onnara, rendara, moonara), a product's own unit conversions, words Kai can't read as a unit (asked — never the default unit),
 * Tamil / English names of the shop's products, and products or brands the shop never fed (a new product is said so, a brand of
 * one it keeps is asked about). Every line typed and as the mic writes it; nothing moves before Confirm.
 */
class KaiUnitsEveryShopTest {
    private val now = LocalDateTime.of(2026, 10, 13, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("shop-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private data class Item(val name: String, val unit: String, val stock: String, val conv: Map<String, String> = emptyMap())
    private sealed interface Want
    private data class Moves(val product: String, val delta: String) : Want
    private class Asks(vararg val words: String) : Want
    private class NewProduct(val name: String) : Want

    private val shops = mapOf(
        "grocery" to listOf(Item("Sugar", "KG", "50"), Item("Sunflower Oil", "LITRE", "40"), Item("Egg", "PCS", "120", mapOf("TRAY" to "30")),
            Item("Milk", "PACK", "40"), Item("Chilli Powder", "GRAM", "5000"), Item("Rice", "KG", "250", mapOf("BAG" to "25")), Item("Biscuit", "PCS", "100", mapOf("BOX" to "24"))),
        "vegetables" to listOf(Item("Tomato", "KG", "30"), Item("Onion", "KG", "50"), Item("Coriander Leaves", "BUNDLE", "40"), Item("Banana", "PCS", "200", mapOf("DOZEN" to "12"))),
        "dairy" to listOf(Item("Curd", "LITRE", "20"), Item("Ghee", "ML", "10000"), Item("Paneer", "KG", "8")),
        "sweets" to listOf(Item("Mysore Pak", "KG", "10"), Item("Laddu", "PCS", "100")),
        "medical" to listOf(Item("Paracetamol", "STRIP", "100", mapOf("BOX" to "10")), Item("Cough Syrup", "BOTTLE", "20"), Item("Cotton", "GRAM", "2000")),
        "hardware" to listOf(Item("Rope", "METER", "200"), Item("Nail", "KG", "20"), Item("Paint", "LITRE", "60")),
        "textile" to listOf(Item("Cotton Cloth", "METER", "200"), Item("Saree", "PCS", "25")),
        "fuel" to listOf(Item("Kerosene", "LITRE", "100")),
    )

    private data class Case(val shop: String, val typed: String, val mic: String?, val want: Want)
    private val cases = listOf(
        // grocery: kg / gram / half / quarter / litre / ml / dozen / tray / packet
        Case("grocery", "Sugar 2 kilo vandhuchu", "சுகர் 2 கிலோ வந்துச்சு", Moves("Sugar", "2")),
        Case("grocery", "Sugar 500 gram vithuchu", "சுகர் 500 கிராம் வித்துச்சு", Moves("Sugar", "-0.5")),
        Case("grocery", "Sugar arai kilo vithuchu", "சுகர் அரை கிலோ வித்துச்சு", Moves("Sugar", "-0.5")),
        Case("grocery", "Sugar kaal kilo vithuchu", "சுகர் கால் கிலோ வித்துச்சு", Moves("Sugar", "-0.25")),
        Case("grocery", "Sugar mukkal kilo vithuchu", "சுகர் முக்கால் கிலோ வித்துச்சு", Moves("Sugar", "-0.75")),
        Case("grocery", "Sugar onnara kilo vithuchu", "சுகர் ஒன்றரை கிலோ வித்துச்சு", Moves("Sugar", "-1.5")),
        Case("grocery", "Sugar 1.5 kg vithuchu", null, Moves("Sugar", "-1.5")),
        // words not in any list: asked, never read as a number or the default unit
        Case("grocery", "Sugar konjam kilo vithuchu", "சுகர் கொஞ்சம் கிலோ வித்துச்சு", Asks("evlo", "எவ்வளவு", "எத்தனை")),
        Case("grocery", "Sugar 2 seer vithuchu", "சுகர் 2 சேர் வித்துச்சு", Asks("seer", "சேர்", "Sugar")),
        Case("grocery", "Sunflower Oil onnara litre vithuchu", "சன்ஃப்ளவர் ஆயில் ஒன்றரை லிட்டர் வித்துச்சு", Moves("Sunflower Oil", "-1.5")),
        Case("grocery", "Sunflower Oil kaal litre vithuchu", "சன்ஃப்ளவர் ஆயில் கால் லிட்டர் வித்துச்சு", Moves("Sunflower Oil", "-0.25")),
        Case("hardware", "Rope arai meter vithuchu", "ரோப் அரை மீட்டர் வித்துச்சு", Moves("Rope", "-0.5")),
        Case("hardware", "Paint moonara litre vithuchu", "பெயிண்ட் மூணரை லிட்டர் வித்துச்சு", Moves("Paint", "-3.5")),
        Case("grocery", "Egg arai dozen vithuchu", "எக் அரை டஜன் வித்துச்சு", Moves("Egg", "-6")),
        Case("grocery", "Sugar 250g vithuchu", "சுகர் 250 கிராம் வித்துச்சு", Moves("Sugar", "-0.25")),
        Case("grocery", "sakkarai 2 kg vandhuchu", "சக்கரை 2 கிலோ வந்துச்சு", Moves("Sugar", "2")),
        Case("grocery", "Sunflower Oil 500 ml vithuchu", "சன்ஃப்ளவர் ஆயில் 500 எம்எல் வித்துச்சு", Moves("Sunflower Oil", "-0.5")),
        Case("grocery", "Sunflower Oil arai litre vithuchu", "சன்ஃப்ளவர் ஆயில் அரை லிட்டர் வித்துச்சு", Moves("Sunflower Oil", "-0.5")),
        Case("grocery", "Sunflower Oil 5 liter vandhuchu", "சன்ஃப்ளவர் ஆயில் 5 லிட்டர் வந்துச்சு", Moves("Sunflower Oil", "5")),
        Case("grocery", "Egg 2 dozen vithuchu", "எக் 2 டஜன் வித்துச்சு", Moves("Egg", "-24")),
        Case("grocery", "muttai 2 tray vandhuchu", "முட்டை 2 ட்ரே வந்துச்சு", Moves("Egg", "60")),
        Case("grocery", "Milk 10 packet vandhuchu", "மில்க் 10 பாக்கெட் வந்துச்சு", Moves("Milk", "10")),
        Case("grocery", "Chilli Powder 2 kg vandhuchu", "சில்லி பவுடர் 2 கிலோ வந்துச்சு", Moves("Chilli Powder", "2000")),
        Case("grocery", "Chilli Powder 100 gram vithuchu", "சில்லி பவுடர் 100 கிராம் வித்துச்சு", Moves("Chilli Powder", "-100")),
        Case("grocery", "Rice 1 moota vandhuchu", "ரைஸ் 1 மூட்டை வந்துச்சு", Moves("Rice", "25")),
        Case("grocery", "arisi 5 kilo vithuchu", "அரிசி 5 கிலோ வித்துச்சு", Moves("Rice", "-5")),
        Case("grocery", "Biscuit 2 box vandhuchu", "பிஸ்கட் 2 பாக்ஸ் வந்துச்சு", Moves("Biscuit", "48")),
        // vegetables
        Case("vegetables", "thakkali 3 kilo vandhuchu", "தக்காளி 3 கிலோ வந்துச்சு", Moves("Tomato", "3")),
        Case("vegetables", "thakkali kaal kilo vithuchu", "தக்காளி கால் கிலோ வித்துச்சு", Moves("Tomato", "-0.25")),
        Case("vegetables", "vengayam 10 kg vandhuchu", "வெங்காயம் 10 கிலோ வந்துச்சு", Moves("Onion", "10")),
        Case("vegetables", "kothamalli 5 kattu vithuchu", "கொத்தமல்லி 5 கட்டு வித்துச்சு", Moves("Coriander Leaves", "-5")),
        Case("vegetables", "vazhaipazham 2 dozen vithuchu", "வாழைப்பழம் 2 டஜன் வித்துச்சு", Moves("Banana", "-24")),
        // dairy
        Case("dairy", "Curd 2 litre vandhuchu", "கர்ட் 2 லிட்டர் வந்துச்சு", Moves("Curd", "2")),
        Case("dairy", "thayir 500 ml vithuchu", "தயிர் 500 எம்எல் வித்துச்சு", Moves("Curd", "-0.5")),
        Case("dairy", "Ghee 1 litre vandhuchu", "கீ 1 லிட்டர் வந்துச்சு", Moves("Ghee", "1000")),
        Case("dairy", "nei 200 ml vithuchu", "நெய் 200 எம்எல் வித்துச்சு", Moves("Ghee", "-200")),
        Case("dairy", "Paneer 500 gram vithuchu", "பனீர் 500 கிராம் வித்துச்சு", Moves("Paneer", "-0.5")),
        // sweets
        Case("sweets", "Mysore Pak 250 gram vithuchu", "மைசூர் பாக் 250 கிராம் வித்துச்சு", Moves("Mysore Pak", "-0.25")),
        Case("sweets", "Laddu 1 dozen vithuchu", "லட்டு 1 டஜன் வித்துச்சு", Moves("Laddu", "-12")),
        // medical
        Case("medical", "Paracetamol 2 box vandhuchu", "பாராசிட்டமால் 2 பாக்ஸ் வந்துச்சு", Moves("Paracetamol", "20")),
        Case("medical", "Paracetamol 3 strip vithuchu", "பாராசிட்டமால் 3 ஸ்ட்ரிப் வித்துச்சு", Moves("Paracetamol", "-3")),
        Case("medical", "Paracetamol 10 tablet vithuchu", "பாராசிட்டமால் 10 டேப்லெட் வித்துச்சு", Asks("tablet", "டேப்லெட்")),
        Case("medical", "Cotton 1 kg vandhuchu", "காட்டன் 1 கிலோ வந்துச்சு", Moves("Cotton", "1000")),
        // hardware / textile / fuel
        Case("hardware", "Rope 15 meter vithuchu", "ரோப் 15 மீட்டர் வித்துச்சு", Moves("Rope", "-15")),
        Case("hardware", "Rope 10 adi vithuchu", "ரோப் 10 அடி வித்துச்சு", Asks("feet", "adi", "அடி")),
        Case("hardware", "Nail arai kilo vithuchu", "நெயில் அரை கிலோ வித்துச்சு", Moves("Nail", "-0.5")),
        Case("hardware", "Paint 4 litre vithuchu", "பெயிண்ட் 4 லிட்டர் வித்துச்சு", Moves("Paint", "-4")),
        Case("textile", "Cotton Cloth 2.5 meter vithuchu", null, Moves("Cotton Cloth", "-2.5")),
        Case("textile", "Cotton Cloth rendara meter vithuchu", "காட்டன் கிளாத் ரெண்டரை மீட்டர் வித்துச்சு", Moves("Cotton Cloth", "-2.5")),
        Case("fuel", "Kerosene 5 litre vithuchu", "கெரசின் 5 லிட்டர் வித்துச்சு", Moves("Kerosene", "-5")),
        // a unit the product can't be counted in, said: asked, never the product's own unit
        Case("grocery", "Sugar 2 bottle vandhuchu", "சுகர் 2 பாட்டில் வந்துச்சு", Asks("bottle", "பாட்டில்")),
        Case("grocery", "Sunflower Oil 2 kg vandhuchu", "சன்ஃப்ளவர் ஆயில் 2 கிலோ வந்துச்சு", Asks("kg", "கிலோ", "litre")),
        // products / brands the shop never fed
        Case("grocery", "Pepsodent 2 box vandhuchu", "பெப்சோடென்ட் 2 பாக்ஸ் வந்துச்சு", NewProduct("Pepsodent")),
        Case("grocery", "Bru coffee 4 packet vandhuchu", "ப்ரூ காபி 4 பாக்கெட் வந்துச்சு", NewProduct("Bru")),
        Case("grocery", "Aashirvaad atta 10 kg vandhuchu", "ஆசீர்வாத் ஆட்டா 10 கிலோ வந்துச்சு", NewProduct("Aashirvaad")),
        Case("grocery", "Gold Winner oil 5 litre vandhuchu", "கோல்ட் வின்னர் ஆயில் 5 லிட்டர் வந்துச்சு", Asks("Gold Winner", "கோல்ட் வின்னர்", "Sunflower Oil")),
        Case("grocery", "Britannia Good Day 2 box vandhuchu", "பிரிட்டானியா குட் டே 2 பாக்ஸ் வந்துச்சு", NewProduct("Britannia")),
    )

    @Test fun everyShopsUnitsTypedAndSpoken() {
        val fails = mutableListOf<String>()
        for (c in cases) for ((way, line) in listOfNotNull("typed" to c.typed, c.mic?.let { "mic" to it })) {
            val inv = Inventory().apply {
                shops.getValue(c.shop).forEachIndexed { i, it -> items += Inventory.Item("i$i", it.name, it.unit, BigDecimal(it.stock), it.conv.mapValues { (_, v) -> BigDecimal(v) }, null, null, "Shop", null) }
            }
            val before = inv.items.associate { it.name to it.stock }
            val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now }, memory = Access(KaiPrivateMemory(InMemoryKaiMemoryStore())))
            val t = runBlocking { kai.ask(line) }
            val text = t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")
            val confirm = t.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") }
            val unchanged = inv.items.all { it.stock.compareTo(before.getValue(it.name)) == 0 } && inv.created.isEmpty()
            val ok = when (val w = c.want) {
                is Moves -> confirm != null && unchanged && run {
                    runBlocking { kai.act(confirm.action, if (way == "mic") KaiLang.TAMIL else KaiLang.TANGLISH) }
                    inv.items.firstOrNull { it.name == w.product }?.let { it.stock.compareTo(before.getValue(w.product) + BigDecimal(w.delta)) == 0 } == true &&
                        inv.items.filter { it.name != w.product }.all { it.stock.compareTo(before.getValue(it.name)) == 0 }
                }
                is Asks -> unchanged && confirm == null && w.words.any { text.contains(it, ignoreCase = true) }
                // A new product: said to be one, its first detail asked — nothing on any product, nothing created before Confirm.
                is NewProduct -> unchanged && confirm == null && listOf("pudhu product", "புது product", "new product").any { text.contains(it, ignoreCase = true) } &&
                    text.contains(w.name, ignoreCase = true) || unchanged && confirm == null && way == "mic" && text.contains("புது product")
            }
            if (!ok) fails += "[${c.shop} · $way] «$line» → ${text.replace("\n", " / ").take(300)}"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
