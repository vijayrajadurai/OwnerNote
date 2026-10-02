package com.shopai.app.books.billing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.shopai.app.books.data.TxnItemEntity
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.Qty
import com.shopai.app.books.model.TxnType
import com.shopai.app.books.tax.GstStates
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Draws an [InvoiceDocument] as an A4 PDF (several pages for long bills). */
object InvoicePdf {
    private const val W = 595
    private const val H = 842
    private const val M = 32f

    private val green = 0xFF1E6B4E.toInt()
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
    fun money(paise: Long): String = "₹" + inr.format(Money.toRupees(paise))
    private fun day(epochDay: Int): String = LocalDate.ofEpochDay(epochDay.toLong()).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
    private fun qty(milli: Long) = Qty.toDecimal(milli).toPlainString()
    private fun pct(bp: Int) = BigDecimal.valueOf(bp.toLong(), 2).stripTrailingZeros().toPlainString() + "%"

    fun fileName(doc: InvoiceDocument): String = "${doc.txn.number.replace(Regex("[^A-Za-z0-9-]"), "_")}.pdf"

    fun render(context: Context, doc: InvoiceDocument): File {
        val pdf = PdfDocument()
        var pageNo = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var c = page.canvas
        var y = header(c, doc)

        fun newPage() {
            footer(c, doc, pageNo)
            pdf.finishPage(page)
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            c.drawText("${doc.title} ${doc.txn.number} (continued)", M, M + 12f, paint(10f, true, muted))
            y = M + 32f
        }

        if (doc.hasItems) {
            y = tableHeader(c, y)
            doc.items.forEachIndexed { i, item ->
                if (y > H - 120f) {
                    newPage()
                    y = tableHeader(c, y)
                }
                y = itemRow(c, y, i + 1, item)
            }
            if (y > H - 300f) newPage()
            y = totals(c, y + 8f, doc)
        } else {
            if (y > H - 260f) newPage()
            y = receipt(c, y, doc)
        }
        if (y > H - 170f) newPage()
        payDetails(c, y + 12f, doc)
        footer(c, doc, pageNo)
        if (doc.isVoid) c.drawText("CANCELLED", W / 2f, H / 2f, paint(64f, true, 0x33D6503C, Paint.Align.CENTER))
        pdf.finishPage(page)

        val dir = File(context.cacheDir, "share/invoices").apply { mkdirs() }
        val file = File(dir, fileName(doc))
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    private fun header(c: Canvas, doc: InvoiceDocument): Float {
        val b = doc.business
        val t = doc.txn
        c.drawRect(0f, 0f, W.toFloat(), 6f, Paint().apply { color = green })
        var y = M + 14f
        c.drawText(b.name, M, y, paint(18f, true))
        c.drawText(doc.title, W - M, y, paint(16f, true, green, Paint.Align.RIGHT))
        val left = listOfNotNull(
            b.address, b.phone?.let { "Phone: $it" }, b.gstin?.let { "GSTIN: $it" },
            b.stateCode?.let { "State: ${GstStates.byCode[it]} ($it)" },
        )
        val right = listOfNotNull(
            "No: ${t.number}", "Date: ${day(t.date)}", t.dueDate?.let { "Due: ${day(it)}" },
            t.placeOfSupply?.takeIf { doc.hasItems }?.let { "Place of supply: ${GstStates.byCode[it] ?: it}" },
        )
        var ly = y + 16f
        left.forEach { wrap(it, 300f, paint(9.5f, color = muted)).forEach { s -> c.drawText(s, M, ly, paint(9.5f, color = muted)); ly += 13f } }
        var ry = y + 16f
        right.forEach { c.drawText(it, W - M, ry, paint(9.5f, color = ink, align = Paint.Align.RIGHT)); ry += 13f }
        y = maxOf(ly, ry) + 10f

        c.drawLine(M, y, W - M, y, Paint().apply { color = line; strokeWidth = 1f })
        y += 16f
        val partyLabel = when (doc.type) {
            TxnType.PURCHASE, TxnType.PURCHASE_RETURN, TxnType.PAYMENT_OUT -> "Supplier"
            else -> "Bill to"
        }
        c.drawText(partyLabel, M, y, paint(9f, true, muted))
        y += 14f
        val p = doc.party
        c.drawText(p?.name ?: doc.txn.partyName ?: "Cash sale", M, y, paint(12f, true))
        y += 14f
        listOfNotNull(
            listOfNotNull(p?.address, p?.city, p?.pincode).joinToString(", ").takeIf { it.isNotBlank() },
            (p?.gstin ?: doc.txn.partyGstin)?.let { "GSTIN: $it" },
            p?.stateCode?.let { "State: ${GstStates.byCode[it]} ($it)" },
            p?.mobile?.let { "Phone: +91 $it" },
            doc.original?.let { "Against ${it.number} dated ${day(it.date)}" },
        ).forEach { wrap(it, W - 2 * M, paint(9.5f)).forEach { s -> c.drawText(s, M, y, paint(9.5f, color = muted)); y += 13f } }
        return y + 10f
    }

    // # | Item / HSN | Qty | Rate | Disc | Taxable | GST | Amount
    private val cols = floatArrayOf(M, M + 20f, M + 215f, M + 285f, M + 345f, M + 395f, M + 465f, W - M)

    private fun tableHeader(c: Canvas, y0: Float): Float {
        c.drawRect(M, y0, W - M, y0 + 20f, Paint().apply { color = mint })
        val p = paint(8.5f, true, green)
        val pr = paint(8.5f, true, green, Paint.Align.RIGHT)
        val by = y0 + 13.5f
        c.drawText("#", cols[0] + 4f, by, p)
        c.drawText("Item / HSN", cols[1], by, p)
        c.drawText("Qty", cols[3] - 4f, by, pr)
        c.drawText("Rate", cols[4] - 4f, by, pr)
        c.drawText("Disc", cols[5] - 4f, by, pr)
        c.drawText("Taxable", cols[6] - 4f, by, pr)
        c.drawText("GST", cols[6] + 30f, by, pr)
        c.drawText("Amount", cols[7] - 2f, by, pr)
        return y0 + 30f
    }

    private fun itemRow(c: Canvas, y0: Float, n: Int, it: TxnItemEntity): Float {
        val body = paint(9f)
        val right = paint(9f, align = Paint.Align.RIGHT)
        val small = paint(7.5f, color = muted)
        c.drawText("$n", cols[0] + 4f, y0, body)
        val nameLines = wrap(it.itemName, cols[3] - cols[1] - 50f, body)
        var y = y0
        nameLines.take(2).forEach { s -> c.drawText(s, cols[1], y, body); y += 11f }
        it.hsnCode?.let { code -> c.drawText(code, cols[1], y, small); y += 10f }
        c.drawText("${qty(it.qtyMilli)} ${it.unit}", cols[3] - 4f, y0, right)
        c.drawText(money(it.ratePaise).removePrefix("₹"), cols[4] - 4f, y0, right)
        c.drawText(if (it.discountPaise > 0) money(it.discountPaise).removePrefix("₹") else "—", cols[5] - 4f, y0, right)
        c.drawText(money(it.taxablePaise).removePrefix("₹"), cols[6] - 4f, y0, right)
        c.drawText(if (it.taxType == "GST") pct(it.gstBp) else "—", cols[6] + 30f, y0, right)
        c.drawText(money(it.totalPaise).removePrefix("₹"), cols[7] - 2f, y0, right)
        val bottom = maxOf(y, y0 + 4f) + 4f
        c.drawLine(M, bottom, W - M, bottom, Paint().apply { color = line; strokeWidth = 0.6f })
        return bottom + 14f
    }

    private fun totals(c: Canvas, y0: Float, doc: InvoiceDocument): Float {
        val t = doc.txn
        val label = paint(9.5f, color = muted)
        val value = paint(9.5f, align = Paint.Align.RIGHT)
        val x1 = W - M - 200f
        var y = y0
        fun row(name: String, paise: Long, always: Boolean = false) {
            if (paise == 0L && !always) return
            c.drawText(name, x1, y, label)
            c.drawText(money(paise), W - M, y, value)
            y += 14f
        }
        row("Subtotal", t.grossPaise, always = true)
        row("Discount", -t.discountPaise)
        row("Taxable value", t.taxablePaise, always = true)
        row("CGST", t.cgstPaise)
        row("SGST", t.sgstPaise)
        row("IGST", t.igstPaise)
        row("Cess", t.cessPaise)
        row("Round off", t.roundOffPaise)
        c.drawRect(x1 - 8f, y - 2f, W - M + 4f, y + 20f, Paint().apply { color = mint })
        c.drawText("Grand total", x1, y + 13f, paint(11f, true, green))
        c.drawText(money(t.totalPaise), W - M, y + 13f, paint(11f, true, green, Paint.Align.RIGHT))
        y += 32f
        if (doc.type == TxnType.SALE || doc.type == TxnType.PURCHASE) {
            row(if (doc.type == TxnType.SALE) "Amount received" else "Amount paid", doc.paidPaise, always = true)
            row(if (doc.type == TxnType.SALE) "Credit notes" else "Debit notes", doc.returnedPaise)
            c.drawText("Balance due", x1, y, paint(10f, true))
            c.drawText(money(doc.balancePaise), W - M, y, paint(10f, true, align = Paint.Align.RIGHT))
            y += 16f
        }
        // Amount in words, left of the totals.
        var wy = y0
        c.drawText("Amount in words", M, wy, paint(8.5f, true, muted))
        wy += 13f
        wrap(RupeesInWords.of(t.totalPaise), x1 - M - 20f, paint(9.5f)).forEach { c.drawText(it, M, wy, paint(9.5f)); wy += 13f }
        return maxOf(y, wy) + 6f
    }

    private fun receipt(c: Canvas, y0: Float, doc: InvoiceDocument): Float {
        var y = y0 + 6f
        c.drawRect(M, y, W - M, y + 44f, Paint().apply { color = mint })
        c.drawText(if (doc.type == TxnType.PAYMENT_OUT) "Amount paid" else "Amount received", M + 12f, y + 18f, paint(9.5f, color = muted))
        c.drawText(money(doc.txn.totalPaise), M + 12f, y + 36f, paint(16f, true, green))
        y += 60f
        val label = paint(9.5f, color = muted)
        listOfNotNull(
            doc.txn.paymentMode?.let { "Mode: ${it.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)}" },
            doc.moneyAccountName?.let { "Account: $it" },
            doc.txn.reference?.let { "Reference: $it" },
            "In words: ${RupeesInWords.of(doc.txn.totalPaise)}",
        ).forEach { c.drawText(it, M, y, label); y += 14f }
        return y
    }

    private fun payDetails(c: Canvas, y0: Float, doc: InvoiceDocument) {
        var y = y0
        val b = doc.business
        val lines = buildList {
            if (doc.type == TxnType.SALE) {
                b.upiId?.let { add("UPI: $it") }
                b.bankDetails?.let { addAll(it.lines()) }
            }
            b.invoiceTerms?.takeIf { doc.type == TxnType.SALE }?.let { add("Terms: $it") }
            doc.txn.notes?.let { add("Notes: $it") }
        }
        if (lines.isNotEmpty()) {
            c.drawText("Payment details & notes", M, y, paint(9f, true, muted))
            y += 13f
            lines.forEach { l -> wrap(l, W - 2 * M - 160f, paint(9f)).forEach { c.drawText(it, M, y, paint(9f)); y += 12f } }
        }
        val sy = maxOf(y0 + 20f, H - 110f)
        c.drawText("For ${b.name}", W - M, sy, paint(9.5f, true, align = Paint.Align.RIGHT))
        c.drawText("Authorised signatory", W - M, sy + 36f, paint(8.5f, color = muted, align = Paint.Align.RIGHT))
    }

    private fun footer(c: Canvas, doc: InvoiceDocument, pageNo: Int) {
        val p = paint(7.5f, color = muted)
        c.drawText("${doc.title} ${doc.txn.number} · page $pageNo · made with OwnerNote", M, H - 20f, p)
    }

    /** Breaks text into lines that fit [width]. */
    private fun wrap(text: String, width: Float, p: Paint): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').forEach { para ->
            var rest = para.trim()
            while (rest.isNotEmpty()) {
                var n = p.breakText(rest, true, width, null).coerceAtLeast(1)
                if (n < rest.length) rest.lastIndexOf(' ', n).takeIf { it > 0 }?.let { n = it }
                out += rest.substring(0, n).trim()
                rest = rest.substring(n).trim()
            }
        }
        return out
    }
}

/** Sends a bill PDF — straight to the party's WhatsApp chat when we have their number. */
object InvoiceShare {
    fun whatsapp(context: Context, file: File, phone: String?, caption: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            clipData = ClipData.newRawUri(file.name, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val jid = phone?.filter(Char::isDigit)?.takeLast(10)?.takeIf { it.length == 10 }?.let { "91$it@s.whatsapp.net" }
        val app = listOf("com.whatsapp", "com.whatsapp.w4b").map { Intent(send).setPackage(it) }
            .firstOrNull { it.resolveActivity(context.packageManager) != null }
        val target = app?.apply { jid?.let { putExtra("jid", it) } } ?: Intent.createChooser(send, "Share ${file.nameWithoutExtension}")
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(target)
    }

    /** Any app (email, Drive, Bluetooth…). */
    fun other(context: Context, file: File, caption: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            clipData = ClipData.newRawUri(file.name, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share ${file.nameWithoutExtension}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Android's print dialog (Wi-Fi / Bluetooth printers, or Save as PDF). Needs an Activity context. */
    fun print(context: Context, file: File) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(
            file.nameWithoutExtension,
            object : PrintDocumentAdapter() {
                override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
                    if (cancel?.isCanceled == true) return callback.onLayoutCancelled()
                    callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
                }

                override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
                    runCatching {
                        file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                    }.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                        .onFailure { callback.onWriteFailed(it.message) }
                }
            },
            PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build(),
        )
    }
}

/** The WhatsApp caption: what, how much, and what is still due. */
fun shareCaption(doc: InvoiceDocument): String = buildString {
    append("${doc.business.name} — ${doc.title.lowercase().replaceFirstChar(Char::uppercase)} ${doc.txn.number}: ${InvoicePdf.money(doc.txn.totalPaise)}")
    if ((doc.type == TxnType.SALE || doc.type == TxnType.PURCHASE) && doc.balancePaise > 0) {
        append(". Balance due ${InvoicePdf.money(doc.balancePaise)}")
        doc.txn.dueDate?.let { append(" by ${LocalDate.ofEpochDay(it.toLong()).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))}") }
    }
    if (doc.type == TxnType.SALE) doc.business.upiId?.let { append(". UPI: $it") }
    append(".")
}
