package com.shopai.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/** The wa.me link for a phone (India: the last 10 digits with 91), or null when there is no usable number. */
fun whatsAppChatLink(phone: String?, text: String): String? {
    val digits = phone?.filter { it.isDigit() }.orEmpty()
    if (digits.length < 10) return null
    return "https://wa.me/91${digits.takeLast(10)}?text=" + Uri.encode(text)
}

/**
 * Opens WhatsApp with the message typed in, to that person's chat when the number is known — the owner presses send.
 * Nothing is sent by the app itself.
 */
fun openWhatsAppText(context: Context, phone: String?, text: String) {
    val link = whatsAppChatLink(phone, text)
    val direct = link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
    if (direct != null && direct.resolveActivity(context.packageManager) != null) {
        context.startActivity(direct)
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val target = listOf("com.whatsapp", "com.whatsapp.w4b")
        .map { Intent(send).setPackage(it) }
        .firstOrNull { it.resolveActivity(context.packageManager) != null }
        ?: Intent.createChooser(send, null)
    context.startActivity(target)
}
