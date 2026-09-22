package com.shopai.app.ui.theme

import androidx.compose.ui.graphics.Color

// Mirrors apps/mobile/src/theme/colors.ts — single source for Compose tokens.
val Primary = Color(0xFF1E6B4E)
val PrimaryDark = Color(0xFF154F39)
val PrimaryLight = Color(0xFFBFE6D2)
val PrimaryMuted = Color(0x1A1E6B4E)
val OnPrimary = Color(0xFFFFFFFF)
val Accent = Color(0xFF2E9F6E)
val Background = Color(0xFFF7F5EF)
val Surface = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF1C1C1A)
val TextSecondary = Color(0xFF7A766C)
val Border = Color(0xFFE8E3D8)
val Danger = Color(0xFFD6503C)
val DangerMuted = Color(0x1AD6503C)
val Success = Color(0xFF1E6B4E)
val SuccessMuted = Color(0x1A1E6B4E)
val Warning = Color(0xFFC4841E)
val Pressure = Color(0xFFD9793C)

// Ledger semantics used across customer/supplier flows:
// credit / vaanganum / to collect = money coming in (green).
// debit / kodukkanum / to pay = money going out (red).
val LedgerCredit = Success
val LedgerCreditMuted = SuccessMuted
val LedgerDebit = Danger
val LedgerDebitMuted = DangerMuted
val LedgerPending = Danger
