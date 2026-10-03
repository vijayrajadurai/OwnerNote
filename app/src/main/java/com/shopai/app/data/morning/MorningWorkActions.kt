package com.shopai.app.data.morning

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.shopai.app.books.billing.BillDrafts
import com.shopai.app.books.billing.DraftKind
import com.shopai.app.books.billing.MoneyAccounts
import com.shopai.app.books.billing.PaymentDraft
import com.shopai.app.books.billing.toInput
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.integration.BooksModule
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.books.model.TxnSource
import com.shopai.app.brain.morning.MorningPaymentDraft
import com.shopai.app.brain.morning.MorningResult
import com.shopai.app.data.repository.ReminderRepository
import com.shopai.app.notifications.ReminderAlarms
import retrofit2.HttpException
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * What Morning Work asks the app to do, done through the existing
 * integrations only: the phone's dialer, WhatsApp (the same app-picking as
 * the other WhatsApp shares), the existing reminder engine and the books'
 * own payment draft → post flow. Each call does exactly one thing the owner
 * just chose — never on its own.
 */
object MorningWorkActions {

    /** Opens the dialer with the number filled in. The owner places the call. */
    fun openDialer(context: Context, phone: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${dialable(phone)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Opens the customer's WhatsApp chat with the message typed in. The owner presses Send there. */
    fun openWhatsApp(context: Context, phone: String, message: String): Boolean {
        val digits = phone.filter { it.isDigit() }.let { if (it.length == 10) "91$it" else it }
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        val view = Intent(Intent.ACTION_VIEW, uri)
        val target = listOf("com.whatsapp", "com.whatsapp.w4b")
            .map { Intent(view).setPackage(it) }
            .firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?: view
        return try {
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /**
     * "Remind me later" through the existing reminder engine (the backend
     * reminder list + the phone's reminder alarm). Offline, the reminder is
     * kept on the task and created when the connection is back.
     */
    suspend fun createReminder(
        reminders: ReminderRepository,
        alarms: ReminderAlarms,
        taskId: String,
        title: String,
        atMillis: Long,
    ): MorningResult = try {
        val created = reminders.createReminder(title, Instant.ofEpochMilli(atMillis).toString())
        alarms.ringAt(created, atMillis)
        MorningResult.ReminderCreated(taskId, created.id)
    } catch (_: IOException) {
        MorningResult.ReminderQueued(taskId, atMillis)
    } catch (_: HttpException) {
        MorningResult.ReminderFailed(taskId)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        MorningResult.ReminderFailed(taskId)
    }

    /**
     * Posts a payment the owner confirmed, through the books' existing
     * payment flow: saved as a reviewed draft, then posted by the engine
     * (which de-duplicates by the draft id, so a retry never posts twice).
     * Null when the books aren't in use yet (the party page records it then).
     */
    suspend fun postPayment(books: BooksModule, draft: MorningPaymentDraft, spoken: Boolean): MorningResult? {
        val s = books.session() ?: return null
        return try {
            MoneyAccounts.ensureDefaults(s)
            val kind = if (draft.outgoing) DraftKind.PAYMENT_OUT else DraftKind.PAYMENT_IN
            val mode = runCatching { PaymentMode.valueOf(draft.mode) }.getOrDefault(PaymentMode.CASH)
            val payload = PaymentDraft(
                kind = kind,
                partyId = draft.partyId,
                amountPaise = Money.ofRupees(BigDecimal.valueOf(draft.amount).toPlainString()),
                mode = mode,
                reference = draft.reference,
                date = LocalDate.now().toString(),
            )
            val bills = BillDrafts(s)
            val source = if (spoken) TxnSource.VOICE else TxnSource.MANUAL
            val id = bills.save(draftKey(draft.id), kind, payload, source)
            val input = payload.toInput(s, id, bills.sourceOf(id))
            when (val r = if (draft.outgoing) s.engine.postPaymentOut(input) else s.engine.postPaymentIn(input)) {
                is PostResult.Posted -> MorningResult.PaymentPosted(draft.id)
                is PostResult.Rejected -> MorningResult.PaymentRejected(draft.id, r.errors.joinToString(" ") { it.message })
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            MorningResult.PaymentRejected(draft.id, e.message)
        }
    }

    /** The account a payment mode lands in (for the review card). */
    suspend fun accountName(books: BooksModule, mode: String): String? {
        val s = books.session() ?: return null
        return runCatching {
            MoneyAccounts.ensureDefaults(s)
            MoneyAccounts.accountFor(s, PaymentMode.valueOf(mode))?.name
        }.getOrNull()
    }

    private fun draftKey(id: String) = id.replace('|', '-')

    private fun dialable(phone: String) = phone.filter { it.isDigit() || it == '+' }
}
