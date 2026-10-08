package com.shopai.app.brain.memory

import com.shopai.app.brain.tools.KaiStock
import java.util.Locale

/** What a taught phrase means, by kind. */
sealed interface PersonalMeaning {
    /** "potti" = box. [unit] = the unit word Kai's stock flow reads ("box", "carton", "packet"). */
    data class Unit(val unit: String) : PersonalMeaning

    /** "konjam nerathula" = 10 minutes. [time] = words Kai's reminder flow reads ("10 minutes"). */
    data class ReminderTime(val time: String) : PersonalMeaning

    /** "anna" = Kumar (a customer / supplier / product of this business, stored by id). */
    data class Entity(val entity: KnownEntity) : PersonalMeaning

    /** Anything else ("maal" = stock, "kaasu pottaan" = customer payment received) — checked for a money / stock meaning before saving. */
    data class Words(val words: String) : PersonalMeaning

    /** A money / stock / summary action ("thooki kudu" = Payment Out). */
    data class Action(val meaning: KaiMeaning) : PersonalMeaning

    /** The owner corrected Kai: these words are NOT [kind] ("bill kuduthaan" = bill handed over, not a payment). */
    data class NotAction(val replacement: String, val kind: String) : PersonalMeaning
}

/** A teaching sentence: [phrase] means [meaning], for this business (or, when the owner said so, all their businesses). */
data class PersonalTeach(val phrase: String, val meaning: PersonalMeaning, val scope: MemoryScope, val sourceText: String)

/**
 * The owner telling Kai what their words mean, in Tamil script, Tanglish,
 * English or mixed — "Kai, enga kadaiyila 'potti' na 1 box", "'பொட்டி'ன்னா box",
 * "maal na stock", "naan 'maal' nu sonna stock meaning", "Enga kadaiyila
 * packet-ku 'cover' nu solvom", "Here we call cartons 'boxes'", "'konjam
 * nerathula' na 10 minutes", "ella kadaiyilum 'maal' na stock".
 *
 * It only READS the sentence: nothing is saved here — Kai repeats the meaning
 * and saves it after the owner says yes.
 */
object KaiPersonalTeaching {
    private const val Q = """[`'"‘’“”]"""
    private const val TERM = """[\p{L}\p{M}][\p{L}\p{M}\s\-]{0,40}?"""

    /** "Kai, …", "Owner, …", and the "illa, …" of a correction ("illa, potti na packet") before the teaching itself. */
    private val lead = Regex("""(?i)^\s*(?:(?:hey\s+)?kai|owner|illa|illai|ille|no|nope|இல்லை|இல்ல)[\s,.:-]+""")
    private val ownerWide = Regex(
        """(?i)(?<![\p{L}])(?:ella|ellaa|ellam|all)\s+(?:my\s+)?(?:kadaiyilum|kadaigalilum|kadaila|kadaiyila|business\s*-?\s*la(?:yum)?|businesses|shops|kadai)(?![\p{L}])|எல்லா\s+கடையிலும்""",
    )
    private val businessWords = Regex(
        """(?i)^\s*(?:enga|en|engal|namma|indha|intha|in\s+my|in\s+this|in\s+our|here\s+in)\s+(?:kadai\s*-?\s*(?:yila|la|il)|kadaiyila|kadaila|shop\s*-?\s*(?:la|il)?|business\s*-?\s*(?:la|il)?|store)[\s,]+|^\s*(?:ingae|inga|here)[\s,]+|^\s*எங்க\s+கடையில[\s,]*""",
    )
    private val questionWords = Regex("""(?i)(?<![\p{L}])(enna|yenna|yaar|yaaru|edhu|ethu|eppo|evlo|what|who|when|how|why|which)(?![\p{L}])""")

    /** A teaching sentence, or null for any other message. [entities] = the business's own records. */
    fun parse(text: String, entities: List<KnownEntity>): PersonalTeach? {
        val raw = text.trim()
        if (raw.isEmpty() || raw.contains('?')) return null
        var t = raw.trimEnd('.', '!', ' ')
        t = t.replace(lead, "")
        val scope = if (ownerWide.containsMatchIn(t)) MemoryScope.OWNER else MemoryScope.BUSINESS
        t = t.replace(ownerWide, " ").replace(Regex("""\s+"""), " ").trim().trimStart(',', ' ')
        t = t.replace(businessWords, "").trim()
        // "… remember pannu / nyabagam vechuko" at the end.
        t = t.replace(Regex("""(?i)[\s,]*(-?\s*nu\s+)?(remember\s+(pannu|panniko|pannikko|vechuko)|nyabagam\s+vechuko|gnabagam\s+vechuko|vechuko|ninaivil vai)\s*$"""), "").trim()

        val (phrase, meaning) = split(t) ?: return null
        val p = phrase.trim().trim('`', '\'', '"', '‘', '’', '“', '”', '-', ' ')
        val m = meaning.trim().trim('`', '\'', '"', '‘', '’', '“', '”', ' ')
            .replace(Regex("""(?i)[\s-]*(?:meaning|nu\s+artham|artham|aa|ah|-nu|nu|dhaan|thaan)$"""), "").trim()
        if (p.isEmpty() || m.isEmpty()) return null
        val pn = KaiPrivateMemory.normalize(p)
        val mn = KaiPrivateMemory.normalize(m)
        if (pn.isEmpty() || mn.isEmpty() || pn == mn) return null
        if (pn.split(' ').size > 4 || mn.split(' ').size > 5) return null
        if (questionWords.containsMatchIn(pn) || questionWords.containsMatchIn(mn)) return null
        // A real name of the business's records is never a new word for something else.
        if (entities.any { KaiPrivateMemory.normalize(it.name) == pn }) return null
        val meaningKind = classify(m, entities)
        // Without quotes only a plain "X na Y" teaches a word ("maal na stock") — a longer sentence with "na" in it is just a message.
        val quoted = Regex(Q).containsMatchIn(raw)
        if (!quoted && meaningKind is PersonalMeaning.Words && KaiTeaching.actionOptions(mn).isEmpty() &&
            (pn.split(' ').size > 1 || mn.split(' ').size > 2)
        ) return null
        return PersonalTeach(p, meaningKind, scope, raw)
    }

    /** (phrase, meaning words) from the sentence shapes owners use. */
    private fun split(t: String): Pair<String, String>? {
        // "naan 'maal' nu sonna stock meaning" / "'maal' nu sonna stock".
        Regex("""(?i)^(?:naan|nan|i)?\s*$Q?($TERM)$Q?\s*-?\s*(?:nu|nnu|னு)\s+(?:sonna|sonnaa|solli(?:na)?|solluven|solren|solradhu)\s+(.+)$""").find(t)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        // "packet-ku 'cover' nu solvom" — the meaning first.
        Regex("""(?i)^($TERM)\s*-?\s*(?:ku|kku|க்கு)\s+$Q?($TERM)$Q?\s*-?\s*(?:nu|னு)\s+(?:solvom|solluvom|solrom|solvaanga|solluvaanga|solradhu|சொல்லுவோம்)$""").find(t)?.let {
            return it.groupValues[2] to it.groupValues[1]
        }
        // "we call cartons 'boxes'" — the meaning first.
        Regex("""(?i)^(?:here\s+)?we\s+(?:call|say)\s+($TERM)\s+$Q($TERM)$Q$""").find(t)?.let {
            return it.groupValues[2] to it.groupValues[1]
        }
        // "'பொட்டி'ன்னா box", "'potti' na 1 box", "potti means box", "potti = box", "potti-na box".
        Regex("""(?i)^$Q($TERM)$Q\s*-?\s*(?:ன்னா|னா|na|nna|naa|means?|=|nu sonna|endral|என்றால்)\s*(.+)$""").find(t)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        Regex("""(?i)^($TERM)\s*(?:-\s*(?:na|nna|naa)|\s(?:na|nna|naa|means?)|\s*=|ன்னா|னா)\s+(.+)$""").find(t)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        return null
    }

    private val reminderTime = Regex("""(?i)^(\d+|oru|rendu|moonu|anju|pathu|one|two|five|ten|fifteen|twenty|thirty)\s*(minutes?|mins?|nimisham|hours?|mani\s*neram)$""")

    /** What the meaning words are: a unit, a reminder time, a record of the business, or words. */
    fun classify(meaning: String, entities: List<KnownEntity>): PersonalMeaning {
        val mn = KaiPrivateMemory.normalize(meaning)
        val words = mn.split(' ').filter { it.isNotEmpty() }
        // "1 box", "oru box", "box", "cartons".
        val unitWord = words.lastOrNull()?.takeIf { words.size == 1 || (words.size == 2 && words[0] in setOf("1", "oru", "one", "a", "an", "ek")) }
        KaiStock.unitOf(unitWord.orEmpty())?.let { return PersonalMeaning.Unit(unitName(it)) }
        reminderTime.find(mn)?.let { m ->
            val n = m.groupValues[1]
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            val en = when {
                unit.startsWith("min") || unit == "nimisham" -> "minutes"
                else -> "hours"
            }
            return PersonalMeaning.ReminderTime("$n $en")
        }
        KaiTeaching.entityIn(meaning, entities)?.let { return PersonalMeaning.Entity(it) }
        return PersonalMeaning.Words(mn)
    }

    /** "BOX" → "box": the unit word as the stock flow and the owner say it. */
    fun unitName(code: String): String = when (code) {
        "PCS" -> "piece"
        "PACK" -> "packet"
        else -> code.lowercase(Locale.ROOT)
    }

    /** The words of a short answer to "'petti' na box-ah? packet-ah?": the unit, or null. */
    fun unitAnswer(text: String): String? {
        val n = KaiPrivateMemory.normalize(text)
            .replace(Regex("""(?:^|\s)(?:it\s+is|adhu|athu|idhu|1|oru|one|a)(?=\s)"""), " ")
            .replace(Regex("""\s*(?:aa|ah|dhaan|thaan|than|owner)$"""), "")
            .trim()
        val w = n.split(' ').filter { it.isNotEmpty() }
        if (w.isEmpty() || w.size > 2) return null
        return KaiStock.unitOf(w.last())?.let(::unitName)
    }
}
