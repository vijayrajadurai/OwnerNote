package com.shopai.app.books.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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
                    .build().also { instance = it }
            }
    }
}
