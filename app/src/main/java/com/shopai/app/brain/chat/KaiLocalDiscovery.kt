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

    /** The kind of place the owner is looking for, or null when this isn't a "where is / near" question. */
    fun request(text: String): Place? {
        if (!near.containsMatchIn(text) && !where.containsMatchIn(text)) return null
        if (books.containsMatchIn(text) || insideShop.containsMatchIn(text)) return null
        return places.firstOrNull { it.first.containsMatchIn(text) }?.second
    }
}
