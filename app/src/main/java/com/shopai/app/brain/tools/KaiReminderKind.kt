package com.shopai.app.brain.tools

/**
 * What a reminder is about — read from the reminder itself, never stored (no new field, no
 * migration): the one reminder engine stays as it is, and only Kai's wording and voice change.
 *
 *   CALL        call / message someone ("Kumar-ku call", "Amma-ku call")
 *   PAYMENT     pay / collect ("Kumar-ku payment", "current bill pay panna")
 *   FOLLOW_UP   "follow up", "check pannu"
 *   BUSINESS    the shop's work (stock, order, supplier, a customer / supplier from the books)
 *   PERSONAL    the owner's own life (son's school pickup, medicine, gym, gate lock, office documents)
 *   TASK        something to do, said with a verb ("car clean panna")
 *   GENERIC     anything else ("walking")
 */
enum class ReminderKind { BUSINESS, PERSONAL, CALL, PAYMENT, TASK, FOLLOW_UP, GENERIC }

object KaiReminderKind {

    private const val B = """(?<![\p{L}])"""
    private const val E = """(?![\p{L}])"""

    /** Family, home, health, school, personal errands — words, not phrases. */
    private val personalWords = Regex(
        """$B(paiyan|paiyana|paiyanai|paiyanuku|ponnu|ponna|ponnai|son|son-a|daughter|kids?|kozhandhai|kozhandhaiya|pasanga|""" +
            """school|college|tuition|amma|appa|wife|husband|mama|athai|thatha|paati|thangachi|family|""" +
            """medicine|marunthu|maathirai|tablet|tablets|doctor|hospital|clinic|gym|walking|walk|yoga|exercise|""" +
            """gate|veedu|veetu|veettu|home|house|car|bike|vandi|current|eb|gas|cylinder|temple|kovil|church|""" +
            """birthday|anniversary|function|marriage|kalyanam|office|documents?)$E""",
        RegexOption.IGNORE_CASE,
    )

    /** The shop's own work. */
    private val businessWords = Regex(
        """$B(stock|maal|order|orders|supplier|customer|vendor|distributor|delivery|invoice|gst|kadai|kadaiku|shop|sales|""" +
            """purchase|collection|vasool|udhaar|credit|ledger|godown|business)$E""",
        RegexOption.IGNORE_CASE,
    )

    private val followUpWords = Regex("""$B(follow\s*-?\s*up|followup)$E""", RegexOption.IGNORE_CASE)

    /** A task said with a verb ("car clean panna", "gym poganum", "documents eduthutu vara"). */
    private val verbEnding = Regex("""(?i)(panna|pannanum|pannu|poganum|poga|vara|varanum|kelambanum|kelamba|edukkanum|edukka|vaanganum|vaanga|kudukkanum|anuppanum|mudikkanum|check|lock|clean)$""")

    fun of(r: KaiReminder): ReminderKind = of(r.action, r.task, r.contactId)

    fun of(action: ReminderAction, task: String, contactId: String? = null): ReminderKind {
        val t = task.trim()
        return when {
            action == ReminderAction.CALL || action == ReminderAction.MESSAGE -> ReminderKind.CALL
            action == ReminderAction.PAYMENT || action == ReminderAction.COLLECTION -> ReminderKind.PAYMENT
            followUpWords.containsMatchIn(t) -> ReminderKind.FOLLOW_UP
            action == ReminderAction.STOCK || businessWords.containsMatchIn(t) || fromBooks(contactId) -> ReminderKind.BUSINESS
            personalWords.containsMatchIn(t) -> ReminderKind.PERSONAL
            verbEnding.containsMatchIn(t.trimEnd('.', ' ')) -> ReminderKind.TASK
            else -> ReminderKind.GENERIC
        }
    }

    /**
     * The owner's own life, not the shop's: a personal reminder never becomes a customer, supplier,
     * transaction, stock or payment entry, and is never taught to Kai's shared memory.
     */
    fun personal(r: KaiReminder): Boolean = personal(r.action, r.task, r.person, r.contactId)

    fun personal(action: ReminderAction, task: String, person: String?, contactId: String? = null): Boolean = when {
        action == ReminderAction.STOCK || action == ReminderAction.COLLECTION -> false
        fromBooks(contactId) -> false
        businessWords.containsMatchIn(task) -> false
        personalWords.containsMatchIn(task) || (person != null && personalWords.containsMatchIn(person)) -> true
        // "Amma-ku call", "Wife-ku call": a phone contact (or nobody from the books) for a call is the owner's own.
        else -> (action == ReminderAction.CALL || action == ReminderAction.MESSAGE) && contactId?.startsWith("phone:") == true
    }

    /** A customer / supplier id from the books (a phone contact's id starts with "phone:"). */
    private fun fromBooks(contactId: String?) = contactId != null && !contactId.startsWith("phone:")
}
