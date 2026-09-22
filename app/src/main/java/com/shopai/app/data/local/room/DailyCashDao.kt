package com.shopai.app.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DailyCashDao {
    @Query("SELECT * FROM daily_cash_entries WHERE date = :dateKey ORDER BY createdAt DESC")
    suspend fun listEntriesByDate(dateKey: String): List<DailyCashEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: DailyCashEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<DailyCashEntryEntity>)

    @Update
    suspend fun updateEntry(entry: DailyCashEntryEntity)

    @Query("SELECT * FROM daily_cash_entries WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: String): DailyCashEntryEntity?

    @Query("DELETE FROM daily_cash_entries WHERE id = :id")
    suspend fun deleteEntry(id: String)

    @Query("DELETE FROM daily_cash_entries WHERE date = :dateKey")
    suspend fun deleteEntriesForDate(dateKey: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureDayOpen(day: DailyCashDayEntity)

    @Query("SELECT * FROM daily_cash_days WHERE date = :dateKey LIMIT 1")
    suspend fun getDay(dateKey: String): DailyCashDayEntity?

    @Query(
        """
        SELECT DISTINCT e.date FROM daily_cash_entries e
        LEFT JOIN daily_cash_days d ON e.date = d.date
        WHERE e.date < :today AND (d.status IS NULL OR d.status = 'OPEN')
        ORDER BY e.date ASC
        """,
    )
    suspend fun listDatesNeedingSubmit(today: String): List<String>

    @Query(
        """
        UPDATE daily_cash_days
        SET status = 'SUBMITTED', submittedAt = :submittedAt
        WHERE date = :dateKey
        """,
    )
    suspend fun markDaySubmitted(dateKey: String, submittedAt: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(day: DailyCashDayEntity)
}
