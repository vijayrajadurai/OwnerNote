package com.shopai.app.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.shopai.app.R
import com.shopai.app.data.model.DailyCashEntry
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.data.model.DailyCashPaymentMode
import com.shopai.app.data.model.DailyCashTotals
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun buildDailyCashShareCaption(
    dateLabel: String,
    cashBoxLabel: String,
): String = "Daily Cash Note for $dateLabel · Kallapetti $cashBoxLabel"

fun createDailyCashNotePdf(
    context: Context,
    dateLabel: String,
    openingLabel: String,
    cashBoxLabel: String,
    totals: DailyCashTotals,
    entries: List<DailyCashEntry>,
): File {
    val pageWidth = 595
    val pageHeight = 842
    val document = PdfDocument()
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF154F39.toInt()
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1A.toInt()
        textSize = 13f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1A.toInt()
        textSize = 11f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7A766C.toInt()
        textSize = 10f
    }
    val inPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E6B4E.toInt()
        textSize = 12f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    val outPaint = Paint(inPaint).apply { color = 0xFFD6503C.toInt() }
    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF7F5EF.toInt() }
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E6B4E.toInt() }

    var pageIndex = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create())
    var canvas = page.canvas
    var y = drawHeader(
        context = context,
        canvas = canvas,
        pageWidth = pageWidth,
        dateLabel = dateLabel,
        openingLabel = openingLabel,
        cashBoxLabel = cashBoxLabel,
        totals = totals,
        titlePaint = titlePaint,
        headingPaint = headingPaint,
        bodyPaint = bodyPaint,
        mutedPaint = mutedPaint,
        cardPaint = cardPaint,
        whitePaint = whitePaint,
        accentPaint = accentPaint,
    )

    fun newPage() {
        document.finishPage(page)
        pageIndex += 1
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create())
        canvas = page.canvas
        y = 48f
        canvas.drawText(context.getString(R.string.cash_note_title), 36f, y, headingPaint)
        y += 24f
    }

    y += 8f
    canvas.drawText(context.getString(R.string.cash_note_entries_heading), 36f, y, headingPaint)
    y += 18f

    if (entries.isEmpty()) {
        canvas.drawText(context.getString(R.string.cash_note_list_empty), 36f, y, mutedPaint)
    } else {
        for (entry in entries) {
            if (y > pageHeight - 56f) newPage()
            val time = formatEntryTimeForShare(entry.createdAt)
            val mode = if (entry.paymentMode == DailyCashPaymentMode.CASH) {
                context.getString(R.string.cash_note_cash)
            } else {
                context.getString(R.string.cash_note_upi)
            }
            val note = entry.note?.takeIf { it.isNotBlank() } ?: "—"
            val sign = if (entry.type == DailyCashEntryType.IN) "+" else "−"
            val amountPaint = if (entry.type == DailyCashEntryType.IN) inPaint else outPaint
            canvas.drawText("$time · $mode · $note", 36f, y, bodyPaint)
            canvas.drawText("$sign${formatInr(entry.amount)}", (pageWidth - 36).toFloat(), y, amountPaint)
            y += 18f
        }
    }

    document.finishPage(page)
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "daily_cash_note.pdf")
    FileOutputStream(file).use { out -> document.writeTo(out) }
    document.close()
    return file
}

fun shareDailyCashNoteToWhatsApp(
    context: Context,
    pdfFile: File,
    caption: String,
) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        pdfFile,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        clipData = ClipData.newRawUri("daily_cash_note", uri)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val whatsapp = Intent(send).setPackage("com.whatsapp")
    val whatsappBiz = Intent(send).setPackage("com.whatsapp.w4b")
    val target = when {
        whatsapp.resolveActivity(context.packageManager) != null -> whatsapp
        whatsappBiz.resolveActivity(context.packageManager) != null -> whatsappBiz
        else -> Intent.createChooser(send, context.getString(R.string.cash_note_share_whatsapp))
    }
    target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(target)
}

private fun drawHeader(
    context: Context,
    canvas: Canvas,
    pageWidth: Int,
    dateLabel: String,
    openingLabel: String,
    cashBoxLabel: String,
    totals: DailyCashTotals,
    titlePaint: Paint,
    headingPaint: Paint,
    bodyPaint: Paint,
    mutedPaint: Paint,
    cardPaint: Paint,
    whitePaint: Paint,
    accentPaint: Paint,
): Float {
    val card = RectF(28f, 28f, pageWidth - 28f, 268f)
    canvas.drawRoundRect(card, 22f, 22f, cardPaint)
    val accentBar = RectF(card.left, card.top, card.left + 10f, card.bottom)
    canvas.drawRoundRect(accentBar, 8f, 8f, accentPaint)

    var y = card.top + 36f
    canvas.drawText(context.getString(R.string.cash_note_title), card.left + 28f, y, titlePaint)
    y += 22f
    canvas.drawText(dateLabel, card.left + 28f, y, mutedPaint)
    y += 28f
    canvas.drawText(context.getString(R.string.cash_note_kallapetti), card.left + 28f, y, mutedPaint)
    y += 22f
    canvas.drawText(cashBoxLabel, card.left + 28f, y, titlePaint)
    y += 18f
    canvas.drawText(
        context.getString(R.string.cash_note_kallapetti_opening, openingLabel),
        card.left + 28f,
        y,
        bodyPaint,
    )

    val chipTop = 196f
    val chipHeight = 48f
    val gap = 10f
    val innerLeft = card.left + 24f
    val innerRight = card.right - 24f
    val chipWidth = (innerRight - innerLeft - gap * 2) / 3f
    listOf(
        context.getString(R.string.cash_note_in) to formatInr(totals.totalIn),
        context.getString(R.string.cash_note_out) to formatInr(totals.totalOut),
        context.getString(R.string.cash_note_net) to formatInr(totals.net),
    ).forEachIndexed { index, (label, value) ->
        val left = innerLeft + index * (chipWidth + gap)
        val chip = RectF(left, chipTop, left + chipWidth, chipTop + chipHeight)
        canvas.drawRoundRect(chip, 12f, 12f, whitePaint)
        canvas.drawText(label, left + 10f, chipTop + 16f, mutedPaint)
        canvas.drawText(value, left + 10f, chipTop + 36f, headingPaint)
    }
    return card.bottom + 28f
}

private fun formatEntryTimeForShare(iso: String): String {
    val formatter = DateTimeFormatter.ofPattern("hh:mm a")
    return runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(formatter)
    }.getOrDefault(iso.take(5))
}
