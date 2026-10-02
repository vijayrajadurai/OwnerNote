package com.shopai.app.data.local.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A shop bill or handwritten note captured with the camera (or typed in).
 * Notes may point at the bill they belong to through [linkedBillId].
 */
@Entity(
    tableName = "captured_documents",
    indices = [Index("linkedBillId")],
)
data class CapturedDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [KIND_BILL] or [KIND_NOTE]. */
    val kind: String,
    val name: String,
    /** Plain decimal, e.g. "599.00" — never a Double, so sums stay exact. */
    val amount: String,
    /** ISO date (yyyy-MM-dd) written on the document, if known. */
    val date: String?,
    val linkedBillId: Long? = null,
    /** Ledger entry created on the server when a bill was saved. */
    val ledgerTransactionId: String? = null,
    /** "DEBIT" (supplier bill) or "CREDIT" (customer bill). */
    val ledgerType: String? = null,
    /** Itemised lines saved with a bill. */
    val details: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_BILL = "BILL"
        const val KIND_NOTE = "NOTE"
    }
}

@Dao
interface CapturedDocumentDao {
    @Query("SELECT * FROM captured_documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CapturedDocumentEntity>>

    @Insert
    suspend fun insert(document: CapturedDocumentEntity): Long

    @Update
    suspend fun update(document: CapturedDocumentEntity)

    @Query("DELETE FROM captured_documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Keeps notes when their bill is removed; they just become unlinked. */
    @Query("UPDATE captured_documents SET linkedBillId = NULL WHERE linkedBillId = :billId")
    suspend fun unlinkNotesFrom(billId: Long)

    @Query("DELETE FROM captured_documents")
    suspend fun deleteAll()
}
