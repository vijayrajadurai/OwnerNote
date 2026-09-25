package com.shopai.app.data.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_cash_days")
data class DailyCashDayEntity(
    @PrimaryKey val date: String,
    val status: String,
    val submittedAt: String?,
    val openingBalance: Double? = null,
)
