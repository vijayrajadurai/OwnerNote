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
        kattinan
        kattinaan
        kattinar
        kattinaar
        kattinanga
        kattinaanga
        katti tan
        katti taan
        kattitaan
        kattittan
        kattittaan
        kattitaru
        kudthan
        kudthaan
        kudhuthan
        kudhuthaan
        kodthan
        kodthaan
        kodhuthan
        pottan
        pottaan
        pottutan
        pottutaan
        pottaanga
        pottanga
        pottaaru
        adachitan
        adachitaan
        adaichitan
        adaichitaan
        adachaan
        adachan
        transfer pannan
        transfer pannaan
        transfer pannitan
        transfer pannitaan
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
        anuppiachu
        anupiachu
        anuppiyachu
        anuppiyaachu
        anuppiyachu
        neft panniten
        neft pannen
        imps panniten
        imps pannen
        rtgs panniten
        rtgs pannen
        kudthen
        kudhuthen
        kodthen
        kodhuthen
            potten
        pottuten
        adachiten
        adaichiten
        adachen
        transfer panniten
        transfer pannen
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
        # English goods said in Tamil letters (speech-to-text)
        ரைஸ் = rice
        ஆயில் = oil
        சுகர் = sugar
        டீ = tea
        தூள் = powder
        பவுடர் = powder
        பைப் = pipe
        பெயிண்ட் = paint
        சட்டை = shirt
        டால் = dal
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
        ippo ipo ippa ipa ippodhu ippothu ipodhu indru inru
        hmm hm hmmm umm um mm ahh aah haan han oh ohh ayyo ayyoo seri sari ok okay
        enaku yenaku yenakku ennaku enakku
        # Tamil-script "me / you / him / who / today …" — and what is left of them once -க்கு is read as the dative ("என|க்கு").
        நான் நா என் என எனக் எனக்கு எனக்கும் நீ நீங்க உன் உன உனக் உனக்கு உங்க உங்களுக்கு நம்ம நமக்கு
        அவன் அவன அவனுக்கு அவள் அவள அவளுக்கு அவர் அவர அவருக்கு அவங்க அவங்களுக்கு
        யார் யாரு யாருக்கு யார யாருகிட்ட இன்னைக்கு இன்னை நாளைக்கு நாளை மணி மணிக்கு கடை கடைக்கு
        மொத்தம் மொத்த மொத்தமா எவ்வளவு எவ்ளோ என்ன எப்போ எப்ப எப்படி எந்த இந்த அந்த எல்லாம் எல்லாரும் எல்லாருக்கும்
        பாக்கி பேலன்ஸ் கடன் பணம் காசு ரூபாய் ஸ்டாக் சரக்கு பொருள் வாரம் மாசம் இன்று நேற்று நேத்து
    """)

    /** How owners actually type a word = the word Kai's rules read ("totel" = total). Whole words only. */
    val spellings: Map<String, String> = pairs("""
        totel = total
        totol = total
        totaal = total
        tottal = total
        totala = total
        totalaa = total
        totela = total
        totelaa = total
        pendig = pending
        pendng = pending
        pendin = pending
        balence = balance
        ballance = balance
        balanse = balance
        paymnet = payment
        payement = payment
        pament = payment
        paymet = payment
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
