package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import java.util.Locale

/**
 * A typed Tanglish line as Kai Chat's mic writes it: the phone's ta-IN speech-to-text puts Tamil words, English loan
 * words and names in Tamil script ("Kumar 2000 kuduthan" → "குமார் 2000 குடுத்தான்", "ABC Traders ku 3000 kuduthen" →
 * "ஏபிசி டிரேடர்ஸுக்கு 3000 குடுத்தேன்"). Every owner line the tests type is played again in this form: whatever Kai does
 * for the typed line it must do for the spoken one (owner's rule, 10 Oct 2026: text box and mic always get the same fixes).
 *
 * Only the words the owner lines use are listed; a word not listed stays as typed. English sentences (the mic listens in
 * Tamil) and lines already in Tamil script are not replayed — [of] returns null for them.
 */
object KaiMicForm {

    /** Tanglish / English loan words → how ta-IN speech-to-text writes them. */
    private val words: Map<String, String> = """
        aachu=ஆச்சு aagiduchu=ஆயிடுச்சு aayiram=ஆயிரம் abc=ஏபிசி add=ஆட் adhigama=அதிகமா agala=ஆகல aguthu=ஆகுது ah=ஆ akka=அக்கா
        anna=அண்ணா anupiten=அனுப்பிட்டேன் anuppiachu=அனுப்பியாச்சு anuppichan=அனுப்பிச்சான் anuppichen=அனுப்பிச்சேன் anuppinan=அனுப்பினான்
        anuppinen=அனுப்பினேன் anuppitan=அனுப்பிட்டான் arisi=அரிசி baaki=பாக்கி bag=பேக் balance=பேலன்ஸ் baniya=பனியா baniyan=பனியன்
        bank=பேங்க் bhaniyan=பனியன் bottle=பாட்டில் box=பாக்ஸ் boxes=பாக்ஸ் call=கால் cash=கேஷ் clear=கிளியர் colga=கோல்கா colgate=கோல்கேட்
        collection=கலெக்ஷன் correct=கரெக்ட் credit=கிரெடிட் customers=கஸ்டமர்ஸ் daily=டெய்லி damage=டேமேஜ் date=டேட் due=டியூ
        eduka=எடுக்க eduthutu=எடுத்துட்டு ellaa=எல்லா enakku=எனக்கு enaku=எனக்கு endha=எந்த enna=என்ன ennai=எண்ணெய் ennaku=எனக்கு
        eppo=எப்போ evening=ஈவினிங் evlo=எவ்வளவு fast=ஃபாஸ்ட் full=ஃபுல் ganesh=கணேஷ் gpay=ஜிபே hmm=ம்ம் horlicks=ஹார்லிக்ஸ்
        indha=இந்த innaiku=இன்னைக்கு ippo=இப்போ irakkinen=இறக்கினேன் irukka=இருக்கா irukken=இருக்கேன் irukku=இருக்கு iruku=இருக்கு
        irundhu=இருந்து kaalai=காலை kaalaila=காலையில kadai=கடை kadan=கடன் kalichu=கழிச்சு kammiya=கம்மியா katti=கட்டி kattinan=கட்டினான்
        kattinen=கட்டினேன் kettupochu=கெட்டுப்போச்சு kg=கிலோ kitta=கிட்ட koduthen=கொடுத்தேன் kudhuthan=குடுத்தான் kudthan=குட்தான்
        kudukanum=குடுக்கணும் kudukkanum=குடுக்கணும் kudupanga=குடுப்பாங்க kuduthaan=குடுத்தான் kuduthaaru=குடுத்தாரு kuduthan=குடுத்தான்
        kuduthanga=குடுத்தாங்க kuduthar=குடுத்தார் kuduthen=குடுத்தேன் kuduthutan=குடுத்துட்டான் kumar=குமார் la=ல lakshmi=லட்சுமி
        late=லேட் list=லிஸ்ட் low=லோ lux=லக்ஸ் maal=மால் maasam=மாசம் maggi=மேகி manikku=மணிக்கு maniku=மணிக்கு moonu=மூணு moota=மூட்டை
        mothama=மொத்தமா move=மூவ் murugan=முருகன் muthu=முத்து naalai=நாளை naalaiku=நாளைக்கு naan=நான் neft=நெஃப்ட் nimisham=நிமிஷம்
        nyabagam=ஞாபகம் oda=ஓட oil=ஆயில் oru=ஒரு overdue=ஓவர்டியூ paduthu=படுத்து panam=பணம் panna=பண்ண pannanum=பண்ணணும்
        pannen=பண்ணேன் pannitaan=பண்ணிட்டான் pannitan=பண்ணிட்டான் panniten=பண்ணிட்டேன் pannu=பண்ணு pannuvana=பண்ணுவானா
        pannuvanga=பண்ணுவாங்க pay=பே payment=பேமெண்ட் pending=பெண்டிங் petti=பெட்டி phone=போன் phonepe=போன்பே pochu=போச்சு
        poganum=போகணும் ponaan=போனான் pottalam=பொட்டலம் ramesh=ரமேஷ் ravi=ரவி remind=ரிமைண்ட் rendu=ரெண்டு return=ரிட்டர்ன்
        rice=ரைஸ் rs=ரூபாய் rupees=ரூபாய் saamaan=சாமான் saavi=சாவி saayangalam=சாயங்காலம் sale=சேல் selvam=செல்வம் settle=செட்டில்
        soap=சோப் sollu=சொல்லு stock=ஸ்டாக் stores=ஸ்டோர்ஸ் sujith=சுஜித் sujithukku=சுஜித்துக்கு supplier=சப்ளையர் thaandi=தாண்டி
        thandhaan=தந்தான் thandhutaanga=தந்துட்டாங்க thanthan=தந்தான் tharanga=தராங்க tharanum=தரணும் tharuvan=தருவான்
        tharuvana=தருவானா tharuvanga=தருவாங்க tharuvara=தருவாரா time=டைம் total=டோட்டல் totala=டோட்டலா totel=டோட்டல் traders=டிரேடர்ஸ்
        udanjiduchu=உடைஞ்சிடுச்சு udhaar=உதார் upi=யுபிஐ vaanga=வாங்க vaanganum=வாங்கணும் vaangi=வாங்கி vaanginen=வாங்கினேன்
        vaaram=வாரம் vandhirukku=வந்திருக்கு vandhuchu=வந்துச்சு vanganum=வாங்கணும் vangunen=வாங்குனேன் vanthuchu=வந்துச்சு
        varanum=வரணும் vendiyadhu=வேண்டியது vikkala=விக்கல vithadhu=வித்தது vithuchu=வித்துச்சு vithuten=வித்துட்டேன் yaar=யார்
        yaaru=யாரு yar=யார் yaru=யாரு yarukita=யாருகிட்ட yaruku=யாருக்கு yentha=எந்த
        adhe=அதே munnadi=முன்னாடி ivan=இவன் adhoda=அதோட mudhal=முதல் aal=ஆள் rendavadhu=ரெண்டாவது kadaisi=கடைசி eduthudu=எடுத்துடு arakilo=அரைக்கிலோ packetu=பாக்கெட்டு ashirvad=ஆசிர்வாத் venam=வேணாம் pudhu=புது pieces=பீஸ் skip=ஸ்கிப் aama=ஆமா ennakku=எனக்கு
        aal=ஆள் adha=அத akash=ஆகாஷ் amazon=அமேசான் anuppu=அனுப்பு aprom=அப்புறம் avan=அவன் bill=பில் cancel=கேன்சல் cm=சிஎம்
        dhaan=தான் employee=எம்ப்ளாயி first=ஃபர்ஸ்ட் gst=ஜிஎஸ்டி illa=இல்ல ipl=ஐபிஎல் ippa=இப்ப joke=ஜோக் kai=கை
        kalichi=கழிச்சு kanakku=கணக்கு ketta=கேட்ட loan=லோன் maathu=மாத்து mazhai=மழை nadu=நாடு nee=நீ nu=னு order=ஆர்டர்
        paakalam=பாக்கலாம் perum=பேரும் podu=போடு pottutta=போட்டுட்ட puriyala=புரியல reminder=ரிமைண்டர் salary=சேலரி score=ஸ்கோர்
        sonna=சொன்ன sonnadhu=சொன்னது tamil=தமிழ் tharnum=தரணும் thappa=தப்பா thappu=தப்பு varuma=வருமா varusham=வருஷம் venum=வேணும்
        panniko=பண்ணிக்கோ athukuda=அதுகூட anjaam=அஞ்சாம் moonaam=மூணாம் ambadhu=அம்பது cinthol=சிந்தால்
        pdf=பிடிஎஃப் itha=இத idha=இத statement=ஸ்டேட்மெண்ட் share=ஷேர் download=டவுன்லோட் ledger=லெட்ஜர் history=ஹிஸ்டரி kudu=குடு debit=டெபிட் pona=போன
        whatsapp=வாட்ஸ்அப் near=நியர் nearby=நியர்பை by=பை iruka=இருக்கா paru=பாரு paaru=பாரு hardware=ஹார்டுவேர் shops=ஷாப்ஸ் spa=ஸ்பா
        salon=சலூன் gym=ஜிம் pakkathula=பக்கத்துல
        poiruchi=போயிருச்சு vangiirukan=வாங்கியிருக்கான் irukan=இருக்கான் vangi=வாங்கி
        cigarette=சிகரெட் pipe=பைப் flake=ஃப்ளேக் lights=லைட்ஸ் inch=இன்ச்
        athu=அது innoru=இன்னொரு adhe=அதே paadhi=பாதி munnadi=முன்னாடி ketten=கேட்டேன்
        business=பிசினஸ் pannalam=பண்ணலாம் idea=ஐடியா jaasthi=ஜாஸ்தி aaga=ஆக leave=லீவு light=லைட் water=வாட்டர் eb=ஈபி sales=சேல்ஸ்
        poyiduchu=போயிடுச்சு kattiten=கட்டிட்டேன்
        pathi=பத்தி porul=பொருள் kadaila=கடையில irukura=இருக்குற kudukal=குடுக்கல் vangal=வாங்கல் ama=ஆமா
        kadaiku=கடைக்கு saman=சாமான் saraku=சரக்கு vandhuruku=வந்திருக்கு pannalama=பண்ணலாமா avlo=அவ்ளோ
        avanga=அவங்க peru=பேரு namba=நம்ம namma=நம்ம vangunan=வாங்குனான் seathuko=சேர்த்துக்கோ serthuko=சேர்த்துக்கோ vengayam=வெங்காயம்
        azhugiduchu=அழுகிடுச்சு azhugipochu=அழுகிப்போச்சு vaadiduchu=வாடிடுச்சு thakkali=தக்காளி kariveppilai=கறிவேப்பிலை loose=லூஸ் packet=பாக்கெட் kilo=கிலோ arai=அரை
        small=ஸ்மால் medium=மீடியம் large=லார்ஜ் family=ஃபேமிலி chinna=சின்ன periya=பெரிய
        pottan=போட்டான் potten=போட்டேன் adachitan=அடைச்சிட்டான் transfer=டிரான்ஸ்ஃபர் pannan=பண்ணான் gram=கிராம் ratri=ராத்திரி poota=பூட்ட
        friday=வெள்ளிக்கிழமை account=அக்கவுண்ட் gold=கோல்டு rate=ரேட் petrol=பெட்ரோல் price=விலை train=ட்ரெயின் varum=வரும் rasi=ராசி
        purchase=பர்சேஸ் selling=செல்லிங் labam=லாபம் profit=ப்ராஃபிட் margin=மார்ஜின் business=பிசினஸ் pogudhu=போகுது value=வேல்யூ selavu=செலவு
        vyabaram=வியாபாரம் summary=சம்மரி irukkanga=இருக்காங்க vasool=வசூல் bills=பில்ஸ் transactions=டிரான்ஸாக்ஷன்ஸ் advance=அட்வான்ஸ் discount=டிஸ்கவுண்ட்
        andha=அந்த aalu=ஆளு thappu=தப்பு vithutom=வித்துட்டோம் serthu=சேர்த்து motham=மொத்தம் peroda=பேரோட meedhi=மீதி naal=நாள் kadaisi=கடைசி thethi=தேதி
        theerapoguthu=தீரப்போகுது sales=சேல்ஸ் vilai=விலை vara=வர
        tharala=தரல kudukala=கொடுக்கல vendiya=வேண்டிய roobaya=ரூபாயை kanakkula=கணக்குல thogaiya=தொகையை thogaiyoda=தொகையோட adhigapaduthu=அதிகப்படுத்து baakiyoda=பாக்கியோட kooduthalaa=கூடுதலா baakiya=பாக்கிய kootti=கூட்டி baakila=பாக்கில kudutha=குடுத்த varavula=வரவுல selavula=செலவுல vegamaa=வேகமா padhivula=பதிவுல thadava=தடவை seekiram=சீக்கிரம் thara=தர varavu=வரவு vai=வை kudukka=குடுக்க seru=சேர் kattittaaru=கட்டிட்டாரு kuduthuttaaru=குடுத்துட்டாரு anuppitten=அனுப்பிட்டேன் pannitten=பண்ணிட்டேன் kuduthuten=குடுத்துட்டேன் rooba=ரூபா innum=இன்னும் record=பதிவு konjam=கொஞ்சம் selavaachu=செலவாச்சு
        yaaruku=யாருக்கு yaarukku=யாருக்கு
        vera=வேற vishayam=விஷயம் aamaa=ஆமா blah=ப்ளா
        irukura=இருக்குற seru=சேரு kali=கழி kammi=கம்மி less=லெஸ் iruntha=இருந்தா invoice=இன்வாய்ஸ் panniko=பண்ணிக்கோ pannidu=பண்ணிடு intha=இந்த
        nalla=நல்லா aguthu=ஆகுது pothu=போகுது nikkuthu=நிக்குது nikkudhu=நிக்குது nikkura=நிக்குற pola=போல pogadha=போகாத item=ஐட்டம் porul=பொருள் vikkuthu=விக்குது yentha=எந்த move=மூவ் agala=ஆகல pogudhu=போகுது
        ellathaiyum=எல்லாத்தையும் kattitan=கட்டிட்டான் ricela=ரைஸ்ல mudangi=முடங்கி vikkadha=விக்காத vikkura=விக்குற report=ரிப்போர்ட்
        thandhaa=தந்தா pesanum=பேசணும் rent=ரெண்ட் current=கரண்ட் vaadagai=வாடகை kattanum=கட்டணும் vithucha=வித்துச்சா vandhucha=வந்துச்சா
        batch=பேட்ச் expiry=எக்ஸ்பைரி strip=ஸ்ட்ரிப் paracetamol=பாராசிட்டமால் tube=டியூப் dolo=டோலோ
        palan=பலன் inniku=இன்னிக்கு biryani=பிரியாணி recipe=ரெசிபி gst=ஜிஎஸ்டி kooda=கூட bore=போர் adikkudhu=அடிக்குது cheque=செக் card=கார்டு
    """.trim().split(Regex("""\s+""")).associate { it.substringBefore('=') to it.substringAfter('=') }

    /** Said as one word ("katti tan" is "கட்டிட்டான்"). */
    private val phrases = listOf("katti tan" to "கட்டிட்டான்", "settle pannitaan" to "செட்டில் பண்ணிட்டான்")

    /** "Kumar ku", "Kumar-ku", "ABC Traders kku": the dative, written onto the name ("குமாருக்கு"). */
    private val dative = Regex("""(?i)([\p{L}]+)\s*-?\s*(?:u?k?ku)(?![\p{L}])""")

    /** The line as the mic writes it, or null when the mic would not hear it as typed (English, or already Tamil). */
    fun of(typed: String): String? {
        if (typed.any { it in '஀'..'௿' } || KaiLanguage.forChat(typed) == KaiLang.ENGLISH) return null
        var s = typed
        for ((p, t) in phrases) s = s.replace(Regex("""(?i)(?<![\p{L}])${Regex.escape(p)}(?![\p{L}])"""), t)
        s = dative.replace(s) { m ->
            words[m.value.lowercase(Locale.ROOT).replace(Regex("""[\s-]"""), "")]?.let { return@replace it } // "ennakku" is a word, not "enna" + "kku"
            val w = m.groupValues[1]
            val ta = words[w.lowercase(Locale.ROOT)] ?: return@replace m.value
            if (ta.endsWith("்")) ta.dropLast(1) + "ுக்கு" else ta + "க்கு"
        }
        s = Regex("""[A-Za-z]+""").replace(s) { m -> words[m.value.lowercase(Locale.ROOT)] ?: m.value }
        return s.takeIf { it != typed }
    }

    /** A phrase Kai repeats back to a spoken line: the owner's words as the mic wrote them, names as the shop has them. */
    fun repeated(typed: String): String = Regex("""(?<![\p{L}])[a-z]+(?![\p{L}])""").replace(typed) { m -> words[m.value] ?: m.value }

    private val months = listOf("January" to "ஜனவரி", "February" to "பிப்ரவரி", "March" to "மார்ச்", "April" to "ஏப்ரல்", "May" to "மே", "June" to "ஜூன்",
        "July" to "ஜூலை", "August" to "ஆகஸ்ட்", "September" to "செப்டம்பர்", "October" to "அக்டோபர்", "November" to "நவம்பர்", "December" to "டிசம்பர்")

    /** A date a reply check looks for, as Kai writes it in Tamil ("October 12" → "அக்டோபர் 12"). */
    fun inTamil(want: String): String = months.fold(want) { t, (en, ta) -> t.replace(en, ta) }

    /** What a reply check can still look for when Kai answers in Tamil: amounts, numbers and names — not Tanglish words. */
    fun keepsInTamil(want: String): Boolean = want.any(Char::isDigit) || want.first().isUpperCase() && want.none { it == ' ' } && want.length > 2
}
