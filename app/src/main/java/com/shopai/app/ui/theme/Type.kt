package com.shopai.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shopai.app.R

// Owner Note redesign fonts (bundled — work offline):
// Outfit for headings, titles and labels; Inter for body text.
val OutfitFamily = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
    Font(R.font.outfit_extrabold, FontWeight.ExtraBold),
)

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val base = Typography()

// Same sizes as before; only the typefaces change.
val ShopAiTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = OutfitFamily),
    displayMedium = base.displayMedium.copy(fontFamily = OutfitFamily),
    displaySmall = base.displaySmall.copy(fontFamily = OutfitFamily),
    headlineLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
    ),
    headlineMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = base.headlineSmall.copy(fontFamily = OutfitFamily),
    titleLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.ExtraBold,
    ),
    titleMedium = base.titleMedium.copy(fontFamily = OutfitFamily),
    titleSmall = base.titleSmall.copy(fontFamily = OutfitFamily),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodySmall = base.bodySmall.copy(fontFamily = InterFamily),
    labelLarge = base.labelLarge.copy(fontFamily = OutfitFamily),
    labelMedium = base.labelMedium.copy(fontFamily = OutfitFamily),
    labelSmall = TextStyle(
        fontFamily = OutfitFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
    ),
)
