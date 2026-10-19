package com.shopai.app.ui.theme

import androidx.compose.ui.graphics.Color

// Mirrors apps/mobile/src/theme/colors.ts — single source for Compose tokens.
// Green UI (owner, 19 Oct 2026): the green wash is the page; one deep navy for actions, white hairline cards ("international level").
val Primary = Color(0xFF1F2B5C)
val PrimaryDark = Color(0xFF141C3D)
val PrimaryLight = Color(0xFFE6E9F4)
val PrimaryMuted = Color(0x1A1F2B5C)
val OnPrimary = Color(0xFFFFFFFF)
val Accent = Color(0xFF2E9F6E)
// Owner Note redesign (green + white): mint-white app background.
val Background = Color(0xFFE8F1E9)
val Surface = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF0F1B14)
val TextSecondary = Color(0xFF5B6670)
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
val BrandGradientStart = Color(0xFF1F2B5C)
val BrandGradientEnd = Color(0xFF1F2B5C)
val BrandGlow = Color(0x331F2B5C)

// Redesign: the soft page wash (white → mint → pale sea-green) and the glass surfaces on it.
val HeaderGreenStart = Color(0xFF1E6B4E)
val HeaderGreenEnd = Color(0xFF2E9F6E)
val AppBackgroundTop = Color(0xFFD3EBD8)
val AppBackgroundMid = Color(0xFFEAF3EB)
val AppBackgroundBottom = Color(0xFFE3ECE4)
val GlassFill = Color(0x9EFFFFFF)
val GlassBorder = Color(0xB3FFFFFF)
val GlassShadow = Color(0x141E6B4E)
