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

    @Test
    fun `a clock time is never a customer named Mani`() {
        val known = listOf("Mani", "Kumar")
        assertEquals("Kumar", KaiUnderstanding.knownPerson("naalaiku 10 manikku Kumar ku call panna remind pannu", known))
        assertEquals("Kumar", KaiUnderstanding.knownPerson("naalaiku 10 manikku குமார் ku call panna remind pannu", known))
        assertEquals("Kumar", KaiUnderstanding.knownPerson("குமார் kitta payment vaanga naalaiku kaalaila 9 manikku remind pannu", known))
        assertEquals("Mani", KaiUnderstanding.knownPerson("naalaiku 10 manikku Mani ku call panna remind pannu", known))
        assertEquals("Mani", KaiUnderstanding.knownPerson("Mani 500 kuduthan", known))
    }

    // Owner's phone (10 Oct 2026): spoken "அறிவுக்கு 7000 தரணும்" became Ravi's entry. A name starting with a vowel sound is
    // never one that doesn't.
    @Test
    fun arivuIsNotRavi() {
        assertFalse(NameSound.same("அறிவு", "Ravi"))
        assertFalse(NameSound.same("Arivu", "ரவி"))
        assertTrue(NameSound.same("அறிவு", "Arivu"))
        assertTrue(NameSound.same("ரவி", "Ravi"))
        assertTrue(NameSound.same("அபி", "Abi"))
    }
}
