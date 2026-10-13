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
        kattitan
        kattitar
        kattitanga
        kattitaanga
        kattirukkaan
        kattirukkan
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
        # "Lakshmi 300 thandhaa" (owner, 12 Oct 2026: answered as a balance question). A bare "settle" / "clear" goes either way — KaiLedgerPhrases reads it.
        thandhaa
        thandha
        thanthaa
        thandhuttaa
        kuduthaa
        koduthaa
        anuppunaa
        anupunaa
        pottaa
        kattunaa
        kattinaa
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
        vithutom
        vithuttom
        vithom
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

    /**
     * Goods by the name the owner says — Tamil script / Tanglish, one word or several → the English name a shop may keep them
     * under (owner's 8-shop stock list, 12 Oct 2026: "கதவு கீல்" / "kathavu keel" = Door hinge, "அளவு நாடா" = Measuring tape).
     * A name counts only when it is the shop's own product, or a whole part of exactly one ("Chain" of "Necklace / chain");
     * one name for two goods ("kambi" = wire, steel rod) is read by what the shop keeps. English names said in Tamil letters
     * ("ஹெட்லைட்") are not listed — [KaiProductSound] hears them.
     */
    val goodsNames: List<Pair<List<String>, String>> = meanings("""
        ஆணி / aani = nail
        திருகு / thirugu = screw
        கதவு கீல் / kathavu keel = door hinge
        கதவு பூட்டு / kathavu pootu = door lock
        கதவு கைப்பிடி / kathavu kaipidi = door handle
        சுத்தியல் / suthiyal = hammer
        இடுக்கி / idukki = pliers
        அளவு நாடா / alavu naada = measuring tape
        ரம்பம் / rambam = hacksaw
        கம்பி / kambi = wire
        மின் சுவிட்ச் / min switch = electrical switch
        மின் பிளக் / min plug = electrical plug
        மின் விளக்கு / min vilakku = light bulb
        தண்ணீர் குழாய் / thanneer kuzhaai = water pipe
        குழாய் இணைப்பு / kuzhaai inaippu = pipe fitting
        குழாய் வால்வு / kuzhaai valve = pipe valve
        சுவர் புட்டி / suvar putty = wall putty
        ஒட்டும் பசை / ottum pasai = adhesive
        கையுறை / kaiyurai = gloves
        அரிசி / arisi = rice
        பச்சரிசி / pachcharisi = raw rice
        புழுங்கல் அரிசி / puzhungal arisi = boiled rice
        இட்லி அரிசி / idli arisi = idli rice
        பாசுமதி அரிசி / basmati arisi = basmati rice
        கோதுமை மாவு / gothumai maavu = wheat flour
        மைதா / maida = refined flour
        ரவை / ravai = semolina
        அரிசி மாவு / arisi maavu = rice flour
        துவரம் பருப்பு / thuvaram paruppu = toor dal
        உளுந்து / ulundhu = urad dal
        பாசிப்பருப்பு / paasi paruppu = moong dal
        கடலைப்பருப்பு / kadalai paruppu = chana dal
        கொண்டைக்கடலை / kondaikadalai = chickpeas
        பச்சைப் பயறு / pachchai payaru = green gram
        சமையல் எண்ணெய் / samayal ennai = cooking oil
        கடலை எண்ணெய் / kadalai ennai = groundnut oil
        நல்லெண்ணெய் / nallennai = sesame oil
        தேங்காய் எண்ணெய் / thengai ennai = coconut oil
        சர்க்கரை / sarkkarai = sugar
        உப்பு / uppu = salt
        வெல்லம் / vellam = jaggery
        மிளகாய்த் தூள் / milagai thool = chilli powder
        மஞ்சள் தூள் / manjal thool = turmeric powder
        மல்லித் தூள் / malli thool = coriander powder
        சீரகம் / seeragam = cumin
        கடுகு / kadugu = mustard seeds
        புளி / puli = tamarind
        தேயிலை / theyilai = tea
        காபித் தூள் / coffee thool = coffee powder
        பால் / paal = milk
        தயிர் / thayir = curd
        பற்பசை / parpasaai = toothpaste
        துணி துவைக்கும் தூள் / thuni thuvaikkum thool = detergent powder
        பாத்திரம் கழுவும் திரவம் / paathiram kazhuvum thiravam = dishwashing liquid
        தரை சுத்தம் செய்யும் திரவம் / tharai sutham seyyum thiravam = floor cleaner
        பிரேக் ஆயில் / brake oil = brake fluid
        குளிரூட்டி திரவம் / kulirootti thiravam = coolant
        எரிபொருள் ஃபில்டர் / eriporul filter = fuel filter
        கண்ணாடி / kannadi = mirror
        பொன்னி பச்சரிசி / ponni pachcharisi = ponni raw rice
        பொன்னி புழுங்கல் அரிசி / ponni puzhungal arisi = ponni boiled rice
        கைக்குத்தல் அரிசி / kaikuthal arisi = hand-pounded rice
        சிவப்பரிசி / sivapparisi = red rice
        கருப்பு அரிசி / karuppu arisi = black rice
        கேரளா மட்டை அரிசி / kerala matta arisi = kerala matta rice
        புதிய அரிசி / pudhu arisi = new-season rice
        பழைய அரிசி / pazhaya arisi = aged rice
        நெல் / nel = paddy
        அரிசி தவிடு / arisi thavidu = rice bran
        அரிசி உமி / arisi umi = rice husk
        கோதுமை / gothumai = wheat
        கம்பு / kambu = pearl millet
        கேழ்வரகு / kelvaragu = finger millet
        சோளம் / cholam = sorghum
        தினை / thinai = foxtail millet
        சாமை / saamai = little millet
        மூட்டை / mootai = sack
        அரிசிப் பை / arisi pai = rice bag
        சட்டை / sattai = shirt
        பேண்ட் / pant = trousers
        வேட்டி / vetti = dhoti
        புடவை / pudavai = saree
        ரவிக்கை / ravikkai = blouse
        சுடிதார் / chudidhar = salwar suit
        குர்த்தி / kurthi = kurti
        துப்பட்டா / thuppatta = dupatta
        நைட்டி / nighty = nightdress
        உள்ளாடை / ulladai = innerwear
        குழந்தைகள் உடை / kuzhandhaigal udai = kidswear
        பள்ளிச் சீருடை / palli seerudai = school uniform
        காலுறை / kaalurai = socks
        பணப்பை / panappai = wallet
        கைப்பை / kaippai = handbag
        காதணி / kaadhani = earrings
        வளையல் / valaiyal = bangles
        மோதிரம் / mothiram = ring
        முடி கிளிப் / mudi clip = hair clip
        ரப்பர் பேண்ட் / rubber band = hair tie
        கண்ணாடி / kannadi = sunglasses
        கைக்கடிகாரம் / kaikadigaaram = wristwatch
        தையல் நூல் / thaiyal nool = sewing thread
        ஊசி / oosi = needle
        எம்.டி.எஃப். பலகை / mdf palagai = mdf board
        பிளாக்போர்டு / blackboard = blockboard
        மரப் பலகை / mara palagai = wooden board
        மரக் கட்டை / mara kattai = timber
        மரப் பட்டை / mara pattai = wooden batten
        மரப் பசை / mara pasai = wood adhesive
        மர பாலிஷ் / mara polish = wood polish
        மர வார்னிஷ் / mara varnish = wood varnish
        கேபினெட் கீல் / cabinet keel = cabinet hinge
        மரத் திருகு / mara thirugu = wood screw
        பிளைவுட் வெட்டும் கருவி / plywood vettum karuvi = plywood cutting tool
        தரை ஓடு / tharai odu = floor tile
        சுவர் ஓடு / suvar odu = wall tile
        செராமிக் ஓடு / ceramic odu = ceramic tile
        விட்ரிஃபைடு ஓடு / vitrified odu = vitrified tile
        போர்சலின் ஓடு / porcelain odu = porcelain tile
        ஆன்டி-ஸ்கிட் ஓடு / anti-skid odu = anti-skid tile
        வெளிப்புற ஓடு / velippura odu = outdoor tile
        படிக்கட்டு ஓடு / padikattu odu = stair tile
        மொசைக் ஓடு / mosaic odu = mosaic tile
        டைல் பசை / tile pasai = tile adhesive
        ஸ்கர்டிங் ஓடு / skirting odu = skirting tile
        டைல் பெட்டி / tile petti = tile box
        செங்கல் / chengal = brick
        சுட்ட செங்கல் / sutta chengal = burnt clay brick
        ஃப்ளை ஆஷ் செங்கல் / fly ash chengal = fly ash brick
        ஹாலோ பிளாக் / hollow block = hollow concrete block
        சாலிட் பிளாக் / solid block = solid concrete block
        சிமெண்டு / simantu = cement
        வெள்ளை சிமெண்டு / vellai simantu = white cement
        பி.பி.சி. சிமெண்டு / ppc simantu = ppc cement
        ஓ.பி.சி. சிமெண்டு / opc simantu = opc cement
        எம்-சாண்ட் / m-sand = manufactured sand
        பி-சாண்ட் / p-sand = plastering sand
        ஆற்று மணல் / aatru manal = river sand
        நிரப்பு மண் / nirappu mannu = filling soil
        செம்மண் / semman = red soil
        தோட்ட மண் / thotta mannu = garden soil
        ஜல்லி / jalli = blue metal aggregate
        6 எம்.எம். ஜல்லி / 6 mm jalli = 6 mm aggregate
        12 எம்.எம். ஜல்லி / 12 mm jalli = 12 mm aggregate
        20 எம்.எம். ஜல்லி / 20 mm jalli = 20 mm aggregate
        40 எம்.எம். ஜல்லி / 40 mm jalli = 40 mm aggregate
        க்ரஷர் தூள் / crusher thool = crusher dust
        கம்பி / kambi = steel rod
        டி.எம்.டி. கம்பி / tmt kambi = tmt steel bar
        கட்டுக் கம்பி / kattu kambi = binding wire
        கூரைத் தாள் / koorai thaal = roofing sheet
        கான்கிரீட் குழாய் / concrete kuzhaai = concrete pipe
        சிமெண்டு மூட்டை / simantu mootai = cement bag
        லாரி லோடு / lorry load = truckload
        ஜிப் / zip = zipper
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
        # "yaaruku naan tharanum", "whom should I pay": asking who, never a person called "Yaaruku" / "Whom" (owner, 12 Oct 2026).
        yaaruku yaarukku yaruku yarukku yaaru yaar yaru yar whom who should
        # A bill, a payment app or an expense — never a person ("current bill 1200 kattinen", "GPay la 500 vandhuchu").
        rent vaadagai vadagai current eb electricity bill gpay phonepe paytm upi cash bank salary sambalam petrol diesel gas kooli transport auto
        ellathaiyum ellathayum ellathaium ellaathaiyum ellathum yellathaium yellathaiyum yellathayum yellam
        ivan ivar ivaru ivanga ival adhu athu idhu ithu adhe athe adhey andha antha indha intha endha entha yentha kadaisiya kadaisi munnadi munnaadi ellam ellaam aal aalu
        innum innoru innonu marupadi thirumba paadhi half
        meedhi meethi meedi rate price vilai
        saraku sarakku saman saaman saamaan samaan maal kadaiku kadaikku porul
        # An accountant's words (owner, 11 Oct 2026: "pona maasam sales", "GST evlo", "advance evlo" were taken as a person's name).
        # "vandhirukku" is "came", never "to Vandhir" (owner's test, 12 Oct 2026).
        vandhir vanthir vandhur vanthur vandhiru vanthiru vandhuru vanthuru vandhiruk vanthiruk vandhuruk vanthuruk irukk iruk
        trial veliya veliye pona ponna pudhu pudhiya puthu puthiya market advance return returns discount kadan vasool gst tax labam laabam profit loss
        bill bills sales purchase purchases expense expenses selavu stock value vyabaram viyabaram business summary report
        லாபம் ஜிஎஸ்டி போன புது பில் வியாபாரம் பிசினஸ்
        # Tamil-script "me / you / him / who / today …" — and what is left of them once -க்கு is read as the dative ("என|க்கு").
        நான் நா என் என எனக் எனக்கு எனக்கும் நீ நீங்க உன் உன உனக் உனக்கு உங்க உங்களுக்கு நம்ம நமக்கு
        அவன் அவன அவனுக்கு அவள் அவள அவளுக்கு அவர் அவர அவருக்கு அவங்க அவங்களுக்கு
        யார் யாரு யாருக்கு யார யாருகிட்ட இன்னைக்கு இன்னை நாளைக்கு நாளை மணி மணிக்கு கடை கடைக்கு
        மொத்தம் மொத்த மொத்தமா எவ்வளவு எவ்ளோ என்ன எப்போ எப்ப எப்படி எந்த இந்த அந்த எல்லாம் எல்லாரும் எல்லாருக்கும்
        பாக்கி பேலன்ஸ் கடன் பணம் காசு ரூபாய் ஸ்டாக் சரக்கு பொருள் வாரம் மாசம் இன்று நேற்று நேத்து
    """)

    private val giveStem = Regex("""^k[uo](?:t|d)h?u?(?:t|d)h?""")
    private val giveEnding = Regex("""^u?t{0,2}(?:aanga|anga|aan|an|aar|ar|aaru|aru|en|achu|aachu|itaan|ittaan|iten|itten)$""")
    private val cameWord = Regex("""^van(?:d|t)h?[iu]\p{L}*?(?:chu|chi|cha)$""")

    /**
     * A verb however it is typed or heard (owner, 11 Oct 2026: "kututhutanga", "vandhuruchi" were not understood):
     * "kututhutanga" / "kudthan" / "koduthaan" → "kuduthutaanga" / "kuduthan" / "kuduthaan" (give), "vandhuchi" / "vandhuruchi" /
     * "vanthuruchu" → "vandhuchu" (came). Only whole verb forms — "Kuttappan" or "kudukkanum" stay as they are. Null: not one.
     */
    fun verbSpelling(word: String): String? {
        val w = word.lowercase(java.util.Locale.ROOT)
        if (w.length >= 6) giveStem.find(w)?.let { m ->
            val rest = w.substring(m.range.last + 1)
            if (giveEnding.matches(rest)) return ("kuduth" + rest).let { if (it.endsWith("anga") && !it.endsWith("aanga")) it.dropLast(4) + "aanga" else it }
                .takeIf { it != w }
        }
        if (cameWord.matches(w) && w != "vandhuchu") return "vandhuchu"
        return null
    }

    /** How owners actually type a word = the word Kai's rules read ("totel" = total). Whole words only. */
    val spellings: Map<String, String> = pairs("""
        totel = total
        # "Kumar kitta pesanum": a call to make — Kai asks when to remind (owner, 12 Oct 2026: answered with a balance).
        pesanum = call pannanum
        pesanu = call pannanum
        yevlo = evlo
        yewlo = evlo
        ewlo = evlo
        evalo = evlo
        evvlo = evlo
        yevvalavu = evvalavu
        nalaiku = naalaiku
        nalaikku = naalaiku
        naalaikku = naalaiku
        vyabaram = viyabaram
        vyaparam = viyabaram
        viyaparam = viyabaram
        vyabharam = viyabaram
        laabam = labam
        laabham = labam
        laabamm = labam
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
        # A day of the month said as an ordinal ("anjaam thethi" = the 5th) and the tens said in Tanglish (owner, 12 Oct 2026).
        onnaam = 1-aam
        rendaam = 2-aam
        moonaam = 3-aam
        moonam = 3-aam
        naalaam = 4-aam
        anjaam = 5-aam
        anjam = 5-aam
        aaraam = 6-aam
        ezhaam = 7-aam
        ettaam = 8-aam
        ombadhaam = 9-aam
        pathaam = 10-aam
        padhaam = 10-aam
        padhinanjaam = 15-aam
        irubadhaam = 20-aam
        iruvadhaam = 20-aam
        muppadhaam = 30-aam
        irubadhu = 20
        iruvadhu = 20
        iruvathu = 20
        muppadhu = 30
        muppathu = 30
        naarpadhu = 40
        naapadhu = 40
        naappathu = 40
        ambadhu = 50
        ambathu = 50
        aimbathu = 50
        arubadhu = 60
        aruvadhu = 60
        ezhubadhu = 70
        ezhuvadhu = 70
        enbadhu = 80
        embadhu = 80
        thonnooru = 90
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

    /** `name / other name = meaning` lines (the names of one thing together), a name allowed under two meanings. */
    private fun meanings(text: String): List<Pair<List<String>, String>> = clean(text).mapNotNull { line ->
        val (k, v) = line.split('=', limit = 2).map { it.trim() }.takeIf { it.size == 2 && it.all(String::isNotEmpty) } ?: return@mapNotNull null
        k.split(" / ").map { it.trim().lowercase() }.filter { it.isNotEmpty() } to v.lowercase()
    }

    private fun pairs(text: String): Map<String, String> = clean(text).mapNotNull { line ->
        val (k, v) = line.split('=', limit = 2).map { it.trim() }.takeIf { it.size == 2 && it.all(String::isNotEmpty) } ?: return@mapNotNull null
        k.lowercase() to v
    }.toMap()
}
