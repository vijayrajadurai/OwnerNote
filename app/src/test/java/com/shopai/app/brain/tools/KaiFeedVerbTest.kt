package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A verb however it is typed (owner, 11 Oct 2026: "kututhutanga", "vandhuruchi" were not understood). */
class KaiFeedVerbTest {
    @Test fun giveSpelledAnyWay() {
        assertEquals("kuduthutaanga", KaiFeed.verbSpelling("kututhutanga"))
        assertEquals("kuduthan", KaiFeed.verbSpelling("kudthan"))
        assertEquals("kuduthaan", KaiFeed.verbSpelling("koduthaan"))
        assertEquals("kuduthaanga", KaiFeed.verbSpelling("kuduthanga"))
        assertNull(KaiFeed.verbSpelling("kuduthaan"))
    }

    @Test fun cameSpelledAnyWay() {
        for (w in listOf("vandhuchi", "vandhuruchi", "vanthuruchu", "vandhuduchu", "vanthuchu")) assertEquals(w, "vandhuchu", KaiFeed.verbSpelling(w))
        assertNull(KaiFeed.verbSpelling("vandhuruku"))
    }

    @Test fun otherWordsStay() {
        for (w in listOf("Kuttappan", "kudukkanum", "kodukkanum", "kutti", "Kumar", "kudumbam", "vandi")) assertNull(w, KaiFeed.verbSpelling(w))
    }
}
