package com.shopai.app.brain.tools

import java.math.BigDecimal
import java.util.Locale

/**
 * Money that came in by UPI, read from a payment app's notification on the owner's phone (Kai வசூல் — owner, 23 Oct 2026:
 * "₹1 GPay pannina udane Kai vandhu yaar kanakku-nu kekkanum"). [sender]: the payer's name as the app showed it, cleaned —
 * null when the app showed only a UPI id or a number. [key]: the same payment seen twice (two apps, an updated notification)
 * has the same key within [KaiUpiSeen.WINDOW_MS].
 */
data class UpiCredit(
    val amount: BigDecimal,
    val sender: String?,
    val app: String,
    val at: Long,
) {
    /** "GPay", "PhonePe", "Paytm", "BHIM": said in Kai's line and used as the payment mode word. */
    val modeWord: String get() = when (app) {
        "GPay", "GPay Business" -> "gpay"
        "PhonePe" -> "phonepe"
        "Paytm", "Paytm Business" -> "paytm"
        else -> "upi"
    }
}

/**
 * Reads a payment app's notification into a [UpiCredit] — or null. Rules on the phone, no AI, nothing sent anywhere (Play's
 * user-data rules: only the payment apps below are read; anything else is dropped unread by the listener).
 *
 * It is careful the way Kai is: **when unsure it returns null** (never a wrong entry) — money the owner sent, a collect
 * request, cashback / rewards / offers, a failed / pending / reversed payment, a refund, a bill or recharge, two different
 * amounts, or no money-in word at all are all ignored.
 *
 * The notification shapes here are the common English ones; the owner's own phone's notifications (screenshots) are to be
 * added to `KaiUpiNoticeTest` before release — a shape not read is simply ignored, never misread.
 */
object KaiUpiNotice {

    /** Payment apps read (package → the name Kai says). Bank apps and other wallets are not read yet. */
    val apps: Map<String, String> = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "GPay",
        "com.google.android.apps.nbu.paisa.merchant" to "GPay Business",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "com.paytm.business" to "Paytm Business",
        "in.org.npci.upiapp" to "BHIM",
    )

    fun isPaymentApp(packageName: String?): Boolean = packageName != null && packageName in apps

    /** [title] / [text] / [bigText]: the notification's own lines (EXTRA_TITLE, EXTRA_TEXT, EXTRA_BIG_TEXT). */
    fun read(packageName: String?, title: String?, text: String?, bigText: String? = null, postedAt: Long = 0L): UpiCredit? {
        val app = apps[packageName ?: return null] ?: return null
        // The expanded text holds the short one: the expanded one alone; else both.
        val body = (if (bigText != null && text != null && bigText.contains(text.trim())) listOf(bigText) else listOfNotNull(text, bigText))
            .joinToString("\n").ifBlank { return null }
        val head = title.orEmpty()
        val all = normalise("$head\n$body")
        val lower = all.lowercase(Locale.ROOT)
        if (notMoneyIn.containsMatchIn(lower)) return null
        if (!moneyIn.containsMatchIn(lower)) return null
        val amount = amountIn(all) ?: return null
        val sender = senderIn(normalise(head), normalise(body))
        return UpiCredit(amount, sender, app, postedAt)
    }

    // ------------------------------------------------------------ words

    /** Money in: "received", "credited", "paid you", "sent you", "has sent". */
    private val moneyIn = Regex(
        """(?<![a-z])(received|credited|paid\s+you|sent\s+you|has\s+sent|have\s+sent\s+you|payment\s+of\s+[^\n]{0,20}?\s+from)(?![a-z])""",
    )

    /**
     * Never money in from a customer: the owner paying ("paid to", "sent to", "you paid"), a debit, a collect request, a
     * failed / pending / reversed / declined payment, a refund, cashback / reward / offer / scratch card, a bill, recharge,
     * autopay / mandate, a reminder or a split.
     */
    private val notMoneyIn = Regex(
        """(?<![a-z])(paid\s+to|sent\s+to|you\s+paid|you\s+sent|payment\s+to|debited|debit(?!\s+card)|withdrawn|""" +
            """request(?:ed|s|ing)?|collect|failed|failure|declined|pending|reversed|reversal|unsuccessful|refund\w*|""" +
            """cashback|cash\s+back|reward\w*|scratch\s*card|offer\w*|coupon|voucher|win\b|won\b|""" +
            """bill(?:s|ed|ing)?|recharge\w*|autopay|auto\s*pay|mandate|e-?mandate|reminder|due\s+on|overdue|split)(?![a-z])""",
    )

    // ------------------------------------------------------------ amount

    /** "₹1", "₹ 2,500.50", "Rs.500", "Rs 1,00,000", "INR 75" — exactly one amount (a balance after it is not counted). */
    private val amountRe = Regex("""(?i)(?:₹|\brs\.?|\binr\b)\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""")
    private val balanceBefore = Regex("""(?i)(bal(?:ance)?|avl|available|a/c\s+bal)\W{0,3}(?:is|:)?\W{0,3}$""")

    fun amountIn(text: String): BigDecimal? {
        val found = amountRe.findAll(text).filterNot { m ->
            // "Avl bal ₹10,000": the account's balance, not the payment.
            balanceBefore.containsMatchIn(text.substring(maxOf(0, m.range.first - 18), m.range.first))
        }.map { BigDecimal(it.groupValues[1].replace(",", "")).stripTrailingZeros() }
            .filter { it.signum() > 0 }
            .distinct()
            .toList()
        return found.singleOrNull()?.let { if (it.scale() < 0) it.setScale(0) else it }
    }

    // ------------------------------------------------------------ sender

    private const val NAME = """[A-Za-z][A-Za-z.' &-]{0,60}?"""
    private val fromName = Regex("""(?i)(?<![a-z])from\s+(?:(?:mr|mrs|ms|miss|dr|m/s)\.?\s+)?($NAME)\s*(?=$|[\n.,!:;(]|\s(?:on|via|to|at|in|using|through|for|into|upi|ref|txn|-)\b|\s+₹|\s+rs\b)""")
    private val namePaid = Regex("""(?i)^\s*($NAME)\s+(?:has\s+)?(?:paid|sent)\s+you\b""", RegexOption.MULTILINE)
    /** A title that is the app or a heading, not a person. */
    private val notAName = Regex(
        """(?i)^(google\s*pay|gpay|g\s*pay|phonepe|phone\s*pe|paytm|bhim|upi|amazon\s*pay|whatsapp|cred|mobikwik|freecharge|navi|super\.?money|pop\s*upi|payment\w*|money|received|credited|you|your|""" +
            """transaction\w*|alert|success\w*|business|merchant|bank|account|a/c|wallet|new\s+payment|amount)\b""",
    )
    private val titles = Regex("""(?i)^(mr|mrs|ms|miss|dr|m/s)\.?\s+""")

    fun senderIn(title: String, body: String): String? {
        // Each place a name can be, in order; the first that is a person's name wins. GPay (owner's phone, 23 Oct 2026):
        // title "Prabhakaran paid you ₹1.00", text "Payment from PhonePe" — "PhonePe" is the payer's app, not the payer.
        val candidates = sequenceOf(
            { namePaid.find(title)?.groupValues?.get(1) },
            { namePaid.find(body)?.groupValues?.get(1) },
            { fromName.find(body)?.groupValues?.get(1) },
            { fromName.find(title)?.groupValues?.get(1) },
            // Title "Kumar", text "Paid you ₹500": the title is the payer.
            { title.trim().takeIf { Regex("""(?i)^\s*(paid|sent)\s+you\b""").containsMatchIn(body) } },
        )
        return candidates.mapNotNull { it()?.let(::cleanName) }.firstOrNull()
    }

    /** "MR KUMAR  S" → "Kumar S"; a UPI id, a number or an app / heading word → null. */
    fun cleanName(raw: String): String? {
        var n = raw.trim().trimEnd('.', ',', '-', ' ').replace(Regex("""\s+"""), " ")
        if (n.contains('@') || n.any(Char::isDigit)) return null
        n = titles.replace(n, "")
        if (n.length < 2 || n.none(Char::isLetter) || notAName.containsMatchIn(n)) return null
        val words = n.split(' ').filter { it.isNotBlank() }
        if (words.size > 5) return null
        // ALL CAPS (as banks write names) → Title Case; a name written in mixed case stays as written.
        return if (n == n.uppercase(Locale.ROOT)) words.joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }
        else words.joinToString(" ")
    }

    /** No-break spaces, "Rs." spacing, curly quotes: one plain form to read. */
    private fun normalise(s: String): String = s.replace(' ', ' ').replace(' ', ' ').replace('’', '\'')
        .replace(Regex("""[ \t]+"""), " ").trim()
}

/**
 * The same payment seen twice — the app updates its notification, or two apps on the phone both say it — is one payment.
 * Kept on the phone for [WINDOW_MS]. Two real payments of the same amount from the same person minutes apart are both kept.
 */
class KaiUpiSeen(private val windowMs: Long = WINDOW_MS) {
    private val recent = ArrayDeque<UpiCredit>()

    /** True the first time; false for a repeat of a payment already taken. */
    @Synchronized
    fun firstTime(c: UpiCredit): Boolean {
        while (recent.isNotEmpty() && c.at - recent.first().at > windowMs) recent.removeFirst()
        val again = recent.any { r ->
            r.amount.compareTo(c.amount) == 0 && kotlin.math.abs(c.at - r.at) <= windowMs &&
                (r.sender == null || c.sender == null || r.sender.equals(c.sender, ignoreCase = true))
        }
        if (!again) recent.addLast(c)
        return !again
    }

    companion object {
        /** An updated notification or a second app comes within seconds; a new payment of the same amount rarely does. */
        const val WINDOW_MS = 90_000L
    }
}

/**
 * Which UPI money Kai speaks up for (owner, 23 Oct 2026: "customer vara personal payment notification-um add panna customer
 * irritate aaga maattaangalaa"). A friend's or family's money is the owner's own — Kai stays quiet:
 * - [Decision.CUSTOMER]: the payer is (or is like) a customer of the books → Kai's alert.
 * - [Decision.UNKNOWN]: not a customer, and the owner asked Kai to ask about everyone (Settings) → Kai's alert.
 * - [Decision.QUIET]: not a customer (or no name shown) → no alert at all.
 * - [Decision.PERSONAL]: the owner said "this one is personal" before → nothing, ever.
 */
object KaiUpiFilter {
    enum class Decision { CUSTOMER, UNKNOWN, QUIET, PERSONAL }

    /** A payer's name as kept in the owner's personal list: the same however the app spells its case or spaces. */
    fun key(name: String): String = name.trim().lowercase(java.util.Locale.ROOT).replace(Regex("""\s+"""), " ")

    fun decide(c: UpiCredit, customers: List<String>, personal: Set<String>, askUnknown: Boolean): Decision {
        val sender = c.sender
        if (sender != null && key(sender) in personal) return Decision.PERSONAL
        if (sender != null && customers.any { likeCustomer(sender, it) }) return Decision.CUSTOMER
        return if (askUnknown) Decision.UNKNOWN else Decision.QUIET
    }

    /**
     * The payer is this customer, or may be: the same name (or in Tamil letters), or the same first name ("Kumar Selvam" pays,
     * the books have "Kumar"). Kai then asks which — a may-be is asked, never drafted (KaiVasool).
     */
    fun likeCustomer(sender: String, customer: String): Boolean {
        if (sender.equals(customer, ignoreCase = true) || com.shopai.app.util.NameSound.same(sender, customer)) return true
        val s = sender.trim().split(Regex("""\s+""")).firstOrNull()?.takeIf { it.length >= 3 } ?: return false
        val first = customer.trim().split(Regex("""\s+""")).firstOrNull() ?: return false
        return s.equals(first, ignoreCase = true) || com.shopai.app.util.NameSound.same(s, first)
    }
}
