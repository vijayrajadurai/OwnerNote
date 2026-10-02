package com.shopai.app.data.local.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One photographed/uploaded handwritten page. */
@Entity(tableName = "handwritten_notes", indices = [Index("imageHash")])
data class HandwrittenNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The image exactly as captured/uploaded (never modified). */
    val originalImagePath: String,
    /** Rotated/cropped/straightened copy the OCR boxes refer to. */
    val processedImagePath: String?,
    /** SHA-256 of the original image, to spot the same page saved twice. */
    val imageHash: String,
    /** All text OCR read from the page, line by line. */
    val extractedText: String,
    val processingStatus: String = STATUS_SAVED,
    val totalTransactions: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_SAVED = "SAVED"
    }
}

/**
 * One person / one transaction from a handwritten note (or added by hand).
 * Keeps what OCR read next to what the owner confirmed, for traceability.
 */
@Entity(
    tableName = "note_transactions",
    indices = [Index("sourceNoteId"), Index("personName"), Index("linkedBillId")],
)
data class NoteTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceNoteId: Long?,
    val personName: String,
    /** Plain decimal ("1500.00"); never a Double so sums stay exact. Null only for migrated rows needing review. */
    val amount: String?,
    val currency: String = "INR",
    /** ISO date (yyyy-MM-dd), or null for "Not specified". */
    val date: String?,
    /** CREDIT (they owe me), DEBIT (I owe them) or UNKNOWN (needs review). */
    val transactionType: String,
    val description: String?,
    val notes: String? = null,
    // What OCR originally read, kept even after the owner corrects it.
    val originalOcrText: String?,
    val originalPersonName: String?,
    val originalAmountText: String?,
    val originalDate: String?,
    val originalType: String?,
    val correctedByUser: Boolean,
    val nameConfidence: Int?,
    val amountConfidence: Int?,
    val dateConfidence: Int?,
    val typeConfidence: Int?,
    /** VERIFIED (read clearly), USER_VERIFIED (checked/fixed by owner) or NEEDS_REVIEW. */
    val reviewStatus: String,
    // Where the line is on the page (fractions of the processed image).
    val boxLeft: Float? = null,
    val boxTop: Float? = null,
    val boxRight: Float? = null,
    val boxBottom: Float? = null,
    val linkedBillId: Long? = null,
    /** Id of the matching Customers/Suppliers ledger entry on the server. */
    val ledgerTransactionId: String? = null,
    /** NOT_SYNCED (owner chose not to), PENDING, SYNCED or FAILED. */
    val ledgerSyncStatus: String = SYNC_NOT_SYNCED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val TYPE_CREDIT = "CREDIT"
        const val TYPE_DEBIT = "DEBIT"
        const val TYPE_UNKNOWN = "UNKNOWN"
        const val STATUS_VERIFIED = "VERIFIED"
        const val STATUS_USER_VERIFIED = "USER_VERIFIED"
        const val STATUS_NEEDS_REVIEW = "NEEDS_REVIEW"
        const val SYNC_NOT_SYNCED = "NOT_SYNCED"
        const val SYNC_PENDING = "PENDING"
        const val SYNC_SYNCED = "SYNCED"
        const val SYNC_FAILED = "FAILED"
    }
}

@Dao
interface HandwrittenNotesDao {
    @Query("SELECT * FROM note_transactions ORDER BY COALESCE(date, '9999-12-31') ASC, id ASC")
    fun observeTransactions(): Flow<List<NoteTransactionEntity>>

    @Query("SELECT * FROM handwritten_notes ORDER BY createdAt DESC")
    fun observeNotes(): Flow<List<HandwrittenNoteEntity>>

    @Query("SELECT * FROM handwritten_notes WHERE imageHash = :hash LIMIT 1")
    suspend fun findNoteByHash(hash: String): HandwrittenNoteEntity?

    @Query("SELECT * FROM handwritten_notes WHERE id = :id LIMIT 1")
    suspend fun getNote(id: Long): HandwrittenNoteEntity?

    @Query("SELECT * FROM note_transactions WHERE ledgerSyncStatus IN ('PENDING', 'FAILED')")
    suspend fun transactionsToSync(): List<NoteTransactionEntity>

    @Query("SELECT * FROM handwritten_notes")
    suspend fun allNotes(): List<HandwrittenNoteEntity>

    @Insert
    suspend fun insertNote(note: HandwrittenNoteEntity): Long

    @Insert
    suspend fun insertTransactions(transactions: List<NoteTransactionEntity>): List<Long>

    @Insert
    suspend fun insertTransaction(transaction: NoteTransactionEntity): Long

    @Update
    suspend fun updateTransaction(transaction: NoteTransactionEntity)

    @Query("DELETE FROM note_transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Long)

    @Query("DELETE FROM note_transactions")
    suspend fun deleteAllTransactions()

    @Query("DELETE FROM handwritten_notes")
    suspend fun deleteAllNotes()

    /** Saves the pages and their rows together: all or nothing. */
    @Transaction
    suspend fun saveNotesWithTransactions(
        notes: List<HandwrittenNoteEntity>,
        transactionsByNote: List<List<NoteTransactionEntity>>,
        manualTransactions: List<NoteTransactionEntity>,
    ): List<Long> {
        val ids = mutableListOf<Long>()
        notes.forEachIndexed { index, note ->
            val noteId = insertNote(note)
            ids += insertTransactions(transactionsByNote[index].map { it.copy(sourceNoteId = noteId) })
        }
        ids += insertTransactions(manualTransactions)
        return ids
    }
}
