package com.shopai.app.brain.chat

/**
 * LOCAL_BUSINESS_DISCOVERY: the owner asks where a kind of place is ("pakkathula hardware kadai
 * irukka?", "supermarket enga irukku?") — not a question about the books, a stock item, a payment
 * or a reminder. A place word plus "near" or "where"; plain rules, words not phrases.
 */
object KaiLocalDiscovery {

    data class Place(val tanglish: String, val tamil: String, val english: String)

    private const val B = """(?<![\p{L}\p{M}])"""
    private const val E = """(?![\p{L}\p{M}])"""

    private val near = Regex(
        """$B(pakkathula|pakkathil|pakathula|pakkam|pakkathule|arugil|arugula|arugala|near|nearby|close\s*by|around\s*here|inga\s*pakkam)$E|பக்கத்துல|பக்கத்தில்|அருகில்|அருகே""",
        RegexOption.IGNORE_CASE,
    )
    private val where = Regex("""$B(enga|engey|engay|engha|engae|where)$E|எங்க|எங்கே""", RegexOption.IGNORE_CASE)
    /** Asking about the shop's own goods or records is not finding a place. */
    private val books = Regex(
        """$B(stock|maal|item|product|balance|pending|tharanum|kudukkanum|kudukanum|payment|bill|evlo|reminder|remind|saavi|key|keys|vechen|vachen|vechu)$E|ஸ்டாக்|பாக்கி|சாவி""",
        RegexOption.IGNORE_CASE,
    )

    /** Kinds of places, most specific first: (words that say it, Tanglish, Tamil, English for a map search). */
    private val places: List<Pair<Regex, Place>> = listOf(
        """hardware|hardwares""" to Place("hardware kadai", "ஹார்டுவேர் கடை", "hardware shop"),
        """super\s*market|supermarket|mart""" to Place("supermarket", "சூப்பர்மார்க்கெட்", "supermarket"),
        """pharmacy|medical|marundhu\s*kadai|marunthu\s*kadai""" to Place("medical shop", "மருந்துக் கடை", "pharmacy"),
        """hospital|clinic|doctor""" to Place("hospital", "ஹாஸ்பிடல்", "hospital"),
        """atm""" to Place("ATM", "ATM", "ATM"),
        """bank""" to Place("bank", "பேங்க்", "bank"),
        """petrol\s*bunk|petrol|bunk""" to Place("petrol bunk", "பெட்ரோல் பங்க்", "petrol pump"),
        """hotel|restaurant|mess|tiffin""" to Place("hotel", "ஹோட்டல்", "restaurant"),
        """bakery""" to Place("bakery", "பேக்கரி", "bakery"),
        """mechanic|garage|workshop""" to Place("mechanic shop", "மெக்கானிக் கடை", "mechanic"),
        """xerox|printing""" to Place("xerox kadai", "ஜெராக்ஸ் கடை", "xerox shop"),
        """market|santhai""" to Place("market", "மார்க்கெட்", "market"),
        """kadai|kada|shop|store|kadaigal""" to Place("kadai", "கடை", "shops"),
    ).map { (words, place) -> Regex("""$B($words)$E""", RegexOption.IGNORE_CASE) to place } +
        listOf(Regex("""சூப்பர்மார்க்கெட்""") to Place("supermarket", "சூப்பர்மார்க்கெட்", "supermarket"),
            Regex("""மருந்து""") to Place("medical shop", "மருந்துக் கடை", "pharmacy"),
            Regex("""கடை""") to Place("kadai", "கடை", "shops"))

    /** "kadai-la enga irukku?" — where inside the owner's own shop, not a shop nearby. */
    private val insideShop = Regex("""(?i)kadai\s*-?\s*(la|le|kulla)(?![\p{L}])|கடையில|கடைல|கடைக்குள்ள""")

    /** "irukka?", "paaru", "thedu", "enga": the owner is looking for it (not "pakkathula … irukku", a statement). */
    private val lookFor = Regex(
        """$B(irukka|irukkaa|iruka|irukaa|irukkanu|irukkaanu|paaru|paru|paar|thedu|thedi|thedunga|kandupidi|kandupudi|find|search|show|enga|engey|where)$E|இருக்கா|பாரு|தேடு|எங்க|எங்கே""",
        RegexOption.IGNORE_CASE,
    )

    /** Words said around a place that are not the place ("Spa near by la iruka paru" → "Spa"). */
    private val notPlace = setOf(
        "near", "nearby", "by", "close", "around", "here", "inga", "ingey", "inge", "pakkam", "pakkathula", "pakkathil", "pakathula", "pakkathule",
        "arugil", "arugula", "arugala", "la", "le", "lae", "ah", "aa", "a", "an", "the", "any", "some", "is", "are", "there", "me", "my", "us", "oru",
        "edhavadhu", "edhaavadhu", "ethavathu", "ethachum", "edhachum", "enakku", "enaku", "ennaku", "kai", "owner", "sir", "anna", "please", "pls",
        "konjam", "idhu", "ithu", "irukka", "irukkaa", "iruka", "irukaa", "irukkanu", "irukkaanu", "irukku", "iruku", "irukkum", "paaru", "paru", "paar",
        "thedu", "thedi", "thedunga", "kandupidi", "kandupudi", "find", "search", "show", "enga", "engey", "where", "nu", "nnu", "sollu", "ennanu",
        "ஒரு", "ஏதாவது", "ல", "லே", "பை", "பக்கத்துல", "பக்கத்தில்", "அருகில்", "அருகே", "இருக்கா", "இருக்கு", "பாரு", "தேடு", "எங்க", "எங்கே",
    )
    private val generalShop = setOf("kadai", "kada", "shop", "shops", "store", "stores", "kadaigal", "கடை")

    /**
     * The kind of place the owner is looking for, or null when this isn't a "where is / near" question. A place Kai has no
     * name for ("Spa near by la iruka paru", "pakkathula salon irukka?") gets the same answer as the listed ones — never
     * a person or product of the shop's ([known]).
     */
    fun request(text: String, known: Collection<String> = emptyList()): Place? {
        if (!near.containsMatchIn(text) && !where.containsMatchIn(text)) return null
        if (books.containsMatchIn(text) || insideShop.containsMatchIn(text)) return null
        val listed = places.firstOrNull { it.first.containsMatchIn(text) }?.second
        if (listed != null && listed.english != "shops") return listed
        if (near.containsMatchIn(text) && lookFor.containsMatchIn(text)) said(text, known)?.let { return it }
        return listed
    }

    /** The words left once the near / looking-for words are taken out: the place, as the owner said it. */
    private fun said(text: String, known: Collection<String>): Place? {
        val rest = near.replace(text, " ").let { lookFor.replace(it, " ") }.split(Regex("""[^\p{L}\p{M}\d]+"""))
            .filter { it.isNotBlank() && it.lowercase() !in notPlace }
        if (rest.isEmpty() || rest.size > 3 || rest.any { w -> w.any(Char::isDigit) }) return null
        if (rest.all { it.lowercase() in generalShop }) return null
        val names = known.flatMap { it.lowercase().split(Regex("""\s+""")) }.toSet()
        if (rest.any { it.lowercase() in names }) return null
        val place = rest.joinToString(" ")
        return Place(place, place, place)
    }
}
