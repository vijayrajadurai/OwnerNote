package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiLang
import com.shopai.app.util.PartyStatement
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Kumar oda credit debit history PDF kudu", "Kumar statement WhatsApp-la anuppu", "குமார் கணக்கு பிடிஎஃப் வேணும்": the owner
 * wants a person's statement as a document (owner, 12 Oct 2026) — a PDF made on the phone and sent on WhatsApp by the owner.
 * Plain rules on the words Kai already reads (typed, or the mic's Tamil in Tanglish); nothing is written.
 */
object KaiStatement {

    /** The document itself: "pdf" (however it is typed or heard). */
    private val pdfWord = Regex("""(?<![\p{L}])(?:pdf|p\.?d\.?f|pdfa|pdf-a|pdf-ah|pdf-aa|pdfla|pdf-la|pdfil|pdf-il|pdfah)(?![\p{L}])""")
    /** What it is about: the statement, the ledger, the history, the account. */
    private val ledgerWord = Regex(
        """(?<![\p{L}])(?:statement|statements|ledger|passbook|history|kanakku|kanakka|kanakkai|varalaaru|transactions?|details|account|credit\s+debit)(?![\p{L}])""",
    )
    /** Sending or keeping it: WhatsApp, share, download, print, send. */
    private val shareWord = Regex(
        """(?<![\p{L}])(?:whatsapp|whatsapp-la|whatsappla|whats\s*app|share|download|print|export|send|forward|anuppu|anupu|anuppunga|anupunga|anuppi\s*vidu|anuppividu|anuppidu)(?![\p{L}])""",
    )
    /** "Idha WhatsApp-la anuppu" right after a person's balance: "this" — that person's statement. */
    private val thisWord = Regex("""(?<![\p{L}])(?:itha|idha|ithai|idhai|ithu|idhu|idhaiyum|ithaiyum|atha|adha|athai|adhai|this|it)(?![\p{L}])""")
    private val whatsappWord = Regex("""(?<![\p{L}])whats\s*app""")

    /** Money or a reminder said with it: "Kumar-ku 500 WhatsApp-la anuppu" is a payment, "Kumar-ku remind pannu" a reminder. */
    private val notStatement = Regex("""(?<![\p{L}])(?:remind|reminder|rs|rupees?|ruba|rubai|gpay|phonepe|paytm|upi|cash|panam|kaasu|call)(?![\p{L}])|₹""")
    private val number = Regex("""(?<![\p{L}\p{N}])(\d[\d,]*)(?![\p{N}])""")

    /** A word of the ask itself ("Statement", "PDF", "WhatsApp") — never a person's name. */
    fun isAskWord(word: String): Boolean = word.lowercase(Locale.ROOT).split(Regex("""\s+""")).any { w ->
        pdfWord.matches(w) || ledgerWord.matches(w) || shareWord.matches(w)
    }

    /** The owner asks for a person's statement as a document (not a payment, not a reminder). */
    fun asks(said: String): Boolean {
        val lower = said.lowercase(Locale.ROOT)
        if (notStatement.containsMatchIn(lower)) return false
        // An amount is money; a year or a day of the month ("2026", "1 to 30") is a period.
        if (number.findAll(lower).any { m -> m.groupValues[1].replace(",", "").toLongOrNull()?.let { it in 100..1899 || it > 2100 } == true }) return false
        return pdfWord.containsMatchIn(lower) || ledgerWord.containsMatchIn(lower) && shareWord.containsMatchIn(lower) || onlyByReference(lower) || asksInvoice(lower)
    }

    private val invoiceWord = Regex("""(?<![\p{L}])(?:invoice|invoices|bill|bills|purchase\s+bill)(?![\p{L}])""")
    private val getWord = Regex("""(?<![\p{L}])(?:kudu|kudunga|kodu|kodunga|kaattu|kaatu|venum|vendum|send|share|anuppu|anupu|anuppunga|show|thaa|tha|paakkanum|paaknum)(?![\p{L}])""")
    private val makeWord = Regex("""(?<![\p{L}])(?:podu|potu|podunga|podanum|create|generate|make|ready|ezhudhu|ezhuthu|pannu|pannunga|scan)(?![\p{L}])""")

    /**
     * "ABC Traders invoice kudu", "Kumar bill PDF anuppu" (owner, 20 Oct 2026: "supplier name mention panni invoice keta pdf-a
     * kudukanum"): that party's bills and entries as the statement PDF — asked for, never a bill to make ("bill podu" stays the
     * Billing screen's). Only with the party named in the line ([KaiAgent] checks the name).
     */
    fun asksInvoice(said: String): Boolean {
        val lower = said.lowercase(Locale.ROOT)
        return invoiceWord.containsMatchIn(lower) && (getWord.containsMatchIn(lower) || pdfWord.containsMatchIn(lower)) && !makeWord.containsMatchIn(lower)
    }

    /** "invoice kudu" with no PDF word: a statement only when a party is named in the line itself. */
    fun invoiceOnly(said: String): Boolean = asksInvoice(said) && !pdfWord.containsMatchIn(said.lowercase(Locale.ROOT)) &&
        !(ledgerWord.containsMatchIn(said.lowercase(Locale.ROOT)) && shareWord.containsMatchIn(said.lowercase(Locale.ROOT)))

    /** "Idha WhatsApp-la anuppu": a statement only because of "this" — it means one only right after a person was talked about. */
    fun onlyByReference(said: String): Boolean {
        val lower = said.lowercase(Locale.ROOT)
        return !pdfWord.containsMatchIn(lower) && !ledgerWord.containsMatchIn(lower) &&
            thisWord.containsMatchIn(lower) && whatsappWord.containsMatchIn(lower) && shareWord.containsMatchIn(lower)
    }

    /** "Itha", "this", "avan": the thing or person just talked about — never a name. */
    fun pointsBack(word: String): Boolean = word.lowercase(Locale.ROOT).let { w ->
        thisWord.matches(w) || w in setOf("avan", "aval", "avar", "avaru", "avanga", "ivan", "ival", "ivar", "ivaru", "ivanga", "andha", "antha", "indha", "intha")
    }

    private val inr = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }
    private fun rupees(v: Double) = "₹" + inr.format(v)
    private fun day(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))

    /** The period in words: "01 Sep 2026 – 30 Sep 2026", or the whole history. */
    fun periodText(from: LocalDate?, to: LocalDate, lang: KaiLang): String = from?.let { "${day(it)} – ${day(to)}" } ?: when (lang) {
        KaiLang.TAMIL -> "முழு கணக்கு"
        KaiLang.TANGLISH -> "Full kanakku"
        KaiLang.ENGLISH -> "Full history"
    }

    /** The message typed under the PDF in WhatsApp — to the customer / supplier, from the shop. */
    fun caption(st: PartyStatement, shopName: String, lang: KaiLang): String {
        val shop = shopName.ifBlank { "" }.let { if (it.isEmpty()) "" else " — $it" }
        val period = periodText(st.from, st.to, lang)
        val balance = rupees(maxOf(0.0, st.closing))
        return if (st.customer) when (lang) {
            KaiLang.TAMIL -> "வணக்கம் ${st.name}, உங்க கணக்கு விவரம் ($period) PDF-ல இருக்கு. தர வேண்டிய பாக்கி: $balance.$shop"
            KaiLang.TANGLISH -> "Vanakkam ${st.name}, unga kanakku statement ($period) PDF-la irukku. Pending: $balance.$shop"
            KaiLang.ENGLISH -> "Hello ${st.name}, your account statement ($period) is attached. Balance due: $balance.$shop"
        } else when (lang) {
            KaiLang.TAMIL -> "வணக்கம் ${st.name}, எங்க கணக்கு விவரம் ($period) PDF-ல இருக்கு. நாங்க கொடுக்க வேண்டியது: $balance.$shop"
            KaiLang.TANGLISH -> "Vanakkam ${st.name}, enga kanakku statement ($period) PDF-la irukku. Naanga kudukka vendiyadhu: $balance.$shop"
            KaiLang.ENGLISH -> "Hello ${st.name}, our account statement ($period) is attached. Balance we owe: $balance.$shop"
        }
    }
}
