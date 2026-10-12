package com.shopai.app.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A customer's / supplier's credit & payment statement as an A4 PDF (Kai Chat: "Kumar statement PDF WhatsApp-la anuppu"),
 * drawn on the phone — no network, no paid service — and shared to WhatsApp. Kai never sends it by itself: WhatsApp opens
 * with the PDF attached and the owner presses send.
 */
object PartyStatementPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 32f

    private val green = 0xFF1E6B4E.toInt()
    private val red = 0xFFB3412E.toInt()
    private val ink = 0xFF12281F.toInt()
    private val muted = 0xFF6B7F76.toInt()
    private val line = 0xFFD6E7DE.toInt()
    private val mint = 0xFFEAF7F0.toInt()

    private fun paint(size: Float, bold: Boolean = false, color: Int = ink, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = align
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private val inr = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    private fun money(v: Double) = "₹" + inr.format(v)
    private fun day(d: LocalDate?) = d?.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)) ?: "—"

    /** The words on the page, in Tamil or English (the owner's language; Tanglish reads English on paper). */
    private class Words(tamil: Boolean, customer: Boolean) {
        val title = if (tamil) "கணக்கு அறிக்கை" else "Account Statement"
        val party = if (customer) (if (tamil) "வாடிக்கையாளர்" else "Customer") else (if (tamil) "சப்ளையர்" else "Supplier")
        val period = if (tamil) "காலம்" else "Period"
        val whole = if (tamil) "முழு கணக்கு" else "Full history"
        val made = if (tamil) "தயாரித்த நாள்" else "Made on"
        val date = if (tamil) "தேதி" else "Date"
        val details = if (tamil) "விவரம்" else "Details"
        val given = if (customer) (if (tamil) "கடன் கொடுத்தது" else "Credit given") else (if (tamil) "வாங்கியது" else "Purchase")
        val paid = if (customer) (if (tamil) "வந்தது" else "Received") else (if (tamil) "கொடுத்தது" else "Paid")
        val balance = if (tamil) "பாக்கி" else "Balance"
        val opening = if (tamil) "முந்தைய பாக்கி" else "Opening balance"
        val creditRow = if (customer) (if (tamil) "கடன்" else "Credit") else (if (tamil) "வாங்கியது" else "Purchase")
        val paymentRow = if (customer) (if (tamil) "பணம் வந்தது" else "Payment received") else (if (tamil) "பணம் கொடுத்தது" else "Payment made")
        val due = if (tamil) "கடைசி தேதி" else "Due"
        val totalGiven = if (customer) (if (tamil) "மொத்த கடன்" else "Total credit") else (if (tamil) "மொத்தம் வாங்கியது" else "Total purchase")
        val totalPaid = if (customer) (if (tamil) "மொத்தம் வந்தது" else "Total received") else (if (tamil) "மொத்தம் கொடுத்தது" else "Total paid")
        val closing = if (customer) (if (tamil) "தர வேண்டிய பாக்கி" else "Balance due") else (if (tamil) "கொடுக்க வேண்டிய பாக்கி" else "Balance to pay")
        val overdue = if (tamil) "தேதி தாண்டிய பாக்கி" else "Overdue"
        val nextDue = if (tamil) "அடுத்த கடைசி தேதி" else "Next due date"
        val none = if (tamil) "இந்த காலத்தில் எந்த பதிவும் இல்லை." else "No entries in this period."
        val page = if (tamil) "பக்கம்" else "Page"
        val footer = if (tamil) "OwnerNote-ல் உருவாக்கப்பட்டது" else "Made with OwnerNote"
    }

    fun fileName(st: PartyStatement): String =
        "${st.name.replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_').ifEmpty { "party" }}_statement_${st.to}.pdf"

    /** Draws [st] for the shop [shopName] ([shopPhone], [shopPlace]) and saves it in the share folder. */
    fun render(context: Context, st: PartyStatement, shopName: String, shopPhone: String?, shopPlace: String?, tamil: Boolean, today: LocalDate): File {
        val w = Words(tamil, st.customer)
        val pdf = PdfDocument()
        var pageNo = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var c = page.canvas
        var y = header(c, st, w, shopName, shopPhone, shopPlace, today)

        fun newPage() {
            footer(c, w, pageNo)
            pdf.finishPage(page)
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            c.drawText("${w.title} — ${st.name}", M, M + 12f, paint(10f, true, muted))
            y = tableHeader(c, M + 26f, w)
        }

        y = tableHeader(c, y, w)
        if (st.from != null) y = row(c, y, null, w.opening, null, null, st.opening, null, w)
        if (st.rows.isEmpty()) {
            c.drawText(w.none, M + 4f, y + 14f, paint(10f, color = muted))
            y += 24f
        }
        for (r in st.rows) {
            if (y > H - 150f) newPage()
            val label = r.label ?: if (r.given > 0) w.creditRow else w.paymentRow
            y = row(c, y, r.date, label, r.given.takeIf { it > 0 }, r.paid.takeIf { it > 0 }, r.balance, r.dueDate?.let { "${w.due}: ${day(it)}" }, w)
        }
        if (y > H - 170f) newPage()
        totals(c, y + 12f, st, w)
        footer(c, w, pageNo)
        pdf.finishPage(page)

        val dir = File(context.cacheDir, "share/statements").apply { mkdirs() }
        val file = File(dir, fileName(st))
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    private fun header(c: Canvas, st: PartyStatement, w: Words, shopName: String, shopPhone: String?, shopPlace: String?, today: LocalDate): Float {
        c.drawRect(0f, 0f, W.toFloat(), 6f, Paint().apply { color = green })
        var y = M + 14f
        c.drawText(shopName.ifBlank { "OwnerNote" }, M, y, paint(18f, true))
        c.drawText(w.title, W - M, y, paint(16f, true, green, Paint.Align.RIGHT))
        var ly = y + 16f
        listOfNotNull(shopPlace?.takeIf { it.isNotBlank() }, shopPhone?.takeIf { it.isNotBlank() }?.let { "Phone: $it" }).forEach {
            c.drawText(it, M, ly, paint(9.5f, color = muted)); ly += 13f
        }
        var ry = y + 16f
        listOf(
            "${w.period}: " + (st.from?.let { "${day(it)} – ${day(st.to)}" } ?: "${w.whole} (${day(st.to)})"),
            "${w.made}: ${day(today)}",
        ).forEach { c.drawText(it, W - M, ry, paint(9.5f, align = Paint.Align.RIGHT)); ry += 13f }
        y = maxOf(ly, ry) + 8f
        c.drawLine(M, y, W - M, y, Paint().apply { color = line; strokeWidth = 1f })
        y += 16f
        c.drawText(w.party, M, y, paint(9f, true, muted))
        y += 15f
        c.drawText(st.name, M, y, paint(13f, true))
        st.phone?.takeIf { it.isNotBlank() }?.let { y += 14f; c.drawText("Phone: $it", M, y, paint(9.5f, color = muted)) }
        return y + 16f
    }

    // Date | Details | Given | Paid | Balance
    private val cols = floatArrayOf(M, M + 78f, M + 320f, M + 410f, W - M)

    private fun tableHeader(c: Canvas, y0: Float, w: Words): Float {
        c.drawRect(M, y0, W - M, y0 + 20f, Paint().apply { color = mint })
        val by = y0 + 13.5f
        c.drawText(w.date, cols[0] + 4f, by, paint(8.5f, true, green))
        c.drawText(w.details, cols[1], by, paint(8.5f, true, green))
        c.drawText(w.given, cols[2] - 4f, by, paint(8.5f, true, green, Paint.Align.RIGHT))
        c.drawText(w.paid, cols[3] - 4f, by, paint(8.5f, true, green, Paint.Align.RIGHT))
        c.drawText(w.balance, cols[4] - 4f, by, paint(8.5f, true, green, Paint.Align.RIGHT))
        return y0 + 20f
    }

    private fun row(c: Canvas, y0: Float, date: LocalDate?, label: String, given: Double?, paid: Double?, balance: Double, note: String?, w: Words): Float {
        val text = paint(9.5f)
        val lines = wrap(label, cols[2] - cols[1] - 70f, text)
        var y = y0 + 14f
        c.drawText(if (date == null && label == w.opening) "" else day(date), cols[0] + 4f, y, text)
        lines.forEachIndexed { i, s -> c.drawText(s, cols[1], y + i * 12f, if (label == w.opening) paint(9.5f, true) else text) }
        given?.let { c.drawText(money(it), cols[2] - 4f, y, paint(9.5f, color = red, align = Paint.Align.RIGHT)) }
        paid?.let { c.drawText(money(it), cols[3] - 4f, y, paint(9.5f, color = green, align = Paint.Align.RIGHT)) }
        c.drawText(money(balance), cols[4] - 4f, y, paint(9.5f, true, align = Paint.Align.RIGHT))
        y += (lines.size - 1) * 12f
        note?.let { y += 12f; c.drawText(it, cols[1], y, paint(8.5f, color = muted)) }
        y += 7f
        c.drawLine(M, y, W - M, y, Paint().apply { color = line; strokeWidth = 0.6f })
        return y
    }

    private fun totals(c: Canvas, y0: Float, st: PartyStatement, w: Words) {
        val left = W - M - 230f
        var y = y0
        fun pair(k: String, v: String, bold: Boolean = false, color: Int = ink) {
            y += 15f
            c.drawText(k, left, y, paint(10f, bold, if (bold) ink else muted))
            c.drawText(v, W - M, y, paint(10f, bold, color, Paint.Align.RIGHT))
        }
        if (st.from != null) pair(w.opening, money(st.opening))
        pair(w.totalGiven, money(st.given))
        pair(w.totalPaid, money(st.paid))
        y += 6f
        c.drawRect(left - 8f, y, W - M + 4f, y + 24f, Paint().apply { color = mint })
        y += 16f
        c.drawText(w.closing, left, y, paint(11.5f, true))
        c.drawText(money(st.closing), W - M, y, paint(11.5f, true, if (st.closing > 0.005) red else green, Paint.Align.RIGHT))
        y += 8f
        if (st.overdue > 0.005) pair(w.overdue, money(st.overdue), color = red)
        st.nextDue?.let { pair(w.nextDue, day(it)) }
    }

    private fun footer(c: Canvas, w: Words, pageNo: Int) {
        c.drawLine(M, H - 40f, W - M, H - 40f, Paint().apply { color = line; strokeWidth = 1f })
        c.drawText(w.footer, M, H - 26f, paint(8.5f, color = muted))
        c.drawText("${w.page} $pageNo", W - M, H - 26f, paint(8.5f, color = muted, align = Paint.Align.RIGHT))
    }

    private fun wrap(text: String, width: Float, p: Paint): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (word in text.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(next) <= width || cur.isEmpty()) cur = next else { out += cur; cur = word }
        }
        if (cur.isNotEmpty()) out += cur
        return out.take(3)
    }

    /**
     * Opens WhatsApp with the PDF attached and [caption] typed — in the chat with [phone] when it is a number Kai can be sure
     * of, else WhatsApp asks whom to send it to. [whatsapp] false (or no WhatsApp on the phone): the phone's share sheet.
     * The owner presses send; nothing goes out by itself.
     */
    fun share(context: Context, file: File, caption: String, phone: String?, whatsapp: Boolean) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            clipData = ClipData.newRawUri(file.name, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val app = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
            whatsapp && Intent(send).setPackage(pkg).resolveActivity(context.packageManager) != null
        }
        val target = if (app != null) {
            Intent(send).setPackage(app).apply { whatsappNumber(phone)?.let { putExtra("jid", "$it@s.whatsapp.net") } }
        } else {
            Intent.createChooser(send, file.name)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(target)
    }
}
