package com.shopai.app.brain.tools

import java.time.LocalDateTime

/**
 * One thing the owner told Kai where they kept it ("Enga Vechen?", owner, 24 Oct 2026): "passport bedroom almirah mela drawer la
 * vechen". [item] is the owner's own word, as said ("veetu saavi", "பாஸ்போர்ட்"); [itemKey] the name it matched in the word list
 * ("house key"), or null for a word of the owner's own ("Thatha kannadi petti"). [placeParts] the place, outermost first
 * (["bedroom", "almirah", "mela", "drawer"]); [person] "X kitta" — the thing is with that person; [keptBy] whose it is / who kept it
 * ("Amma thodu"). [history] the places it was in before a move, oldest first. Closed ("eduthuten", "kidaichiduchu") entries stay,
 * hidden unless asked.
 */
data class KeptItem(
    val id: String,
    val item: String,
    val itemKey: String?,
    val placeParts: List<String>,
    val person: String?,
    val keptBy: String?,
    val photo: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val status: Status,
    val history: List<KeptPlace> = emptyList(),
) {
    enum class Status { ACTIVE, CLOSED }

    val active: Boolean get() = status == Status.ACTIVE
}

/** A place a kept thing was in before it moved: its parts / person, and when it left. */
data class KeptPlace(val parts: List<String>, val person: String?, val until: LocalDateTime)

/** The table's text forms of [KeptItem]'s lists (one Room column each; no JSON library needed on the JVM). */
object KeptText {
    private const val PART = "\u001F"
    private const val ENTRY = "\u001E"

    fun parts(parts: List<String>): String = parts.joinToString(PART)

    fun parts(text: String?): List<String> = text.orEmpty().split(PART).filter { it.isNotBlank() }

    fun history(history: List<KeptPlace>): String =
        history.joinToString(ENTRY) { h -> listOf(h.until.toString(), h.person.orEmpty(), parts(h.parts)).joinToString("\u001D") }

    fun history(text: String?): List<KeptPlace> = text.orEmpty().split(ENTRY).filter { it.isNotBlank() }.mapNotNull { e ->
        val f = e.split("\u001D")
        val until = runCatching { LocalDateTime.parse(f[0]) }.getOrNull() ?: return@mapNotNull null
        KeptPlace(parts(f.getOrNull(2)), f.getOrNull(1)?.takeIf { it.isNotBlank() }, until)
    }
}

/**
 * The full list's groups (the owner's list › list_all_answer): things with a person first ("Aatkal kitta"), then one group per top
 * place (room / almirah …), the group with the newest thing first; inside a group, newest first. Kai Chat and the Enga Vechen
 * screen show the same groups.
 */
object KeptGroups {
    data class Group(val top: String?, val withPeople: Boolean, val items: List<KeptItem>)

    fun of(items: List<KeptItem>): List<Group> {
        val newest = items.sortedByDescending { it.updatedAt }
        val people = newest.filter { it.person != null }
        val placed = newest.filter { it.person == null }.groupBy { it.placeParts.firstOrNull()?.lowercase(java.util.Locale.ROOT) }
            .entries.sortedByDescending { (_, v) -> v.maxOf { it.updatedAt } }
            .map { (top, v) -> Group(top?.let { v.first().placeParts.first() }, false, v) }
        return listOfNotNull(people.takeIf { it.isNotEmpty() }?.let { Group(null, true, it) }) + placed
    }

    /** Days since it was given ("Ravi kitta laptop — 12 naal aachu"); 30 or more is shown highlighted. */
    fun daysWith(k: KeptItem, today: java.time.LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(k.createdAt.toLocalDate(), today)

    const val OLD_DAYS = 30L
}
