package com.shopai.app.data.repository

import com.shopai.app.data.local.room.CapturedDocumentDao
import com.shopai.app.data.local.room.CapturedDocumentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal

/** A bill with the handwritten notes linked to it. */
data class BillWithNotes(
    val bill: CapturedDocumentEntity,
    val notes: List<CapturedDocumentEntity>,
) {
    val notesTotal: BigDecimal
        get() = notes.fold(BigDecimal.ZERO) { acc, note -> acc + (note.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO) }
}

data class CapturedDocuments(
    val bills: List<BillWithNotes>,
    val unlinkedNotes: List<CapturedDocumentEntity>,
) {
    val isEmpty: Boolean get() = bills.isEmpty() && unlinkedNotes.isEmpty()
}

/**
 * Groups notes under their bill (newest first). A note whose bill no
 * longer exists is treated as unlinked rather than hidden.
 */
fun groupCapturedDocuments(all: List<CapturedDocumentEntity>): CapturedDocuments {
    val bills = all.filter { it.kind == CapturedDocumentEntity.KIND_BILL }.sortedByDescending { it.createdAt }
    val billIds = bills.map { it.id }.toSet()
    val notes = all.filter { it.kind == CapturedDocumentEntity.KIND_NOTE }.sortedByDescending { it.createdAt }
    return CapturedDocuments(
        bills = bills.map { bill -> BillWithNotes(bill, notes.filter { it.linkedBillId == bill.id }) },
        unlinkedNotes = notes.filter { it.linkedBillId == null || it.linkedBillId !in billIds },
    )
}

/** Stored only on this phone: the server has no place for bill↔note links. */
class CapturedDocumentRepository(private val dao: CapturedDocumentDao) {
    fun observe(): Flow<CapturedDocuments> = dao.observeAll().map(::groupCapturedDocuments)

    suspend fun save(document: CapturedDocumentEntity): Long =
        if (document.id == 0L) dao.insert(document) else document.id.also { dao.update(document) }

    suspend fun delete(document: CapturedDocumentEntity) {
        if (document.kind == CapturedDocumentEntity.KIND_BILL) dao.unlinkNotesFrom(document.id)
        dao.deleteById(document.id)
    }

    suspend fun clear() = dao.deleteAll()
}
