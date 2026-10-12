package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/** An entry said in other words is said again the one way Kai's entry rules read (owner's dataset, 12 Oct 2026). */
class KaiLedgerPhrasesTest {
    private fun c(s: String) = KaiLedgerPhrases.canonical(s)

    @Test fun thePersonOwesTheOwner() {
        assertEquals("Kumar enakku 1500 tharanum", c("Kumar innum 1500 rooba tharala"))
        assertEquals("Ravi enakku 800 tharanum", c("Ravi thara vendiya 800 roobaya kanakkula podu"))
        assertEquals("Ravi enakku 10000 tharanum", c("Ravikitta irundhu 10000 rooba vaanganum"))
        assertEquals("Kumar enakku 80 tharanum", c("Kumar kitta vara vendiya thogaiya 80 rooba adhigapaduthu"))
        assertEquals("Ravi enakku 700 tharanum", c("Ravi baakiyoda kooduthalaa 700 rooba varavu vai"))
        assertEquals("Kumar enakku 2500 tharanum", c("vegamaa Kumar enaku thara vendiya kanakkula innum 2500 rooba seru"))
        // The mic's Tamil after KaiSpokenWords: the common words stay in Tamil and are read the same.
        assertEquals("Ravi enakku 800 tharanum", c("Ravi தர vendiya 800 roobaya kanakkula podu"))
        assertEquals("Ravi enakku 700 tharanum", c("Ravi baakiyoda kooduthalaa 700 rupees வரவு வை"))
    }

    @Test fun theOwnerOwesThePerson() {
        assertEquals("naan Kumarku 150 tharanum", c("Kumarku innum 150 rooba kudukala"))
        assertEquals("naan Ravi-ku 4000 tharanum", c("Ravi-ku 4000 rupees baaki irukku"))
        assertEquals("naan ABC Traders ku 600 tharanum", c("ABC Traders ku 600 rooba settle pannanum"))
        assertEquals("naan Kumarku 7500 tharanum", c("Kumarku kudukka vendiya baakiya 7500 rooba kootti podu"))
        assertEquals("naan Raviku 1200 tharanum", c("Raviku thara vendiya thogaiyoda 1200 rooba kooda seru"))
        // "enaku Naan Kumarku 10000 kudukanum": the leading "enaku" is not "to me".
        assertEquals("Naan Kumarku 10000 rooba kudukanum", c("enaku Naan Kumarku 10000 rooba kudukanum"))
        // "மணிக்கு" read as the time word "manikku": at the start of a line with an amount, it is Mani-ku.
        assertEquals("naan Maniku 450 tharanum", c("manikku 450 rupees baaki irukku"))
    }

    @Test fun paymentsBothWays() {
        assertEquals("Ravi 450 kuduthaan", c("Ravi baakila 450 rooba kattittaaru"))
        assertEquals("Ravi 5000 kuduthaan", c("Ravi kudutha 5000 roobaya varavula podu"))
        assertEquals("Raviku 450 kuduthen", c("Raviku kudutha 450 roobaya selavula record pannu"))
        assertEquals("Kumarku 3000 kuduthen", c("Kumarku 3000 rooba settle pannitten"))
        assertEquals("Raviku 5000 kuduthen", c("Raviku 5000 rooba anuppitten"))
    }

    @Test fun questionsAboutWhatTheOwnerOwes() {
        assertEquals("Kumar-ku evlo kudukkanum?", c("Naan Kumarku evlo tharanum?"))
        assertEquals("Kumar-ku evlo kudukkanum", c("Kumarku evlo kudukkanum"))
        // A pronoun is the person already talked about — left as said.
        assertEquals("naan avarukku evlo tharanum?", c("naan avarukku evlo tharanum?"))
    }

    @Test fun everythingElseIsLeftAsSaid() {
        for (s in listOf("Kumar evlo tharanum", "Kumar 2000 kuduthan", "5 manikku Kumar-ku call pannu", "innaikku arisi 500 rooba selavaachu",
                "Colgate 2 box vandhuchu", "naalaikku 10 manikku Kumar call")) assertEquals(s, c(s))
    }
}
