package com.shopai.app.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DailyCashEntryEntity::class, DailyCashDayEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ShopAiLocalDatabase : RoomDatabase() {
    abstract fun dailyCashDao(): DailyCashDao

    companion object {
        @Volatile
        private var instance: ShopAiLocalDatabase? = null

        fun get(context: Context): ShopAiLocalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShopAiLocalDatabase::class.java,
                    "shop_ai_local.db",
                ).build().also { instance = it }
            }
    }
}
