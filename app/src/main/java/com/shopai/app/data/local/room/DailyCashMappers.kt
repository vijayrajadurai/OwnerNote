package com.shopai.app.data.local.room

import com.shopai.app.data.model.DailyCashEntry
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.data.model.DailyCashPaymentMode

fun DailyCashEntryEntity.toDomain(): DailyCashEntry =
    DailyCashEntry(
        id = id,
        date = date,
        type = DailyCashEntryType.valueOf(type),
        amount = amount,
        paymentMode = DailyCashPaymentMode.valueOf(paymentMode),
        note = note,
        createdAt = createdAt,
    )

fun DailyCashEntry.toEntity(): DailyCashEntryEntity =
    DailyCashEntryEntity(
        id = id,
        date = date,
        type = type.name,
        amount = amount,
        paymentMode = paymentMode.name,
        note = note,
        createdAt = createdAt,
    )
