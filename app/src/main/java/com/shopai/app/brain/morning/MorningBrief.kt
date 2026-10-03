package com.shopai.app.brain.morning

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Who asked for the brief — the owner (Kai Chat / voice) or a scheduled morning notification. Same engine, same data. */
enum class MorningTrigger { MANUAL, SCHEDULED }

/** Sections of the brief, in the order Kai reads them (the priority order of the work). */
enum class MorningSection(val icon: String, val ta: String, val tl: String, val en: String) {
    COLLECTIONS("🔴", "வசூல்", "Collections", "Collections"),
    PAYMENTS("💰", "கொடுக்க வேண்டியது", "Payments", "Payments"),
    STOCK("📦", "ஸ்டாக்", "Stock", "Stock"),
    EXPIRY("⌛", "காலாவதி", "Expiry", "Expiry"),
    REMINDERS("⏰", "நினைவூட்டல்கள்", "Reminders", "Reminders"),
    DRAFTS("📝", "உறுதி செய்ய வேண்டியவை", "Drafts to confirm", "Drafts to confirm"),
    ;

    fun label(lang: KaiLang) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }

    companion object {
        fun of(type: MorningTaskType) = when (type) {
            MorningTaskType.COLLECT_PAYMENT, MorningTaskType.PAYMENT_FOLLOWUP -> COLLECTIONS
            MorningTaskType.SUPPLIER_PAYMENT -> PAYMENTS
            MorningTaskType.LOW_STOCK -> STOCK
            MorningTaskType.EXPIRY -> EXPIRY
            MorningTaskType.REMINDER -> REMINDERS
            MorningTaskType.PENDING_DRAFT -> DRAFTS
        }
    }
}

/** One line of the brief, tied to the task (and so to the record) it came from. */
data class MorningBriefItem(val task: MorningTask, val text: String)

/**
 * The morning brief: today's open Morning Work tasks, grouped and in priority
 * order, every figure copied from the task (which the analyzer copied from the
 * ledger / stock movements / reminder engine). Only sections with items exist.
 */
data class MorningBrief(
    val businessId: String,
    val lang: KaiLang,
    val trigger: MorningTrigger,
    val greeting: String,
    val sections: List<Pair<MorningSection, List<MorningBriefItem>>>,
    /** The first thing to do (the top task), and how Kai says it. */
    val first: MorningTask?,
    val firstText: String?,
    val openCount: Int,
    val offline: Boolean,
    /** The open tasks in the order Kai offers them ("first … pannalama?", then Skip → the next). */
    val queue: List<MorningTask> = emptyList(),
) {
    val empty: Boolean get() = first == null

    /** The brief as Kai shows it in chat (and says it, when the owner spoke). */
    val text: String
        get() = buildString {
            append(greeting)
            if (empty) return@buildString
            append("\n").append(briefPick(lang, ta = "இன்னைக்கு முக்கியம்:", tl = "Innaiku important:", en = "Important today:"))
            for ((section, items) in sections) {
                append("\n\n").append(section.icon).append(' ').append(section.label(lang))
                items.forEach { append("\n").append(it.text) }
            }
            firstText?.let {
                append("\n\n").append(briefPick(lang, ta = "முதல் வேலை:", tl = "First priority:", en = "First priority:")).append("\n").append(it).append('.')
            }
            if (offline) append("\n\n").append(briefPick(lang, ta = "(கடைசியா sync ஆன தகவல்)", tl = "(Last sync aana information)", en = "(Last synced information)"))
        }
}

/**
 * Builds the brief from Morning Work's plan — one formatter for Kai Chat, the
 * voice screen and a future scheduled morning notification. It reads nothing
 * itself and changes nothing: no payment, sale, purchase, stock or reminder.
 */
object MorningBriefs {
    /** Items per section — the brief stays short; the Morning Work screen has the full list. */
    const val PER_SECTION = 3
    private const val QUEUE = 10

    fun build(plan: MorningPlan, now: ZonedDateTime, lang: KaiLang, trigger: MorningTrigger = MorningTrigger.MANUAL): MorningBrief {
        val open = MorningAnalyzer.sort(plan.openTasks)
        val today = now.toLocalDate()
        val sections = MorningSection.entries.mapNotNull { section ->
            val items = open.filter { MorningSection.of(it.taskType) == section }.take(PER_SECTION).map { MorningBriefItem(it, line(it, today, now, lang)) }
            items.takeIf { it.isNotEmpty() }?.let { section to it }
        }
        val first = open.firstOrNull()
        val greet = greetWord(now.hour, lang)
        val greeting = if (first == null) briefPick(lang,
            ta = "$greet ஓனர் ☀️ இன்னைக்கு முக்கியமான வேலை எதுவும் உங்க records-ல இல்ல. எல்லாம் clear!",
            tl = "$greet Owner ☀️ Innaiku important work edhuvum unga records-la illa. Ellam clear!",
            en = "$greet Owner ☀️ Nothing important in your records for today. All clear!")
        else briefPick(lang, ta = "$greet ஓனர் ☀️", tl = "$greet Owner ☀️", en = "$greet Owner ☀️")
        return MorningBrief(plan.businessId, lang, trigger, greeting, sections, first, first?.let { firstText(it, lang) }, open.size, plan.offline, open.take(QUEUE))
    }

    /** A scheduled morning notification: (title, text) from the same brief. */
    fun notification(brief: MorningBrief): Pair<String, String> {
        val title = briefPick(brief.lang, ta = "Kai — காலை வேலை", tl = "Kai — Morning Work", en = "Kai — Morning Work")
        val body = if (brief.empty) brief.greeting else buildString {
            append(briefPick(brief.lang, ta = "இன்னைக்கு ${brief.openCount} முக்கிய வேலை.", tl = "Innaiku ${brief.openCount} important work.", en = "${brief.openCount} important tasks today."))
            brief.firstText?.let { append(' ').append(briefPick(brief.lang, ta = "முதல்: ", tl = "First: ", en = "First: ")).append(it).append('.') }
        }
        return title to body
    }

    /** "Kumar — ₹8,000 overdue", "Colgate — 5 PCS left (reorder 10)", "10:00 AM — Kumar call". */
    fun line(t: MorningTask, today: LocalDate, now: ZonedDateTime, lang: KaiLang): String {
        val f = t.facts
        val amount = f.amount?.let { KaiFormat.rupees(it) }
        fun due(): String = when (t.reason) {
            PriorityReason.OVERDUE -> briefPick(lang, ta = "லேட்", tl = "overdue", en = "overdue")
            PriorityReason.DUE_TODAY -> briefPick(lang, ta = "இன்னைக்கு due", tl = "due today", en = "due today")
            PriorityReason.DUE_SOON -> t.dueDate?.let { briefPick(lang, ta = "${KaiFormat.date(it, lang, today)} due", tl = "due ${KaiFormat.date(it, lang, today)}", en = "due ${KaiFormat.date(it, lang, today)}") }
                ?: briefPick(lang, ta = "நிலுவை", tl = "pending", en = "pending")
            PriorityReason.PARTIALLY_PAID -> briefPick(lang, ta = "பாதி கட்டியது", tl = "partly paid", en = "partly paid")
            else -> briefPick(lang, ta = "நிலுவை", tl = "pending", en = "pending")
        }
        fun qty(v: Double?) = v?.let { java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() + (f.unit?.let { u -> " $u" } ?: "") }
        return when (t.taskType) {
            MorningTaskType.COLLECT_PAYMENT, MorningTaskType.PAYMENT_FOLLOWUP, MorningTaskType.SUPPLIER_PAYMENT ->
                "${t.title} — " + listOfNotNull(amount, due()).joinToString(" ")
            MorningTaskType.LOW_STOCK -> "${t.title} — " + when {
                t.reason == PriorityReason.OUT_OF_STOCK -> briefPick(lang, ta = "ஸ்டாக் இல்ல", tl = "out of stock", en = "out of stock")
                else -> {
                    val level = (f.reorderLevel ?: f.minimum)?.let { qty(it) }
                    val left = qty(f.stock)
                    briefPick(lang,
                        ta = "${left ?: ""} மட்டும்" + (level?.let { " (reorder $it)" } ?: ""),
                        tl = "${left ?: ""} dhaan irukku" + (level?.let { " (reorder level $it)" } ?: ""),
                        en = "${left ?: ""} left" + (level?.let { " (below reorder level $it)" } ?: "")).trim()
                }
            }
            MorningTaskType.EXPIRY -> "${t.title}" + (f.batchNo?.let { " ($it)" } ?: "") + " — " + when (t.reason) {
                PriorityReason.EXPIRED -> briefPick(lang, ta = "காலாவதி ஆகிடுச்சு", tl = "expired", en = "expired")
                else -> f.expiryDay?.let { d -> briefPick(lang, ta = "${KaiFormat.date(LocalDate.ofEpochDay(d), lang, today)} காலாவதி", tl = "expiry ${KaiFormat.date(LocalDate.ofEpochDay(d), lang, today)}", en = "expires ${KaiFormat.date(LocalDate.ofEpochDay(d), lang, today)}") }
                    ?: briefPick(lang, ta = "விரைவில் காலாவதி", tl = "expiry soon", en = "expiring soon")
            }
            MorningTaskType.REMINDER -> (f.reminderAtMillis?.let { clock(it, now) + " — " } ?: "") + t.title
            MorningTaskType.PENDING_DRAFT -> t.title
        }
    }

    /** "Kumar collection follow-up", "ABC Traders payment", "Colgate stock order". */
    fun firstText(t: MorningTask, lang: KaiLang): String = when (t.taskType) {
        MorningTaskType.COLLECT_PAYMENT, MorningTaskType.PAYMENT_FOLLOWUP -> briefPick(lang, ta = "${t.title} வசூல் follow-up", tl = "${t.title} collection follow-up", en = "${t.title} collection follow-up")
        MorningTaskType.SUPPLIER_PAYMENT -> briefPick(lang, ta = "${t.title} payment", tl = "${t.title} payment", en = "${t.title} payment")
        MorningTaskType.LOW_STOCK -> briefPick(lang, ta = "${t.title} ஸ்டாக் order", tl = "${t.title} stock order", en = "${t.title} stock order")
        MorningTaskType.EXPIRY -> briefPick(lang, ta = "${t.title} காலாவதி check", tl = "${t.title} expiry check", en = "${t.title} expiry check")
        MorningTaskType.REMINDER -> t.title
        MorningTaskType.PENDING_DRAFT -> briefPick(lang, ta = "${t.title} உறுதி செய்ய", tl = "${t.title} confirm panna", en = "Confirm ${t.title}")
    }

    private fun clock(millis: Long, now: ZonedDateTime) =
        Instant.ofEpochMilli(millis).atZone(now.zone).toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))

    private fun greetWord(hour: Int, lang: KaiLang) = when (hour) {
        in 4..11 -> briefPick(lang, ta = "குட் மார்னிங்", tl = "Good morning", en = "Good morning")
        in 12..15 -> briefPick(lang, ta = "வணக்கம்", tl = "Vanakkam", en = "Good afternoon")
        else -> briefPick(lang, ta = "வணக்கம்", tl = "Vanakkam", en = "Good evening")
    }
}

private fun briefPick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }
