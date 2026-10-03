package com.shopai.app.util

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiUnderstanding
import com.shopai.app.data.model.PartySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A name in English letters finds the same customer saved in Tamil (and back) — never a different person. */
class NameSoundTest {
    @Test
    fun sameNameAcrossScripts() {
        listOf(
            "Kumar" to "குமார்", "Ganesh" to "கணேஷ்", "Selvam" to "செல்வம்", "Dinesh" to "தினேஷ்",
            "Nadhiya" to "நதியா", "Nadiya" to "நதியா", "Deekshitha" to "தீக்ஷிதா", "Silver" to "சில்வர்", "Abi" to "அபி",
        ).forEach { (en, ta) ->
            assertTrue("$en ~ $ta", NameSound.same(en, ta))
            assertTrue("$ta ~ $en", NameSound.same(ta, en))
        }
    }

    @Test
    fun differentPeopleStayDifferent() {
        assertFalse(NameSound.same("Kumar", "குமரன்"))
        assertFalse(NameSound.same("Ramesh", "குமார்"))
        assertFalse(NameSound.same("Appu", "அபி"))
        assertFalse(NameSound.same("Kumar", "Kamar"))
        assertFalse(NameSound.same("Selvam", "செல்வி"))
    }

    @Test
    fun kaiFindsTheTamilCustomer() {
        val known = listOf("குமார்", "செல்வம்", "mani")
        assertEquals("குமார்", KaiUnderstanding.knownPerson("Kumar kita evlo pending?", known))
        assertEquals("குமார்", KaiUnderstanding.knownPerson("Kumar-ku 10 minutes kalichi call panna remind pannu", known))
        assertEquals("செல்வம்", KaiUnderstanding.knownPerson("Selvam kitta 2000 vanginen", known))
        assertEquals("mani", KaiUnderstanding.knownPerson("mani evlo tharanum", known))
        val ledger = BusinessSnapshot(customers = listOf(PartySummary("c1", "குமார்", null, 2000.0, null)))
        assertEquals("c1", ledger.find("Kumar").single().id)
    }
}
