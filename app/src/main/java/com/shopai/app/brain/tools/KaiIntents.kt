package com.shopai.app.brain.tools

import com.shopai.app.brain.chat.KaiSmallTalk
import java.time.LocalDateTime

/** What the owner wants, in Kai's priority order (an explicit button tap is handled before any words). */
enum class KaiIntentKind {
    REMINDER,
    STOCK_IN,
    STOCK_OUT,
    OPEN_BILL_SCANNER,
    CALL,
    FINANCIAL,
    BUSINESS_QUERY,
    CALCULATOR,
    CONVERSATION,
    UNKNOWN,
}

/**
 * One brain for voice and text: the same words — typed Tanglish, English,
 * spoken Tamil script, or mixed — reach the same intent. The order is the
 * priority: reminder → stock in → stock out → bill scanner → call →
 * financial transaction → business question → calculator → conversation →
 * unknown (Kai asks / learns).
 */
object KaiIntents {
    // Action-log intent names.
    const val STOCK_IN = "STOCK_IN"
    const val STOCK_OUT = "STOCK_OUT"
    const val STOCK_IN_CAMERA = "STOCK_IN_CAMERA"
    const val CREATE_PRODUCT = "CREATE_PRODUCT"
    const val OPEN_BILL_SCANNER = "OPEN_BILL_SCANNER"
    const val CREATE_REMINDER = "CREATE_REMINDER"
    const val CALL_CONTACT = "CALL_CONTACT"
    const val LEARN_SLANG = "LEARN_SLANG"
    const val SMALL_TALK = "SMALL_TALK"

    /** "bill scan pannu", "bill ah scan pannu", "indha bill add pannu", "purchase bill scan pannu", "bill photo edu", "bill camera open pannu". */
    private val billWords = Regex("""(?i)(?<![\p{L}])(bill|bills|invoice|receipt)(?![\p{L}])|பில்|ரசீது""")
    private val billDo = Regex(
        """(?i)(?<![\p{L}])(scan|add|enter|podu|potu|poodu|eduthu|edu|edunga|photo|pic|picture|camera|cam|open|upload|serthu|sethu|save|click)(?![\p{L}])|சேர்|ஸ்கேன்""",
    )

    fun isBillScan(text: String): Boolean {
        val t = KaiSpokenWords.normalize(text)
        if (!billWords.containsMatchIn(t) || !billDo.containsMatchIn(t)) return false
        // "bill evlo?", "bill-ku remind pannu" are not the scanner.
        return !Regex("""(?i)(?<![\p{L}])(evlo|evvalavu|how much|remind|reminder|pending|baaki|due)(?![\p{L}])""").containsMatchIn(t)
    }

    /** The intent of [raw] (after the owner's private words were applied). */
    fun classify(raw: String, now: LocalDateTime, people: List<String>, products: List<ProductRef>): KaiIntentKind {
        val text = KaiSpokenWords.normalize(raw.trim())
        if (text.isEmpty()) return KaiIntentKind.UNKNOWN
        if (KaiReminderUnderstanding.understand(text, now, people) != null) return KaiIntentKind.REMINDER
        KaiStock.understand(text, products)?.let { req ->
            if (req.product != null || KaiCommands.personIn(text, people) == null) return if (req.incoming) KaiIntentKind.STOCK_IN else KaiIntentKind.STOCK_OUT
        }
        if (isBillScan(text)) return KaiIntentKind.OPEN_BILL_SCANNER
        return when (KaiCommands.route(text, now, people)) {
            is KaiCommand.Call -> KaiIntentKind.CALL
            is KaiCommand.Payment -> KaiIntentKind.FINANCIAL
            is KaiCommand.Calculate -> KaiIntentKind.CALCULATOR
            is KaiCommand.Reminder -> KaiIntentKind.REMINDER
            is KaiCommand.ScanBill -> KaiIntentKind.OPEN_BILL_SCANNER
            is KaiCommand.Stock, KaiCommand.LowStock, is KaiCommand.MoneyBalance, KaiCommand.TopProducts -> KaiIntentKind.BUSINESS_QUERY
            KaiCommand.Question -> when {
                KaiSmallTalk.kindOf(text) != null -> KaiIntentKind.CONVERSATION
                else -> KaiIntentKind.BUSINESS_QUERY
            }
        }
    }

    /** Intents Kai Chat handles for the voice screen too (one conversation for voice and text). */
    fun handledByKai(kind: KaiIntentKind) = kind in setOf(
        KaiIntentKind.REMINDER, KaiIntentKind.STOCK_IN, KaiIntentKind.STOCK_OUT, KaiIntentKind.OPEN_BILL_SCANNER, KaiIntentKind.CONVERSATION,
    )
}
