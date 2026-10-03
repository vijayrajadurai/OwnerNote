package com.shopai.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.ui.theme.BrandGlow
import com.shopai.app.ui.theme.BrandGradientEnd
import com.shopai.app.ui.theme.BrandGradientStart
import com.shopai.app.ui.theme.OnPrimary
import com.shopai.app.ui.theme.OutfitFamily
import com.shopai.app.ui.theme.TextPrimary
import com.shopai.app.ui.theme.TextSecondary

val AuthBackgroundBrush = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFEEFDF7)))

@Composable
fun AuthBrandHeader(
    modifier: Modifier = Modifier,
    tagline: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .shadow(elevation = 8.dp, shape = CircleShape, clip = false, ambientColor = BrandGlow, spotColor = BrandGlow)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Color(0xFFE3EDE8), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.cd_app_icon),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.brand_name),
            fontFamily = OutfitFamily,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        if (tagline != null) {
            Text(
                text = tagline,
                fontFamily = OutfitFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
fun GradientActionButton(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    val active = enabled && !loading
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer { alpha = if (enabled || loading) 1f else 0.6f }
            .shadow(
                elevation = if (active) 10.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = BrandGlow,
                spotColor = BrandGlow,
            )
            .clip(shape)
            .background(Brush.linearGradient(listOf(BrandGradientStart, BrandGradientEnd)))
            .clickable(enabled = active, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = OnPrimary,
                strokeWidth = 2.5.dp,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    color = OnPrimary,
                    fontFamily = OutfitFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = OnPrimary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp),
                )
            }
        }
    }
}
