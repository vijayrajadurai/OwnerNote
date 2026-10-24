package com.shopai.app.brain.tools

import java.util.Locale

/**
 * The words of "Enga Vechen?" — read from the owner's own list, `kai/enga_vechen_words.json` (the source of truth, 24 Oct 2026):
 * the verbs of each intent, place markers, positions, places, items, people, time and filler words, in Tamil script, Tanglish and
 * English. Kai matches them by sound ([KaiKept]), so a spelling the list doesn't have is still read; a word in no list is the
 * owner's own word and kept as said — never rejected.
 */
object KaiKeptWords {

    /** One meaning of a word: what kind of word it is, its name ("house key", "bedroom", "save") and its group ("keys", "rooms"). */
    data class Entry(val kind: Kind, val name: String, val group: String)

    enum class Kind { SAVE, ASK, MOVE, TAKEN, LIST_ALL, LIST, MARKER, LEVEL, SIDE, PART, PLACE, ITEM, PERSON, TIME, FILLER }

    /** A phrase of the list (its words, lower case) and its meaning. */
    data class Phrase(val words: List<String>, val entry: Entry)

    val phrases: List<Phrase> by lazy { build(read()) }

    /** Item groups ("documents", "keys", "money_and_gold"…) → their item names. */
    val itemGroups: Map<String, List<String>> by lazy { phrases.filter { it.entry.kind == Kind.ITEM }.groupBy { it.entry.group }.mapValues { (_, v) -> v.map { it.entry.name }.distinct() } }

    /** The group of an item name ("passport" → "documents"). */
    fun groupOf(itemKey: String?): String? = itemKey?.let { k -> phrases.firstOrNull { it.entry.kind == Kind.ITEM && it.entry.name.equals(k, true) }?.entry?.group }

    /** The owner's list as written: `{ "intents": { "save": { "words": [...] } … }, "places": { "rooms": [{ "name", "aliases" }] } … }`. */
    private fun read(): Map<String, Any?> {
        val text = KaiKeptWords::class.java.getResourceAsStream("/kai/enga_vechen_words.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return (Json(text).value() as? Map<String, Any?>).orEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun build(j: Map<String, Any?>): List<Phrase> = buildList {
        fun words(any: Any?): List<String> = (any as? List<Any?>).orEmpty().filterIsInstance<String>()
        fun add(kind: Kind, name: String, group: String, said: String) {
            val ws = said.lowercase(Locale.ROOT).replace("...", " ").split(Regex("""[\s]+""")).map { it.trim(',', '.', '?') }.filter { it.isNotEmpty() }
            if (ws.isNotEmpty()) add(Phrase(ws, Entry(kind, name, group)))
        }
        val intents = j["intents"] as? Map<String, Any?> ?: emptyMap()
        for ((name, kind) in listOf("save" to Kind.SAVE, "ask" to Kind.ASK, "move" to Kind.MOVE, "taken" to Kind.TAKEN, "list_all" to Kind.LIST_ALL, "list" to Kind.LIST)) {
            words((intents[name] as? Map<String, Any?>)?.get("words")).forEach { add(kind, name, name, it) }
        }
        words((j["place_markers"] as? Map<String, Any?>)?.get("words")).forEach { add(Kind.MARKER, it, "marker", it) }
        (j["positions"] as? Map<String, Any?>)?.let { p ->
            words(p["levels"]).forEach { add(Kind.LEVEL, it, "levels", it) }
            words(p["sides"]).forEach { add(Kind.SIDE, it, "sides", it) }
            words(p["parts"]).forEach { add(Kind.PART, it, "parts", it) }
        }
        fun named(section: Any?, kind: Kind) {
            for ((group, list) in (section as? Map<String, Any?>).orEmpty()) {
                for (e in (list as? List<Any?>).orEmpty().filterIsInstance<Map<String, Any?>>()) {
                    val name = e["name"] as? String ?: continue
                    add(kind, name, group, name)
                    words(e["aliases"]).forEach { add(kind, name, group, it) }
                }
            }
        }
        named(j["places"], Kind.PLACE)
        named(j["items"], Kind.ITEM)
        words((j["people"] as? Map<String, Any?>)?.get("words")).forEach { add(Kind.PERSON, KaiRelations.shown(it) ?: it, "people", it) }
        words((j["time_words"] as? Map<String, Any?>)?.get("words")).forEach { add(Kind.TIME, timeName(it), "time", it) }
        words(j["filler_words_to_ignore"]).forEach { add(Kind.FILLER, it, "filler", it) }
    }

    /** The time a time word stands for ("nethu" → "yesterday"), for the list's time filter. */
    private fun timeName(w: String): String = when (w.lowercase(Locale.ROOT)) {
        "innaiku", "today", "இன்னைக்கு" -> "today"
        "nethu", "netthu", "yesterday", "நேத்து" -> "yesterday"
        "poona vaaram", "last week", "போன வாரம்" -> "last week"
        "poona maasam", "last month", "போன மாசம்" -> "last month"
        else -> w
    }

    /** A small reader for the owner's word list (objects, arrays, strings, numbers, true / false / null). */
    private class Json(private val s: String) {
        private var i = 0
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s.getOrNull(i)) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++
            while (true) {
                ws(); if (s[i] == '}') { i++; return m }
                val k = str(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i] == ',') i++
            }
        }
        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++
            while (true) {
                ws(); if (s[i] == ']') { i++; return l }
                l += value(); ws()
                if (s[i] == ',') i++
            }
        }
        private fun str(): String {
            val b = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val c = s[i]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> b.append(c)
                    }
                } else b.append(s[i])
                i++
            }
            i++
            return b.toString()
        }
        private fun num(): Any? {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDoubleOrNull()
        }
    }
}
