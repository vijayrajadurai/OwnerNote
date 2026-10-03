package com.shopai.app.brain.memory

import com.shopai.app.brain.KaiLang

/**
 * Kai's PRIVATE business memory — how ONE shop's owner speaks.
 *
 * Two layers, never mixed:
 *  - GLOBAL KAI CORE (KaiCommands, KaiChatUnderstanding, KaiCalculator, the
 *    books engine…): general Tamil / Tanglish / English and business rules.
 *  - PRIVATE OWNER MEMORY (this package): one owner's own slang,
 *    nicknames and phrases in their business. Every record carries the
 *    business id and the owner id; an owner only ever loads their own
 *    records — another owner (or another business) never sees them.
 *
 * Memory only helps Kai understand LANGUAGE. It turns the owner's own words
 * into words the global core already understands; it never posts, never
 * changes a rule, and a financial action still goes draft → owner confirm →
 * existing engine.
 */

/**
 * WORD: the owner's own spelling / word for an everyday word ("ramba" = "romba") — rewritten before Kai reads the message.
 * UNIT_ALIAS: the shop's word for a unit ("potti" = box). REMINDER_TERM: a time phrase ("konjam nerathula" = 10 minutes).
 * CORRECTION: words the owner said are NOT an action ("bill kuduthaan" = bill handed over, not a payment).
 */
enum class MemoryType { SLANG, PRODUCT_ALIAS, CUSTOMER_ALIAS, SUPPLIER_ALIAS, ACTION_ALIAS, ABBREVIATION, PREFERENCE, WORD, UNIT_ALIAS, REMINDER_TERM, CORRECTION }

/** Whether a memory is used. DELETED records are kept only as a tombstone (never used, never shown). */
enum class MemoryStatus { ACTIVE, DISABLED, DELETED }

enum class MemorySource { OWNER_CONFIRMED, OWNER_CREATED, SYSTEM }

enum class MemoryConfidence { LOW, MEDIUM, HIGH }

/**
 * How far Kai got with a phrase: it is only used once the owner confirmed it.
 * UNKNOWN → OBSERVED → SUGGESTED (= learned, pending the owner's confirmation) → OWNER_CONFIRMED;
 * CORRECTED = confirmed again with a new meaning (the old one is no longer used); DISABLED.
 */
enum class LearningState { UNKNOWN, OBSERVED, SUGGESTED, OWNER_CONFIRMED, DISABLED, CORRECTED }

/**
 * What an action phrase means, in words the global core understands
 * ([canonical]) — the private phrase is replaced by these, then the normal
 * Kai pipeline runs (draft, confirmation, engine) exactly as for any owner.
 */
enum class KaiMeaning(val canonical: String, val ta: String, val tl: String, val en: String, val financial: Boolean) {
    PAYMENT_OUT("kuduthen", "பணம் கொடுத்தது (Payment Out)", "Payment Out", "Payment Out", true),
    PAYMENT_IN("vanginen", "பணம் வந்தது (Payment In)", "Payment In", "Payment In", true),
    STOCK_IN("stock in", "ஸ்டாக் உள்ளே (Stock In)", "Stock In", "Stock In", true),
    STOCK_OUT("stock out", "ஸ்டாக் வெளியே (Stock Out)", "Stock Out", "Stock Out", true),
    BUSINESS_SUMMARY("business summary sollu", "வியாபார சுருக்கம்", "Business summary", "Business summary", false),
    RECEIVABLE_SUMMARY("total pending evlo", "வர வேண்டிய பாக்கி", "Receivable summary", "Receivable summary", false),
    PAYABLE_SUMMARY("total evlo kudukkanum", "கொடுக்க வேண்டியது", "Payable summary", "Payable summary", false),
    LOW_STOCK("low stock enna", "குறைந்த ஸ்டாக்", "Low stock", "Low stock", false),
    CASH_BALANCE("cash evlo irukku", "கையில் பணம்", "Cash balance", "Cash balance", false),
    ;

    fun label(lang: KaiLang) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }
}

/**
 * One learned piece of a business's language. [businessId] is mandatory —
 * there is no memory without an owner business.
 */
data class KaiMemory(
    val id: String,
    val businessId: String,
    val ownerId: String?,
    val memoryType: MemoryType,
    /** As the owner said it ("thooki kudu"). */
    val triggerPhrase: String,
    /** Lower-case, single-spaced, punctuation-free ("thooki kudu"). */
    val normalizedPhrase: String,
    /** KaiMeaning name for action phrases; "PRODUCT" / "CUSTOMER" / "SUPPLIER" for nicknames; the expansion for abbreviations. */
    val meaningType: String,
    /** What it means, for display ("Payment Out", "Colgate 200g"). */
    val meaningValue: String,
    /** The product / customer / supplier id for nicknames — never just a name. */
    val referenceEntityId: String? = null,
    val confidence: MemoryConfidence = MemoryConfidence.HIGH,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val source: MemorySource,
    val learningState: LearningState = LearningState.OWNER_CONFIRMED,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val lastUsedAt: Long? = null,
    val usageCount: Int = 0,
    /** Other spellings the owner approved for the same meaning ("thooki kudunga"). */
    val variants: List<String> = emptyList(),
    /** Where Kai used it ("Colgate 2 potti vandhudhu") — a few, for the owner to recognise it. */
    val examples: List<String> = emptyList(),
    /** The teaching sentence (only that one — no other conversation is kept). */
    val sourceText: String? = null,
    /** The meaning it had before the owner corrected it (history; never used). */
    val correctedFrom: String? = null,
) {
    /** Used automatically only when the owner confirmed / created it and it is switched on. */
    val usable: Boolean
        get() = status == MemoryStatus.ACTIVE &&
            (learningState == LearningState.OWNER_CONFIRMED || learningState == LearningState.CORRECTED) &&
            (source == MemorySource.OWNER_CONFIRMED || source == MemorySource.OWNER_CREATED)

    /** The kind of word, as the owner would think of it. */
    val category: String
        get() = when (memoryType) {
            MemoryType.WORD, MemoryType.SLANG, MemoryType.ABBREVIATION -> if (normalizedPhrase.contains(' ')) "PHRASE" else "WORD"
            MemoryType.UNIT_ALIAS -> "UNIT_ALIAS"
            MemoryType.REMINDER_TERM -> "REMINDER_TERM"
            MemoryType.PRODUCT_ALIAS -> "PRODUCT_ALIAS"
            MemoryType.CUSTOMER_ALIAS -> "CUSTOMER_ALIAS"
            MemoryType.SUPPLIER_ALIAS -> "SUPPLIER_ALIAS"
            MemoryType.CORRECTION -> "BUSINESS_TERM"
            MemoryType.PREFERENCE -> "PREFERENCE"
            MemoryType.ACTION_ALIAS -> when (meaning) {
                KaiMeaning.PAYMENT_IN, KaiMeaning.PAYMENT_OUT -> "PAYMENT_TERM"
                KaiMeaning.STOCK_IN, KaiMeaning.STOCK_OUT -> "STOCK_TERM"
                else -> "BUSINESS_TERM"
            }
        }

    val meaning: KaiMeaning? get() = KaiMeaning.entries.firstOrNull { it.name == meaningType }
    val phrases: List<String> get() = listOf(normalizedPhrase) + variants
}

/** A phrase Kai noticed but has not been told about. Never used to act. */
data class KaiObservation(
    val businessId: String,
    val normalizedPhrase: String,
    val count: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val state: LearningState = LearningState.OBSERVED,
    /** Kai's best guess (only to ask a better question). */
    val guess: String? = null,
    val confidence: MemoryConfidence = MemoryConfidence.LOW,
    /** The owner said "no" to the guess — Kai does not ask the same guess again. */
    val rejected: List<String> = emptyList(),
)

/** Everything one owner taught Kai in one business. Stored and loaded only under that business and owner. */
data class KaiMemoryBook(
    val businessId: String,
    val ownerId: String? = null,
    val memories: List<KaiMemory> = emptyList(),
    val observations: List<KaiObservation> = emptyList(),
)

/** Names Kai can resolve nicknames to (the business's own records). */
data class KnownEntity(val id: String, val name: String, val type: MemoryType)
