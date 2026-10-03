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
    fun normalize(text: String): String {
        if (!hasTamil(text)) return text
        var s = text
        // Units first ("மணி நேரத்துல" before "மணி").
        for ((re, unit) in units) {
            s = re.replace(s) { m -> if (m.value.endsWith("ல") || m.value.endsWith("ல்") || m.value.endsWith("லே")) "$unit la" else unit }
        }
        s = tamilWord.replace(s) { m -> wordMap[m.value] ?: dative(m.value) ?: m.value }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

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
