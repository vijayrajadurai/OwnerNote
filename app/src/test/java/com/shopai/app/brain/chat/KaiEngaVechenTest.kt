package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.KeptItem
import com.shopai.app.brain.tools.KeptPlace
import com.shopai.app.brain.tools.KeptText
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "Enga Vechen?" (owner, 24 Oct 2026) in Kai Chat, typed and as the mic writes it: a kept thing is a Confirm card first and written
 * only by Confirm ("Save aagiduchu" only when the table said so); asked back with its place and date; moved with its old place kept;
 * closed; listed by place; the full list grouped as the owner's list says (`list_all_answer`); a word of two meanings asked;
 * "X illa Y" on the open card; the place asked when it wasn't said.
 */
class KaiEngaVechenTest {
    private var now = LocalDateTime.of(2026, 8, 12, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = listOf(PartySummary("c1", "Kumar", null, 1000.0, "2026-10-18T00:00:00Z")), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(inv: Inventory) : KaiTools by inv {
        val kept = mutableListOf<KeptItem>()
        var tableWorks = true
        override suspend fun keptItems() = kept.toList()
        override suspend fun saveKept(item: KeptItem): Boolean {
            if (!tableWorks) return false
            kept.removeAll { it.id == item.id }; kept += item; return true
        }
        override suspend fun deleteKept(id: String) = kept.removeAll { it.id == id }
    }

    private inner class Kai {
        val tools = Tools(Inventory())
        val kai = KaiAgent(KaiBusinessBrain(Books(), today = { now.toLocalDate() }), Books(), tools, { now })
        var last: KaiTurn? = null
        fun say(line: String): KaiTurn = runBlocking { kai.ask(line) }.also { last = it }
        fun tap(pick: (KaiAction) -> Boolean): KaiTurn? {
            val b = last?.card?.buttons?.firstOrNull { pick(it.action) } ?: return null
            return runBlocking { kai.act(b.action, KaiLang.TANGLISH) }.also { last = it }
        }
        fun confirm() = tap { it is KaiAction.ConfirmKept }
        fun keep(line: String) { say(line); assertTrue("$line → ${all(last)}", last?.card?.buttons?.any { it.action is KaiAction.ConfirmKept } == true); confirm() }
    }

    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")
    private fun ways(typed: String): List<String> = listOfNotNull(typed, KaiMicForm.of(typed)).distinct()

    @Test fun aKeptThingIsWrittenOnlyByConfirmAndAskedBackWithItsPlaceAndDate() {
        for (line in ways("passport bedroom almirah mela drawer la vechen")) {
            now = LocalDateTime.of(2026, 8, 12, 10, 0)
            val k = Kai()
            val t = k.say(line)
            assertTrue(all(t), t.card?.buttons?.any { it.action is KaiAction.ConfirmKept } == true)
            assertTrue("nothing before Confirm", k.tools.kept.isEmpty())
            assertFalse(all(t), Regex("Save aagiduchu|Save ஆயிடுச்சு").containsMatchIn(all(t)))
            val saved = k.confirm()!!
            assertTrue(all(saved), Regex("Save aagiduchu|Save ஆயிடுச்சு").containsMatchIn(saved.reply.text))
            assertEquals(1, k.tools.kept.size)
            assertEquals("passport", k.tools.kept.single().itemKey)
            now = LocalDateTime.of(2026, 10, 24, 9, 0)
            for (ask in ways("passport enga?")) {
                val a = k.say(ask)
                assertTrue("$ask → ${all(a)}", a.reply.text.contains("Bedroom almirah, mela drawer") && Regex("12 Aug|12 ஆகஸ்ட்").containsMatchIn(a.reply.text))
            }
        }
    }

    @Test fun saveAagiduchuOnlyWhenTheTableSaved() {
        val k = Kai()
        k.tools.tableWorks = false
        k.say("charger hall table la vechen")
        val t = k.confirm()!!
        assertFalse(t.reply.text, t.reply.text.contains("Save aagiduchu"))
        assertTrue(t.reply.text, t.reply.text.contains("Save aagala"))
        assertTrue(k.tools.kept.isEmpty())
    }

    @Test fun notKeptIsSaidPlainlyWithTheClosestBySound() {
        val k = Kai()
        k.keep("passport almirah la vechen")
        val t = k.say("aadhaar card enga")
        assertTrue(all(t), t.reply.text.contains("innum sollala") && t.reply.text.contains("Passport"))
        assertTrue(t.card?.buttons?.any { it.action == KaiAction.Say("passport enga") } == true)
        // A spelling of a kept thing is that thing.
        assertTrue(all(k.say("paasport enga")), k.last!!.reply.text.contains("Almirah"))
    }

    @Test fun aMoveKeepsTheOldPlaceAndAClosedThingIsHidden() {
        for (m in listOf(false, true)) {
            fun said(l: String) = if (m) KaiMicForm.of(l) ?: l else l
            val k = Kai()
            k.keep(said("patta almirah la vechen"))
            val mv = k.say(said("patta ippo bank locker la"))
            assertTrue("$m ${all(mv)}", mv.card?.buttons?.any { it.action is KaiAction.ConfirmKept } == true && Regex("Munnaadi|முன்னாடி").containsMatchIn(all(mv)))
            assertEquals("not moved before Confirm", listOf("almirah"), k.tools.kept.single().placeParts)
            k.confirm()
            val kept = k.tools.kept.single()
            assertEquals(2, kept.placeParts.size)
            assertEquals(listOf("almirah"), kept.history.single().parts)
            val a = k.say(said("patta enga"))
            assertTrue("$m ${all(a)}", Regex("almirah la irundhuchu|almirah-ல இருந்துச்சு").containsMatchIn(a.reply.text))
            // Taken: a Confirm card, then closed — hidden from the list, shown by "pazhaya list kaattu".
            k.say(said("patta eduthuten"))
            assertTrue(k.tools.kept.single().active)
            k.confirm()
            assertFalse(k.tools.kept.single().active)
            assertTrue(all(k.say(said("ennalam nyabagam vechikka sonnen"))), Regex("Innum edhuvum|இன்னும் எதுவும்").containsMatchIn(k.last!!.reply.text))
            assertTrue(all(k.say(said("pazhaya list kaattu"))), Regex("eduthaachu|எடுத்தாச்சு").containsMatchIn(all(k.last)))
        }
    }

    @Test fun aPlaceListsWhatIsInIt() {
        val k = Kai()
        k.keep("passport almirah la vechen")
        k.keep("chain almirah locker la vechen")
        k.keep("charger hall table la vechen")
        for (line in ways("almirah la ennenna irukku")) {
            val t = k.say(line)
            assertTrue("$line → ${all(t)}", all(t).contains("Passport") && all(t).contains("Chain") && !all(t).contains("Charger"))
        }
    }

    @Test fun theFullListIsGroupedPeopleFirstAndOldOnesMarked() {
        now = LocalDateTime.of(2026, 9, 1, 10, 0)
        val k = Kai()
        k.keep("Ravi kitta laptop kuduthen")
        now = LocalDateTime.of(2026, 10, 20, 10, 0)
        k.keep("passport bedroom almirah la vechen")
        k.keep("aadhaar card bedroom drawer la vechen")
        k.keep("medicine kitchen shelf la vechen")
        now = LocalDateTime.of(2026, 10, 24, 10, 0)
        val t = k.say("Kai ennalam nyabagam vechikka sonnen")
        assertTrue(all(t), t.reply.text.startsWith("Neenga 4 porul nyabagam vechikka sonneenga") && t.reply.text.contains("2 bedroom") &&
            t.reply.text.contains("1 kitchen") && t.reply.text.contains("1 per kitta kuduthadhu"))
        val lines = t.card!!.lines
        assertTrue(lines.toString(), lines.first().contains("Aatkal kitta"))
        assertTrue(lines.toString(), lines.any { it.startsWith("⚠") && it.contains("Ravi kitta Laptop") && it.contains("53 naal") })
        // Newest first inside a group: the aadhaar card (same day, kept later) is not after… both bedroom, newest group first.
        assertTrue(lines.toString(), lines.indexOfFirst { it.contains("Bedroom") } < lines.indexOfFirst { it.contains("Kitchen (") } || lines.indexOfFirst { it.contains("Kitchen (") } < 0)
        assertTrue(t.card!!.buttons.any { it.action == KaiAction.OpenRecord("KEPT") })
        // The voice reads the summary only — no item names in the spoken text.
        assertFalse(t.reply.text, t.reply.text.contains("Passport"))
        // Filters.
        assertTrue(all(k.say("yaar yaar kitta enna kuduthen")), k.last!!.reply.text.startsWith("Neenga 1 porul"))
        assertTrue(all(k.say("documents ellam enga irukku")), k.last!!.reply.text.startsWith("Neenga 2 porul"))
        assertTrue(all(k.say("bedroom la ennalam vechen")), k.last!!.reply.text.startsWith("Neenga 2 porul"))
        assertTrue(all(k.say("Ravi kitta ennalam kuduthen")), k.last!!.reply.text.startsWith("Neenga 1 porul"))
        assertTrue(all(k.say("innaiku ennalam vechen")), k.last!!.reply.text.startsWith("Appadi edhuvum illa"))
    }

    @Test fun moreThanTenAreCollapsedToGroups() {
        val k = Kai()
        for (i in 1..12) k.tools.kept += KeptItem("k$i", "porul $i", null, listOf(if (i % 2 == 0) "bedroom" else "kitchen", "box"), null, null, null, now,
            now.plusMinutes(i.toLong()), KeptItem.Status.ACTIVE)
        val t = k.say("ennalam nyabagam vechikka sonnen")
        assertEquals(t.card!!.lines.toString(), 2, t.card!!.lines.size)
        assertTrue(t.card!!.lines.toString(), t.card!!.lines.all { Regex("""\(6\)$""").containsMatchIn(it) })
    }

    @Test fun anEmptyListSaysTheOwnersExample() {
        val t = Kai().say("ennalam nyabagam vechikka sonnen")
        assertEquals("Innum edhuvum nyabagam vechikka sollala. Example: \"Kai, passport almirah la vechen\" nu sollunga.", t.reply.text)
    }

    @Test fun aWordOfTwoMeaningsIsAskedWithButtons() {
        for (line in ways("kannadi drawer la vechen")) {
            val k = Kai()
            val t = k.say(line)
            assertEquals(all(t), KaiMood.CLARIFY, t.reply.mood)
            assertEquals(2, t.card!!.buttons.count { it.action is KaiAction.KeptMeaning })
            k.tap { it is KaiAction.KeptMeaning && it.name == "specs" }
            assertTrue(all(k.last), k.last?.card?.buttons?.any { it.action is KaiAction.ConfirmKept } == true)
            k.confirm()
            assertEquals("specs", k.tools.kept.single().itemKey)
        }
    }

    @Test fun xIllaYChangesTheOpenCardAndAMissingPlaceIsAsked() {
        val k = Kai()
        k.say("passport almirah la vechen")
        val t = k.say("almirah illa cupboard")
        assertTrue(all(t), all(t).contains("Idam: Cupboard") && t.card?.buttons?.any { it.action is KaiAction.ConfirmKept } == true)
        assertTrue(k.tools.kept.isEmpty())
        k.confirm()
        assertEquals(listOf("cupboard"), k.tools.kept.single().placeParts)
        // No place said: asked; the answer is the place.
        val k2 = Kai()
        assertEquals(KaiMood.CLARIFY, k2.say("passport vechen").reply.mood)
        val c = k2.say("almirah mela drawer la")
        assertTrue(all(c), all(c).contains("Almirah, mela drawer") && c.card?.buttons?.any { it.action is KaiAction.ConfirmKept } == true)
        // "seri" right after the card confirms it; "vendam" drops it.
        assertTrue(k2.say("seri").reply.text.contains("Save aagiduchu"))
        val k3 = Kai()
        k3.say("charger hall la vechen")
        k3.say("vendam")
        assertTrue(k3.tools.kept.isEmpty())
    }

    @Test fun aBareFullListIsOnlyThisListRightAfterIt() {
        val k = Kai()
        k.keep("passport almirah la vechen")
        assertTrue(all(k.say("full list kaattu")), k.last!!.reply.text.startsWith("Neenga 1 porul"))
        // Out of the blue, "full list" is not read as this list.
        assertFalse(all(Kai().say("full list kaattu")), Regex("Innum edhuvum nyabagam").containsMatchIn(Kai().say("full list kaattu").reply.text))
    }

    @Test fun theTablesTextFormsComeBack() {
        val h = listOf(KeptPlace(listOf("almirah", "mela"), null, now), KeptPlace(emptyList(), "Ravi", now.plusDays(1)))
        assertEquals(h, KeptText.history(KeptText.history(h)))
        assertEquals(listOf("bedroom", "rack 3"), KeptText.parts(KeptText.parts(listOf("bedroom", "rack 3"))))
        assertNull(com.shopai.app.brain.tools.KaiKept.read(""))
    }
}
