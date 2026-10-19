package com.shopai.app.ui.theme

import androidx.compose.ui.graphics.Color

// Mirrors apps/mobile/src/theme/colors.ts — single source for Compose tokens.
// Green UI (owner, 19 Oct 2026): green actions on a soft green wash — no black (owner: "black colour remove pannu").
val Primary = Color(0xFF1E6B4E)
val PrimaryDark = Color(0xFF145A3F)
val PrimaryLight = Color(0xFFD6E6D9)
val PrimaryMuted = Color(0x1A1E6B4E)
val OnPrimary = Color(0xFFFFFFFF)
val Accent = Color(0xFF2E9F6E)
// Owner Note redesign (green + white): mint-white app background.
val Background = Color(0xFFE8F1E9)
val Surface = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF16201B)
val TextSecondary = Color(0xFF4D4D52)
val Border = Color(0xFFC9DBCD)
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

// Redesign: the brand gradient (buttons) — deep green to emerald — and its soft glow.
val BrandGradientStart = Color(0xFF1E6B4E)
val BrandGradientEnd = Color(0xFF2E9F6E)
val BrandGlow = Color(0x331E6B4E)

// Redesign: the soft page wash (white → mint → pale sea-green) and the glass surfaces on it.
val HeaderGreenStart = Color(0xFF1E6B4E)
val HeaderGreenEnd = Color(0xFF2E9F6E)
val AppBackgroundTop = Color(0xFFD3EBD8)
val AppBackgroundMid = Color(0xFFEAF3EB)
val AppBackgroundBottom = Color(0xFFE3ECE4)
val GlassFill = Color(0x9EFFFFFF)
val GlassBorder = Color(0xB3FFFFFF)
val GlassShadow = Color(0x141E6B4E)
