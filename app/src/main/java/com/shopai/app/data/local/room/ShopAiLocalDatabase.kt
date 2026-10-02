package com.shopai.app.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DailyCashEntryEntity::class,
        DailyCashDayEntity::class,
        VoiceCheckinPrefsEntity::class,
        CapturedDocumentEntity::class,
        HandwrittenNoteEntity::class,
        NoteTransactionEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class ShopAiLocalDatabase : RoomDatabase() {
    abstract fun dailyCashDao(): DailyCashDao
    abstract fun voiceCheckinDao(): VoiceCheckinDao
    abstract fun capturedDocumentDao(): CapturedDocumentDao
    abstract fun handwrittenNotesDao(): HandwrittenNotesDao

    companion object {
        @Volatile
        private var instance: ShopAiLocalDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_cash_days ADD COLUMN openingBalance REAL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS voice_checkin_prefs (
                        id INTEGER NOT NULL PRIMARY KEY,
                        enabled INTEGER NOT NULL DEFAULT 0,
                        slot8AmEnabled INTEGER NOT NULL DEFAULT 1,
                        slot12PmEnabled INTEGER NOT NULL DEFAULT 1,
                        slot4PmEnabled INTEGER NOT NULL DEFAULT 1,
                        slot8PmEnabled INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS captured_documents (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        kind TEXT NOT NULL,
                        name TEXT NOT NULL,
                        amount TEXT NOT NULL,
                        date TEXT,
                        linkedBillId INTEGER,
                        ledgerTransactionId TEXT,
                        ledgerType TEXT,
                        details TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_captured_documents_linkedBillId ON captured_documents (linkedBillId)",
                )
            }
        }

        // Handwritten notes → one row per transaction. Notes saved by the older
        // single-note form move across as "Needs Review" (they had no direction).
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS handwritten_notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        originalImagePath TEXT NOT NULL,
                        processedImagePath TEXT,
                        imageHash TEXT NOT NULL,
                        extractedText TEXT NOT NULL,
                        processingStatus TEXT NOT NULL,
                        totalTransactions INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_handwritten_notes_imageHash ON handwritten_notes (imageHash)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceNoteId INTEGER,
                        personName TEXT NOT NULL,
                        amount TEXT,
                        currency TEXT NOT NULL,
                        date TEXT,
                        transactionType TEXT NOT NULL,
                        description TEXT,
                        notes TEXT,
                        originalOcrText TEXT,
                        originalPersonName TEXT,
                        originalAmountText TEXT,
                        originalDate TEXT,
                        originalType TEXT,
                        correctedByUser INTEGER NOT NULL,
                        nameConfidence INTEGER,
                        amountConfidence INTEGER,
                        dateConfidence INTEGER,
                        typeConfidence INTEGER,
                        reviewStatus TEXT NOT NULL,
                        boxLeft REAL,
                        boxTop REAL,
                        boxRight REAL,
                        boxBottom REAL,
                        linkedBillId INTEGER,
                        ledgerTransactionId TEXT,
                        ledgerSyncStatus TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_transactions_sourceNoteId ON note_transactions (sourceNoteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_transactions_personName ON note_transactions (personName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_transactions_linkedBillId ON note_transactions (linkedBillId)")
                db.execSQL(
                    """
                    INSERT INTO note_transactions (
                        sourceNoteId, personName, amount, currency, date, transactionType,
                        originalPersonName, originalAmountText, originalDate, correctedByUser,
                        reviewStatus, linkedBillId, ledgerSyncStatus, createdAt, updatedAt
                    )
                    SELECT NULL, name, amount, 'INR', date, 'UNKNOWN',
                        name, amount, date, 1,
                        'NEEDS_REVIEW', linkedBillId, 'NOT_SYNCED', createdAt, createdAt
                    FROM captured_documents WHERE kind = 'NOTE'
                    """.trimIndent(),
                )
                db.execSQL("DELETE FROM captured_documents WHERE kind = 'NOTE'")
            }
        }

        fun get(context: Context): ShopAiLocalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShopAiLocalDatabase::class.java,
                    "shop_ai_local.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
    }
}
