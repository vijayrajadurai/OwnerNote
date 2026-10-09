package com.shopai.app.brain.tools

/**
 * KAI's own word feed — what shop owners say and what it means, kept in one place so a new word is one more line here,
 * not a code change. No outside model, no paid API: Kai reads only this and its rules.
 *
 * How to feed (one entry per line; `#` starts a note):
 *  - a word list: the word or phrase on its own line ("anuppinaan")
 *  - a meaning list: `word = meaning` ("arisi = rice", "kattu = BUNDLE")
 *
 * Every list is added to the words Kai already knew — nothing here removes or changes them. After feeding, the owner-line
 * test (src/test/resources/kai/owner-lines.txt) must still pass.
 */
object KaiFeed {

    /** Money the owner RECEIVED — "Kumar 500 ____" (the customer paid / sent / gave). */
    val receivedWords: List<String> = lines("""
        anuppinan
        anuppinaan
        anuppichan
        anuppichaan
        anupinan
        kuduthaaru
        kuduthar
        kuduthaanga
        kuduthutaru
        thandhutaan
        thandhaaru
        settle pannitaan
        settle pannitaar
        clear pannitaan
        clear pannitaar
    """)

    /** Money the owner PAID — "ABC Traders-ku 500 ____". */
    val paidWords: List<String> = lines("""
        anuppinen
        anuppichen
        anupiten
        settle panniten
        clear panniten
        kattinen
        katten
        kattiten
    """)

    /** Stock that CAME IN — "Colgate 2 box ____". */
    val stockInWords: List<String> = lines("""
        vandhuchu
        vandhirukku
        irakkinen
        irakkitten
        load vandhuchu
        maal vandhuchu
        வந்துச்சு
        வந்திருக்கு
    """)

    /** Stock that WENT OUT — sold, damaged, used — "Colgate 10 ____". */
    val stockOutWords: List<String> = lines("""
        vithuchu
        vithiruchu
        vithutten
        vithen
        vitten
        udanjiduchu
        udanjuchu
        keduthuchu
        kettupochu
        expiry aagiduchu
        வித்துச்சு
        வித்துடுச்சு
        உடைஞ்சிடுச்சு
    """)

    /** A product's local name = the name the shop keeps it under (Tamil script or Tanglish → English). */
    val productNames: Map<String, String> = pairs("""
        arisi = rice
        ennai = oil
        ennei = oil
        sakkarai = sugar
        sarkarai = sugar
        seeni = sugar
        uppu = salt
        paal = milk
        paruppu = dal
        maavu = atta
        godhumai = wheat
        muttai = egg
        vengayam = onion
        thakkali = tomato
        urulai = potato
        kadalai = groundnut
        sopu = soap
        theeppetti = matchbox
        vellam = jaggery
        puli = tamarind
        milagai = chilli
        milagu = pepper
        manjal = turmeric
        kadugu = mustard
        seeragam = jeera
        அரிசி = rice
        எண்ணெய் = oil
        எண்ணை = oil
        சர்க்கரை = sugar
        சீனி = sugar
        உப்பு = salt
        பால் = milk
        பருப்பு = dal
        மாவு = atta
        முட்டை = egg
        வெங்காயம் = onion
        தக்காளி = tomato
        சோப்பு = soap
        வெல்லம் = jaggery
        புளி = tamarind
        மிளகாய் = chilli
        மஞ்சள் = turmeric
    """)

    /** A unit word = Kai's unit (PCS, BOX, BAG, KG, GRAM, LITRE, ML, PACKET, BOTTLE, DOZEN, BUNDLE, METER …). */
    val units: Map<String, String> = pairs("""
        pottalam = PACKET
        pottalangal = PACKET
        sachet = PACKET
        sachets = PACKET
        jar = BOTTLE
        jars = BOTTLE
        tin = BOTTLE
        tins = BOTTLE
    """)

    /** Words that are never a person's name ("Yeppa", "Innaiku", "Friday" …). */
    val notNames: Set<String> = words("""
        yeppa yeppo yepo eppadi yeppadi innaiku naalaiku nalaiku
        monday tuesday wednesday thursday friday saturday sunday
        general generala yarlam yaarlam yaarellam yarukita yarukitta
        late correct usually phone number mobile contact
    """)

    /** Everyday words Kai reads without asking what they mean. */
    val everydayWords: Set<String> = words("""
        anuppu anuppinen anuppinan vithuchu vithen settle clear credit kadan udhaar
        fast slow move moving dead vikkala vikkudhu
    """)

    // ------------------------------------------------------------------ reading the feed

    private fun clean(text: String): List<String> = text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    private fun lines(text: String): List<String> = clean(text).map { it.lowercase() }

    private fun words(text: String): Set<String> = clean(text).flatMap { it.lowercase().split(Regex("""\s+""")) }.toSet()

    private fun pairs(text: String): Map<String, String> = clean(text).mapNotNull { line ->
        val (k, v) = line.split('=', limit = 2).map { it.trim() }.takeIf { it.size == 2 && it.all(String::isNotEmpty) } ?: return@mapNotNull null
        k.lowercase() to v
    }.toMap()
}
