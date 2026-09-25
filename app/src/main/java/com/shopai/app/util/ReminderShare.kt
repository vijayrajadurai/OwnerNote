package com.shopai.app.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.shopai.app.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

fun formatReminderShareDate(iso: String): String {
    val date = parseIsoToLocalDate(iso) ?: return iso.take(10)
    return java.time.format.DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH).format(date)
}

fun formatPaymentReminderSender(name: String?, phone: String?): String {
    val displayName = name?.trim().orEmpty()
    val digits = displayIndianPhone(phone)
    return when {
        displayName.isNotBlank() && digits.isNotBlank() -> "$displayName, $digits"
        displayName.isNotBlank() -> displayName
        digits.isNotBlank() -> digits
        else -> "Owner Note"
    }
}

fun buildPaymentReminderShareText(
    partyName: String,
    amountLabel: String,
    dueDateLabel: String,
    senderLabel: String,
): String {
    val who = partyName.trim()
    val head = if (who.isNotBlank()) "Payment Reminder for $who" else "Payment Reminder"
    return "$head — $amountLabel on $dueDateLabel send by $senderLabel"
}

fun createPaymentReminderCardBitmap(
    context: Context,
    amountLabel: String,
    dueDateLabel: String,
    senderLabel: String,
): Bitmap {
    val width = 1080
    val height = 640
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0xFF8B1A12.toInt(), 0xFFD32F2F.toInt(), 0xFFFF6A3D.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
    }
    val card = RectF(48f, 48f, width - 48f, height - 48f)
    canvas.drawRoundRect(card, 48f, 48f, bgPaint)

    val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
    }
    canvas.drawRoundRect(
        RectF(card.left + 8f, card.top + 8f, card.right - 8f, card.bottom - 8f),
        40f,
        40f,
        glass,
    )

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 42f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 36f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val amountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 72f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    val title = context.getString(R.string.reminder_share_card_title)
    canvas.drawText(title, card.left + 56f, card.top + 110f, titlePaint)
    canvas.drawText(amountLabel, card.left + 56f, card.top + 220f, amountPaint)
    canvas.drawText(
        context.getString(R.string.reminder_share_card_on, dueDateLabel),
        card.left + 56f,
        card.top + 310f,
        bodyPaint,
    )
    val sentBy = context.getString(R.string.reminder_share_card_sent_by, senderLabel)
    val sentByPaint = Paint(bodyPaint)
    val maxWidth = card.width() - 112f
    while (sentByPaint.textSize > 24f && sentByPaint.measureText(sentBy) > maxWidth) {
        sentByPaint.textSize -= 2f
    }
    canvas.drawText(sentBy, card.left + 56f, card.top + 380f, sentByPaint)
    return bitmap
}

fun sharePaymentReminderToWhatsApp(
    context: Context,
    bitmap: Bitmap,
    caption: String,
) {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "payment_reminder.png")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        clipData = ClipData.newRawUri("reminder", uri)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val whatsapp = Intent(send).setPackage("com.whatsapp")
    val whatsappBiz = Intent(send).setPackage("com.whatsapp.w4b")
    val target = when {
        whatsapp.resolveActivity(context.packageManager) != null -> whatsapp
        whatsappBiz.resolveActivity(context.packageManager) != null -> whatsappBiz
        else -> Intent.createChooser(send, context.getString(R.string.reminder_share_whatsapp))
    }
    target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(target)
}
