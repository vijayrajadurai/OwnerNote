package com.shopai.app.brain.morning

import com.shopai.app.brain.memory.KaiMemory
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.MemoryStatus
import com.shopai.app.brain.tools.KaiSpokenWords
import java.util.Locale

/**
 * The owner's own Morning Work order ("Collections first, appuram Stock,
 * last-la Reminders"). Private to one owner in one business: it is kept in
 * that owner's Kai memory (a PREFERENCE record, scoped by business + owner)
 * and only ever changed after the owner pressed Save.
 *
 * It only changes the ORDER Kai reads the sections in — never which tasks
 * exist, their figures, or their priority inside a section. Every section
 * still appears (nothing is hidden); sections the owner didn't name keep the
 * default order.
 */
data class MorningRoutinePreference(
    val id: String,
    val ownerId: String?,
    val businessId: String,
    /** All sections, in the owner's order. */
    val orderedSections: List<MorningSection>,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

object MorningRoutines {
    /** The existing default: the priority order of the work. */
    val DEFAULT: List<MorningSection> = MorningSection.entries.toList()

    /** Where the routine lives in the owner's Kai memory. */
    const val MEMORY_KEY = "morning routine"
    const val MEMORY_MEANING = "MORNING_ROUTINE"

    /** [order] completed with any missing section (default order) — always every section, once. */
    fun complete(order: List<MorningSection>): List<MorningSection> = (order + DEFAULT).distinct()

    /** Tasks in the owner's section order; inside a section the normal priority order. Null routine = the default priority order. */
    fun sort(tasks: List<MorningTask>, routine: List<MorningSection>?): List<MorningTask> {
        val sorted = MorningAnalyzer.sort(tasks)
        if (routine.isNullOrEmpty() || complete(routine) == DEFAULT) return sorted
        val rank = complete(routine).withIndex().associate { (i, s) -> s to i }
        return sorted.sortedBy { rank[MorningSection.of(it.taskType)] ?: Int.MAX_VALUE } // stable: priority kept inside a section
    }

    /** "1. Collections  2. Stock  …" for the confirmation card. */
    fun describe(order: List<MorningSection>, lang: com.shopai.app.brain.KaiLang): List<String> =
        complete(order).mapIndexed { i, s -> "${i + 1}. ${s.icon} ${s.label(lang)}" }

    // ------------------------------------------------------------ storage (the owner's Kai memory)

    /** The saved routine of the owner whose memory is open (null: none saved / switched off → default). */
    fun load(memory: KaiPrivateMemory): MorningRoutinePreference? {
        val m = memory.preference(MEMORY_MEANING) ?: return null
        return fromMemory(m)
    }

    /** Only after the owner pressed Save. */
    suspend fun save(memory: KaiPrivateMemory, order: List<MorningSection>): MorningRoutinePreference? {
        val m = memory.savePreference(MEMORY_KEY, MEMORY_MEANING, complete(order).joinToString(",") { it.name })
        return fromMemory(m)
    }

    /** "Default morning order-ku change pannu" (after Save): the owner's routine is removed; the default order is used. */
    suspend fun reset(memory: KaiPrivateMemory) {
        memory.forgetPreference(MEMORY_MEANING)
    }

    fun fromMemory(m: KaiMemory): MorningRoutinePreference? {
        val order = m.meaningValue.split(',').mapNotNull { n -> MorningSection.entries.firstOrNull { it.name == n.trim() } }
        if (order.isEmpty()) return null
        return MorningRoutinePreference(m.id, m.ownerId, m.businessId, complete(order), m.status == MemoryStatus.ACTIVE, m.createdAt, m.updatedAt)
    }
}

/** What the owner said about their morning order. */
sealed interface RoutineRequest {
    /** A new order. [moved] = the one section the owner moved (a single change), else null. */
    data class Set(val order: List<MorningSection>, val moved: Pair<MorningSection, RoutinePlace>? = null) : RoutineRequest

    /** Back to the default order. */
    data object Reset : RoutineRequest

    /** "Morning routine change pannu" without the order: Kai asks for it. */
    data object Change : RoutineRequest
}

enum class RoutinePlace { FIRST, NEXT, LAST }

/**
 * Reads a morning order from the owner's words (Tamil script, Tanglish,
 * English): "Morning-la first collections paakanum. Appuram stock. Last-la
 * reminders.", "Stock first, collection next", "Reminders-a last-la podu",
 * "Collections first venam, stock first", "Default morning order-ku change pannu".
 *
 * It answers only when the words are clearly about the ORDER of the morning
 * brief — an amount, a time or a person means it is something else (a
 * payment, a reminder…), and a single "first X kaatu" counts only right after
 * a morning brief ([context]).
 */
object MorningRoutineParser {
    private val sectionWords: List<Pair<Regex, MorningSection>> = listOf(
        w("""pending\s+payments?|customer\s+(?:payments?|dues?)|collections?|collect|vasool\w*|vasul\w*|receivables?|vara\s*vendiya\w*|baaki""") to MorningSection.COLLECTIONS,
        w("""supplier\s+(?:payments?|dues?)|purchases?|payables?|kudukka\s*vendiya\w*|kodukka\s*vendiya\w*|suppliers?|payments?""") to MorningSection.PAYMENTS,
        w("""low\s+stock|stocks?|inventory""") to MorningSection.STOCK,
        w("""expiry|expiring|expire[sd]?|kaalavadhi""") to MorningSection.EXPIRY,
        w("""reminders?|remind|nyabagam\w*""") to MorningSection.REMINDERS,
        w("""drafts?""") to MorningSection.DRAFTS,
    )
    private val first = w("""first(?:u|la)?|fast|mudhal\w*|modhal\w*|muthal\w*|top""")
    private val next = w("""next|appuram|aprom|apram|aprm|then|second|piragu|adhukappuram|after\s+that""")
    private val last = w("""last(?:u|la)?|kadaisi\w*|kadasi\w*|finally|end(?:la)?""")
    /** Connectors that start a new part of the order ("…, appuram stock"). */
    private val connector = Regex("""(?i)(?<![\p{L}])(?=(?:appuram|aprom|apram|aprm|then|piragu|adhukappuram|and)(?![\p{L}]))""")
    private val negation = w("""venam|vendam|vendaam|venaam|dont|don't|do\s+not|not|no""")
    private val routineWords = w(
        """routine|brief|morning\s*(?:work|brief|order|routine|la)|morningla|kaalaila|kalaila|kaalai|every\s+morning|daily|dhinamum|dinamum|inime|next\s+time""",
    )
    private val placedLa = w("""first\s*la|firstla|last\s*la|lastla|kadaisi\s*la|kadaisila|mudhalla|modhalla""")
    private val placeVerb = w("""podu|potu|vei|vai|vaiyi|kaattu|kaatu|kaami|kaaminga|kaattunga|show|venum|vendum|put|keep""")
    private val reset = w("""default""")
    private val changeVerb = w("""change|maathu|maaththu|mathu|matthu|maatru|edit|reset""")
    /** A time or a date — a reminder / schedule, not an order. */
    private val timeWords = w("""naalai\w*|nalaikku|tomorrow|manikku|mani|minutes?|mins?|hours?|am|pm|o'clock|today|innaiku|innikku|weeks?|months?|years?|vaaram|maasam""")

    fun parse(raw: String, current: List<MorningSection>?, context: Boolean = false, people: List<String> = emptyList()): RoutineRequest? {
        val text = KaiSpokenWords.normalize(raw.trim()).lowercase(Locale.ROOT).replace('-', ' ')
        if (text.isBlank() || text.any { it.isDigit() }) return null
        if (timeWords.containsMatchIn(text)) return null
        if (people.any { p -> p.isNotBlank() && Regex("""(?i)(?<![\p{L}])${Regex.escape(p.lowercase(Locale.ROOT))}""").containsMatchIn(text) }) return null
        val aboutRoutine = routineWords.containsMatchIn(text)
        val mentionsOrder = Regex("""(?i)(?<![\p{L}])order(?![\p{L}])""").containsMatchIn(text)

        // "Default morning order-ku change pannu", "morning routine reset pannu".
        if (reset.containsMatchIn(text) && (aboutRoutine || mentionsOrder)) return RoutineRequest.Reset
        if (Regex("""(?i)(?<![\p{L}])reset(?![\p{L}])""").containsMatchIn(text) && aboutRoutine) return RoutineRequest.Reset

        val clauses = text.split(Regex("""[.,;!?\n]+""")).flatMap { it.split(connector) }.map { it.trim() }.filter { it.isNotEmpty() }
        data class Mention(val section: MorningSection, val place: RoutinePlace?, val negated: Boolean)
        val mentions = mutableListOf<Mention>()
        for (clause in clauses) {
            val found = sections(clause)
            if (found.isEmpty()) continue
            val cues = cues(clause)
            val negated = negation.containsMatchIn(clause)
            // The order words come before the sections ("first collections") or after them ("stock first").
            val cueBefore = cues.isNotEmpty() && cues.first().first < found.first().first
            for ((pos, section) in found) {
                val cue = if (cueBefore) cues.lastOrNull { it.first < pos } else cues.firstOrNull { it.first > pos } ?: cues.lastOrNull { it.first < pos }
                mentions += Mention(section, cue?.second, negated && cue != null)
            }
        }
        if (mentions.isEmpty()) {
            return if ((aboutRoutine || mentionsOrder) && changeVerb.containsMatchIn(text) && Regex("""(?i)routine|order""").containsMatchIn(text)) RoutineRequest.Change else null
        }
        val placed = mentions.filter { it.place != null }
        if (placed.isEmpty()) return null
        val strong = aboutRoutine || mentions.map { it.section }.distinct().size >= 2 ||
            (placedLa.containsMatchIn(text) && placeVerb.containsMatchIn(text)) || context
        if (!strong) return null

        val wanted = mentions.filter { !it.negated }.distinctBy { it.section }
        if (wanted.isEmpty()) return null
        val firsts = wanted.filter { it.place == RoutinePlace.FIRST }.map { it.section }
        val middle = wanted.filter { it.place == RoutinePlace.NEXT || it.place == null }.map { it.section }
        val lasts = wanted.filter { it.place == RoutinePlace.LAST }.map { it.section }
        if (wanted.size == 1) {
            val one = wanted.single()
            val place = one.place ?: return null
            val base = MorningRoutines.complete(current ?: MorningRoutines.DEFAULT) - one.section
            val order = when (place) {
                RoutinePlace.FIRST -> listOf(one.section) + base
                RoutinePlace.LAST -> base + one.section
                RoutinePlace.NEXT -> base.take(1) + one.section + base.drop(1)
            }
            return RoutineRequest.Set(order, one.section to place)
        }
        val named = firsts + middle + lasts
        val order = firsts + middle + (MorningRoutines.DEFAULT - named.toSet()) + lasts
        return RoutineRequest.Set(order)
    }

    private fun sections(clause: String): List<Pair<Int, MorningSection>> {
        val taken = mutableListOf<IntRange>()
        val out = mutableListOf<Pair<Int, MorningSection>>()
        for ((rx, section) in sectionWords) {
            for (m in rx.findAll(clause)) {
                if (taken.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                taken += m.range
                out += m.range.first to section
            }
        }
        return out.sortedBy { it.first }
    }

    private fun cues(clause: String): List<Pair<Int, RoutinePlace>> {
        val out = mutableListOf<Pair<Int, RoutinePlace>>()
        first.findAll(clause).forEach { out += it.range.first to RoutinePlace.FIRST }
        next.findAll(clause).forEach { out += it.range.first to RoutinePlace.NEXT }
        last.findAll(clause).forEach { out += it.range.first to RoutinePlace.LAST }
        return out.sortedBy { it.first }
    }

    private fun w(body: String) = Regex("""(?i)(?<![\p{L}])(?:$body)(?![\p{L}])""")
}
