package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.KaiServices
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.data.model.DailyCashTotals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The beauty parlour & salon master list (owner's PDF, 19 Oct 2026): every service on it is Kai's by its own name, listed on the
 * Daily Sales Note's Menu for the owner to pick and price (never a sample price), and sold by chat typed and as the ta-IN mic
 * writes it. "Hand wax" is full arms or half arms — asked, never guessed ("hand wax correct-ah work aagudhaa paaru"). A word
 * that is not a service is never turned into one. Nothing is written before Confirm.
 */
class KaiSalonServicesTest {
    private val now = LocalDateTime.of(2026, 10, 19, 11, 0)
    private val today = now.toLocalDate()

    private class Note {
        data class Entry(val day: LocalDate, val amount: BigDecimal, val upi: Boolean, val note: String)
        val entries = mutableListOf<Entry>()
        val opening = HashMap<LocalDate, BigDecimal>()
        fun view(day: LocalDate): CashNoteView {
            val es = entries.filter { it.day == day }
            val cash = es.sumOf { it.amount.toDouble() }
            return CashNoteView(day, es.size, DailyCashTotals(cash, 0.0, cash, 0.0, 0.0, 0.0, cash, 0.0, cash), opening[day]?.toDouble(),
                opening[day]?.toDouble()?.plus(cash), false, es.mapIndexed { i, e -> CashLine(true, e.amount.toDouble(), e.upi, e.note, "2026-10-19T0$i:00:00Z") })
        }
    }

    private class Books(val note: Note) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
        override suspend fun cashNote(day: LocalDate) = note.view(day)
    }

    private class Tools(val inv: Inventory, val note: Note, val category: String) : KaiTools by inv {
        override suspend fun cashOpening(day: LocalDate) = note.opening[day] ?: BigDecimal.ZERO
        override suspend fun setCashOpening(day: LocalDate, amount: BigDecimal): ActionOutcome { note.opening[day] = amount; return ActionOutcome.Done("k", amount) }
        override suspend fun addCashSale(day: LocalDate, amount: BigDecimal, upi: Boolean, note: String): ActionOutcome {
            this.note.entries += Note.Entry(day, amount, upi, note); return ActionOutcome.Done("c${this.note.entries.size}", amount)
        }
        override suspend fun sellsServices() = category == "BEAUTY_SALON"
        override suspend fun businessCategory() = category
    }

    private class Access(val memory: KaiPrivateMemory) : KaiMemoryAccess {
        override suspend fun current() = memory.also { it.open("salon-1", "owner-1") }
        override suspend fun entities(): List<KnownEntity> = emptyList()
    }

    private inner class Shop(category: String = "BEAUTY_SALON") {
        val note = Note()
        val memory = KaiPrivateMemory(InMemoryKaiMemoryStore())
        val tools = Tools(Inventory(), note, category)
        val kai = KaiAgent(KaiBusinessBrain(Books(note)), Books(note), tools, { now }, memory = Access(memory))
        var last: KaiTurn? = null
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }.also { last = it }
        fun confirm(): KaiTurn {
            val b = last?.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") } ?: error("no Confirm: ${all(last)}")
            return runBlocking { kai.act(b.action, KaiLang.TANGLISH)!! }.also { last = it }
        }
        fun menu(vararg items: Pair<String, String>) = runBlocking {
            memory.open("salon-1", "owner-1"); items.forEach { (n, p) -> KaiMenu.save(memory, n, BigDecimal(p)) }
        }
    }

    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")
    private fun bothWays(typed: String): List<String> = listOfNotNull(typed, KaiMicForm.of(typed)?.takeIf { it != typed })

    @Test fun everyServiceOnTheMasterListIsKais() {
        val names = KaiServices.catalogue.map { it.name.lowercase() }.toSet()
        for (n in listOf("Full Arms Waxing", "Half Arms Waxing", "Full Legs Waxing", "Underarms Waxing", "Rica Waxing", "Gold Facial", "Fruit Facial",
            "Keratin Treatment", "Nanoplastia", "Root Touch-Up", "Upper Lip Threading", "Spa Pedicure", "Gel Nail Extensions", "HD Makeup", "Saree Draping",
            "Bridal Mehendi", "Fade Haircut", "Clean Shave", "Foot Reflexology", "Eyelash Extensions", "Ear Piercing", "Express Grooming Package"))
            assertTrue(n, n.lowercase() in names)
        // On the Menu's list, by the master list's sections — names only, never a price.
        val list = KaiServices.menuList(KaiServices.kindsFor("BEAUTY_SALON")).toMap()
        assertTrue(list.keys.toString(), list.keys.containsAll(listOf("Hair Services", "Facial & Skin Care", "Waxing & Hair Removal", "Mehendi Services", "Body Spa & Massage")))
        assertTrue(list["Waxing & Hair Removal"].toString(), list.getValue("Waxing & Hair Removal").containsAll(listOf("Full Arms Waxing", "Half Arms Waxing")))
        assertTrue("never an ask-word on the list", list.values.flatten().none { it == "Arms waxing" })
        // Typing on the Menu: "wax" / "gold" bring the services up.
        assertTrue(KaiServices.suggestions("gold", KaiServices.kindsFor("BEAUTY_SALON")).contains("Gold Facial"))
        assertTrue(KaiServices.suggestions("hand wax", KaiServices.kindsFor("BEAUTY_SALON")).containsAll(listOf("Full Arms Waxing", "Half Arms Waxing")))
    }

    @Test fun handWaxIsAskedFullOrHalfAndSavedOnlyOnConfirm() {
        for (line in bothWays("hand wax 300") + "ஹேண்ட் வாக்ஸ் முன்னூறு" + "கை வாக்ஸ் 300") for (answer in listOf("full", "ஃபுல்", "muzhusa")) {
            val s = Shop()
            val ask = s.say(line)
            assertTrue("$line → ${all(ask)}", all(ask).contains("Full Arms Waxing") && all(ask).contains("Half Arms Waxing") && ask.reply.mood == com.shopai.app.brain.KaiMood.CLARIFY)
            assertTrue("[$line] nothing before Confirm", s.note.entries.isEmpty())
            val draft = s.say(answer)
            assertTrue("[$line / $answer] draft: ${all(draft)}", all(draft).contains("₹300") && all(draft).contains("Full Arms Waxing") && draft.card?.buttons?.isNotEmpty() == true)
            assertTrue("[$line] nothing before Confirm", s.note.entries.isEmpty())
            s.confirm()
            assertEquals("[$line / $answer]", "Full Arms Waxing ×1 ₹300", s.note.entries.single().note)
        }
    }

    @Test fun halfHandWaxAndFullHandWaxAreTheirOwnService() {
        for ((typed, want) in listOf("half hand wax 200" to "Half Arms Waxing", "full hand wax 350" to "Full Arms Waxing", "full leg wax 500" to "Full Legs Waxing",
            "underarm wax 100" to "Underarms Waxing")) for (line in bothWays(typed)) {
            val s = Shop()
            val draft = s.say(line)
            val amount = Regex("""\d+""").find(typed)!!.value
            assertTrue("$line → ${all(draft)}", all(draft).contains(want) && all(draft).contains("₹$amount") && draft.card?.buttons?.isNotEmpty() == true)
            s.confirm()
            assertEquals(line, "$want ×1 ₹$amount", s.note.entries.single().note)
        }
        // "arai kai wax" / "அரை கை வாக்ஸ்": half.
        for (line in listOf("arai kai wax 200", "அரை கை வாக்ஸ் 200")) {
            val s = Shop()
            assertTrue("$line → ${all(s.say(line))}", all(s.last).contains("Half Arms Waxing") && all(s.last).contains("₹200"))
        }
    }

    @Test fun theOwnersOwnHandWaxOnTheMenuIsSoldAtTheirPrice() {
        for (line in bothWays("hand wax onnu") + "ஹேண்ட் வாக்ஸ் ஒன்னு") {
            val s = Shop()
            s.menu("Hand Wax" to "280")
            val draft = s.say(line)
            assertTrue("$line → ${all(draft)}", all(draft).contains("Hand Wax") && all(draft).contains("₹280") && draft.card?.buttons?.isNotEmpty() == true)
            s.confirm()
            assertEquals(line, "Hand Wax ×1 ₹280", s.note.entries.single().note)
        }
        // A service picked from the Menu's list ("Full Arms Waxing") is sold by how the owner says it: "full hand wax onnu".
        for (line in bothWays("full hand wax onnu") + "ஃபுல் ஹேண்ட் வாக்ஸ் ஒன்னு") {
            val s = Shop()
            s.menu("Full Arms Waxing" to "400")
            val draft = s.say(line)
            assertTrue("$line → ${all(draft)}", all(draft).contains("Full Arms Waxing") && all(draft).contains("₹400") && draft.card?.buttons?.isNotEmpty() == true)
        }
        // Full / half on the Menu: "hand wax" asks between the owner's two, with their prices.
        for (line in bothWays("hand wax onnu")) {
            val s = Shop()
            s.menu("Full Arms Waxing" to "400", "Half Arms Waxing" to "250")
            val ask = s.say(line)
            assertTrue("$line → ${all(ask)}", all(ask).contains("₹400") && all(ask).contains("₹250"))
            val draft = s.say("half")
            assertTrue("$line half → ${all(draft)}", all(draft).contains("Half Arms Waxing") && all(draft).contains("₹250"))
        }
    }

    @Test fun masterListServicesAreSoldTypedAndByMic() {
        for ((typed, mic, want) in listOf(
            Triple("gold facial 600", "கோல்ட் ஃபேஷியல் 600", "Gold Facial"),
            Triple("keratin treatment 2500", "கெரட்டின் ட்ரீட்மென்ட் 2500", "Keratin Treatment"),
            Triple("upper lip 30", "அப்பர் லிப் 30", "Upper Lip Threading"),
            Triple("spa pedicure 700", "ஸ்பா பெடிக்யூர் 700", "Spa Pedicure"),
        )) for (line in listOf(typed, mic)) {
            val s = Shop()
            val draft = s.say(line)
            val amount = Regex("""\d+""").find(typed)!!.value
            assertTrue("$line → ${all(draft)}", all(draft).contains(want) && all(draft).replace(",", "").contains("₹$amount"))
        }
    }

    @Test fun aWordNotOnTheListIsNeverTurnedIntoAService() {
        // "hand" alone, "wax" said as a thing the shop keeps, a person's name: never Full Arms Waxing / Hand Massage.
        for (line in listOf("hand bag 300", "ஹேண்ட் பேக் 300")) {
            val s = Shop()
            val t = s.say(line)
            assertTrue("$line → ${all(t)}", !all(t).contains("Arms Waxing") && !all(t).contains("Hand Massage") && s.note.entries.isEmpty())
        }
        // A grocery: "hand wax" is not its service — nothing written.
        for (line in bothWays("hand wax 300")) {
            val s = Shop("GROCERY")
            val t = s.say(line)
            assertTrue("[grocery] $line → ${all(t)}", s.note.entries.isEmpty() && t.card?.buttons.isNullOrEmpty())
        }
    }

    /**
     * Release check (20 Oct 2026): a count or a "did it" word between the service and its price — "gold facial onnu 900", "keratin
     * panninom 3500" — is still that price (Kai asked "evlo?" again); and the mic's "ஃபுல் கால் வாக்ஸ்" is Full Legs Waxing (கால் = leg).
     */
    @Test fun thePriceSaidAfterACountOrAVerbIsThePrice() {
        for ((typed, want, amount) in listOf(Triple("gold facial onnu 900", "Gold Facial", "900"), Triple("keratin panninom 3500", "Keratin Treatment", "3500"),
            Triple("pedicure onnu 450", "Pedicure", "450"), Triple("full kaal wax 500", "Full Legs Waxing", "500"))) for (line in bothWays(typed)) {
            val s = Shop()
            val draft = s.say(line)
            val shown = "₹" + "%,d".format(amount.toInt())
            assertTrue("$line → ${all(draft)}", all(draft).contains(want) && all(draft).contains(shown) && draft.card?.buttons?.isNotEmpty() == true)
            assertTrue("[$line] nothing before Confirm", s.note.entries.isEmpty())
            s.confirm()
            assertEquals(line, "$want ×1 ₹$amount", s.note.entries.single().note)
        }
        for (line in listOf("ஃபுல் கால் வாக்ஸ் 500", "கோல்ட் ஃபேஷியல் ஒன்னு 900")) {
            val s = Shop()
            val draft = s.say(line)
            assertTrue("$line → ${all(draft)}", (all(draft).contains("Full Legs Waxing") || all(draft).contains("Gold Facial")) && draft.card?.buttons?.isNotEmpty() == true &&
                !all(draft).contains("evlo") && !all(draft).contains("எவ்வளவு"))
        }
        // A word Kai doesn't know there ("semma") is never read as a different price: nothing saved, any draft at the price said.
        Shop().let { s -> val t = s.say("keratin semma 3500"); assertTrue(all(t), s.note.entries.isEmpty() && (t.card == null || all(t).contains("₹3,500"))) }
        // (a small number after a service is still how many — "head massage 2" is two, never ₹2; and two services keep their own prices)
        val s = Shop()
        val two = s.say("gold facial 900 pedicure 450")
        assertTrue(all(two), all(two).contains("₹900") && all(two).contains("₹450"))
    }
}
