package com.shopai.app.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_cash_entries")
data class DailyCashEntryEntity(
    @PrimaryKey val id: String,
    val date: String,
    val type: String,
    val amount: Double,
    val paymentMode: String,
    val note: String?,
    val createdAt: String,
)
