package com.shopai.app.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DailyCashEntryEntity::class, DailyCashDayEntity::class, VoiceCheckinPrefsEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class ShopAiLocalDatabase : RoomDatabase() {
    abstract fun dailyCashDao(): DailyCashDao
    abstract fun voiceCheckinDao(): VoiceCheckinDao

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

        fun get(context: Context): ShopAiLocalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShopAiLocalDatabase::class.java,
                    "shop_ai_local.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
