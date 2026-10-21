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
        // The numbers as they are said (owner's mic, 12 Oct 2026: "அம்பது ரூபா" for a selling rate was not understood).
        "அம்பது" to "50", "அம்பதுக்கு" to "50", "இருவது" to "20", "நாப்பது" to "40", "அறுபது" to "60", "அறுவது" to "60", "எழுபது" to "70",
        "எழுவது" to "70", "எண்பது" to "80", "எம்பது" to "80", "தொண்ணூறு" to "90", "பதினொன்னு" to "11", "பன்னெண்டு" to "12",
        "பன்னிரண்டு" to "12", "இருபத்தஞ்சு" to "25", "இருவத்தஞ்சு" to "25", "முப்பத்தஞ்சு" to "35", "நாப்பத்தஞ்சு" to "45",
        "அம்பத்தஞ்சு" to "55", "எழுபத்தஞ்சு" to "75", "நூத்தம்பது" to "150", "ரூவா" to "rupees", "ரூபாய்க்கு" to "rupees",
        // A day of the month said as an ordinal: "அஞ்சாம் தேதி" = the 5th (owner's mic, 12 Oct 2026).
        "ஒண்ணாம்" to "1-aam", "ஒன்றாம்" to "1-aam", "ரெண்டாம்" to "2-aam", "இரண்டாம்" to "2-aam", "மூணாம்" to "3-aam", "மூன்றாம்" to "3-aam",
        "நாலாம்" to "4-aam", "நான்காம்" to "4-aam", "அஞ்சாம்" to "5-aam", "ஐந்தாம்" to "5-aam", "ஆறாம்" to "6-aam", "ஏழாம்" to "7-aam",
        "எட்டாம்" to "8-aam", "ஒம்பதாம்" to "9-aam", "ஒன்பதாம்" to "9-aam", "பத்தாம்" to "10-aam", "பதினஞ்சாம்" to "15-aam",
        "பதினைந்தாம்" to "15-aam", "இருபதாம்" to "20-aam", "இருவதாம்" to "20-aam", "இருபத்தஞ்சாம்" to "25-aam", "முப்பதாம்" to "30-aam",
        // "அதுகூட 100 ஆட் பண்ணிக்கோ": on top of the entry just saved.
        "பண்ணிக்கோ" to "panniko", "பண்ணிக்கோங்க" to "pannikonga", "பண்ணிக்கங்க" to "pannikonga", "பண்ணிக்க" to "pannikka",
        // Weekdays as said ("வெள்ளிக்கிழமை" to "due date eppa?" was answered as a date question).
        "திங்கக்கிழமை" to "monday", "திங்கள்கிழமை" to "monday", "செவ்வாய்க்கிழமை" to "tuesday", "செவ்வாகிழமை" to "tuesday",
        "புதன்கிழமை" to "wednesday", "வியாழக்கிழமை" to "thursday", "வெள்ளிக்கிழமை" to "friday", "சனிக்கிழமை" to "saturday",
        "ஞாயிற்றுக்கிழமை" to "sunday", "ஞாயித்துக்கிழமை" to "sunday", "ஞாயிறு" to "sunday",
        "அதுகூட" to "athukuda", "அதுக்கூட" to "athukuda", "அதுகூடவே" to "athukuda", "அதோடு" to "adhoda",
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
        // How owners say an entry in other words (owner's dataset, 12 Oct 2026; KaiLedgerPhrases). Common words the rest of
        // Kai still reads in Tamil ("தர வேண்டும்", "வரவு", "கொடுக்க") are not mapped; KaiLedgerPhrases reads them either way.
        "தரல" to "tharala", "கொடுக்கல" to "kudukala", "குடுக்கல" to "kudukala", "வேண்டிய" to "vendiya",
        "ரூபாயை" to "roobaya", "கணக்குல" to "kanakkula", "தொகையை" to "thogaiya", "தொகையோட" to "thogaiyoda",
        "அதிகப்படுத்து" to "adhigapaduthu", "பாக்கியோட" to "baakiyoda", "கூடுதலா" to "kooduthalaa", 
        "பாக்கிய" to "baakiya", "கூட்டி" to "kootti", "பாக்கில" to "baakila",
        "கொடுத்த" to "kudutha", "குடுத்த" to "kudutha", "வரவுல" to "varavula", "செலவுல" to "selavula", 
        "செலவாச்சு" to "selavaachu", "வேகமா" to "vegamaa", "பதிவுல" to "padhivula", "தடவை" to "thadava", "சீக்கிரம்" to "seekiram",
        "செட்டில்" to "settle",
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
        "வந்திருக்கு" to "vandhiruku", "வந்துருக்கு" to "vandhuruku",
        // The phone's mic often writes the written forms (owner, 18 Oct 2026: "கடைக்கு சரக்கு வந்திருக்கிறது" was not read).
        "வந்திருக்கிறது" to "vandhiruku", "வந்திருக்குது" to "vandhiruku", "வந்துருக்குது" to "vandhuruku", "வந்துள்ளது" to "vandhiruku",
        "வந்துள்ளன" to "vandhiruku", "வந்திருக்கின்றன" to "vandhiruku", "வந்துவிட்டது" to "vandhuchu", "வந்துட்டுது" to "vandhuchu", "வந்துச்சு" to "vandhuchu", "வந்துருச்சு" to "vandhuchu", "வந்துடுச்சு" to "vandhuchu", "வந்திருச்சு" to "vandhuchu", "வந்துட்டு" to "vandhuchu",
        // Months said aloud ("மே 5 தரணும்" was read as "5th of next month" — owner, 11 Oct 2026).
        "ஜனவரி" to "january", "சனவரி" to "january", "பிப்ரவரி" to "february", "பெப்ரவரி" to "february", "மார்ச்" to "march", "ஏப்ரல்" to "april",
        "மே" to "may", "ஜூன்" to "june", "ஜுன்" to "june", "ஜூலை" to "july", "ஜுலை" to "july", "ஆகஸ்ட்" to "august", "ஆகஸ்டு" to "august",
        "செப்டம்பர்" to "september", "செப்டெம்பர்" to "september", "அக்டோபர்" to "october", "நவம்பர்" to "november", "டிசம்பர்" to "december", "புது" to "pudhu", "புதுசா" to "pudhusa",
        "அவுட்" to "out", "போச்சு" to "pochu", "சேல்" to "sale", "ஆச்சு" to "aachu", "எடுத்துட்டாங்க" to "eduthutanga",
        "பீஸ்" to "pieces", "பீசஸ்" to "pieces", "பாக்ஸ்" to "box", "பாக்கெட்" to "packet", "பாக்கெட்டு" to "packet", "பாக்கெட்டுக்கு" to "packetku", "பாக்கெடுக்கு" to "packetku", "பாக்ஸுக்கு" to "boxku", "ஸ்மால்" to "small", "மீடியம்" to "medium", "லார்ஜ்" to "large", "ஃபேமிலி" to "family", "சின்ன" to "chinna", "பெரிய" to "periya", "அரைக்கிலோ" to "arakilo", "அரைகிலோ" to "arakilo", "கால்கிலோ" to "kaalkilo", "முக்கால்கிலோ" to "mukkaalkilo", "கிலோ" to "kg",
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
        // A person's statement as a document (owner, 12 Oct 2026: "குமார் கணக்கு பிடிஎஃப் வேணும்", "ஸ்டேட்மெண்ட் வாட்ஸ்அப்ல அனுப்பு").
        "பிடிஎஃப்" to "pdf", "பிடிஎப்" to "pdf", "பீடிஎஃப்" to "pdf", "பிடிஎஃப்ல" to "pdf la", "பிடிஎஃப்பா" to "pdf-a", "ஸ்டேட்மென்ட்" to "statement",
        "ஸ்டேட்மெண்ட்" to "statement", "ஸ்டேட்மென்ட்ட" to "statement", "லெட்ஜர்" to "ledger", "ஷேர்" to "share", "டவுன்லோட்" to "download",
        "டவுன்லோடு" to "download", "பிரிண்ட்" to "print", "ப்ரிண்ட்" to "print", "அனுப்பு" to "anuppu", "அனுப்புங்க" to "anuppunga",
        "அனுப்பிவிடு" to "anuppividu", "வாட்ஸ்அப்பில்" to "whatsapp la", "வாட்ஸ்அப்புல" to "whatsapp la", "டீடெயில்ஸ்" to "details",
        "ஓட" to "oda", "குடு" to "kudu", "குடுங்க" to "kudunga", "போன" to "pona",
        "பிடிஎஃப்ஆ" to "pdf-a", "பிடிஎஃப்ப" to "pdf-a", "பிடிஎஃப்பை" to "pdf-a", "பிடிஎஃப்ஃபா" to "pdf-a", "இத" to "itha", "இதை" to "idhai", "இதையும்" to "idhaiyum", "அத" to "atha", "அதை" to "adhai",
        "வாட்ஸ்அப்ல" to "whatsapp la", "ரெண்டு பேரும்" to "rendu perum", "பேரும்" to "perum", "ஃபர்ஸ்ட்" to "first", "முதல்ல" to "mudhalla", "அதே" to "adhe", "முன்னாடி" to "munnadi", "இவன்" to "ivan", "இவர்" to "ivar", "இவங்க" to "ivanga", "அதோட" to "adhoda", "முதல்" to "mudhal", "ரெண்டாவது" to "rendavadhu", "இரண்டாவது" to "rendavadhu", "மூணாவது" to "moonavadhu", "மூன்றாவது" to "moonavadhu", "நாலாவது" to "naalavadhu", "கடைசி" to "kadaisi",
        // An accountant's words said aloud (owner, 11 Oct 2026).
        "பர்சேஸ்" to "purchase", "செல்லிங்" to "selling", "பிரைஸ்" to "price", "மார்ஜின்" to "margin", "ப்ராஃபிட்" to "profit", "ப்ராபிட்" to "profit",
        // (advice, a shop holiday and a utility bill said aloud — owner, 14 Oct 2026: "என்ன பிசினஸ் பண்ணலாம்", "லைட் பில் 1200 கட்டினேன்")
        "பண்ணலாம்" to "pannalam", "ஐடியா" to "idea", "ஜாஸ்தி" to "jaasthi", "ஆக" to "aaga", "லீவு" to "leave", "லீவ்" to "leave",
        "லைட்" to "light", "வாட்டர்" to "water", "ஈபி" to "eb",
        "லாபம்" to "labam", "பிசினஸ்" to "business", "போகுது" to "pogudhu", "வேல்யூ" to "value", "வர" to "vara", "கலெக்ஷன்" to "collection", "வேண்டியது" to "vendiyadhu",
        "டிரான்ஸாக்ஷன்" to "transaction", "டிரான்ஸாக்ஷன்ஸ்" to "transactions", "பில்ஸ்" to "bills", "லாஸ்" to "loss", "டேக்ஸ்" to "tax", "அட்வான்ஸ்" to "advance",
        "டிஸ்கவுண்ட்" to "discount", "செலவு" to "selavu", "வியாபாரம்" to "viyabaram", "சம்மரி" to "summary", "இருக்காங்க" to "irukkanga",
        "தீரப்போகுது" to "theerapogudhu",
        // Context words said aloud (owner, 12 Oct 2026): "அந்த ஆளு", "ரெண்டு பேரோட", "வித்துட்டோம்", "மீதி".
        "அந்த" to "andha", "பேரு" to "peru", "பேரோட" to "peroda", "வித்துட்டோம்" to "vithutom", "வித்தோம்" to "vithom", "மீதி" to "meedhi", "வசூல்" to "vasool", "ஷீட்" to "sheet",
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
        "கிராம்" to "gram", "இன்ச்" to "inch", "சாமான்" to "saamaan", "சரக்கு" to "saraku", "கடைக்கு" to "kadaiku", "சேர்க்கலாமா" to "serkkalama",
        "அவ்ளோ" to "avlo", "பண்ணலாமா" to "pannalama", "அவ்வளவு" to "avvalavu", "போதும்" to "podhum", "கேட்டேன்" to "ketten", "கேட்டோம்" to "kettom", "இஞ்ச்" to "inch", "கிராமு" to "gram", "கிராம்ஸ்" to "grams", "மில்லி" to "ml", "ஸ்ட்ரிப்" to "strip", "எக்ஸ்பைர்" to "expire", "எக்ஸ்பையர்" to "expire", "எக்ஸ்பையரி" to "expiry", "பேட்ச்" to "batch", "ஆம்ப்" to "amp", "ஆம்பியர்" to "amp", "வாட்" to "watt", "வாட்ஸ்" to "watt", "வோல்ட்" to "volt", "டப்பா" to "dabba", "டப்பாக்கள்" to "dabba", "டின்" to "tin", "பக்கெட்" to "bucket", "பக்கெட்டு" to "bucket", "சுருள்" to "churul", "ஜார்" to "jar", "காயில்" to "coil", "காய்ல்" to "coil", "ராட்" to "rod", "ராடு" to "rod", "ராட்ஸ்" to "rod", "லெங்த்" to "length", "லென்த்" to "length", "நாஸ்" to "nos", "நோஸ்" to "nos", "ஆம்ப்ஸ்" to "amps", "இன்டு" to "into", "எம்எல்" to "ml", "ஸ்கொயர்" to "square", "முக்கால்" to "mukkal",
        // Units of the Unit Master and the app's screens (owner, 20 Oct 2026: "features ellam Kai-ku theriyanum, voice chat-laiyum").
        "பவுச்" to "pouch", "சாஷே" to "sachet", "சாசே" to "sachet", "சாஷெ" to "sachet", "டிரம்" to "drum", "ட்ரம்" to "drum", "பேல்" to "bale", "ஸ்பூல்" to "spool", "ரீல்" to "reel", "கோன்" to "cone", "ஆம்பியூல்" to "ampoule", "ஆம்பூல்" to "ampoule", "டோஸ்" to "dose", "கஜம்" to "gajam", "யார்டு" to "yard", "யார்ட்" to "yard", "ப்ராடக்ட்" to "product", "ப்ரொடக்ட்" to "product", "புராடக்ட்" to "product", "ப்ராடக்" to "product", "மாஸ்டர்" to "master", "ஃபார்ம்" to "form", "ஸ்கிரீன்" to "screen", "கடை" to "kadai", "கடையோட" to "kadaiyoda", "வகை" to "vagai", "வகைகள்" to "vagaigal", "கேட்டகிரி" to "category", "கேட்டகரி" to "category", "கேட்டகிரிஸ்" to "categories", "வியாபார" to "viyabara", "டைப்" to "type", "கொள்முதல்" to "kolmudhal", "வாங்கின" to "vaangina", "டிராஃப்ட்" to "draft", "டிராப்ட்" to "draft", "ட்ராஃப்ட்" to "draft", "ட்ராப்ட்" to "draft",
        "ஒன்றரை" to "onnara", "ஒண்ணரை" to "onnara", "அக்கா" to "akka", "செட்டில்மென்ட்" to "settlement", "செட்டில்மெண்ட்" to "settlement", "முடிச்சிட்டான்" to "mudichitaan", "முடிச்சுட்டான்" to "mudichutaan", "முடிச்சிட்டாங்க" to "mudichitaanga", "முடிச்சிட்டாரு" to "mudichitaaru", "தேதிக்குள்" to "thethikulla", "தேதிக்குள்ள" to "thethikulla", "தேதிக்குள்ளே" to "thethikulla", "தேதிக்குள்ளார" to "thethikulla", "தம்பி" to "thambi", "மாதம்" to "maasam", "ஒன்னரை" to "onnara", "ரெண்டரை" to "rendara", "இரண்டரை" to "rendara", "மூணரை" to "moonara", "மூன்றரை" to "moonara", "நாலரை" to "naalara", "அஞ்சரை" to "anjara", "டேப்லெட்" to "tablet", "டேப்லெட்ஸ்" to "tablets", "டாப்லெட்" to "tablet", "ஒன்றேகால்" to "onnekaal", "டியூப்" to "tube", "டியூப்ஸ்" to "tubes", "பேட்ச்சு" to "batch", "பேட்ச்ல" to "batch", "காலாவதியான" to "expired", "எம்எம்" to "mm", "எம்.எம்" to "mm", "இன்ச்" to "inch", "குவிண்டால்" to "quintal", "டன்" to "tonne", "லோடு" to "load", "லோட்" to "load", "கிட்" to "kit", "சிஎஃப்டி" to "cft", "மீட்டர்" to "meter", "செட்" to "set", "ரோல்" to "roll",
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
        "வாங்குனேன்" to "vangunen", "வாங்குனான்" to "vangunan", "வாங்குனது" to "vangunadhu", "அனுப்பிச்சான்" to "anuppichan", "அனுப்பிச்சாரு" to "anuppichaaru",
        "அனுப்பிச்சேன்" to "anuppichen", "தந்துட்டாங்க" to "thandhutaanga", "தந்துட்டாரு" to "thandhutaaru", "தராங்க" to "tharanga", "தர்றாங்க" to "tharraanga",
        "இறக்கினேன்" to "irakkinen", "இறக்கிட்டேன்" to "irakkitten", "பொட்டலம்" to "pottalam", "பொட்டலங்கள்" to "pottalangal", "பேக்" to "bag",
        "பேக்ஸ்" to "bags", "ஓவர்டியூ" to "overdue", "கஸ்டமர்ஸ்" to "customers", "சப்ளையர்ஸ்" to "suppliers", "எல்லா" to "ellaa", "எல்லாம்" to "ellam",
        "எல்லாரும்" to "ellaarum", "கம்மியா" to "kammiya", "அதிகமா" to "adhigama", "வித்தது" to "vithadhu", "மாசம்" to "maasam", "வாரம்" to "vaaram",
        "டெய்லி" to "daily", "தினமும்" to "daily", "ஸ்கிப்" to "skip", "எந்த" to "endha", "ஹிஸ்டரி" to "history", "லாஸ்ட்" to "last",
        "குடுத்தேன்" to "kuduthen", "கொடுத்தேன்" to "kuduthen", "குடுத்துட்டேன்" to "kuduthuten", "கொடுத்துட்டேன்" to "kuduthuten",
        "தந்தேன்" to "kuduthen", "அனுப்பினேன்" to "anuppinen", "அனுப்பிட்டேன்" to "anuppiten", "அனுப்பிச்சேன்" to "anuppichen",
        "கட்டினேன்" to "kattinen", "கட்டிட்டேன்" to "kattiten", "வாங்கிட்டேன்" to "vangiten", "வாங்கினேன்" to "vaanginen", "வாங்கி" to "vaangi", "இருக்கேன்" to "irukken", "இருக்கான்" to "irukkaan", "வாங்கியிருக்கேன்" to "vaangi irukken", "வாங்கிருக்கேன்" to "vaangi irukken", "வாங்கியிருக்கான்" to "vaangi irukkaan", "வாங்கிருக்கான்" to "vaangi irukkaan",
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
        // English said into the Tamil mic (release check, 20 Oct 2026: "ரிமைண்ட் மீ டுமாரோ அட் 5 பிஎம்" was set for today, "லட்சுமி பெய்டு 500" read the balance).
        "டுமாரோ" to "tomorrow", "டுமாரொ" to "tomorrow", "டுமோரோ" to "tomorrow", "டுமார்ரோ" to "tomorrow", "டூமாரோ" to "tomorrow", "டுடே" to "today", "டூடே" to "today",
        "பெய்டு" to "paid", "பெய்ட்" to "paid", "பெயிட்" to "paid", "பேய்டு" to "paid", "ரிசீவ்டு" to "received", "ரிசீவ்ட்" to "received", "சோல்டு" to "sold", "சோல்ட்" to "sold",
        "ஆடட்" to "added", "ஆடெட்" to "added", "ஆட்டட்" to "added", "பாக்ஸஸ்" to "boxes", "பாக்சஸ்" to "boxes",
        "ஜிபே" to "gpay", "கூகுள்பே" to "gpay", "போன்பே" to "phonepe", "பேடிஎம்" to "paytm", "யுபிஐ" to "upi", "பே" to "pay", "ஜிபேல" to "gpay la",
        "பேமெண்ட்" to "payment", "பேமென்ட்" to "payment", "பேமண்ட்" to "payment", "செட்டில்" to "settle", "கிளியர்" to "clear",
        // "லட்சுமி 300 தந்தா", "குமார் கிட்ட பேசணும்", "ரெண்ட் கட்டணும்", "கரண்ட் பில் 1200 கட்டினேன்" (owner, 12 Oct 2026).
        "தந்தா" to "thandhaa", "தந்துட்டா" to "thandhuttaa", "பேசணும்" to "pesanum", "பேசனும்" to "pesanum", "ரெண்ட்" to "rent",
        "வாடகை" to "vaadagai", "கரண்ட்" to "current", "சேலரி" to "salary", "சம்பளம்" to "sambalam", "செட்டில்ட்" to "settled",
        "கேஷ்" to "cash", "கேஷா" to "cash", "ஆன்லைன்" to "online", "ஆன்லைன்ல" to "online la", "டிரான்ஸ்பர்" to "transfer", "ட்ரான்ஸ்ஃபர்" to "transfer",
        "கிரெடிட்" to "credit", "கிரெடிட்ல" to "credit la", "க்ரெடிட்" to "credit", "டெபிட்" to "debit", "உதார்" to "udhaar",
        "பெண்டிங்" to "pending", "பேலன்ஸ்" to "balance", "லிஸ்ட்" to "list", "டோட்டல்" to "total", "டோட்டலா" to "total", "டோட்டல்ல" to "total la", "மொத்தத்துல" to "mothathula", "மொத்தம்" to "motham", "மொத்தமா" to "mothama",
        "டியூ" to "due", "டேட்" to "date", "தேதி" to "thethi", "வேண்டாம்" to "venam", "வேணாம்" to "venam", "எடுத்துடு" to "eduthudu", "எடுத்துடுங்க" to "eduthudunga", "வேணும்" to "venum",
        "கஸ்டமர்" to "customer", "சப்ளையர்" to "supplier", "ஓனர்" to "owner", "அண்ணா" to "anna", "அண்ணே" to "anna", "சார்" to "sir",
        "சரி" to "seri", "ஓகே" to "ok", "ஆமா" to "aama", "ஆமாம்" to "aama", "இல்ல" to "illa", "இல்லை" to "illa", "தெரியாது" to "theriyaadhu", "தெரியல" to "theriyala", "தெரியலை" to "theriyala", "சேவ்" to "save",
        "இப்போ" to "ippo", "இப்ப" to "ippa", "இப்பொழுது" to "ippo", "எப்போ" to "eppo", "எப்ப" to "eppa", "எப்படி" to "eppadi",
        "யார்" to "yaar", "யாரு" to "yaaru", "யாருக்கு" to "yaarukku", "யாருகிட்ட" to "yaarukitta", "யாரெல்லாம்" to "yaarellam",
        "கொஞ்சம்" to "konjam", "ஃபுல்" to "full", "ஃபுல்லா" to "fulla", "எல்லாத்தையும்" to "ellathaiyum", "முடங்கியிருக்கு" to "mudangi irukku", "முடங்கி" to "mudangi", "விக்காத" to "vikkaadha", "மெதுவா" to "medhuva", "அறிக்கை" to "arikkai", "விக்குற" to "vikkura", "போகல" to "pogala", "கம்மி" to "kammi", "ல" to "la", "நோட்" to "note", "நோட்டு" to "note", "எழுது" to "ezhudhu", "எழுதிக்கோ" to "ezhudhiko", "எழுதிடு" to "ezhudhidu",
        "எழுதுங்க" to "ezhudhunga", "என்ட்ரி" to "entry", "அமௌண்ட்" to "amount", "அமவுண்ட்" to "amount", "அக்கவுண்ட்" to "account", "அக்கவுண்ட்ல" to "account la",
        "கூட" to "kooda", "ஸ்ரீ" to "sri", "குறைச்சிடு" to "kuraichidu", "மைனஸ்" to "minus", "கழி" to "kali", "லெஸ்" to "less", "இருந்தா" to "iruntha", "இருக்குற" to "irukura", "சேரு" to "seru", "சேத்துக்கோ" to "seathuko", "ஸ்டாக்க" to "stock ah", "போல" to "pola", "போகாத" to "pogaadha", "போவுது" to "pogudhu", "ஐட்டம்" to "item", "ஐட்டங்கள்" to "items", "நிக்குது" to "nikkudhu", "நிக்கிற" to "nikkira", "நிக்குற" to "nikkura", "விக்குது" to "vikkudhu", "ரிப்போர்ட்" to "report", "பர்ஃபார்மன்ஸ்" to "performance", "பெர்ஃபார்மன்ஸ்" to "performance", "எல்லாத்தயும்" to "ellathaiyum", "முழுசா" to "muzhusa", "ரூபாய்" to "rupees", "ரூபா" to "rupees",
        // ---- stock (what typed Tanglish already says) ----
        "வித்துச்சு" to "vithuchu", "வித்துடுச்சு" to "vithiruchu", "வித்துட்டேன்" to "vithutten", "வித்தேன்" to "vithen", "விக்கல" to "vikkala",
        "ஆயிடுச்சு" to "aagiduchu", "ஆகிடுச்சு" to "aagiduchu", "ஆயிருச்சு" to "aagiduchu", "உடைஞ்சிடுச்சு" to "udanjiduchu", "உடைஞ்சுச்சு" to "udanjuchu",
        "கெட்டுப்போச்சு" to "kettupochu", "அழுகிடுச்சு" to "azhugiduchu", "அழுகிப்போச்சு" to "azhugipochu", "அழுகுச்சு" to "azhuguchu", "வாடிடுச்சு" to "vaadiduchu", "போயிடுச்சு" to "poiduchu", "போயிருச்சு" to "poiruchu", "போயிருச்சி" to "poiruchu", "போயிடுச்சி" to "poiduchu", "சேர்த்துக்கோ" to "add pannu", "சேர்த்துடு" to "add pannu", "ஸ்டாக்ல" to "stock la",
        "டேமேஜ்" to "damage", "ரிட்டர்ன்" to "return", "எக்ஸ்பைரி" to "expiry", "லோ" to "low", "ஃபாஸ்ட்" to "fast", "ஸ்லோ" to "slow", "மூவ்" to "move",
        "ஆகுது" to "aagudhu", "ஆகல" to "agala", "சேல்ஸ்" to "sales", "இருப்பு" to "iruppu", "கையில" to "kaila",
        "மூட்டை" to "moota", "பாட்டில்" to "bottle", "லிட்டர்" to "litre", "டஜன்" to "dozen", "பீஸ்கள்" to "pieces",
        // ---- the owner's own ways of saying it (19 Oct 2026; read by KaiIntentWords) ----
        // stock in
        "இறக்குமதி" to "irakkumadhi", "இறங்கிடுச்சு" to "irangiduchu", "இறங்குச்சு" to "iranguchu", "இறங்கிச்சு" to "irangichu",
        "சேர்ந்துச்சு" to "serndhuchu", "சேந்துச்சு" to "sendhuchu", "சேர்ந்திருக்கு" to "serndhirukku", "டெலிவரி" to "delivery", "அன்லோட்" to "unload",
        "அடுக்கினேன்" to "adukkinen", "அடுக்கிட்டேன்" to "adukkitten", "அடுக்கினோம்" to "adukkinom", "ஏத்திட்டேன்" to "eathitten", "ஏத்தினேன்" to "eathinen",
        "ஏத்தினோம்" to "eathinom", "கிடைச்சுச்சு" to "kidaichuchu", "கிடைச்சிடுச்சு" to "kidaichiduchu", "ரீஃபில்" to "refill", "ரேக்ல" to "rack la",
        "பண்ணோம்" to "pannom", "பண்ணினோம்" to "panninom",
        // stock out
        "தீர்ந்துடுச்சு" to "theernjuduchu", "தீர்ந்துச்சு" to "theerndhuchu", "தீர்ந்திடுச்சு" to "theernjuduchu", "காலி" to "kaali",
        "முடிஞ்சிடுச்சு" to "mudinjiduchu", "முடிஞ்சுச்சு" to "mudinjuchu", "வீட்டுக்கு" to "veetukku", "எடுத்துட்டேன்" to "eduthutten", "எடுத்தேன்" to "eduthen",
        "திருப்பி" to "thiruppi", "எலி" to "eli", "தின்னுடுச்சு" to "thinnuduchu", "தின்னிடுச்சு" to "thinniduchu", "கோடவுன்ல" to "godown la",
        "கோடவுன்" to "godown", "குடோன்ல" to "godown la", "சொந்த" to "sondha", "யூஸ்க்கு" to "use ku", "யூஸ்" to "use", "முடிச்சான்" to "mudichaan",
        "முடிச்சாங்க" to "mudichaanga", "கட்டி" to "katti",
        "ரேக்" to "rack", "அடுத்த" to "adutha", "சேத்து" to "sethu", "வேண்டி" to "vendi", "போட" to "poda", "அனுப்பணும்" to "anuppanum",
        "பூட்டணும்" to "poottanum", "ஆகணும்" to "aaganum", "நேரம்" to "neram", "ஃப்ரீ" to "free", "ஃப்ரீயா" to "free-a", "வந்து" to "vandhu", "கடிச்சிடுச்சு" to "kadichiduchu", "கடிச்சுச்சு" to "kadichuchu", "ஒழுகுச்சு" to "oluguchu",
        "ஒழுகிடுச்சு" to "olugiduchu", "பழசாகிடுச்சு" to "pazhasaagiduchu", "பழசாயிடுச்சு" to "pazhasaayiduchu", "திருடு" to "thirudu", "திருட்டு" to "thiruttu",
        // reminders
        "எழுப்பி" to "ezhuppi", "விடு" to "vidu", "விடுங்க" to "vidunga", "தட்டி" to "thatti", "மறந்துடாம" to "maranthudaama", "மறந்திடாம" to "maranthudaama",
        "மறக்காம" to "marakkaama", "பாத்துக்கோ" to "paathuko", "பார்த்துக்கோ" to "paathuko", "எழுதி" to "eluthi", "வை" to "vai", "வச்சுக்கோ" to "vachuko",
        "கேக்க" to "kekka", "கேட்க" to "kekka", "கட்ட" to "katta", "சனியும்" to "saniyum", "வெள்ளியும்" to "velliyum", "ஞாயிறும்" to "nyayirum",
        "மண்டே" to "monday", "டியூஸ்டே" to "tuesday", "ட்யூஸ்டே" to "tuesday", "வெட்னஸ்டே" to "wednesday", "தர்ஸ்டே" to "thursday", "தர்ஸ்டே" to "thursday",
        "ஃப்ரைடே" to "friday", "பிரைடே" to "friday", "ப்ரைடே" to "friday", "சாட்டர்டே" to "saturday", "சாட்டர்டேவும்" to "saturday", "சண்டே" to "sunday",
        // money
        "பக்கி" to "pakki", "வச்சுட்டு" to "vachuttu", "வெச்சுட்டு" to "vachuttu", "போனான்" to "ponaan", "போனாங்க" to "ponaanga", "போயிட்டான்" to "poyitaan",
        "சொல்லிட்டு" to "sollitu", "போட்டுக்கோ" to "potukko", "பற்று" to "patru", "வச்சாங்க" to "vechaanga", "வெச்சாங்க" to "vechaanga", "வச்சான்" to "vechaan",
        "பாதி" to "paadhi", "காசு" to "kaasu",
        // expiry
        "முடியுற" to "mudiyura", "முடியப்போகுது" to "mudiyapogudhu", "நெருங்குது" to "nerungudhu", "கெட்டுப்" to "kettu", "போகும்" to "pogum",
        "தாண்டிடுச்சு" to "thaandiduchu", "தாண்டின" to "thaandina", "பெஸ்ட்" to "best", "பிஃபோர்" to "before", "பிபோர்" to "before",
    ).distinctBy { it.first }.sortedByDescending { it.first.length }

    private val wordMap = words.toMap()

    /** Time units with any ending ("நிமிஷத்துல", "மினிட்ஸ்ல", "மணிநேரத்துல"): the unit, plus "la" when it ended in -ல. */
    private val units: List<Pair<Regex, String>> = listOf(
        Regex("""மணி\s*நேர[$TA]*""") to "hours",
        Regex("""(?:நிமிஷ|நிமிட|நிமிச|மினிட்|மினிட)[$TA]*""") to "minutes",
        Regex("""(?:வினாடி|செகண்ட்|செகன்ட்|செக்கண்ட்)[$TA]*""") to "seconds",
        Regex("""(?:ஹவர்|ஹவர்ஸ்)[$TA]*""") to "hours",
        Regex("""(?:நாள்|டேஸ்|டேய்ஸ்)[$TA]*""") to "days",
    )

    private val quarterWord = Regex("""(?<![$TA])கால்(?=\s+(?:கிலோ|லிட்டர்|லிட்டரு|மீட்டர்|டஜன்|லிட்டர்|kg|litre|meter)(?![$TA]))""")

    /** "ஃபுல் கால் வாக்ஸ்": கால் is the leg here, not "call" (release check, 20 Oct 2026: sold as plain "Waxing"). */
    private val legWax = Regex("""(?<![$TA])கால்\s+(?:வாக்ஸ்|வேக்ஸ்|வாக்சிங்|வேக்சிங்|வாக்ஸிங்)(?![$TA])""")

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

    /** English letters as ta-IN speech-to-text writes them ("பி" = B) — said before a number in a batch code ("பி 20" = B20). */
    private val codeLetters = mapOf("ஏ" to "A", "பி" to "B", "பீ" to "B", "சி" to "C", "சீ" to "C", "டி" to "D", "ஈ" to "E", "எஃப்" to "F", "ஜி" to "G",
        "எச்" to "H", "ஹெச்" to "H", "ஐ" to "I", "ஜே" to "J", "கே" to "K", "எல்" to "L", "எம்" to "M", "என்" to "N", "ஓ" to "O", "கியூ" to "Q", "க்யூ" to "Q",
        "ஆர்" to "R", "எஸ்" to "S", "டீ" to "T", "யூ" to "U", "வி" to "V", "எக்ஸ்" to "X", "ஒய்" to "Y", "வய்" to "Y", "இசட்" to "Z", "ஜெட்" to "Z")
    private val spokenCode = Regex("""(?<![\p{L}\p{M}])((?:${codeLetters.keys.sortedByDescending { it.length }.joinToString("|")})(?:\s?(?:${codeLetters.keys.sortedByDescending { it.length }.joinToString("|")}))?)\s?-?\s?(?=\d)""")

    /** A batch code said aloud: "பி 20" / "பிசி20" → "B20" / "BC20" (only letters right before a number — nothing else changes). */
    /** [code] only where the code is one the shop's product names have ("எம் 8" = M8 for Hex bolt M8; "டி 10 பாக்கெட்" stays D, 10 packets). */
    fun code(text: String, known: Set<String>): String = spokenCode.replace(text) { m ->
        val digits = Regex("""^\s*\d+""").find(text.substring(m.range.last + 1))?.value?.trim().orEmpty()
        val c = code(m.value + digits).removeSuffix(digits)
        if (digits.isNotEmpty() && c != m.value && (c.trim() + digits).uppercase() in known) c else m.value
    }

    fun code(text: String): String = spokenCode.replace(text) { m ->
        var rest = m.groupValues[1].replace(" ", "")
        val out = StringBuilder()
        while (rest.isNotEmpty()) {
            val k = codeLetters.keys.sortedByDescending { it.length }.firstOrNull { rest.startsWith(it) } ?: return@replace m.value
            out.append(codeLetters.getValue(k)); rest = rest.removePrefix(k)
        }
        out.toString()
    }

    /** Number words as [normalize] leaves them (Tanglish), for "a unit right after its number". */
    val numberWordsPattern = "onnu|oru|rendu|moonu|naalu|anju|aaru|ezhu|ettu|ombodhu|pathu|one|two|three|four|five|six|seven|eight|nine|ten"

    private fun wordRule(from: String, to: String) = Regex("""(?<![\p{L}\p{M}])$from(?![\p{L}\p{M}])""") to to
    private fun tailRule(from: String, to: String) = Regex("""(?<=[\p{L}\p{M}])$from(?![\p{L}\p{M}])""") to to
    /** "தர வேண்டும்", "கொடுக்க வேண்டும்", "பண்ண வேண்டும்": the verb + "-ணும்". */
    private val mustRule = Regex("""(?<![\p{L}\p{M}])([\u0B80-\u0BFF]*[\u0B95-\u0BB9])\s+வேண்டும்(?![\p{L}\p{M}])""")
    /** In order: each rule sees the line as the rules before it left it. Made once — [normalize] runs on every line. */
    private val writtenRules: List<Pair<Regex, String>> by lazy {
        listOf(
            wordRule("வேண்டும்", "வேணும்"), wordRule("வேண்டாம்", "வேணாம்"),
            tailRule("ருக்கிறதா", "ருக்கா"), tailRule("ருக்கிறது", "ருக்கு"), tailRule("ருக்கின்றன", "ருக்கு"),
            tailRule("கிறது", "குது"), tailRule("கிறார்கள்", "குறாங்க"), tailRule("கிறார்", "குறார்"), tailRule("கிறேன்", "குறேன்"), tailRule("கிறான்", "குறான்"),
            tailRule("ுங்கள்", "ுங்க"), tailRule("ார்கள்", "ாங்க"),
            wordRule("ஆகிவிட்டது", "ஆயிடுச்சு"),
            tailRule("துவிட்டேன்", "துட்டேன்"), tailRule("துவிட்டான்", "துட்டான்"), tailRule("துவிட்டார்", "துட்டார்"), tailRule("துவிட்டாங்க", "துட்டாங்க"),
            tailRule("துவிட்டது", "துடுச்சு"),
            tailRule("ிவிட்டேன்", "ிட்டேன்"), tailRule("ிவிட்டான்", "ிட்டான்"), tailRule("ிவிட்டார்", "ிட்டார்"), tailRule("ிவிட்டது", "ிடுச்சு"),
            wordRule("வந்தது", "வந்துச்சு"),
            Regex("""(?<![\p{L}\p{M}])கொடு""") to "குடு",
            wordRule("இல்லை", "இல்ல"), wordRule("ஆமாம்", "ஆமா"),
        )
    }

    /**
     * Written (formal) Tamil the phone's mic often writes, back to the spoken forms Kai's words are kept in (owner, 18 Oct 2026:
     * "கடைக்கு சரக்கு வந்திருக்கிறது" was not read; "text-la varudhu, mic-la varala"): "தர வேண்டும்" → "தரணும்", "இருக்கிறது" →
     * "இருக்கு", "சொல்லுங்கள்" → "சொல்லுங்க", "கொடுத்துவிட்டேன்" → "குடுத்துட்டேன்", "இல்லை" → "இல்ல". Only word endings and these
     * few words change; names and other words stay as written.
     */
    fun spoken(text: String): String {
        if (!hasTamil(text)) return text
        var s = mustRule.replace(text) { m -> m.groupValues[1] + "ணும்" }
        for ((re, to) in writtenRules) s = re.replace(s, to)
        return s
    }

    /**
     * Typed spellings that join two words the mic writes apart: "nyabagapaduthu" is the mic's "ஞாபகப்படுத்து" = "nyabagam paduthu"
     * (release check, 20 Oct 2026: typed, "…Kumar-kitta baaki ketkanum nyabagapaduthu" read Kumar's balance; by mic it set the
     * reminder). Only these joined forms change; everything else typed passes through.
     */
    private val joinedRemind = Regex("""(?i)(?<![\p{L}])(?:nyabaga|gnabaga|gnyabaga|nyaabaga|ngyabaga|nyabagam|gnabagam)p(?:adu|adh)t?th?u(nga|ngo)?(?![\p{L}])""")

    fun typed(text: String): String = if (hasTamil(text)) text else joinedRemind.replace(text) { m -> "nyabagam paduthu" + m.groupValues[1] }

    fun normalize(text: String): String {
        if (!hasTamil(text)) return text
        var s = amounts(spoken(text))
        // "கால் கிலோ" is a quarter kilo, not "call" (owner, 19 Oct 2026: "சுகர் கால் கிலோ வித்துச்சு").
        s = quarterWord.replace(s, "kaal")
        s = legWax.replace(s, "kaal wax")
        // "8 பை 4": a sheet's size said by mic — "by", never the bag பை (release check, 20 Oct 2026).
        s = Regex("""(\d)\s*பை\s*(\d)""").replace(s, "$1 by $2")
        // "டிசம்பர் 5ஆம் தேதிக்குள்": the day said with its "-ஆம்" (owner, 19 Oct 2026: "Sarath 5000 tharanum December 5" by mic asked
        // the due date again) — "5-aam", as "ஐந்தாம்" already reads.
        s = Regex("""(?<![\d])(\d{1,2})\s*(?:ஆம்|ம்|வது)(?![$TA])""").replace(s) { m -> "${m.groupValues[1]}-aam" }
        // Units first ("மணி நேரத்துல" before "மணி").
        for ((re, unit) in units) {
            s = re.replace(s) { m -> if (m.value.endsWith("ல") || m.value.endsWith("ல்") || m.value.endsWith("லே")) "$unit la" else unit }
        }
        s = tamilWord.replace(s) { m -> wordOf(m.value) }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /** Words of goods names (KaiFeed): "விளக்கு" (lamp) of "மின் விளக்கு" ends like "-க்கு" but is never "to விள". */
    private val goodsWords: Set<String> by lazy { KaiFeed.goodsNames.flatMap { (names, _) -> names.filter(::hasTamil).flatMap { it.split(' ') } }.toSet() }

    /** A Tanglish word back in the Tamil letters the mic writes it in ("mani" → "மணி"), else as Kai's voice spells it. */
    fun tamilOf(latin: String): String? = latin.lowercase().let { l -> words.firstOrNull { (_, v) -> v == l }?.first ?: KaiTamilVoice.tamil(l) }

    private fun wordOf(w: String): String = wordMap[w] ?: if (w in goodsWords) w else dative(w)?.let { d ->
        val stem = d.substringBefore('-')
        // ("பேகுக்கு", "பாக்ஸுக்கு": a loan word ending in a consonant takes -உக்கு — பேக் + க்கு is bag-ku, kept as a name otherwise)
        (wordMap[stem] ?: stem.takeIf { it.endsWith("ு") }?.let { wordMap[it.dropLast(1) + "்"] })?.let { "$it-${d.substringAfter('-')}" } ?: d
    } ?: w

    /**
     * A phrase Kai keeps (a reminder's task) back in the owner's own words: each word [normalize] turned into Tanglish goes back to
     * the Tamil the mic wrote ("சபரி-ku பர்த்டே விஷ் pannanum" → "சபரிக்கு பர்த்டே விஷ் பண்ணணும்"). Other words stay as they are.
     */
    fun asSaid(phrase: String, said: String): String {
        if (!hasTamil(said)) return phrase
        val back = tamilWord.findAll(said).map { it.value }.mapNotNull { w -> wordOf(w).takeIf { it != w && !it.contains(' ') }?.let { it.lowercase() to w } }.toMap()
        return phrase.split(' ').joinToString(" ") { t -> back[t.lowercase()] ?: t }
    }

    /**
     * A typed name for a person the shop keeps in Tamil letters: "madhan ku 100 add panniko" with "மதன்" in the books →
     * "மதன்-ku 100 add panniko" (owner's phone, 12 Oct 2026: answered with Madhan's balance instead of the entry). Only a word
     * that sounds like exactly one Tamil-script name, and no name the shop has in English letters; Kai's own words never.
     */
    /** The one person kept in Tamil letters a typed word names ("madhan" → "மதன்"), or null — see [withBookNames]. */
    fun bookNameIn(text: String, names: List<String>): String? {
        val swapped = withBookNames(text, names)
        if (swapped == text) return null
        return names.filter { hasTamil(it) && swapped.contains(it) }.distinct().singleOrNull()
    }

    fun withBookNames(text: String, names: List<String>): String {
        if (hasTamil(text)) return text
        val tamilNames = names.map { it.trim() }.filter { n -> hasTamil(n) && !n.contains(' ') }.distinct()
        if (tamilNames.isEmpty()) return text
        val latin = names.filter { !hasTamil(it) }.flatMap { it.lowercase().split(Regex("""\s+""")) }.toSet()
        val known = wordMap.values.flatMap { it.lowercase().split(' ') }.toSet() + KaiFeed.notNames + KaiFeed.everydayWords
        return Regex("""(?<![\p{L}])([A-Za-z]{3,})(\s*-?\s*(?:kku|ku|kitta|oda|ukku))?(?![\p{L}])""").replace(text) { m ->
            val w = m.groupValues[1]
            val lw = w.lowercase()
            if (lw in latin || lw in known) return@replace m.value
            val hit = tamilNames.filter { com.shopai.app.util.NameSound.same(w, it) }.singleOrNull() ?: return@replace m.value
            hit + (m.groupValues[2].takeIf { it.isNotBlank() }?.let { "-" + it.trim().trimStart('-').trim() } ?: "")
        }
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
            // "கமலி" / "கமலு" for "கமல்" kept in Tamil: the mic adds a vowel to a name ending in a consonant (owner, 11 Oct 2026).
            tamilKept.filter { t -> t.length >= 3 && t.endsWith("்") && t !in KaiFeed.notNames && w.length == t.length &&
                w.startsWith(t.dropLast(1)) && w.last() in "ாிீுூெேைொோ" }.singleOrNull()?.let { return@replace it + m.groupValues[2] }
            KaiFeed.productNames[w]?.let { english -> if (shopWords.any { it.equals(english, ignoreCase = true) }) return@replace english + m.groupValues[2] }
            // A shop name's last word ("ஷாப்", the mic's "ஷாபு-ku") is only ever a shop word of the shop's names — never "Soap".
            if (KaiCommands.isShopWord(w)) return@replace nameWords.filter { n -> KaiCommands.isShopWord(n) && (spelled(n) == w || startsAlike(w, n) && (sounds(w, n) || sounds(w, soft(n)))) }.singleOrNull()
                ?.let { it + m.groupValues[2] } ?: m.value
            nameWords.filter { n -> spelled(n) == w || startsAlike(w, n) && (sounds(w, n) || sounds(w, soft(n))) }.singleOrNull()
                ?.let { return@replace it + m.groupValues[2] }
            // "ரமேஷும்" (Ramesh too): the name, then "um".
            if (w.endsWith("ும்") && w.length > 4) {
                val stem = w.dropLast(3) + "்"
                nameWords.filter { n -> startsAlike(stem, n) && (sounds(stem, n) || sounds(stem, soft(n))) }.singleOrNull()?.let { return@replace "$it um" + m.groupValues[2] }
            }
            // "ஸ்டோர்ஸ்ல" (at Murugan Stores): the name, then "-la" — never a name "Storsla" (owner's test, 19 Oct 2026).
            if (w.endsWith("்ல") && w.length > 4) {
                val stem = w.dropLast(1)
                nameWords.filter { n -> startsAlike(stem, n) && (sounds(stem, n) || sounds(stem, soft(n))) }.singleOrNull()?.let { return@replace "$it-la" + m.groupValues[2] }
            }
            // A name cut short, said right before its quantity ("கோல்கா 1 பாக்ஸ்" = Colgate): the one name it is the start of.
            val beforeQty = Regex("""^\s*\d""").containsMatchIn(text.substring(m.range.last + 1))
            val key = com.shopai.app.util.NameSound.key(w)
            if (beforeQty && key.length >= 3) nameWords.filter { n -> startsAlike(w, n) && com.shopai.app.util.NameSound.key(n).let { it.length == key.length + 1 && it.startsWith(key) } }
                .singleOrNull()?.let { return@replace it + m.groupValues[2] }
            // "செல்வத்துக்கு" read as செல்வம்-ku: the shop's Selvam — or its "Selva", or "Bharath" for பரத்துக்கு (the -த் name) —
            // whichever one the books keep (owner, 20 Oct 2026: "selvam or selva name correct-ah eduthu kudukanum").
            if (w.endsWith("ம்") && m.groupValues[2] == "-ku" && w.length >= 4) {
                val stem = w.dropLast(2)
                for (alt in listOf(stem + "த்", stem)) {
                    if (alt in tamilKept) return@replace "$alt-ku"
                    nameWords.filter { n -> startsAlike(alt, n) && (sounds(alt, n) || sounds(alt, soft(n))) }.singleOrNull()?.let { return@replace "$it-ku" }
                }
            }
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
        // Consonant + "ு" (the -உக்கு ending) → the consonant with its pulli: ருத்ரனு → ருத்ரன். A name that itself ends in "-u"
        // after a single hard consonant or வ/ஜ keeps it: அறிவுக்கு → அறிவு (Arivu), பாபுக்கு → பாபு — a name ending in that
        // consonant doubles it instead (சுஜித்துக்கு, திலீப்புக்கு).
        val c = stem.getOrNull(stem.length - 2)
        val doubled = stem.length >= 4 && stem[stem.length - 3] == '்' && stem[stem.length - 4] == c
        val endsInU = stem.endsWith("ு") && c != null && c in "வபஜகசடதற" && !doubled
        // "அண்ணாவுக்கு", "அக்காவுக்கு", "ராஜாவுக்கு": "-வுக்கு" after a long "ஆ" is only the glide — அண்ணா-ku (owner, 19 Oct 2026:
        // "முருகேசன் அண்ணாவுக்கு 1200 கடன்" was drafted for "முருகேசன் அண்ணாவு").
        if (stem.length >= 4 && stem.endsWith("ாவு")) return stem.dropLast(2) + "-ku"
        // "செல்வத்துக்கு" = செல்வம் + -க்கு (a name ending in -ம் takes -த்து-, owner, 20 Oct 2026: it was "செல்வத்த்"): a bare
        // consonant before "த்து" — the -ம் name. ([withNames] tries the -த் name too — பரத்துக்கு with Bharath in the books.)
        if (stem.length >= 5 && stem.endsWith("த்து") && stem[stem.length - 5] in 'க'..'ஹ') return stem.dropLast(4) + "ம்-ku"
        val fixed = if (stem.endsWith("ு") && stem.length >= 2 && !endsInU) stem.dropLast(1) + "்" else stem
        return "$fixed-ku"
    }
}
