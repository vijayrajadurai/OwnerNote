package com.shopai.app.books.integration

import androidx.room.withTransaction
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.PartyInput
import com.shopai.app.books.engine.PartyOpeningInput
import com.shopai.app.books.engine.PostMeta
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.nameKey
import com.shopai.app.books.integration.KhataImport.Row
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.TxnSource
import java.time.LocalDate

/** Confirm on a Khatabook report ([KhataImport]): the people and their balances into the books — all or nothing. */
object KhataImportSave {

    /** What Confirm did: people added (with a balance or without), people already in the books (left as they were). */
    data class Outcome(val added: Int, val withBalance: Int, val alreadyThere: List<String>)

    /**
     * Writes the [rows] the owner confirmed as [kind] — all or nothing. A customer's "you'll get" is what they owe (a supplier's
     * "you'll give" what the shop owes); the other side is their advance. The collection date is the opening balance's due date.
     */
    suspend fun save(session: BooksSession, kind: PartyKind, rows: List<Row>, today: LocalDate = LocalDate.now()): Outcome {
        var added = 0; var withBalance = 0; val there = mutableListOf<String>()
        val biz = session.ctx.businessId
        session.db.withTransaction {
            for (r in rows) {
                if (session.dao.partyByName(biz, kind.name, nameKey(r.name)) != null) { there += r.name; continue }
                val mobile = r.phone?.takeIf { it.length == 10 && it[0] in "6789" }
                val party = session.masters.createParty(PartyInput(kind = kind, name = r.name, mobile = mobile, notes = "From Khatabook")).orThrow()
                added++
                val owed = if (kind == PartyKind.CUSTOMER) r.getPaise - r.givePaise else r.givePaise - r.getPaise
                if (owed == 0L) continue
                val posted = session.engine.postPartyOpening(
                    PartyOpeningInput(
                        partyId = party.id,
                        amountPaise = kotlin.math.abs(owed),
                        inPartysFavour = owed < 0,
                        date = today,
                        dueDate = r.collectionDate?.takeIf { owed > 0 },
                        meta = PostMeta(clientKey = "khatabook-${kind.name}-${nameKey(r.name)}-${r.phone.orEmpty()}-$owed", source = TxnSource.IMPORT, notes = "Balance brought from Khatabook"),
                    ),
                )
                if (posted is PostResult.Rejected && posted.errors.none { it.code == BooksErrorCode.OPENING_EXISTS }) throw BooksRejectedException(posted.errors)
                withBalance++
            }
        }
        return Outcome(added, withBalance, there)
    }

    /** "₹85,552" / "₹373.50" — an amount as the list shows it. */
    fun rupees(paise: Long): String {
        val f = java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN")).apply { minimumFractionDigits = if (paise % 100 == 0L) 0 else 2; maximumFractionDigits = 2 }
        return "₹" + f.format(paise / 100.0)
    }

    private fun <T> MasterResult<T>.orThrow(): T = when (this) {
        is MasterResult.Ok -> value
        is MasterResult.Rejected -> throw BooksRejectedException(errors)
    }
}
