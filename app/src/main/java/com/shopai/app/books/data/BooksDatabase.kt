package com.shopai.app.books.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * OwnerNote Books — its own database file, separate from `shop_ai_local.db`
 * (Daily Cash Note, notes, OCR), so the existing features are never touched
 * by billing / inventory / accounting schema changes.
 */
@Database(
    entities = [
        BusinessEntity::class,
        BranchEntity::class,
        WarehouseEntity::class,
        UserEntity::class,
        PartyEntity::class,
        CategoryEntity::class,
        BrandEntity::class,
        UnitEntity::class,
        HsnSacEntity::class,
        ProductEntity::class,
        BatchEntity::class,
        MoneyAccountEntity::class,
        TxnEntity::class,
        TxnItemEntity::class,
        PostingEntity::class,
        StockMovementEntity::class,
        AllocationEntity::class,
        NumberSeriesEntity::class,
        AuditEntity::class,
        OutboxEntity::class,
        DraftEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class BooksDatabase : RoomDatabase() {
    abstract fun dao(): BooksDao

    companion object {
        @Volatile
        private var instance: BooksDatabase? = null

        fun get(context: Context): BooksDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, BooksDatabase::class.java, "ownernote_books.db")
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }

        /**
         * v2 (owner's inventory fields, 20 Oct 2026): GST left blank is "not specified" (never 0%), purchase / sales units,
         * more than one unit conversion, and the product's kind of business. Products saved before keep everything; one whose
         * GST was the form's pre-selected 0% (taxType GST, rate 0) is "not specified" — no 0% was ever chosen for it.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN gstSpecified INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE products ADD COLUMN moreUnits TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN purchaseUnit TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN salesUnit TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN businessKind TEXT")
                db.execSQL("UPDATE products SET gstSpecified = 0 WHERE taxType = 'GST' AND gstBp = 0")
            }
        }
    }
}
