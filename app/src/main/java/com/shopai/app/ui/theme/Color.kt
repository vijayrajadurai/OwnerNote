package com.shopai.app.ui.theme

import androidx.compose.ui.graphics.Color

// Mirrors apps/mobile/src/theme/colors.ts — single source for Compose tokens.
val Primary = Color(0xFF1E6B4E)
val PrimaryDark = Color(0xFF154F39)
val PrimaryLight = Color(0xFFD9F2E5)
val PrimaryMuted = Color(0x1A1E6B4E)
val OnPrimary = Color(0xFFFFFFFF)
val Accent = Color(0xFF2E9F6E)
// Owner Note redesign (green + white): mint-white app background.
val Background = Color(0xFFF1F9F5)
val Surface = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF12281F)
val TextSecondary = Color(0xFF6B7F76)
val Border = Color(0xFFD6E7DE)
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
val BrandGradientStart = Primary
val BrandGradientEnd = Color(0xFF2FB27A)
val BrandGlow = Color(0x66FFFFFF)

// Redesign: the soft page wash (white → mint → pale sea-green) and the glass surfaces on it.
val HeaderGreenStart = Color(0xFF075C45)
val HeaderGreenEnd = Color(0xFF16845F)
val AppBackgroundTop = Color(0xFFF1F9F5)
val AppBackgroundMid = Color(0xFFF1F9F5)
val AppBackgroundBottom = Color(0xFFEAF6F0)
val GlassFill = Color(0xB8FFFFFF)
val GlassBorder = Color(0xE6FFFFFF)
val GlassShadow = Color(0x2E1E6B4E)
