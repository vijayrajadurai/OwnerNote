package com.shopai.app.brain.chat

import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.MemoryType
import java.math.BigDecimal
import java.util.Locale

/**
 * The shop's Menu (owner, 16 Oct 2026): the items the owner sells by the piece and their prices — "Cutting 100, Shaving 80,
 * Facial 110" — added by the owner, because every shop's prices are its own. Kai never puts an item or a price in it by
 * itself: an item comes in only when the owner adds it on the Menu screen, or says it in chat and confirms
 * ("menu: cutting 100, shaving 80, facial 110"). Kept in the owner's private memory of this business, the same place the
 * Daily Sales Note reads a price from, so the screen and Kai Chat are one Menu.
 */
object KaiMenu {

    data class Item(val name: String, val price: BigDecimal)

    /** How a Menu item is kept with the owner's memory (a [MemoryType.PREFERENCE] of this kind, "Name|price"). */
    const val KIND = "SERVICE_PRICE"

    private fun key(name: String) = "service price ${name.trim().lowercase(Locale.ROOT)}"

    /** The Menu, by name. */
    fun items(memory: KaiPrivateMemory): List<Item> =
        memory.usable().filter { it.memoryType == MemoryType.PREFERENCE && it.meaningType == KIND }
            .mapNotNull { m -> m.meaningValue.split('|').takeIf { it.size == 2 }?.let { (n, p) -> p.toBigDecimalOrNull()?.let { Item(n, it) } } }
            .distinctBy { it.name.lowercase(Locale.ROOT) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    /** Adds [name] at [price], or changes its price — the owner's own action (the screen's Save, or Confirm in chat). */
    suspend fun save(memory: KaiPrivateMemory, name: String, price: BigDecimal) {
        val n = clean(name) ?: error("Item name venum")
        require(price.signum() > 0) { "Vilai 0-ku mela irukkanum" }
        memory.savePreference(key(n), KIND, "$n|${price.stripTrailingZeros().toPlainString()}")
    }

    /** Takes [name] off the Menu; false when it wasn't on it. */
    suspend fun remove(memory: KaiPrivateMemory, name: String): Boolean = memory.forgetPreferenceNamed(KIND, key(name)) != null

    /** An item's name as kept: trimmed, single spaces, first letter capital ("hair  cutting" → "Hair cutting"). */
    fun clean(name: String): String? = name.trim().replace(Regex("""\s+"""), " ").takeIf { it.length >= 2 && it.any(Char::isLetter) }
        ?.replaceFirstChar { it.titlecase(Locale.ROOT) }

    private val menuWord = Regex("""(?i)^\s*(?:en\s+|unga\s+|my\s+)?(?:menu|மெனு)(?:\s*-?\s*(?:la|le|ல|ல்|லே))?(?:\s+(?:add|serthudu|serthu|podu|போடு|சேர்த்துடு|சேர்))?\s*[:\-–]?\s*""")
    private val itemPrice = Regex("""([\p{L}\p{M}][\p{L}\p{M}'. ]*?)\s*[-–:=]?\s*(?:₹|rs\.?\s*|rupees?\s*)?(\d[\d,]*(?:\.\d+)?)\s*(?:rs|rupees?|ruba\p{L}*|ரூபாய்|ரூ)?(?![\d\p{L}\p{M}])""", RegexOption.IGNORE_CASE)
    private val trailing = Regex("""(?i)\s*(?:add\s*pannu|add\s*pannidu|serthudu|serthu\s*vai|podu|pottudu|save\s*pannu|menu\s*la\s*podu|ஆட்\s*பண்ணு|சேர்த்துடு|போடு)\s*[.!]*\s*$""")
    private val fillers = setOf("oru", "and", "um", "plus", "matrum", "also", "next", "appram", "aprom", "innoru", "ஒரு", "அப்புறம்")

    /**
     * The items and prices of a line that starts with "menu" — "menu: cutting 100, shaving 80, facial 110", "menu la cutting
     * 100 shaving 80 add pannu", "மெனு கட்டிங் 100 ஷேவிங் 80" — or null when the line is no Menu line or names no price.
     * [heard] is the line with spoken numbers as digits ("நூறு" → 100) when the mic wrote them.
     */
    fun parse(text: String, heard: String = text): List<Pair<String, BigDecimal>>? {
        val t = (if (menuWord.containsMatchIn(heard)) heard else text).trim()
        val m = menuWord.find(t)?.takeIf { it.range.first == 0 } ?: return null
        val rest = trailing.replace(t.substring(m.range.last + 1), "")
        val items = itemPrice.findAll(rest).mapNotNull { hit ->
            val words = hit.groupValues[1].trim().split(Regex("""\s+""")).filter { it.lowercase(Locale.ROOT) !in fillers && it.isNotBlank() }
            val name = clean(words.joinToString(" ")) ?: return@mapNotNull null
            val price = hit.groupValues[2].replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            name to price
        }.toList().distinctBy { it.first.lowercase(Locale.ROOT) }
        return items.takeIf { it.isNotEmpty() }
    }
}
