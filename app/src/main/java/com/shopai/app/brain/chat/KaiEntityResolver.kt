package com.shopai.app.brain.chat

import com.shopai.app.brain.tools.PartyMatch
import java.util.Locale

/**
 * Which customer / supplier the owner means. A name is not an identity: the books may hold three
 * "Lokesh"es. Kai uses what the owner said and what was just talked about, in this order —
 * phone number, exact name + place / shop name, the person already in the conversation — and when
 * more than one record still fits, it asks. It never picks the first match, never maps "Kumaran"
 * to "Kumar", and only ever sees the candidates the books returned for that name (this business only).
 */
object KaiEntityResolver {

    sealed interface Result {
        /** Exactly one record fits; [by] says why (phone / place / context / only one). */
        data class One(val party: PartyMatch, val by: String) : Result
        /** More than one record fits what was said: ask. */
        data class Many(val candidates: List<PartyMatch>) : Result
        /** No record has this exact name. */
        data object None : Result

        /** How sure the identity is: only CONFIRMED may carry money; AMBIGUOUS is asked; UNKNOWN is asked / learned. */
        val confidence: Confidence get() = when (this) {
            is One -> Confidence.CONFIRMED
            is Many -> Confidence.AMBIGUOUS
            None -> Confidence.UNKNOWN
        }
    }

    enum class Confidence { CONFIRMED, AMBIGUOUS, UNKNOWN }

    private const val B = """(?<![\p{L}\p{M}])"""
    private const val E = """(?![\p{L}\p{M}])"""
    private val phone = Regex("""(?<!\d)(?:\+?91[\s-]?)?([6-9]\d{9})(?!\d)""")

    /** A 10-digit Indian mobile number in the words, if any. */
    fun phoneIn(text: String): String? = phone.find(text.replace(Regex("""(?<=\d)[\s-](?=\d)"""), ""))?.groupValues?.get(1)

    private fun digits(p: String?) = p?.filter(Char::isDigit)?.takeLast(10)

    /** The words that set this record apart from others with the same name: its town, its address / shop words. */
    private fun marks(p: PartyMatch): List<String> {
        val words = listOfNotNull(p.city, p.details).joinToString(" ")
            .split(Regex("""[^\p{L}\p{M}]+""")).map { it.lowercase(Locale.ROOT) }.filter { it.length >= 3 }
        val name = p.name.lowercase(Locale.ROOT).split(' ').toSet()
        return words.filter { it !in name && it !in common }.distinct()
    }
    private val common = setOf("street", "road", "nagar", "main", "shop", "the", "and", "near", "opp", "cross", "colony", "salai")

    private fun says(text: String, word: String) = Regex("""$B${Regex.escape(word)}""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /**
     * The records this name can mean: exactly this name (or the same name in the other script), and — when such a record
     * exists — the longer names that carry it as a whole word ("Lokesh" → "Lokesh" and "Madurai Lokesh": two people the
     * owner may mean, so Kai asks). Never a part of a word ("Kumar" is not "Kumaran"), never a shorter name.
     */
    fun sameName(name: String, candidates: List<PartyMatch>): List<PartyMatch> {
        val exact = candidates.filter { it.name.trim().equals(name.trim(), ignoreCase = true) || com.shopai.app.util.NameSound.same(it.name, name) }
        if (exact.isEmpty()) return exact
        val n = name.trim().lowercase(Locale.ROOT).split(Regex("""\s+"""))
        val longer = candidates.filter { c ->
            val w = c.name.trim().lowercase(Locale.ROOT).split(Regex("""\s+"""))
            c !in exact && w.size > n.size && w.windowed(n.size).any { win -> win == n || n.size == 1 && com.shopai.app.util.NameSound.same(win.single(), name) }
        }
        return exact + longer
    }

    /** The words of [p]'s name that not every one of [all] has ("Madurai" in "Madurai Lokesh" next to "Lokesh"). */
    private fun nameMarks(p: PartyMatch, all: List<PartyMatch>): List<String> {
        val shared = all.map { it.name.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{M}]+""")).toSet() }
            .reduceOrNull { a, b -> a intersect b }.orEmpty()
        return p.name.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{M}]+""")).filter { it.length >= 3 && it !in shared }
    }

    /**
     * [name] as said, [said] the owner's words (place, shop, phone may be in them), [candidates] the books' records for
     * that name, [contextId] the record already being talked about.
     */
    fun resolve(name: String, said: String, candidates: List<PartyMatch>, contextId: String? = null, referenceId: String? = null): Result {
        val exact = sameName(name, candidates).distinctBy { it.id }
        if (exact.isEmpty()) return Result.None
        // 0. The owner's own established reference ("Kumar House" = this record), resolved to its canonical id.
        referenceId?.let { id -> exact.firstOrNull { it.id == id }?.let { return Result.One(it, "owner reference") } }
        // 1. A phone number is the strongest identifier.
        phoneIn(said)?.let { number ->
            exact.filter { digits(it.phone) == number }.singleOrNull()?.let { return Result.One(it, "phone") }
        }
        // 2. Name + place / shop words that only one record has.
        val scored = exact.map { p -> p to (marks(p) + nameMarks(p, exact)).distinct().count { says(said, it) } }
        val best = scored.maxOf { it.second }
        if (best > 0) {
            val top = scored.filter { it.second == best }.map { it.first }
            return if (top.size == 1) Result.One(top.single(), "place") else Result.Many(top)
        }
        if (exact.size == 1) return Result.One(exact.single(), "only one")
        // 3. The one already in the conversation.
        exact.firstOrNull { it.id == contextId }?.let { return Result.One(it, "context") }
        return Result.Many(exact)
    }

    /** How Kai names one record among same-named ones: "Nagapattinam Lokesh", "Lokesh (…3210)", or just the name. */
    fun label(p: PartyMatch): String = when {
        !p.city.isNullOrBlank() -> "${p.city.trim()} ${p.name}"
        !p.details.isNullOrBlank() -> "${p.name} (${p.details.trim().take(30)})"
        digits(p.phone)?.length == 10 -> "${p.name} (…${digits(p.phone)!!.takeLast(4)})"
        else -> p.name
    }

    /** Grammar / money / question words said next to a name — never a part of who it is. */
    fun isFunctionWord(word: String) = word.lowercase(Locale.ROOT) in functionWords

    private val functionWords = setOf(
        "enakku", "enaku", "ennaku", "yenakku", "yenaku", "naan", "nan", "na", "ku", "kku", "ukku", "kitta", "kita", "kitte", "irundhu", "irunthu",
        "tharanum", "tharanu", "kudukkanum", "kudukanum", "kodukkanum", "vaanganum", "vanganum", "vanganu", "kuduthen", "kuduthaan", "kuduthutaan",
        "vanginen", "vaanginen", "evlo", "evvalavu", "balance", "pending", "innum", "already", "ippa", "oru", "rs", "rupees", "and", "or", "the", "to",
        "from", "me", "i", "owe", "owes", "paid", "pay", "save", "add", "pannu", "panniko", "sollu", "due", "eppa", "eppo", "history", "details",
        "avan", "avar", "avanga", "enna", "how", "much", "is", "has", "have", "collect", "payment", "cash", "upi", "remind", "call",
    )

    /**
     * The words the owner used to name one record when the plain name is shared — "Kumar House enaku 600", "Kumar Anna-ku 500",
     * "Velachery Kumar" — or null when only the name was said. Never stored by itself: only offered to the owner to remember.
     */
    fun referenceIn(said: String, name: String): String? {
        val tokens = Regex("""[\p{L}\p{M}][\p{L}\p{M}'.-]*""").findAll(said).map { it.value }.toList()
        val i = tokens.indexOfFirst { it.substringBefore('-').equals(name, ignoreCase = true) }
        if (i < 0) return null
        fun clean(w: String) = w.substringBefore('-').trim('.', '\'')
        fun usable(w: String) = clean(w).let { c -> c.length >= 2 && c.lowercase() !in functionWords && !c.equals(name, ignoreCase = true) }
        if (tokens[i].contains('-')) return null // "Kumar-ku": the name alone
        tokens.getOrNull(i + 1)?.takeIf(::usable)?.let { return "${tokens[i]} ${clean(it)}" }
        tokens.getOrNull(i - 1)?.takeIf { i == 1 && usable(it) }?.let { return "${clean(it)} ${tokens[i]}" }
        return null
    }

    private val countWord = mapOf(2 to "rendu", 3 to "moonu", 4 to "naalu", 5 to "anju")

    /** "Owner, Lokesh-nu rendu records irukku. Chennai Lokesh-aa illa Nagapattinam Lokesh-aa?" */
    fun question(name: String, candidates: List<PartyMatch>, lang: com.shopai.app.brain.KaiLang, labelOf: (PartyMatch) -> String = ::label): String {
        val labels = candidates.map(labelOf)
        return when (lang) {
            com.shopai.app.brain.KaiLang.TAMIL -> "Owner, $name-னு ${candidates.size} records இருக்கு. " + labels.joinToString("-ஆ, ") + "-ஆ?"
            com.shopai.app.brain.KaiLang.ENGLISH -> "Owner, there are ${candidates.size} records named $name. " + labels.joinToString(", ", postfix = "?").replaceLast(", ", " or ")
            com.shopai.app.brain.KaiLang.TANGLISH -> "Owner, $name-nu ${countWord[candidates.size] ?: candidates.size.toString()} records irukku. " +
                if (labels.size == 2) "${labels[0]}-aa illa ${labels[1]}-aa?" else labels.dropLast(1).joinToString("-aa, ") + "-aa, illa ${labels.last()}-aa?"
        }
    }

    private fun String.replaceLast(old: String, new: String): String {
        val i = lastIndexOf(old)
        return if (i < 0) this else substring(0, i) + new + substring(i + old.length)
    }

    /** The answer to "which Lokesh?": a place / shop word / phone that picks one of [candidates], or "first" / "rendavadhu". */
    fun pick(answer: String, candidates: List<PartyMatch>): PartyMatch? {
        phoneIn(answer)?.let { n -> candidates.singleOrNull { digits(it.phone) == n }?.let { return it } }
        val byMark = candidates.filter { p -> (marks(p) + nameMarks(p, candidates)).any { says(answer, it) } }
        if (byMark.size == 1) return byMark.single()
        // "Lokesh" to "Lokesh-aa illa Madurai Lokesh-aa?": the one whose whole name was said and is the only one that fits.
        if (byMark.isEmpty()) candidates.filter { p -> p.name.split(Regex("""\s+""")).all { says(answer, it) } }
            .distinctBy { it.name.lowercase(Locale.ROOT) }.singleOrNull()
            ?.let { named -> candidates.filter { it.name.equals(named.name, ignoreCase = true) }.singleOrNull() }?.let { return it }
        val ordinals = listOf(
            Regex("""(?i)${B}(first|1st|onnu|mudhal|mudhalavadhu|modhal)$E|^\s*1\s*$"""),
            Regex("""(?i)${B}(second|2nd|rendavadhu|rendaavadhu|rendu)$E|^\s*2\s*$"""),
            Regex("""(?i)${B}(third|3rd|moonavadhu|moonaavadhu)$E|^\s*3\s*$"""),
        )
        ordinals.forEachIndexed { i, r -> if (r.containsMatchIn(answer) && i < candidates.size) return candidates[i] }
        return null
    }

    // ------------------------------------------------------------ references: "avan", "avanukku", "andha customer"

    private val personRef = Regex(
        """$B(avanukku|avanuku|avanuk|avarukku|avaruku|avangalukku|avangaluku|avalukku|avaluku|avan|avanu|avar|avaru|avanga|avangal|aval|ava|""" +
            """avanoda|avaroda|avangaloda|avanai|avarai|avana|avara|indha\s+aal|andha\s+aal|andha\s+customer|andha\s+supplier|indha\s+customer|""" +
            """same\s+person|same\s+customer|him|her|he|she)$E|அவனுக்கு|அவருக்கு|அவங்களுக்கு|அவளுக்கு|அவன்|அவர்|அவங்க|அவள்""",
        RegexOption.IGNORE_CASE,
    )

    fun mentionsPerson(text: String): Boolean = personRef.containsMatchIn(text)

    /** "avanukku 500 tharanum" → "Lokesh-ku 500 tharanum": the reference becomes the name, with its case ending kept. */
    fun withName(text: String, name: String): String = personRef.replace(text) { m ->
        val w = m.value.lowercase(Locale.ROOT)
        when {
            w.endsWith("ukku") || w.endsWith("uku") || w.endsWith("nuk") || w.endsWith("க்கு") -> "$name-ku"
            w.endsWith("oda") -> "$name oda"
            w.endsWith("ai") || w == "avana" || w == "avara" -> "$name-a"
            else -> name
        }
    }

    /** Known people named in the words, each once, in the order said ("Kumar and Ramesh" → [Kumar, Ramesh]). */
    fun peopleIn(text: String, people: List<String>): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        return people.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
            .mapNotNull { name ->
                Regex("""(?<![\p{L}])${Regex.escape(name.lowercase(Locale.ROOT))}(?:u?k?ku|kitta|kita|oda|odu|idam|ai|um|ukkum|a|aa|ah|u|க்கு|கிட்ட)?(?![\p{L}\p{M}])""")
                    .find(lower)?.let { name to it.range }
            }
            // "Madurai Lokesh evlo?": one person — the "Lokesh" inside a longer name already found is not another.
            .fold(emptyList<Pair<String, IntRange>>()) { acc, p ->
                if (acc.any { p.second.first >= it.second.first && p.second.last <= it.second.last }) acc else acc + p
            }
            .sortedBy { it.second.first }.map { it.first }
    }
}
