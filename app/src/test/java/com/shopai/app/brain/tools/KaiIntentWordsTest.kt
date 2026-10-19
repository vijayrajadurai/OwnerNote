package com.shopai.app.brain.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The owner's own words for an intent, read as Kai's rules read them (owner, 19 Oct 2026: "words maaralaam, intent purinjikkanum")
 * — and the guards that keep a rewrite from changing what was meant (principle 3: never guess).
 */
class KaiIntentWordsTest {
    private fun c(s: String) = KaiIntentWords.canonical(s)

    @Test fun stockFamilies() {
        assertEquals("Colgate 2 box vandhuchu", c("Colgate 2 box irakkumadhi aachu"))
        assertEquals("Oil 1 box kadaikku vandhuchu", c("Oil 1 box kadaikku serndhuchu"))
        assertEquals("Maggi 40 vandhuchu", c("Maggi 40 rack-la adukkinen"))
        assertEquals("Lux soap 10 vithuchu", c("Lux soap 10 theernjuduchu"))
        assertEquals("Rice 5 kg veetukku pochu", c("Rice 5 kg veetukku eduthutten"))
        assertEquals("Oil 1 box return anuppitten", c("Oil 1 box thirupi anuppinen"))
        assertEquals("Rice 3 kg wastage aachu", c("Rice 3 kg eli kadichiduchu"))
        assertEquals("Oil 2 bottle damage aachu", c("Oil 2 bottle oluguchu"))
    }

    @Test fun remindersOnlyWithATimeAndNeverAQuestion() {
        assertEquals("naalaiku kaalai 9 manikku Kumar-kitta panam kekka remind pannu", c("naalaiku kaalai 9 manikku Kumar-kitta panam kekka ezhuppi vidu"))
        assertEquals("Friday saayangaalam 6 manikku current bill katta remind pannu", c("Friday saayangaalam 6 manikku current bill katta sollu"))
        assertEquals("naalanniku kaalai 10 manikku Selvam veetuku poganum remind pannu", c("naalanniki kaalai 10 manikku Selvam veetuku poganum eluthi vei"))
        assertEquals("ovvoru Sani kaalai 8 manikku stock ennanum nu remind pannu", c("ovvoru Saniyum kaalai 8 manikku stock ennanum nu sollu"))
        // a report asked for, a question: unchanged
        assertEquals("innaiku sales sollu", c("innaiku sales sollu"))
        assertEquals("naalaiku yaar tharanum sollu", c("naalaiku yaar tharanum sollu"))
    }

    @Test fun moneyFamilies() {
        assertEquals("Kumar 500 kadan", c("Kumar 500 pakki vechutu ponaan"))
        assertEquals("Kumar-ku 700 note pannu", c("Kumar-ku 700 eluthi vei"))
        assertEquals("Ramesh 1200 note pannu", c("Ramesh 1200 account-la potukko"))
        assertEquals("Lakshmi akka 1000 kuduthaanga", c("Lakshmi akka 1000 varavu vechaanga"))
        assertEquals("Selvam 3000 kuduthaan", c("Selvam paadhi kaasu 3000 kuduthaan"))
        assertEquals("Murugan Stores kitta 6000 ku credit la maal vaanginen", c("Murugan Stores-la 6000-ku saraku kadanukku eduthen"))
        assertEquals("Ramesh rendu aayiram GPay-la pottaan", c("Ramesh rendaayiram GPay-la pottaan"))
        // a new person said as one: the line is about them ("pudhu customer" is never their name)
        assertEquals("Dinesh 600 kadan", c("pudhu customer Dinesh 600 kadan"))
        assertEquals("pudhu customer yaaru", c("pudhu customer yaaru"))
        // an entry, then when it is to be paid: its due day — never a second, opposite line
        assertEquals("Kumar ku 1500 kadan kuduthen due 20th", c("Kumar ku 1500 kadan kuduthen 20th tharuvan"))
        assertEquals("Senthil 5000 kadan due adutha maasam 5", c("Senthil 5000 kadan, adutha maasam 5 tharuvan"))
        assertEquals("Vasanth Traders kitta 5000 ku credit la maal vaanginen due 30th", c("Vasanth Traders kitta 5000 ku credit la maal vaanginen, 30th kudukkanum"))
        // (no entry before it: the owed line itself, read as before)
        assertEquals("Sarath 5000 tharanum December 5", c("Sarath 5000 tharanum December 5"))
        // paid in full, however said — the whole pending (only with no amount: "Ramesh 2000 settle pannitaan" is ₹2,000)
        assertEquals("Kumar full ah kuduthutan", c("Kumar full settlement pannitan"))
        assertEquals("Kumar full ah kuduthutan", c("Kumar ellam clear pannitan"))
        assertEquals("Kumar full ah kuduthutan", c("Kumar kanakku mudichitaan"))
        assertEquals("Kumar full ah kuduthutan", c("Kumar muzhusa kuduthutan"))
        assertEquals("Ramesh 2000 settle pannitaan", c("Ramesh 2000 settle pannitaan"))
    }

    @Test fun linesKaiAlreadyReadsAreLeftAlone() {
        for (s in listOf("Kumar 2000 kuduthan", "Colgate 2 box vandhuchu", "kanakku mudinjiduchu", "Kumar evlo tharanum", "naalaiku 10 manikku Kumar call remind pannu",
                "Colgate stock evlo", "thirumbi vandhuchu")) assertEquals(s, c(s))
    }
}
