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
        venam=வேணாம் pudhu=புது pieces=பீஸ் skip=ஸ்கிப் aama=ஆமா ennakku=எனக்கு
        aal=ஆள் adha=அத akash=ஆகாஷ் amazon=அமேசான் anuppu=அனுப்பு aprom=அப்புறம் avan=அவன் bill=பில் cancel=கேன்சல் cm=சிஎம்
        dhaan=தான் employee=எம்ப்ளாயி first=ஃபர்ஸ்ட் gst=ஜிஎஸ்டி illa=இல்ல ipl=ஐபிஎல் ippa=இப்ப joke=ஜோக் kai=கை
        kalichi=கழிச்சு kanakku=கணக்கு ketta=கேட்ட loan=லோன் maathu=மாத்து mazhai=மழை nadu=நாடு nee=நீ nu=னு order=ஆர்டர்
        paakalam=பாக்கலாம் perum=பேரும் podu=போடு pottutta=போட்டுட்ட puriyala=புரியல reminder=ரிமைண்டர் salary=சேலரி score=ஸ்கோர்
        sonna=சொன்ன sonnadhu=சொன்னது tamil=தமிழ் tharnum=தரணும் thappa=தப்பா thappu=தப்பு varuma=வருமா varusham=வருஷம் venum=வேணும்
        whatsapp=வாட்ஸ்அப் near=நியர் nearby=நியர்பை by=பை iruka=இருக்கா paru=பாரு paaru=பாரு hardware=ஹார்டுவேர் shops=ஷாப்ஸ் spa=ஸ்பா
        salon=சலூன் gym=ஜிம் pakkathula=பக்கத்துல
        pottan=போட்டான் potten=போட்டேன் adachitan=அடைச்சிட்டான் transfer=டிரான்ஸ்ஃபர் pannan=பண்ணான் gram=கிராம் ratri=ராத்திரி poota=பூட்ட
        friday=வெள்ளிக்கிழமை account=அக்கவுண்ட் gold=கோல்டு rate=ரேட் petrol=பெட்ரோல் price=விலை train=ட்ரெயின் varum=வரும் rasi=ராசி
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
