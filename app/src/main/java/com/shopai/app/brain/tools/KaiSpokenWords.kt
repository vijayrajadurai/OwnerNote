package com.shopai.app.brain.tools

/**
 * The phone's speech-to-text listens in Tamil (ta-IN) and writes what it
 * hears in Tamil script — Tamil words AND English loan words alike:
 * "2 நிமிஷத்துல ருத்ரனுக்கு கால் பண்ணனும் ரிமைண்டர் பண்ணு",
 * "டூ மினிட்ஸ்ல …". Kai's command rules (time, reminders, stock, bills) read
 * Tanglish, so a spoken sentence and the same sentence typed in Tanglish
 * must become the same words before the rules run. This maps the command
 * vocabulary (numbers, time words, verbs) to Tanglish; every other word —
 * names above all — is kept as it was said.
 *
 * Typed Tanglish / English passes through unchanged.
 */
object KaiSpokenWords {

    private const val TA = "஀-௿"

    /** Whole-word replacements, longest first (a word is a run of Tamil letters and marks). */
    private val words: List<Pair<String, String>> = listOf(
        // ---- numbers (Tamil, and English said in Tamil script) ----
        "ஒன்று" to "1", "ஒண்ணு" to "1", "ஒரு" to "1", "ரெண்டு" to "2", "இரண்டு" to "2", "மூணு" to "3", "மூன்று" to "3",
        "நாலு" to "4", "நான்கு" to "4", "அஞ்சு" to "5", "ஐந்து" to "5", "ஆறு" to "6", "ஏழு" to "7", "எட்டு" to "8",
        "ஒன்பது" to "9", "பத்து" to "10", "பதினஞ்சு" to "15", "பதினைந்து" to "15", "இருபது" to "20", "முப்பது" to "30",
        "நாற்பது" to "40", "ஐம்பது" to "50", "அரை" to "half",
        "டூ" to "2", "த்ரீ" to "3", "ஃபோர்" to "4", "ஃபைவ்" to "5", "பைவ்" to "5", "டென்" to "10", "ட்வென்டி" to "20",
        // ---- time ----
        "நாளைக்கு" to "naalaikku", "நாளை" to "naalai", "நாளன்னைக்கு" to "naalanniku",
        "இன்னைக்கு" to "innaikku", "இன்னிக்கு" to "innaikku", "இன்று" to "indru",
        "காலையில" to "kaalaila", "காலையில்" to "kaalaila", "காலைல" to "kaalaila", "காலை" to "kaalai",
        "மதியம்" to "madhiyam", "சாயங்காலம்" to "saayangalam", "மாலை" to "maalai", "இரவு" to "iravu", "ராத்திரி" to "raathiri",
        "மணிக்கு" to "manikku", "மணி" to "mani",
        "கழிச்சு" to "kalichi", "கழிச்சி" to "kalichi", "கழித்து" to "kalichi", "அப்புறம்" to "apram", "அப்றம்" to "apram", "பிறகு" to "piragu",
        "மாலையில்" to "maalaila", "மாலையில" to "maalaila", "மாலைல" to "maalaila", "சாவி" to "saavi", "போட்டு" to "pottu", "போடு" to "podu",
        "இன்னும்" to "innum",
        // ---- reminders / calls ----
        "ரிமைண்டர்" to "reminder", "ரிமைண்ட்" to "remind", "ரிமைன்டர்" to "reminder",
        "ஞாபகப்படுத்து" to "nyabagam paduthu", "ஞாபகப்படுத்துங்க" to "nyabagam paduthu", "ஞாபகம்" to "nyabagam",
        "நினைவூட்டு" to "remind", "நினைவூட்டுங்க" to "remind", "நினைவுபடுத்து" to "nyabagam paduthu", "நினைவுப்படுத்து" to "nyabagam paduthu",
        "கால்" to "call", "போன்" to "phone", "மெசேஜ்" to "message",
        "பண்ணு" to "pannu", "பண்ணுங்க" to "pannunga", "பண்ணனும்" to "pannanum", "பண்ணணும்" to "pannanum", "பண்ண" to "panna",
        "பண்ணிடு" to "pannidu", "பண்றேன்" to "panren",
        "சொல்லு" to "sollu", "சொல்லுங்க" to "sollunga",
        // ---- stock ----
        "ஸ்டாக்" to "stock", "ஸ்டாக்கு" to "stock", "இன்வென்டரி" to "inventory", "ஆட்" to "add", "சேர்" to "serthu", "சேர்த்து" to "serthu",
        "வந்திருக்கு" to "vandhiruku", "வந்துருக்கு" to "vandhuruku", "வந்துச்சு" to "vandhuchu", "புது" to "pudhu", "புதுசா" to "pudhusa",
        "அவுட்" to "out", "போச்சு" to "pochu", "சேல்" to "sale", "ஆச்சு" to "aachu", "எடுத்துட்டாங்க" to "eduthutanga",
        "பீஸ்" to "pieces", "பீசஸ்" to "pieces", "பாக்ஸ்" to "box", "பாக்கெட்" to "packet", "கிலோ" to "kg",
        // ---- bills / camera ----
        "பில்" to "bill", "பில்லு" to "bill", "ஸ்கேன்" to "scan", "போட்டோ" to "photo", "கேமரா" to "camera", "ஓபன்" to "open",
        "எடு" to "edu", "எடுங்க" to "edunga", "இந்த" to "indha", "பர்ச்சேஸ்" to "purchase",
        // ---- conversation ----
        "சாப்டியா" to "saaptiya", "சாப்பிட்டியா" to "saaptiya", "சாப்டீங்களா" to "saapteengala", "சாப்பிட்டீங்களா" to "saapteengala",
        "என்ன" to "enna", "பண்ற" to "panra", "பண்றீங்க" to "panreenga", "குட்" to "good", "மார்னிங்" to "morning", "நைட்" to "night",
        "வணக்கம்" to "vanakkam", "ரொம்ப" to "romba", "பிஸி" to "busy", "நன்றி" to "nandri", "தேங்க்ஸ்" to "thanks",
        "எப்படி" to "eppadi", "இருக்கீங்க" to "irukkeenga", "இருக்க" to "irukka",
        // "இருக்கு" is "is there", never a name with "-க்கு" ("இர்-ku"); "எவ்வளவு" is "evlo".
        "இருக்கு" to "irukku", "இருக்கா" to "irukka", "இருக்குதா" to "irukka", "இருக்குது" to "irukku",
        "எவ்வளவு" to "evlo", "எவ்ளோ" to "evlo", "எத்தனை" to "ethana",
        "க்கு" to "ku", "கு" to "ku", "நீ" to "nee", "நீங்க" to "neenga", "நீங்கள்" to "neengal",
        // ---- money: who gave, who paid, who owes (what typed Tanglish already says) ----
        "குடுத்தான்" to "kuduthaan", "கொடுத்தான்" to "kuduthaan", "குடுத்தாரு" to "kuduthaaru", "கொடுத்தாரு" to "kuduthaaru",
        "குடுத்தார்" to "kuduthar", "கொடுத்தார்" to "kuduthar", "குடுத்தாங்க" to "kuduthaanga", "கொடுத்தாங்க" to "kuduthaanga",
        "குடுத்தா" to "kudutha", "கொடுத்தா" to "kudutha", "குடுத்துட்டான்" to "kuduthutaan", "கொடுத்துட்டான்" to "kuduthutaan",
        "குடுத்துட்டாரு" to "kuduthutaru", "கொடுத்துட்டாரு" to "kuduthutaru", "குடுத்துட்டாங்க" to "kuduthutaanga", "கொடுத்துட்டாங்க" to "kuduthutaanga",
        "தந்தான்" to "thandhaan", "தந்தாரு" to "thandhaaru", "தந்தார்" to "thandhaaru", "தந்துட்டான்" to "thandhutaan", "தந்தாங்க" to "thandhaanga",
        "அனுப்பினான்" to "anuppinaan", "அனுப்பிட்டான்" to "anuppitaan", "அனுப்பினாரு" to "anuppinaar", "அனுப்பினார்" to "anuppinaar",
        "அனுப்பிட்டாரு" to "anuppitaaru", "அனுப்பினாங்க" to "anuppinaanga", "அனுப்பிட்டாங்க" to "anuppitaanga",
        "கட்டினான்" to "kattinaan", "கட்டிட்டான்" to "kattitaan", "கட்டினாரு" to "kattinaar", "கட்டினார்" to "kattinaar", "கட்டிட்டாரு" to "kattitaru",
        "கட்டினாங்க" to "kattinaanga",
        "அனுப்பியாச்சு" to "anuppiachu", "அனுப்பிட்டாச்சு" to "anuppiachu", "அனுப்பிச்சாச்சு" to "anuppiachu", "குட்தான்" to "kudthan",
        "கொட்தான்" to "kodthan", "குடுதான்" to "kudthan", "குட்டான்" to "kudthan", "நெஃப்ட்" to "neft", "நெப்ட்" to "neft", "ஐஎம்பிஎஸ்" to "imps",
        "பாக்கலாம்" to "paakalam", "பார்க்கலாம்" to "paakalam", "மாத்து" to "maathu", "மாத்துங்க" to "maathunga", "கேன்சல்" to "cancel",
        "டெலீட்" to "delete", "தப்பா" to "thappa", "தப்பு" to "thappu", "சொன்னது" to "sonnadhu", "போட்டுட்ட" to "pottutta", "போட்டுட்டேன்" to "pottuten",
        "புரியல" to "puriyala", "புரிஞ்சுக்கல" to "purinjukala", "ஜிஎஸ்டி" to "gst", "பில்லு" to "bill", "இன்வாய்ஸ்" to "invoice", "வாட்ஸ்அப்" to "whatsapp",
        "வாட்ஸ்அப்ல" to "whatsapp la", "ரெண்டு பேரும்" to "rendu perum", "பேரும்" to "perum", "ஃபர்ஸ்ட்" to "first", "முதல்ல" to "mudhalla",
        "கேட்ட" to "ketta", "ஆளு" to "aalu", "ஆள்" to "aal", "தேதி" to "thethi", "கிழமை" to "kizhamai", "வருஷம்" to "varusham",
        // ---- what Kai doesn't do (weather, news, orders, staff) — said in Tamil script, heard as typed ----
        "கணக்கு" to "kanakku", "கணக்க" to "kanakka", "சேலரி" to "salary", "சம்பளம்" to "sambalam", "எம்ப்ளாயி" to "employee", "எம்ப்ளாயீ" to "employee",
        "ஸ்டாஃப்" to "staff", "சிஎம்" to "cm", "பிஎம்" to "pm", "அமேசான்" to "amazon", "ஃப்ளிப்கார்ட்" to "flipkart", "பிளிப்கார்ட்" to "flipkart",
        "ஸ்விக்கி" to "swiggy", "ஆன்லைன்" to "online", "ஆர்டர்" to "order", "ஐபிஎல்" to "ipl", "கிரிக்கெட்" to "cricket", "ஸ்கோர்" to "score",
        "நியூஸ்" to "news", "ஜோக்" to "joke", "லோன்" to "loan",
        // ---- places nearby ("ஸ்பா நியர் பை ல இருக்கா பாரு") ----
        "நியர்" to "near", "நியர்பை" to "nearby", "ஹார்டுவேர்" to "hardware", "ஹார்ட்வேர்" to "hardware", "ஹார்டுவேர்ஸ்" to "hardware",
        "ஷாப்" to "shop", "ஷாப்ஸ்" to "shops", "ஸ்பா" to "spa", "சலூன்" to "salon", "பார்லர்" to "parlour", "ஜிம்" to "gym", "பாரு" to "paaru",
        // ---- units, payment modes, money words, sums and small talk said in Tamil script (typed versions already understood) ----
        "கிராம்" to "gram", "கிராமு" to "gram", "கிராம்ஸ்" to "grams", "மில்லி" to "ml", "ஸ்ட்ரிப்" to "strip", "ரோல்" to "roll",
        "செக்" to "cheque", "செக்ல" to "cheque la", "செக்கு" to "cheque", "கார்டு" to "card", "கார்ட்" to "card", "கார்டுல" to "card la", "கார்ட்ல" to "card la",
        "டிரான்ஸ்ஃபர்" to "transfer", "ட்ரான்ஸ்ஃபர்" to "transfer", "டிரான்ஸ்பர்" to "transfer", "ட்ரான்ஸ்பர்" to "transfer",
        "போட்டான்" to "pottaan", "போட்டுட்டான்" to "pottutaan", "போட்டாங்க" to "pottaanga", "போட்டாரு" to "pottaaru", "போட்டேன்" to "potten", "போட்டுட்டேன்" to "pottuten",
        "அடைச்சிட்டான்" to "adachitaan", "அடைச்சுட்டான்" to "adachitaan", "அடைச்சான்" to "adachaan", "அடைச்சிட்டேன்" to "adachiten", "அடைச்சேன்" to "adachen",
        "பெருக்கல்" to "perukkal", "கூட்டல்" to "koottal", "கழித்தல்" to "kazhithal", "வகுத்தல்" to "vaguthal",
        "பர்சன்ட்" to "percent", "பெர்சன்ட்" to "percent", "பர்சென்ட்" to "percent", "பெர்சென்ட்" to "percent", "பர்செண்ட்" to "percent",
        "நல்லா" to "nalla", "நல்லாருக்கியா" to "nalla irukkiya", "இருக்கியா" to "irukkiya", "இருக்கீங்களா" to "irukkeengala", "போரடிக்குது" to "bore adikkudhu",
        "கோல்டு" to "gold", "தங்கம்" to "thangam", "பெட்ரோல்" to "petrol", "டீசல்" to "diesel", "டாலர்" to "dollar", "ரேட்" to "rate", "விலை" to "vilai",
        "ட்ரெயின்" to "train", "டிரெயின்" to "train", "ராசி" to "raasi", "பலன்" to "palan", "ரெசிபி" to "recipe",
        "ம்ம்" to "hmm", "ம்ம்ம்" to "hmm", "ஹ்ம்" to "hmm", "அய்யோ" to "ayyo", "ஐயோ" to "ayyo",
        "வாங்குனேன்" to "vangunen", "வாங்குனது" to "vangunadhu", "அனுப்பிச்சான்" to "anuppichan", "அனுப்பிச்சாரு" to "anuppichaaru",
        "அனுப்பிச்சேன்" to "anuppichen", "தந்துட்டாங்க" to "thandhutaanga", "தந்துட்டாரு" to "thandhutaaru", "தராங்க" to "tharanga", "தர்றாங்க" to "tharraanga",
        "இறக்கினேன்" to "irakkinen", "இறக்கிட்டேன்" to "irakkitten", "பொட்டலம்" to "pottalam", "பொட்டலங்கள்" to "pottalangal", "பேக்" to "bag",
        "பேக்ஸ்" to "bags", "ஓவர்டியூ" to "overdue", "கஸ்டமர்ஸ்" to "customers", "சப்ளையர்ஸ்" to "suppliers", "எல்லா" to "ellaa", "எல்லாம்" to "ellam",
        "எல்லாரும்" to "ellaarum", "கம்மியா" to "kammiya", "அதிகமா" to "adhigama", "வித்தது" to "vithadhu", "மாசம்" to "maasam", "வாரம்" to "vaaram",
        "டெய்லி" to "daily", "தினமும்" to "daily", "ஸ்கிப்" to "skip", "எந்த" to "endha", "ஹிஸ்டரி" to "history", "லாஸ்ட்" to "last",
        "குடுத்தேன்" to "kuduthen", "கொடுத்தேன்" to "kuduthen", "குடுத்துட்டேன்" to "kuduthuten", "கொடுத்துட்டேன்" to "kuduthuten",
        "தந்தேன்" to "kuduthen", "அனுப்பினேன்" to "anuppinen", "அனுப்பிட்டேன்" to "anuppiten", "அனுப்பிச்சேன்" to "anuppichen",
        "கட்டினேன்" to "kattinen", "கட்டிட்டேன்" to "kattiten", "வாங்கிட்டேன்" to "vangiten", "வாங்கினேன்" to "vaanginen", "வாங்கி" to "vaangi", "இருக்கேன்" to "irukken", "வாங்கியிருக்கேன்" to "vaangi irukken", "வாங்கிருக்கேன்" to "vaangi irukken",
        "கரெக்டா" to "correcta", "கரெக்ட்" to "correct", "கரெக்டான" to "correct", "லேட்டா" to "lateaa", "லேட்" to "late", "டைம்" to "time",
        "டைம்ல" to "time la", "டைமுக்கு" to "time ku", "சரியா" to "sariya", "சரியான" to "sariyaana", "பண்ணுவாங்க" to "pannuvaanga", "பண்ணுவான்" to "pannuvaan",
        "பண்ணுவாரு" to "pannuvaaru", "பண்ணுவானா" to "pannuvaana", "தருவாங்க" to "tharuvaanga", "குடுப்பாங்க" to "kuduppaanga", "கொடுப்பாங்க" to "kuduppaanga",
        "தேதியில" to "thethi la", "தேதியில்" to "thethi la", "தேதில" to "thethi la", "தருவாரா" to "tharuvaara", "தருவானா" to "tharuvaana",
        "தருவாங்களா" to "tharuvaangala", "குடுப்பான்" to "kuduppaan", "கொடுப்பான்" to "kuduppaan", "குடுப்பானா" to "kuduppaana",
        "டீடைல்" to "details", "டீடைல்ஸ்" to "details", "டீட்டெயில்ஸ்" to "details", "விவரம்" to "details",
        "பண்ணிட்டான்" to "pannitaan", "பண்ணிட்டாரு" to "pannitaaru", "பண்ணிட்டார்" to "pannitaaru", "பண்ணிட்டாங்க" to "pannitaanga",
        "பண்ணான்" to "pannaan", "பண்ணாரு" to "pannaaru", "பண்ணார்" to "pannaaru", "பண்ணாங்க" to "pannaanga",
        "பண்ணேன்" to "pannen", "பண்ணிட்டேன்" to "panniten", "பண்ணினேன்" to "panninen", "பண்ணிருக்கான்" to "pannirukkaan",
        "தரணும்" to "tharanum", "தரனும்" to "tharanum", "குடுக்கணும்" to "kudukkanum", "கொடுக்கணும்" to "kodukkanum", "குடுக்கனும்" to "kudukkanum",
        "வரணும்" to "varanum", "வாங்கணும்" to "vaanganum", "வாங்க" to "vaanga", "தருவான்" to "tharuvaan", "தருவாரு" to "tharuvaaru", "தருவேன்" to "tharuven",
        "கடன்" to "kadan", "கடனா" to "kadana", "கடனுக்கு" to "kadanuku", "பாக்கி" to "baaki", "பாக்கியா" to "baaki", "வெச்சிருக்கான்" to "vechirukkaan",
        "எனக்கு" to "enakku", "நான்" to "naan", "நா" to "naa", "அவன்" to "avan", "அவரு" to "avaru", "அவர்" to "avar", "அவங்க" to "avanga",
        "கிட்ட" to "kitta", "கிட்டே" to "kitta", "இருந்து" to "irundhu", "இருந்தது" to "irundhuchu",
        "ஜிபே" to "gpay", "கூகுள்பே" to "gpay", "போன்பே" to "phonepe", "பேடிஎம்" to "paytm", "யுபிஐ" to "upi", "பே" to "pay", "ஜிபேல" to "gpay la",
        "பேமெண்ட்" to "payment", "பேமென்ட்" to "payment", "பேமண்ட்" to "payment", "செட்டில்" to "settle", "கிளியர்" to "clear",
        "கேஷ்" to "cash", "கேஷா" to "cash", "ஆன்லைன்" to "online", "ஆன்லைன்ல" to "online la", "டிரான்ஸ்பர்" to "transfer", "ட்ரான்ஸ்ஃபர்" to "transfer",
        "கிரெடிட்" to "credit", "கிரெடிட்ல" to "credit la", "க்ரெடிட்" to "credit", "டெபிட்" to "debit", "உதார்" to "udhaar",
        "பெண்டிங்" to "pending", "பேலன்ஸ்" to "balance", "லிஸ்ட்" to "list", "டோட்டல்" to "total", "டோட்டலா" to "total", "டோட்டல்ல" to "total la", "மொத்தத்துல" to "mothathula", "மொத்தம்" to "motham", "மொத்தமா" to "mothama",
        "டியூ" to "due", "டேட்" to "date", "தேதி" to "thethi", "வேண்டாம்" to "venam", "வேணாம்" to "venam", "வேணும்" to "venum",
        "கஸ்டமர்" to "customer", "சப்ளையர்" to "supplier", "ஓனர்" to "owner", "அண்ணா" to "anna", "அண்ணே" to "anna", "சார்" to "sir",
        "சரி" to "seri", "ஓகே" to "ok", "ஆமா" to "aama", "ஆமாம்" to "aama", "இல்ல" to "illa", "இல்லை" to "illa", "சேவ்" to "save",
        "இப்போ" to "ippo", "இப்ப" to "ippa", "இப்பொழுது" to "ippo", "எப்போ" to "eppo", "எப்ப" to "eppa", "எப்படி" to "eppadi",
        "யார்" to "yaar", "யாரு" to "yaaru", "யாருக்கு" to "yaarukku", "யாருகிட்ட" to "yaarukitta", "யாரெல்லாம்" to "yaarellam",
        "கொஞ்சம்" to "konjam", "ஃபுல்" to "full", "ஃபுல்லா" to "fulla", "முழுசா" to "muzhusa", "ரூபாய்" to "rupees", "ரூபா" to "rupees",
        // ---- stock (what typed Tanglish already says) ----
        "வித்துச்சு" to "vithuchu", "வித்துடுச்சு" to "vithiruchu", "வித்துட்டேன்" to "vithutten", "வித்தேன்" to "vithen", "விக்கல" to "vikkala",
        "ஆயிடுச்சு" to "aagiduchu", "ஆகிடுச்சு" to "aagiduchu", "ஆயிருச்சு" to "aagiduchu", "உடைஞ்சிடுச்சு" to "udanjiduchu", "உடைஞ்சுச்சு" to "udanjuchu",
        "கெட்டுப்போச்சு" to "kettupochu", "போயிடுச்சு" to "poiduchu", "சேர்த்துக்கோ" to "add pannu", "சேர்த்துடு" to "add pannu", "ஸ்டாக்ல" to "stock la",
        "டேமேஜ்" to "damage", "ரிட்டர்ன்" to "return", "எக்ஸ்பைரி" to "expiry", "லோ" to "low", "ஃபாஸ்ட்" to "fast", "ஸ்லோ" to "slow", "மூவ்" to "move",
        "ஆகுது" to "aagudhu", "ஆகல" to "agala", "சேல்ஸ்" to "sales", "இருப்பு" to "iruppu", "கையில" to "kaila",
        "மூட்டை" to "moota", "பாட்டில்" to "bottle", "லிட்டர்" to "litre", "டஜன்" to "dozen", "பீஸ்கள்" to "pieces",
    ).sortedByDescending { it.first.length }

    private val wordMap = words.toMap()

    /** Time units with any ending ("நிமிஷத்துல", "மினிட்ஸ்ல", "மணிநேரத்துல"): the unit, plus "la" when it ended in -ல. */
    private val units: List<Pair<Regex, String>> = listOf(
        Regex("""மணி\s*நேர[$TA]*""") to "hours",
        Regex("""(?:நிமிஷ|நிமிட|நிமிச|மினிட்|மினிட)[$TA]*""") to "minutes",
        Regex("""(?:வினாடி|செகண்ட்|செகன்ட்|செக்கண்ட்)[$TA]*""") to "seconds",
        Regex("""(?:ஹவர்|ஹவர்ஸ்)[$TA]*""") to "hours",
        Regex("""(?:நாள்|டேஸ்|டேய்ஸ்)[$TA]*""") to "days",
    )

    private val tamilWord = Regex("""[$TA]+""")

    fun hasTamil(text: String) = text.any { it in '஀'..'௿' }

    /** The sentence with its command words in Tanglish; names and other words stay as said. */
    /** "ரெண்டாயிரம்" = 2000, "ஐநூறு" = 500; "ஆயிரத்து ஐநூறு" = 1500. */
    private val thousands = listOf("ஒரு ஆயிரம்" to 1, "ஆயிரம்" to 1, "ரெண்டாயிரம்" to 2, "இரண்டாயிரம்" to 2, "மூவாயிரம்" to 3, "மூணாயிரம்" to 3,
        "நாலாயிரம்" to 4, "நான்காயிரம்" to 4, "அஞ்சாயிரம்" to 5, "ஐயாயிரம்" to 5, "ஐந்தாயிரம்" to 5, "ஆறாயிரம்" to 6, "ஏழாயிரம்" to 7,
        "எட்டாயிரம்" to 8, "ஒம்பதாயிரம்" to 9, "ஒன்பதாயிரம்" to 9, "பத்தாயிரம்" to 10)
    private val hundreds = listOf("நூறு" to 100, "இருநூறு" to 200, "எரநூறு" to 200, "முன்னூறு" to 300, "நானூறு" to 400, "ஐநூறு" to 500,
        "அஞ்சூறு" to 500, "அறநூறு" to 600, "எழுநூறு" to 700, "எண்ணூறு" to 800, "தொள்ளாயிரம்" to 900)
    private val thousandsMap = thousands.toMap() + thousands.associate { (w, n) -> w.removeSuffix("ம்") + "த்து" to n }
    private val hundredsMap = hundreds.toMap()
    private val amountWords = Regex("""(?<![$TA])(${thousandsMap.keys.sortedByDescending { it.length }.joinToString("|")})(?:\s+(${hundreds.map { it.first }.sortedByDescending { it.length }.joinToString("|")}))?(?![$TA])|""" +
        """(?<![$TA])(${hundreds.map { it.first }.sortedByDescending { it.length }.joinToString("|")})(?![$TA])""")

    /** "ரெண்டு ஆயிரம்", "மூணு ஆயிரம்": a number said, then thousand. */
    private val countThousand = Regex("""(?<![$TA])(ஒரு|ஒன்னு|ரெண்டு|இரண்டு|மூணு|மூன்று|நாலு|நான்கு|அஞ்சு|ஐந்து|ஆறு|ஏழு|எட்டு|ஒன்பது|பத்து)\s+ஆயிரம்(?![$TA])""")
    private val counts = mapOf("ஒரு" to 1, "ஒன்னு" to 1, "ரெண்டு" to 2, "இரண்டு" to 2, "மூணு" to 3, "மூன்று" to 3, "நாலு" to 4, "நான்கு" to 4,
        "அஞ்சு" to 5, "ஐந்து" to 5, "ஆறு" to 6, "ஏழு" to 7, "எட்டு" to 8, "ஒன்பது" to 9, "பத்து" to 10)

    private fun amounts(raw: String): String = amountWords.replace(countThousand.replace(raw) { m -> "${counts.getValue(m.groupValues[1]) * 1000}" }) { m ->
        val (t, h, alone) = m.destructured
        if (alone.isNotEmpty()) hundredsMap.getValue(alone).toString()
        else (thousandsMap.getValue(t) * 1000 + (hundredsMap[h] ?: 0)).toString()
    }

    fun normalize(text: String): String {
        if (!hasTamil(text)) return text
        var s = amounts(text)
        // Units first ("மணி நேரத்துல" before "மணி").
        for ((re, unit) in units) {
            s = re.replace(s) { m -> if (m.value.endsWith("ல") || m.value.endsWith("ல்") || m.value.endsWith("லே")) "$unit la" else unit }
        }
        s = tamilWord.replace(s) { m -> wordMap[m.value] ?: dative(m.value)?.let { d -> wordMap[d.substringBefore('-')]?.let { "$it-${d.substringAfter('-')}" } ?: d } ?: m.value }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Tamil-script words that are a name the shop keeps in English letters: "குமார்" → Kumar, "ஸ்டோர்ஸ்-ku" → Stores-ku,
     * "ஏபிசி" → ABC (letters said one by one). Only a word that sounds like exactly one of [names]' words; a new name
     * ("சுஜித்") stays as said.
     */
    fun withNames(text: String, names: List<String>): String {
        if (!hasTamil(text)) return text
        val nameWords = names.flatMap { it.trim().split(Regex("""\s+""")) }.filter { it.length >= 2 && !hasTamil(it) && it.any(Char::isLetter) }
            .distinctBy { it.lowercase() }
        if (nameWords.isEmpty()) return text
        val shopWords = names.map { it.trim() } + nameWords
        val tamilKept = names.flatMap { it.trim().split(Regex("""\s+""")) }.filter(::hasTamil).toSet()
        return Regex("""([$TA]+)(-ku|-kitta)?""").replace(text) { m ->
            val w = m.groupValues[1]
            // A name the shop keeps in Tamil stays; "அரிசி", "டீ தூள்": goods by their common name (KaiFeed), never a person.
            if (w in tamilKept) return@replace m.value
            KaiFeed.productNames[w]?.let { english -> if (shopWords.any { it.equals(english, ignoreCase = true) }) return@replace english + m.groupValues[2] }
            nameWords.filter { n -> spelled(n) == w || startsAlike(w, n) && (sounds(w, n) || sounds(w, soft(n))) }.singleOrNull()
                ?.let { return@replace it + m.groupValues[2] }
            // A name cut short, said right before its quantity ("கோல்கா 1 பாக்ஸ்" = Colgate): the one name it is the start of.
            val beforeQty = Regex("""^\s*\d""").containsMatchIn(text.substring(m.range.last + 1))
            val key = com.shopai.app.util.NameSound.key(w)
            if (beforeQty && key.length >= 3) nameWords.filter { n -> startsAlike(w, n) && com.shopai.app.util.NameSound.key(n).let { it.length == key.length + 1 && it.startsWith(key) } }
                .singleOrNull()?.let { return@replace it + m.groupValues[2] }
            m.value
        }
    }

    private fun sounds(tamilWord: String, name: String) = com.shopai.app.util.NameSound.same(tamilWord, name)

    /** "Agencies" is said "ஏஜென்சீஸ்": c / g before e / i sound soft (s / j). */
    private fun soft(name: String) = name.replace(Regex("""(?i)c(?=[eiy])"""), "s").replace(Regex("""(?i)g(?=[ei])"""), "j")

    /** Both start with a vowel, or both with a consonant ("அரிசி" is not "Raja"). */
    private fun startsAlike(tamilWord: String, name: String): Boolean {
        val tamilVowel = tamilWord.first() in '\u0B85'..'\u0B94'
        val latinVowel = name.first().lowercaseChar() in "aeiou"
        return tamilVowel == latinVowel
    }

    private val letters = mapOf('A' to "ஏ", 'B' to "பி", 'C' to "சி", 'D' to "டி", 'E' to "ஈ", 'F' to "எஃப்", 'G' to "ஜி", 'H' to "எச்",
        'I' to "ஐ", 'J' to "ஜே", 'K' to "கே", 'L' to "எல்", 'M' to "எம்", 'N' to "என்", 'O' to "ஓ", 'P' to "பி", 'Q' to "க்யூ", 'R' to "ஆர்",
        'S' to "எஸ்", 'T' to "டி", 'U' to "யூ", 'V' to "வி", 'W' to "டபிள்யூ", 'X' to "எக்ஸ்", 'Y' to "ஒய்", 'Z' to "இசட்")

    /** "ABC" said letter by letter, as Tamil speech-to-text writes it ("ஏபிசி"). */
    private fun spelled(word: String): String? =
        if (word.length in 2..5 && word.all { it in 'A'..'Z' }) word.map { letters.getValue(it) }.joinToString("") else null

    /**
     * "ருத்ரனுக்கு" (to Ruthran) → "ருத்ரன்-ku", "குமாருக்கு" → "குமார்-ku": the
     * name with Kai's "-ku", so the person rules find it.
     */
    private fun dative(word: String): String? {
        val stem = when {
            word.endsWith("க்கு") -> word.removeSuffix("க்கு")
            word.endsWith("கிட்ட") -> return word.removeSuffix("கிட்ட") + "-kitta"
            else -> return null
        }
        if (stem.length < 2) return null
        // Consonant + "ு" (the -உக்கு ending) → the consonant with its pulli: ருத்ரனு → ருத்ரன்.
        val fixed = if (stem.endsWith("ு") && stem.length >= 2) stem.dropLast(1) + "்" else stem
        return "$fixed-ku"
    }
}
