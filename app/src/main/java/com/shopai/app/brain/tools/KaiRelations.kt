package com.shopai.app.brain.tools

import java.util.Locale

/**
 * Family / relation words said next to a name (owner, 21 Oct 2026: "akka thambi anna amma appa macha — family inthamari nariya
 * varum"): "Kumar thambi", "Kumar oda akka", "Kumar-in appa", "குமார் அக்கா". Such a word may be the owner's respectful way of
 * calling that very person ("Lakshmi akka", "Ravi anna") or someone else — that person's relative. Kai never decides it: a record
 * of that whole name answers, else the owner is asked once. One list, in every way it is typed, written by the mic and said in
 * English; a new word is one more line here.
 */
object KaiRelations {
    /** Every spelling → the word as Kai says it back. */
    private val tamilOf = HashMap<String, String>()
    private val words: Map<String, String> = buildMap {
        fun add(shown: String, vararg spellings: String) {
            (spellings.toList() + shown).forEach { put(it.lowercase(Locale.ROOT), shown) }
            spellings.firstOrNull { w -> w.any { it in '\u0B80'..'\u0BFF' } }?.let { tamilOf[shown] = it }
        }
        add("thambi", "thamby", "tambi", "thambhi", "thambii", "தம்பி")
        add("anna", "annan", "annaa", "annai", "அண்ணா", "அண்ணன்")
        add("akka", "akkaa", "akkka", "அக்கா")
        add("thangachi", "thangachchi", "thangatchi", "thangai", "thangaichi", "தங்கச்சி", "தங்கை")
        add("amma", "ammaa", "ammma", "அம்மா")
        add("appa", "appaa", "அப்பா")
        add("paati", "patti", "aaya", "aayaa", "பாட்டி", "ஆயா")
        add("thatha", "thaatha", "tatha", "thaatha", "தாத்தா")
        add("machan", "machaan", "machchan", "macha", "machaa", "machi", "machchi", "மச்சான்", "மச்சி", "மச்சா")
        add("maama", "mama", "maaman", "maamaa", "மாமா", "மாமன்")
        add("maami", "mami", "மாமி")
        add("athai", "aththai", "atthai", "athhai", "அத்தை")
        add("chithappa", "sithappa", "chitappa", "chithapa", "சித்தப்பா")
        add("chithi", "sithi", "chitthi", "சித்தி")
        add("periyappa", "periappa", "periyapa", "பெரியப்பா")
        add("periyamma", "periamma", "periyama", "பெரியம்மா")
        add("magan", "makan", "மகன்")
        add("magal", "makal", "மகள்")
        add("paiyan", "payyan", "payan", "paiyyan", "பையன்")
        add("ponnu", "ponu", "பொண்ணு")
        add("purushan", "kanavan", "husband", "புருஷன்", "கணவன்")
        add("manaivi", "pondatti", "pondaati", "pondaatti", "wife", "மனைவி", "பொண்டாட்டி")
        add("mappillai", "maapillai", "mapillai", "மாப்பிள்ளை")
        add("marumagal", "marumagan", "மருமகள்", "மருமகன்")
        add("nanban", "nanbi", "friend", "frnd", "dost", "நண்பன்", "நண்பி", "ஃப்ரெண்ட்")
        add("brother", "bro")
        add("sister", "sis")
        add("mother", "mom")
        add("father", "dad")
        add("son")
        add("daughter")
        add("uncle")
        add("aunty", "aunt", "anti")
        add("cousin")
    }

    /** Case endings a relation word carries in a sentence: "akka-ku", "thambi kitta", "அக்காவுக்கு", "appa-oda". */
    private val ending = Regex("""(?i)(?:-?(?:vukku|vuku|ukku|kku|ku|oda|ooda|kitta|kita|kitte|ta|um|kum|ga|nga|ngalukku|kum)|வுக்கு|க்கு|வோட|ோட|கிட்ட|வும்)+$""")

    /** The word as Kai says it back, or null when [word] is no relation ("Kumar" / "box"). */
    fun shown(word: String): String? {
        val w = word.trim().lowercase(Locale.ROOT).trim('.', ',', '?', '!', '\'')
        return words[w] ?: words[ending.replace(w, "")]
    }

    fun isRelation(word: String) = shown(word) != null

    /** The word in Tamil letters, for a reply in Tamil ("akka" → "அக்கா"); the word itself when it has none (English ones). */
    fun inTamil(shown: String): String = tamilOf[shown] ?: shown

    /**
     * The relation said right after [person] in [text] — "Kumar thambi", "Kumar oda akka", "Kumar-oda appa", "Kumar's brother",
     * "குமார் அக்கா" — as Kai says it; null when none.
     */
    fun after(text: String, person: String): String? {
        val m = Regex("""(?i)(?<![\p{L}\p{M}])${Regex.escape(person)}(?:'s|s)?(?:\s*-?\s*(?:oda|ooda|in|ude|ோட|உடைய))?\s*-?\s*([\p{L}\p{M}]+)""").find(text) ?: return null
        return shown(m.groupValues[1])
    }

    /** [text] with the relation after [person] taken out ("Kumar akka evlo tharanum" → "Kumar evlo tharanum"): the person themself. */
    fun without(text: String, person: String): String =
        Regex("""(?i)((?<![\p{L}\p{M}])${Regex.escape(person)})(?:'s|s)?(?:\s*-?\s*(?:oda|ooda|in|ude|ோட|உடைய))?\s*-?\s*([\p{L}\p{M}]+)""").replace(text) { m ->
            if (isRelation(m.groupValues[2])) m.groupValues[1] + (ending.find(m.groupValues[2].lowercase(Locale.ROOT))?.value?.let { e -> if (e.startsWith("-")) e else "-$e" }.orEmpty()) else m.value
        }
}
