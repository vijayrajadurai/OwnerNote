package com.shopai.app.brain.tools

import com.shopai.app.brain.tools.KaiKeptWords.Entry
import com.shopai.app.brain.tools.KaiKeptWords.Kind
import com.shopai.app.util.NameSound
import java.util.Locale

/**
 * "Enga Vechen?" — what an owner's line says about a thing they kept (owner, 24 Oct 2026): "passport bedroom almirah mela drawer la
 * vechen" (save), "veetu saavi enga" (ask), "patta ippo bank locker la" (move), "remote kidaichiduchu" (taken), "almirah la ennenna
 * irukku" (list), "Kai ennalam nyabagam vechikka sonnen" (list everything, with its filters). Typed or as the ta-IN mic writes it:
 * the words come from the owner's list ([KaiKeptWords]) and are matched by sound — Tanglish spellings by [KaiWordSound.near], Tamil
 * script against the list's English letters by [NameSound.same] — so a spelling the list doesn't have is still read.
 *
 * Never a guess: a word in no list is the owner's own word, kept as said ("Thatha kannadi petti"); a word of two meanings
 * ("kannadi" — specs or a mirror) comes back with both, for Kai to ask. A line with money in it ("Kumar ku 2000 kuduthen"), a stock
 * or a books question ("Rice stock enga irukku") is never read here — those stay Kai's books lines. Reads only; nothing is written.
 */
object KaiKept {

    enum class Intent { SAVE, ASK, MOVE, TAKEN, LIST, LIST_ALL }

    /** "Amma ennalam vechaanga" (person), "poona maasam" (time), "bedroom la ennalam" (room), "documents ellam" (type), "yaar yaar kitta". */
    data class Filter(
        val person: String? = null,
        val time: String? = null,
        val room: List<String> = emptyList(),
        val type: String? = null,
        val givenToPeople: Boolean = false,
        val closed: Boolean = false,
    )

    /**
     * What the line said. [item] the owner's own words for the thing; [itemKey] the list's name for it (null: the owner's own word,
     * or two meanings — then [meanings] holds both); [place] outermost first; [person] who has it ("Ravi kitta" — [given]) or whose
     * it is ("Amma thodu").
     */
    data class Read(
        val intent: Intent,
        val item: String? = null,
        val itemKey: String? = null,
        val meanings: List<String> = emptyList(),
        val place: List<String> = emptyList(),
        val person: String? = null,
        val given: Boolean = false,
        val time: String? = null,
        val filter: Filter? = null,
    )

    /** One word / phrase of the line and what it can mean (empty: a word of the owner's own). */
    private data class Span(val raw: String, val low: String, val entries: List<Entry>, val number: Boolean = false, val score: Int = 0) {
        fun has(k: Kind) = entries.any { it.kind == k }
        fun only(vararg k: Kind) = entries.isNotEmpty() && entries.all { it.kind in k }
        val unknown get() = entries.isEmpty() && !number
    }

    private const val TA = "஀-௿"

    private val plainWords = setOf("pudhu", "puthu", "pudu", "maadhiri", "maathiri", "madhiri", "paadhi", "pathi", "avan", "aval", "avanga", "pazhaya", "palaya", "periya", "chinna", "sinna", "innoru", "rendu", "moonu", "pala",
        "புது", "பழைய", "பெரிய", "சின்ன", "இன்னொரு")

    /** Money, stock and books words: such a line is Kai's books line, never a kept thing. */
    private val booksWords = Regex(
        """(?i)(?<![\p{L}])(evlo|evvalavu|evalavu|tharanum|tharnum|kudukkanum|kudukanum|kadan|udhaar|credit|debit|balance|baaki|pakki|stock|rate|vilai|price|sales?|vithen|vithuchu|vithuten|vithutten|vandhuchu|vanthuchu|order|report|pdf|statement|remind|reminder|alarm|payment|gpay|phonepe|upi|rs|rupees?|rooba|ruba|kanakku|ledger|bill\s*podu|expiry|kudu|anuppu|send|share|whatsapp|customers?|suppliers?|pending|due|party|parties|cancel|thappa|vandhu|vanthu|vandhirukku|vandhuruku|vandhurukku|kadaiku|kadaikku|saraku|sarakku\s+vandh\w*|kudukkal|vaangal|settle|vaangitten|vangitten|vaangiten|podanum|poda|podu|manikku|maniku|nyabagam\s*paduth\w*|add|kg|kilo|litre|liter|pcs|pieces|dozen|packet|bottle|tharuvan\w*|kuduthan|kuduthaan|kuduthutan|kuduthutaan|kuduthanga|kuduthaanga|vaanginen|vanginen)(?![\p{L}])|""" +
            "(?<![\u0B80-\u0BFF])(குடு|அனுப்பு|ஷேர்)(?![\u0B80-\u0BFF])|ரிமைண்டர்|ரிப்போர்ட்|போடணும்|செட்டில்|வாங்கிட்டேன்|ரூபா|மணிக்கு|நினைவூட்டு|ஞாபகப்படுத்து|கேன்சல்|தப்பா|வந்து|வந்திருக்கு|கடைக்கு|வாங்கல்|லிட்டர்|கிலோ|கஸ்டமர்|சப்ளையர்|பெண்டிங்|குடுத்தான்|கொடுத்தான்|குடுத்துட்டான்|குடுத்தாங்க|வாங்கினேன்|பிடிஎஃப்|ஸ்டேட்மெண்ட்|சேல்ஸ்|பேமெண்ட்|வாட்ஸ்அப்|₹|எவ்வளவு|தரணும்|குடுக்கணும்|கடன்|கிரெடிட்|பேலன்ஸ்|பாக்கி|ஸ்டாக்|விலை|ரேட்|வித்துச்சு|வித்தேன்|வந்துச்சு|ரிமைண்ட்|ரூபாய்|கணக்கு"
    )

    /** Case markers only ("la", "ulla", "ல") — dropped from the place; position markers ("mela", "pinnadi") are kept as parts. */
    private val caseOnly = setOf("la", "le", "laa", "ulla", "ulle", "ullae", "in", "inside", "on", "with", "idathula", "ulla pakkam", "ல", "உள்ள")

    /** The mic's Tamil position words, as the list's parts are kept. */
    private val tamilPositions = mapOf("மேல" to "mela", "மேலே" to "mela", "கீழ" to "keezha", "கீழே" to "keezha", "நடுவுல" to "naduvula", "நடு" to "naduvula",
        "பக்கத்துல" to "pakkathula", "பின்னாடி" to "pinnadi", "முன்னாடி" to "munnadi", "மூலையில" to "moolaila", "அடியில" to "adiyila",
        "கடைசி" to "kadaisi", "இடது" to "left", "வலது" to "right", "லெஃப்ட்" to "left", "லெப்ட்" to "left", "ரைட்" to "right", "டாப்" to "top", "தட்டு" to "thattu", "அறை" to "arai")
    private val sameSide = mapOf("idathu" to "left", "idadhu" to "left", "valathu" to "right", "valadhu" to "right", "mele" to "mela", "keela" to "keezha",
        "kizha" to "keezha", "naduvil" to "naduvula", "pinnaadi" to "pinnadi", "munnaadi" to "munnadi", "moolayila" to "moolaila", "adila" to "adiyila")

    /** Item group names said for the list's type filter ("documents ellam"). */
    private val groupWords = mapOf("documents" to "documents", "document" to "documents", "papers" to "documents", "dakumentu" to "documents",
        "டாக்குமெண்ட்" to "documents", "டாக்குமெண்ட்ஸ்" to "documents", "keys" to "keys", "saavi" to "keys", "சாவி" to "keys",
        "nagai" to "money_and_gold", "jewels" to "money_and_gold", "நகை" to "money_and_gold", "electronics" to "electronics")

    private val everything = Regex("""(?i)(?<![\p{L}])(ennalam|ennellam|ennenna|ennanna|ellam|ellaam|ellathayum|ellathaiyum|ellaa)(?![\p{L}])|என்னலாம்|என்னென்ன|எல்லாம்|எல்லாத்தையும்""")
    private val keptVerb = Regex("""(?i)(?<![\p{L}])((vech|vach|vaich|vaech|pott|sonn|nyabagam|nayabaga|gnabagam|ezhudhi)\p{L}*|kuduthen|kuduthuten|kuduthutten|kuduthom|kuduthirukken|save\s+pann(a|irukka|iruka|en|inen|iten))|வச்ச|வைச்ச|வைத்த|போட்டேன்|குடுத்தேன்|கொடுத்தேன்|குடுத்துட்டேன்|சொன்ன|ஞாபகம்""")
    private val question = Regex("""(?i)(?<![\p{L}])(enna|yenna|yaaru|yaar|eppo|ethana|endha|epdi|eppadi|why|what|when|how|irukka|iruka)(?![\p{L}])|என்ன|யாரு|எப்போ|எத்தனை|எந்த|இருக்கா(?![\u0B80-\u0BFF])""")
    private fun known0(p: Parts) = p.itemKey != null || p.meanings.isNotEmpty()
    private val oldOnes = Regex("""(?i)(?<![\p{L}])(pazhaya|palaya|pazaya|old|closed|eduthadhu|eduthathu)(?![\p{L}])|பழைய""")
    private val whoAll = Regex("""(?i)(?<![\p{L}])(yaar|yaaru|yar)\s+(yaar|yaaru|yar)\s+(kitta|kita)|யார்\s+யார்\s+கிட்ட|யாரு\s+யாரு\s+கிட்ட""")
    private val yours = Regex("""(?i)(?<![\p{L}])(un|unakku|unaku|unnoda)\s*(kitta|kita)?\s+(enna|ennenna|ennalam)|உன்\s*கிட்ட\s+(என்ன|என்னென்ன)|உனக்கு\s+என்ன""")

    /**
     * What [line] says about a kept thing, or null when it isn't such a line. [people] are the books' names (a "kitta" name or a
     * person filter is matched to them, never changed into them); [recent] = Kai's last answer was about kept things, so a bare
     * "full list" / "ellam sollu" is this list.
     */
    fun read(line: String, people: List<String> = emptyList(), recent: Boolean = false): Read? {
        val text = KaiSpokenWords.spoken(line.trim())
        if (text.isEmpty() || booksWords.containsMatchIn(text)) return null
        // An amount said in Tamil words ("ரெண்டாயிரம்", "ஆயிரத்து ஐநூறு"): money — the books' line.
        if (KaiSpokenWords.hasTamil(text) && text.none(Char::isDigit) && KaiSpokenWords.normalize(text).any(Char::isDigit)) return null
        val spans = spans(text)
        if (spans.isEmpty()) return null
        // A number is only a place's ("rack 3", "drawer 2", "2nd shelf") — any other number is money or a count: a books line.
        spans.forEachIndexed { i, s ->
            if (s.number && !(i > 0 && spans[i - 1].let { p -> p.score == 3 && (p.has(Kind.PART) || p.entries.any { e -> e.kind == Kind.PLACE && e.group in setOf("furniture", "containers", "shop") }) }) && !Regex("""^\d+(st|nd|rd|th|vathu|aavathu)$""").matches(s.low)) return null
        }
        val old = oldOnes.containsMatchIn(text)
        // A person of the books named without "kitta" ("Kumar ellathaiyum kuduthutan", "Kumar muzhusa kuduthutan"): the books' line.
        spans.forEachIndexed { i, sp ->
            if (sp.entries.none { it.kind == Kind.ITEM || it.kind == Kind.PLACE } && people.any { it.equals(sp.raw, true) || NameSound.same(it, sp.raw) } &&
                spans.getOrNull(i + 1)?.low !in setOf("kitta", "kita", "கிட்ட")) return null
        }

        // ---- everything ("ennalam nyabagam vechikka sonnen", "yaar yaar kitta enna kuduthen", "documents ellam enga irukku") ----
        listAll(text, spans, people, recent, old)?.let { return it }

        val ask = spans.any { it.has(Kind.ASK) }
        val listWord = spans.any { it.has(Kind.LIST) }
        val move = spans.any { it.has(Kind.MOVE) }
        val taken = spans.any { it.has(Kind.TAKEN) }
        val save = spans.any { it.has(Kind.SAVE) }
        val parts = parts(spans, people)
        val every = everything.containsMatchIn(text)

        // "almirah la ennenna irukku", "bedroom la ennalam": what is in that place.
        if (listWord && !save && parts.place.isNotEmpty() && parts.item == null) return Read(Intent.LIST, place = parts.place)
        if (listWord && !save && every && parts.item != null && parts.place.isEmpty() && parts.itemKey == null) {
            // "kitchen ennalam irukku" said with a room the list doesn't split off: the owner's word is the place.
            return Read(Intent.LIST, place = listOf(parts.item))
        }
        if (every && !save && parts.place.isNotEmpty() && parts.item == null) return Read(Intent.LIST, place = parts.place)
        val item = parts.item ?: return null
        // "Kumaran store ku enna kuduthen": a question about the books, never a thing kept.
        if ((question.containsMatchIn(text) || line.trimEnd().endsWith("?")) && !ask) return null
        // "Ravi ku laptop kuduthen" is Ravi's books unless the thing is one of the list's ("ku" is the books' word; kept things are "kitta").
        if (spans.any { it.low in setOf("ku", "kku", "க்கு") || (Regex("""(?i)^\p{L}{3,}(ukku|kku)$""").matches(it.low) || Regex("""^[$TA]{2,}க்கு$""").matches(it.low)) && it.unknown } && !known0(parts)) return null
        // A person of the books said as the thing ("Kumar kitta irukku"): a books line, never a kept thing.
        if (parts.itemKey == null && people.any { it.equals(item, true) || NameSound.same(it, item) }) return null
        val takenOnly = spans.any { it.has(Kind.TAKEN) && !it.has(Kind.SAVE) }
        val saveOnly = spans.any { it.has(Kind.SAVE) && !it.has(Kind.TAKEN) }
        val known = parts.itemKey != null || parts.meanings.isNotEmpty()
        // The owner's own word for the thing needs a place said plainly: a place of the list, or "la" / "ல".
        val strongPlace = spans.any { it.has(Kind.PLACE) && it.score >= 2 } || spans.any { it.low in caseOnly }
        return when {
            ask -> Read(Intent.ASK, item, parts.itemKey, parts.meanings, parts.place, parts.person, parts.given)
            // "kuduthuten" is both: kept with a "kitta" person, else given away.
            taken && (takenOnly && !saveOnly || !saveOnly && !parts.given) && parts.place.isEmpty() ->
                Read(Intent.TAKEN, item, parts.itemKey, parts.meanings, person = parts.person, given = parts.given)
            move && (parts.place.isNotEmpty() || parts.given) -> Read(Intent.MOVE, item, parts.itemKey, parts.meanings, parts.place, parts.person, parts.given, parts.time)
            // Kept somewhere / with someone; "passport vechen" with no place is asked where — only for a thing of the list.
            save && (parts.given || known || parts.place.isNotEmpty() && strongPlace) -> Read(Intent.SAVE, item, parts.itemKey, parts.meanings, parts.place, parts.person, parts.given, parts.time)
            // "BP maathirai fridge mela left side dabba la", "Cinthol carton godown rack 3 keezha": no verb — a known thing and a known place.
            !save && !taken && !move && parts.knownItem && parts.knownPlace && !listWord && spans.size >= 3 &&
                parts.headScore >= 2 && spans.any { it.has(Kind.PLACE) && it.score == 3 } &&
                spans.none { sp -> people.any { it.equals(sp.raw, true) || NameSound.same(it, sp.raw) } } ->
                Read(Intent.SAVE, item, parts.itemKey, parts.meanings, parts.place, parts.person, parts.given, parts.time)
            else -> null
        }
    }

    private fun listAll(text: String, spans: List<Span>, people: List<String>, recent: Boolean, old: Boolean): Read? {
        val time = spans.firstOrNull { it.has(Kind.TIME) }?.entries?.first { it.kind == Kind.TIME }?.name
        if (whoAll.containsMatchIn(text)) return Read(Intent.LIST_ALL, filter = Filter(time = time, givenToPeople = true, closed = old))
        val phrase = spans.firstOrNull { it.has(Kind.LIST_ALL) }
        val strong = phrase != null && (keptVerb.containsMatchIn(phrase.low) || yours.containsMatchIn(phrase.low) ||
            Regex("""(?i)theriyum|porul|idam|enga|remember|my list|பொருள்|உனக்கு""").containsMatchIn(phrase.low))
        val mine = yours.containsMatchIn(text)
        val every = everything.containsMatchIn(text)
        val kept = keptVerb.containsMatchIn(text) || spans.any { it.has(Kind.SAVE) }
        val asked = spans.any { it.has(Kind.ASK) }
        if (!(strong || mine || (phrase != null && recent) || (every && (kept || asked)) || (old && Regex("""(?i)list|லிஸ்ட்""").containsMatchIn(text)))) return null
        // Filters, from the words left once the list words go.
        val rest = spans.filterNot { s -> s.has(Kind.LIST_ALL) || s.has(Kind.SAVE) || s.has(Kind.ASK) || s.has(Kind.LIST) || s.has(Kind.FILLER) || s.has(Kind.TIME) ||
            everything.matches(s.low) || keptVerb.containsMatchIn(s.low) || s.low in setOf("naan", "nan", "நான்", "enna", "என்ன", "irukku", "iruku", "இருக்கு", "nu", "னு", "un", "உன்", "kitta", "கிட்ட", "pazhaya", "list", "kaattu", "பழைய", "லிஸ்ட்", "காட்டு") }
        var person: String? = null
        var type: String? = null
        val room = mutableListOf<String>()
        for ((i, s) in rest.withIndex()) {
            val next = rest.getOrNull(i + 1)
            when {
                groupWords[s.low] != null -> type = groupWords[s.low]
                s.has(Kind.PERSON) -> person = s.entries.first { it.kind == Kind.PERSON }.name
                s.has(Kind.ITEM) && s.entries.none { it.kind == Kind.PLACE } -> type = KaiKeptWords.groupOf(s.entries.first { it.kind == Kind.ITEM }.name)
                s.has(Kind.PLACE) -> room += placeName(s, room)
                s.unknown && (next?.low in setOf("kitta", "kita", "கிட்ட") || people.any { NameSound.same(it, s.raw) || it.equals(s.raw, true) } || i == 0) ->
                    person = people.firstOrNull { it.equals(s.raw, true) } ?: people.firstOrNull { NameSound.same(it, s.raw) } ?: s.raw.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
        return Read(Intent.LIST_ALL, filter = Filter(person = person, time = time, room = room, type = type, closed = old))
    }

    private data class Parts(
        val item: String?, val itemKey: String?, val meanings: List<String>, val place: List<String>, val person: String?, val given: Boolean,
        val time: String?, val knownItem: Boolean, val knownPlace: Boolean, val headScore: Int = 0,
    )

    /** The thing, its place and the person, from the line's words. */
    private fun parts(all: List<Span>, people: List<String>): Parts {
        val time = all.firstOrNull { it.has(Kind.TIME) }?.entries?.first { it.kind == Kind.TIME }?.name
        // Verbs, list words, time and filler words go ("anna" before "kitta" is a person, not a filler).
        var spans = all.filterIndexed { i, s ->
            val beforeKitta = all.getOrNull(i + 1)?.low in setOf("kitta", "kita", "கிட்ட")
            !(s.has(Kind.SAVE) || s.has(Kind.ASK) || s.has(Kind.MOVE) || s.has(Kind.TAKEN) || s.has(Kind.LIST) || s.has(Kind.LIST_ALL) || s.has(Kind.TIME) ||
                (s.has(Kind.FILLER) && !(beforeKitta && s.has(Kind.PERSON)) && !s.has(Kind.ITEM) && !s.has(Kind.PLACE)) ||
                s.low in setOf("ippo", "ippa", "இப்போ", "இப்ப", "naan", "nan", "நான்", "irukku", "iruku", "இருக்கு", "irundhuchu", "inime", "now", "the", "my", "a", "i", "have", "pannen", "panniten", "pannu", "pannunga", "panna", "senjen", "பண்ணேன்", "பண்ணிட்டேன்") ||
                everything.matches(s.low))
        }
        // "Ravi kitta", "Amma kitta": the person who has it.
        var person: String? = null
        var given = false
        val k = spans.indexOfFirst { it.low in setOf("kitta", "kita", "கிட்ட", "ku", "kku", "க்கு") }
        if (k > 0) {
            val p = spans[k - 1]
            person = p.entries.firstOrNull { it.kind == Kind.PERSON }?.name
                ?: people.firstOrNull { it.equals(p.raw, true) || NameSound.same(it, p.raw) }
                ?: if (p.unknown) p.raw.replaceFirstChar { it.titlecase(Locale.ROOT) } else null
            if (person != null) { given = true; spans = spans.filterIndexed { i, _ -> i != k - 1 && i != k } }
        }
        // "Amma thodu …": whose it is.
        if (person == null && spans.size >= 2 && spans[0].has(Kind.PERSON) && !spans[0].has(Kind.PLACE)) {
            person = spans[0].entries.first { it.kind == Kind.PERSON }.name
            spans = spans.drop(1)
        }
        // The thing: the first word only the list's things have (else one that is also a place), with the owner's words next to it.
        val itemOnly = spans.indexOfFirst { it.has(Kind.ITEM) && it.entries.none { e -> e.kind == Kind.PLACE } }
        val head = if (itemOnly >= 0) itemOnly else spans.indexOfFirst { it.has(Kind.ITEM) }
        var from: Int
        var to: Int
        if (head >= 0) {
            from = head; to = head
            while (from > 0 && spans[from - 1].unknown) from--
            while (to + 1 < spans.size && (spans[to + 1].unknown || spans[to + 1].only(Kind.ITEM))) to++
        } else {
            // No word of the list: the owner's own words before the place ("Thatha petti almirah la vechen" — kept as said).
            from = spans.indexOfFirst { it.unknown }
            if (from < 0) return Parts(null, null, emptyList(), placeOf(spans, -1, -1), person, given, time, false, spans.any { it.has(Kind.PLACE) })
            to = from
            while (to + 1 < spans.size && spans[to + 1].unknown) to++
        }
        val run = spans.subList(from, to + 1)
        val item = run.joinToString(" ") { it.raw }
        val heads = run.filter { it.has(Kind.ITEM) }
        val qualified = run.size > 1
        val candidates = (if (head >= 0) spans[head] else null)?.entries.orEmpty().filter { it.kind == Kind.ITEM || (!qualified && it.kind == Kind.PLACE) }.map { it.name }.distinct()
        val (key, meanings) = when {
            candidates.size == 1 && head >= 0 -> candidates.single() to emptyList()
            candidates.size > 1 -> null to candidates
            else -> null to emptyList()
        }
        val place = placeOf(spans, from, to)
        return Parts(item, key, meanings, place, person, given, time, heads.isNotEmpty(), spans.filterIndexed { i, _ -> i !in from..to }.any { it.has(Kind.PLACE) },
            if (head >= 0) spans[head].score else 0)
    }

    /** The place's parts, outermost first: the list's names ("dabba" → "box"), positions as said, the owner's own words as said. */
    private fun placeOf(spans: List<Span>, from: Int, to: Int): List<String> {
        val out = mutableListOf<String>()
        for ((i, s) in spans.withIndex()) {
            if (i in from..to) continue
            val low = s.low
            when {
                s.number -> if (out.isNotEmpty()) out[out.size - 1] = out.last() + " " + s.raw else out += s.raw
                low in setOf("side", "pakkam", "சைடு", "பக்கம்") && spans.getOrNull(i - 1)?.has(Kind.SIDE) == true -> Unit
                low in caseOnly || s.low in setOf("kitta", "kita", "கிட்ட") -> Unit
                s.has(Kind.PLACE) -> out += placeName(s, out)
                s.has(Kind.SIDE) || s.has(Kind.LEVEL) || s.has(Kind.PART) || s.has(Kind.MARKER) -> out += tamilPositions[s.raw] ?: sameSide[low] ?: if (s.has(Kind.SIDE) && low.startsWith("left")) "left" else if (s.has(Kind.SIDE) && low.startsWith("right")) "right" else s.raw
                s.has(Kind.FILLER) || s.has(Kind.PERSON) -> Unit
                s.unknown -> out += s.raw
            }
        }
        return out
    }

    /** A place word as kept: the list's name, unless that name is already in the place ("bike … dicky": "dicky" as said) or it has two. */
    private fun placeName(s: Span, sofar: List<String>): String {
        val names = s.entries.filter { it.kind == Kind.PLACE }.map { it.name }.distinct()
        val name = names.singleOrNull() ?: return tamilPositions[s.raw] ?: s.raw
        // A vehicle's parts are said as they are ("dicky", "glove box"), never turned into the vehicle.
        val vehicle = s.entries.any { it.kind == Kind.PLACE && it.group == "vehicle" } && !KaiWordSound.near(s.low, name.lowercase(Locale.ROOT)) && !NameSound.same(s.raw, name)
        return if (vehicle || sofar.any { it.equals(name, true) }) s.raw else name
    }

    /** A word of the owner's Enga Vechen list (any script, by sound) — for picking the mic's best guess ([com.shopai.app.brain.chat.KaiHeard]). */
    fun knows(word: String): Boolean = Tok(word).let { t -> candidates(t).any { p -> p.words.size == 1 && match(t, p.words[0], p.entry.kind) > 0 } }

    // ------------------------------------------------------------------ words → spans

    /** English letters as the mic writes them ("ஆர்சி" = RC, "டிஎல்" = DL). */
    private val letterNames = listOf("எக்ஸ்" to "x", "எஃப்" to "f", "ஹெச்" to "h", "எச்" to "h", "ஆர்" to "r", "எல்" to "l", "எம்" to "m", "என்" to "n",
        "எஸ்" to "s", "டீ" to "t", "டி" to "d", "சி" to "c", "சீ" to "c", "பி" to "b", "பீ" to "b", "ஏ" to "a", "ஈ" to "e", "ஜி" to "g", "கே" to "k", "வி" to "v", "யூ" to "u", "ஓ" to "o")

    private fun spelled(w: String): String? {
        var rest = w
        val out = StringBuilder()
        while (rest.isNotEmpty()) {
            val (ta, la) = letterNames.firstOrNull { rest.startsWith(it.first) } ?: return null
            out.append(la); rest = rest.removePrefix(ta)
        }
        return out.toString().takeIf { it.length in 2..3 }
    }

    private class Tok(raw0: String) {
        val raw = raw0
        val low = (if (KaiSpokenWords.hasTamil(raw0)) spelled(raw0) else null) ?: raw0.lowercase(Locale.ROOT)
        /** A Tamil word as Kai's words read it ("குடுத்தேன்" → "kuduthen"). */
        val norm: String by lazy { KaiSpokenWords.normalize(raw).lowercase(Locale.ROOT) }
    }

    /** The line's words, each phrase of the list matched as one (longest first; "nagai petti" is a jewel box, not gold + suitcase). */
    private fun spans(text: String): List<Span> {
        val toks = tokens(text)
        val out = mutableListOf<Span>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            // Describing words ("pudhu chappal", "periya petti") are the owner's own words, never matched by sound ("pudhu" is no "photo").
            if (t.low in plainWords) { out += Span(t.raw, t.low, emptyList()); i++; continue }
            if (t.raw.all(Char::isDigit) || Regex("""^\d+(st|nd|rd|th|vathu|aavathu)$""").matches(t.low)) { out += Span(t.raw, t.low, emptyList(), number = true); i++; continue }
            var bestLen = 0
            var bestScore = 0
            val best = mutableListOf<Entry>()
            for (p in candidates(t)) {
                val n = p.words.size
                if (i + n > toks.size) continue
                var score = 3
                for (j in 0 until n) {
                    score = minOf(score, match(toks[i + j], p.words[j], p.entry.kind))
                    if (score == 0) break
                }
                if (score == 0) continue
                // A phrase of places that are each a place ("bank locker"): its words stay apart.
                // ("fridge mela", "bed adiyila": a place and a position — kept as two parts.)
                if (p.entry.kind == Kind.PLACE && n > 1 && p.words.all { w -> KaiKeptWords.phrases.any { q -> q.words.size == 1 && q.words[0] == w &&
                        q.entry.kind in setOf(Kind.PLACE, Kind.LEVEL, Kind.MARKER, Kind.SIDE, Kind.PART) } }) continue
                // Longest first; then the word itself over the same sound over a near one ("thatha" is the grandfather, not "thattu").
                if (n > bestLen || (n == bestLen && score > bestScore)) { bestLen = n; bestScore = score; best.clear(); best += p.entry }
                else if (n == bestLen && score == bestScore) best += p.entry
            }
            // The mic's Tamil word means what the same word in English letters means: "காசு" (the list's cash) is "kaasu" — cash or coins.
            if (bestLen == 1 && bestScore == 3 && KaiSpokenWords.hasTamil(t.raw)) {
                val latin = KaiKeptWords.phrases.filter { p -> p.entry in best && p.words.size == 1 && !KaiSpokenWords.hasTamil(p.words[0]) }.map { it.words[0] }.filter { NameSound.same(t.raw, it) }.toSet()
                KaiKeptWords.phrases.filter { p -> p.words.size == 1 && p.words[0] in latin && p.entry.kind in setOf(Kind.ITEM, Kind.PLACE) && p.entry !in best }
                    .forEach { best += it.entry }
            }
            if (bestLen == 0) { out += Span(t.raw, t.low, emptyList()); i++ }
            else { out += Span(toks.subList(i, i + bestLen).joinToString(" ") { it.raw }, toks.subList(i, i + bestLen).joinToString(" ") { it.low }, best.distinct(), score = bestScore); i += bestLen }
        }
        return out
    }

    /** Words, with a case ending glued on split off ("drawerla" / "டிராயர்ல" → drawer + la) when what's left is a place of the list. */
    private fun tokens(text: String): List<Tok> {
        val words = text.replace(Regex("""[?!.,;:"“”'()]"""), " ").replace(Regex("""(?<=\p{L})-(?=\p{L})"""), " ").replace("-", " ")
            .replace(Regex("""(?i)(?<![\p{L}])enna\s+enna(?![\p{L}])"""), "ennenna").replace(Regex("""என்ன\s+என்ன"""), "என்னென்ன")
            .split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val out = mutableListOf<Tok>()
        for (w in words) {
            val t = Tok(w)
            if (candidates(t).any { p -> p.words.size == 1 && match(t, p.words[0], p.entry.kind) > 0 }) { out += t; continue }
            val tamil = KaiSpokenWords.hasTamil(w)
            val m = (if (tamil) Regex("""^([$TA]{2,}?)(க்குள்ள|குள்ள|யில|ுல|ில|லே|ல்|ல)$""") else Regex("""(?i)^(\p{L}{3,}?)(kulla|ulla|ulle|ula|ila|la|le)$""")).find(w)
            val k = (if (tamil) Regex("""^([$TA]{2,}?)(கிட்ட)$""") else Regex("""(?i)^(\p{L}{3,}?)(kitta)$""")).find(w)
            when {
                k != null -> { out += Tok(k.groupValues[1]); out += Tok(if (tamil) "கிட்ட" else "kitta") }
                m != null && placeWord(Tok(m.groupValues[1])) -> { out += Tok(m.groupValues[1]); out += Tok(if (tamil) "ல" else "la") }
                else -> out += t
            }
        }
        return out
    }

    private fun placeWord(t: Tok) = candidates(t).any { p -> p.words.size == 1 && (p.entry.kind == Kind.PLACE || p.entry.kind == Kind.PART) && match(t, p.words[0], p.entry.kind) > 0 }

    /** The list's phrases whose first word could be [t] (by its first sound, so a near spelling is still tried). */
    private fun candidates(t: Tok): List<KaiKeptWords.Phrase> = byFirst.getOrElse(firstKey(t.low)) { emptyList() } + if (KaiSpokenWords.hasTamil(t.raw)) latinPhrases else emptyList()

    private val latinPhrases: List<KaiKeptWords.Phrase> by lazy { KaiKeptWords.phrases.filter { p -> !KaiSpokenWords.hasTamil(p.words[0]) } }
    private val byFirst: Map<String, List<KaiKeptWords.Phrase>> by lazy { KaiKeptWords.phrases.groupBy { firstKey(it.words[0]) } }

    private fun firstKey(w: String): String = if (KaiSpokenWords.hasTamil(w)) "ta:" + w.take(1) else KaiWordSound.sound(w).take(1).let { c -> if (c.isNotEmpty() && c[0] in "aeiu") "v" else c }

    /** "குமார்" is not "camera" (the same k-m-r): the first vowel must agree too (a / e / i one, o / u one — English letters spell them loosely). */
    private fun firstVowelAlike(a: String, b: String): Boolean {
        fun v(x: String) = NameSound.vowels(x).firstOrNull()?.let { if (it in "aei") 'a' else 'u' }
        // ("umbrella" — English letters' opening "u" is Tamil's "அ")
        val latin = if (KaiSpokenWords.hasTamil(a)) b else a
        return v(a) == v(b) || latin.startsWith("u", ignoreCase = true)
    }

    /** 3 = the word itself, 2 = the same sound, 1 = a near sound, 0 = not it. Short words and markers only as written. */
    private fun match(t: Tok, word: String, kind: Kind): Int {
        if (t.low == word) return 3
        val loose = kind != Kind.MARKER && kind != Kind.FILLER && kind != Kind.LEVEL && kind != Kind.SIDE
        // An intent word only as itself or the same sound ("vachen" / "vechen") — never a near one ("kuduthan" he gave is not "kuduthen").
        val verb = kind in intents
        val tTa = KaiSpokenWords.hasTamil(t.raw)
        val wTa = KaiSpokenWords.hasTamil(word)
        return when {
            !loose -> if (tTa && !wTa && tamilPositions[t.raw] == word) 2 else 0
            tTa && wTa -> if (KaiSpokenWords.spoken(t.raw) == word) 3 else 0
            tTa && !wTa -> when {
                t.norm == word -> 2
                // A thing / place in Tamil letters by sound — three consonants or more ("பாஸ்போர்ட்", "லேப்டாப்"); two only with the same vowels.
                !verb && word.length >= 4 && NameSound.same(t.raw, word) && firstVowelAlike(t.raw, word) &&
                    (NameSound.key(t.raw).length >= 3 || vowelsAlike(t.raw, word)) -> 2
                else -> 0
            }
            !tTa && !wTa -> if (word.length < 4 || t.low.length < 4) 0 else if (KaiWordSound.sound(t.low) == KaiWordSound.sound(word)) 2
                else if (!verb && KaiWordSound.near(t.low, word)) 1 else 0
            else -> 0
        }
    }

    private val intents = setOf(Kind.SAVE, Kind.ASK, Kind.MOVE, Kind.TAKEN, Kind.LIST, Kind.LIST_ALL)

    private fun vowelsAlike(a: String, b: String): Boolean {
        fun v(x: String) = NameSound.vowels(x).map { if (it in "aei") 'a' else 'u' }.joinToString("").replace(Regex("(.)\\1+"), "$1")
        return v(a) == v(b)
    }
}
