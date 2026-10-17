package com.shopai.app.brain.tools

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Stock that is about to expire (owner, 13 Oct 2026 — medical, grocery, bakery, cosmetics, seeds & pesticides, dairy & frozen,
 * pet food, paint & adhesives, catering, FMCG wholesale): what Kai answers when asked, and the daily expiry alert. Read-only, from
 * the books' batch records ([BatchFact]); nothing is guessed — a product with no batch / expiry recorded is simply not in it.
 */
object KaiExpiry {
    /** The daily alert covers what expires within this many days (and what has already expired). */
    const val ALERT_DAYS = 7L
    /** "Expiry aagapora stock sollu" with no time said: the next 30 days. */
    const val ASK_DAYS = 30L

    /**
     * Goods that can expire, in any shop (owner, 17 Oct 2026: "grocery-ku fruits and vegetables, vera enna enna product expiry
     * aaga vaaippu irukko adhellaam"): fruits & vegetables, dairy & beverages, bakery, meat & fish, grocery & staples, snacks,
     * personal care, cleaning liquids, baby care, medicines, seeds / fertiliser / pesticide, pooja (ghee, oil).
     */
    private val expires = setOf(ProductKind.VEGETABLE, ProductKind.BEVERAGE, ProductKind.BAKERY, ProductKind.MEAT, ProductKind.GROCERY,
        ProductKind.SNACKS, ProductKind.FMCG, ProductKind.HOMECARE, ProductKind.BABY, ProductKind.PHARMACY, ProductKind.AGRI, ProductKind.POOJA)
    /** Goods that never expire: garments, hardware, electricals, mobiles, stationery, footwear, kitchenware, toys, auto parts. */
    private val lasts = setOf(ProductKind.GARMENT, ProductKind.HARDWARE, ProductKind.ELECTRICAL, ProductKind.ELECTRONICS, ProductKind.STATIONERY,
        ProductKind.FOOTWEAR, ProductKind.KITCHENWARE, ProductKind.TOYS, ProductKind.AUTO)

    /** The kind of goods a product's category names ("Grocery", "FMCG", "Fruits & Vegetables", "Garments & Textiles"); null = not one of them. */
    fun kindOfCategory(category: String?): ProductKind? {
        val c = category?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ProductKind.entries.firstOrNull { k -> k.name.equals(c, ignoreCase = true) || k.category.equals(c, ignoreCase = true) ||
            k.category.startsWith(c, ignoreCase = true) || c.startsWith(k.category.substringBefore(' '), ignoreCase = true) }
            ?.takeIf { it != ProductKind.GENERAL }
    }

    /**
     * Whether a product can expire: by its category, else by its name ("Bread", "Milk", "Paracetamol"); a product neither tells
     * about ("General") — the owner said its expiry, so yes. Never for clothes, hardware, mobiles and the like.
     */
    /** The product is goods that expire, as its category or name says ("Bread", "Fruits & Vegetables") — for showing the expiry fields. */
    fun knownToExpire(category: String?, name: String): Boolean =
        (kindOfCategory(category) ?: KaiInventory.kindByName(name))?.let { it in expires } == true

    fun canExpire(category: String?, name: String): Boolean {
        val kind = kindOfCategory(category) ?: KaiInventory.kindByName(name) ?: return true
        return kind in expires || kind !in lasts
    }

    /** What the owner asked: a window ([days] ahead, [expiredOnly]), and FEFO (which batch to sell first). */
    data class Ask(val days: Long, val expiredOnly: Boolean, val fefo: Boolean)

    private fun has(t: String, r: String) = Regex("""(?i)(?<![\p{L}])(?:$r)(?![\p{L}])""").containsMatchIn(t)

    /** The window the owner said: "innaikku" 0, "intha vaaram" / "7 naal" 7, "intha maasam" 30, "rendu maasam" 60; "expired" only the expired. */
    fun ask(text: String): Ask {
        val t = text.lowercase(Locale.ROOT)
        val fefo = has(t, "fefo|first\\s+expiry\\s+first\\s+out") || Regex("""(?i)(endha|entha|yentha|which)\s+batch\s+(first|mudhal|modhal)""").containsMatchIn(t) ||
            Regex("""(?i)batch\s+(first|mudhal|modhal)\w*\s+(vikk|sell|kudu)""").containsMatchIn(t) || t.contains("முதல்ல விக்க")
        val expiredOnly = has(t, "expired|expire\\s+aach\\w*|expire\\s+aayiduch\\w*|kaalavadhi\\s+aach\\w*") || t.contains("காலாவதி ஆச்சு") || t.contains("எக்ஸ்பைர் ஆச்சு")
        val n = Regex("""(\d{1,3})\s*(?:naal|nal|days?|நாள்)""").find(t)?.groupValues?.get(1)?.toLongOrNull()
        val days = when {
            n != null -> n
            has(t, "innaikku|innaiku|inniku|today") || t.contains("இன்னைக்கு") -> 0L
            has(t, "naalaikku|naalaiku|nalaiku|tomorrow") || t.contains("நாளைக்கு") -> 1L
            has(t, "vaaram|vaaramm|week|varam") || t.contains("வாரம்") -> 7L
            has(t, "rendu\\s+maasam|2\\s+maasam|two\\s+months") -> 60L
            has(t, "maasam|masam|month") || t.contains("மாசம்") -> 30L
            else -> ASK_DAYS
        }
        return Ask(days, expiredOnly, fefo)
    }

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.ENGLISH -> en; else -> tl }

    /** "2 naal-la", "innaikku expire", "3 naal munnaadi expire aachu". */
    fun whenText(d: LocalDate, today: LocalDate, lang: KaiLang): String {
        val days = ChronoUnit.DAYS.between(today, d)
        return when {
            days < 0 -> pick(lang, ta = "${-days} நாள் முன்னாடி expire ஆச்சு", tl = "${-days} naal munnaadi expire aachu", en = "expired ${-days} days ago")
            days == 0L -> pick(lang, ta = "இன்னைக்கு expire", tl = "innaikku expire", en = "expires today")
            days == 1L -> pick(lang, ta = "நாளைக்கு expire", tl = "naalaikku expire", en = "expires tomorrow")
            else -> pick(lang, ta = "$days நாள்ல", tl = "$days naal-la", en = "in $days days")
        }
    }

    /** "Milk — Batch M2 — 20 packets — October 14th (1 naal-la)". */
    fun line(b: BatchFact, today: LocalDate, lang: KaiLang): String =
        "${b.product} — Batch ${b.batchNo} — ${KaiStock.shown(b.qty, b.unit)} — ${KaiFormat.date(b.expiry, lang, today)} (${whenText(b.expiry, today, lang)})"

    /** The batches in the window asked, soonest first (expired first), with stock left. */
    fun within(batches: List<BatchFact>, today: LocalDate, ask: Ask): List<BatchFact> =
        batches.filter { it.qty.signum() > 0 && (if (ask.expiredOnly) it.expiry.isBefore(today) else !it.expiry.isAfter(today.plusDays(ask.days))) }
            .sortedWith(compareBy({ it.expiry }, { it.product }))

    /** FEFO: for each product with batches, the batch to sell first — the one that expires first. */
    fun firstOut(batches: List<BatchFact>): List<BatchFact> =
        batches.filter { it.qty.signum() > 0 }.groupBy { it.product.lowercase(Locale.ROOT) }.values.map { bs -> bs.minBy { it.expiry } }.sortedBy { it.expiry }

    /** The daily alert: what expires within [ALERT_DAYS] days or already has; null when nothing does (no notification). */
    data class Alert(val title: String, val body: String, val count: Int)

    fun alert(batches: List<BatchFact>, today: LocalDate, lang: KaiLang): Alert? {
        val due = within(batches, today, Ask(ALERT_DAYS, expiredOnly = false, fefo = false))
        if (due.isEmpty()) return null
        val expired = due.count { it.expiry.isBefore(today) }
        val title = pick(lang,
            ta = "⚠️ Expiry alert: ${due.size} stock" + if (expired > 0) " ($expired expire ஆச்சு)" else "",
            tl = "⚠️ Expiry alert: ${due.size} stock" + if (expired > 0) " ($expired expire aachu)" else "",
            en = "⚠️ Expiry alert: ${due.size} batch${if (due.size == 1) "" else "es"}" + if (expired > 0) " ($expired expired)" else "")
        val shown = due.take(5).map { "${it.product} — ${KaiStock.shown(it.qty, it.unit)} — ${whenText(it.expiry, today, lang)}" }
        val more = (due.size - shown.size).takeIf { it > 0 }?.let { pick(lang, ta = "+ இன்னும் $it", tl = "+ innum $it", en = "+ $it more") }
        return Alert(title, (shown + listOfNotNull(more)).joinToString("\n"), due.size)
    }
}
