package com.shopai.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.shopai.app.data.local.room.HandwrittenNoteEntity
import com.shopai.app.data.local.room.HandwrittenNotesDao
import com.shopai.app.data.local.room.NoteTransactionEntity
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.SYNC_FAILED
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.SYNC_NOT_SYNCED
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.SYNC_PENDING
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.SYNC_SYNCED
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.TYPE_CREDIT
import com.shopai.app.data.local.room.NoteTransactionEntity.Companion.TYPE_DEBIT
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.util.localDateToIsoInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** A handwritten page ready to be saved with its rows. */
data class NotePageToSave(
    val originalImagePath: String,
    val processedImagePath: String?,
    val imageHash: String,
    val extractedText: String,
    val transactions: List<NoteTransactionEntity>,
)

/**
 * Handwritten notes and their transactions, stored on this phone (Room).
 * Optionally mirrored into the existing Customers (credit) / Suppliers
 * (debit) ledger on the server, retried until it succeeds.
 */
class HandwrittenNotesRepository(
    private val appContext: Context,
    private val dao: HandwrittenNotesDao,
    private val transactionRepository: TransactionRepository,
) {
    fun observeTransactions(): Flow<List<NoteTransactionEntity>> = dao.observeTransactions()

    fun observeNotes(): Flow<List<HandwrittenNoteEntity>> = dao.observeNotes()

    suspend fun getNote(id: Long): HandwrittenNoteEntity? = dao.getNote(id)

    /** The already-saved page with the same image, if this photo was saved before. */
    suspend fun findSavedPage(imageHash: String): HandwrittenNoteEntity? = dao.findNoteByHash(imageHash)

    // ---- Image files ----

    private val imageDir: File get() = File(appContext.filesDir, "handwritten").apply { mkdirs() }

    /** Copies the picked/captured image unchanged; returns (path, sha-256). */
    suspend fun storeOriginal(uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
        val file = File(imageDir, "${UUID.randomUUID()}-original.jpg")
        appContext.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: error("Could not open the image")
        file.absolutePath to sha256(file)
    }

    suspend fun storeProcessed(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val file = File(imageDir, "${UUID.randomUUID()}-page.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.absolutePath
    }

    /** Removes images of pages that were discarded instead of saved. */
    suspend fun deleteFiles(paths: List<String?>) = withContext(Dispatchers.IO) {
        paths.filterNotNull().forEach { runCatching { File(it).delete() } }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Saving ----

    /**
     * Saves all pages and rows in one database transaction (all or nothing),
     * then — if asked — adds them to the Customers/Suppliers ledger.
     */
    suspend fun saveAll(
        pages: List<NotePageToSave>,
        manual: List<NoteTransactionEntity>,
        syncToLedger: Boolean,
    ): SaveOutcome {
        fun prepare(row: NoteTransactionEntity) = row.copy(
            ledgerSyncStatus = if (syncToLedger && row.transactionType in listOf(TYPE_CREDIT, TYPE_DEBIT)) SYNC_PENDING else SYNC_NOT_SYNCED,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.saveNotesWithTransactions(
            notes = pages.map {
                HandwrittenNoteEntity(
                    originalImagePath = it.originalImagePath,
                    processedImagePath = it.processedImagePath,
                    imageHash = it.imageHash,
                    extractedText = it.extractedText,
                    totalTransactions = it.transactions.size,
                )
            },
            transactionsByNote = pages.map { page -> page.transactions.map(::prepare) },
            manualTransactions = manual.map(::prepare),
        )
        val failed = if (syncToLedger) syncPending() else 0
        return SaveOutcome(saved = pages.sumOf { it.transactions.size } + manual.size, syncFailed = failed)
    }

    data class SaveOutcome(val saved: Int, val syncFailed: Int)

    suspend fun update(row: NoteTransactionEntity) = dao.updateTransaction(row.copy(updatedAt = System.currentTimeMillis()))

    suspend fun addManual(row: NoteTransactionEntity): Long = dao.insertTransaction(row)

    suspend fun delete(row: NoteTransactionEntity) = dao.deleteTransaction(row.id)

    /**
     * Adds not-yet-synced rows to the server ledger. Returns how many failed
     * (they stay FAILED and are retried next time). Never posts a row twice.
     */
    suspend fun syncPending(): Int {
        var failed = 0
        for (row in dao.transactionsToSync()) {
            val amount = row.amount?.toBigDecimalOrNull()
            if (amount == null || row.transactionType !in listOf(TYPE_CREDIT, TYPE_DEBIT) || row.ledgerTransactionId != null) {
                if (row.ledgerTransactionId != null) dao.updateTransaction(row.copy(ledgerSyncStatus = SYNC_SYNCED))
                continue
            }
            val date = row.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            // The ledger's due date must be today or later; older dates go in the description.
            val due = date?.takeIf { !it.isBefore(LocalDate.now()) }?.let { localDateToIsoInstant(it) }
            val description = listOfNotNull(
                "Handwritten note" + (row.date?.let { " – $it" } ?: ""),
                row.description?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            val result = runCatching {
                if (row.transactionType == TYPE_CREDIT) {
                    transactionRepository.createCredit(
                        CreateCreditInput(customerName = row.personName, amount = amount.toDouble(), description = description, dueDate = due),
                        source = com.shopai.app.books.model.TxnSource.OCR,
                    ).id
                } else {
                    transactionRepository.createDebit(
                        CreateDebitInput(supplierName = row.personName, amount = amount.toDouble(), description = description, dueDate = due),
                        source = com.shopai.app.books.model.TxnSource.OCR,
                    ).id
                }
            }
            result.onSuccess { id ->
                dao.updateTransaction(row.copy(ledgerTransactionId = id, ledgerSyncStatus = SYNC_SYNCED, updatedAt = System.currentTimeMillis()))
            }.onFailure {
                failed++
                dao.updateTransaction(row.copy(ledgerSyncStatus = SYNC_FAILED, updatedAt = System.currentTimeMillis()))
            }
        }
        return failed
    }

    /** On logout: this phone's notes belong to the account that made them. */
    suspend fun clear() {
        val files = dao.allNotes().flatMap { listOf(it.originalImagePath, it.processedImagePath) }
        dao.deleteAllTransactions()
        dao.deleteAllNotes()
        deleteFiles(files)
    }
}
