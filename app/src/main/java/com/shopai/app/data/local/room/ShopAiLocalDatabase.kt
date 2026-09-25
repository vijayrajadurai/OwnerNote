package com.shopai.app.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DailyCashEntryEntity::class, DailyCashDayEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class ShopAiLocalDatabase : RoomDatabase() {
    abstract fun dailyCashDao(): DailyCashDao

    companion object {
        @Volatile
        private var instance: ShopAiLocalDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_cash_days ADD COLUMN openingBalance REAL")
            }
        }

        fun get(context: Context): ShopAiLocalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShopAiLocalDatabase::class.java,
                    "shop_ai_local.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
