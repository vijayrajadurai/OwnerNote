package com.shopai.app.ui.components

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.theme.OnPrimary
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import com.shopai.app.ui.theme.BrandGlow
import com.shopai.app.ui.theme.BrandGradientEnd
import com.shopai.app.ui.theme.BrandGradientStart
import com.shopai.app.ui.theme.OutfitFamily
import com.shopai.app.ui.theme.AppBackgroundBottom
import com.shopai.app.ui.theme.AppBackgroundMid
import com.shopai.app.ui.theme.AppBackgroundTop
import com.shopai.app.ui.theme.Background
import com.shopai.app.ui.theme.GlassBorder
import com.shopai.app.ui.theme.GlassFill
import com.shopai.app.ui.theme.GlassShadow

/**
 * The Owner Note primary action: a gradient pill (deep green → emerald)
 * with a soft green glow, pressing in slightly when tapped.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val shape = RoundedCornerShape(percent = 50)
    val active = enabled && !loading
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "primaryPress")
    Box(
        modifier = modifier
            .then(Modifier.fillMaxWidth())
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled || loading) 1f else 0.6f
            }
            .shadow(
                elevation = if (active) 10.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = BrandGlow,
                spotColor = BrandGlow,
            )
            .clip(shape)
            .background(Brush.linearGradient(listOf(BrandGradientStart, BrandGradientEnd)))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = active,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = OnPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = label,
                color = OnPrimary,
                fontFamily = OutfitFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.16.sp,
            )
        }
    }
}

/**
 * The Owner Note secondary ("ghost") button: a pill with a light glass
 * fill, a soft green border and green text. Same parameters as Material's
 * OutlinedButton, so screens only switch the import.
 */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(percent = 50),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.primary,
    ),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.28f else 0.12f)),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) {
            content()
        }
    }
}

@Composable
fun ShopTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            readOnly = readOnly,
            enabled = enabled,
            isError = error != null,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** True while the light (green + white) theme is showing. */
@Composable
fun isRedesignLight(): Boolean = MaterialTheme.colorScheme.background == Background

/**
 * The page background: in the light theme a soft white → mint wash
 * (the redesign), in dark mode the plain theme background.
 */
@Composable
fun Modifier.appBackground(): Modifier =
    if (isRedesignLight()) {
        background(Brush.linearGradient(listOf(AppBackgroundTop, AppBackgroundMid, AppBackgroundBottom)))
    } else {
        background(MaterialTheme.colorScheme.background)
    }

/**
 * A glass surface: translucent white with a bright edge and a soft green
 * shadow (light theme); the plain surface colour in dark mode.
 */
@Composable
fun Modifier.glassSurface(shape: Shape = RoundedCornerShape(24.dp)): Modifier =
    if (isRedesignLight()) {
        shadow(elevation = 10.dp, shape = shape, clip = false, ambientColor = GlassShadow, spotColor = GlassShadow)
            .clip(shape)
            .background(GlassFill)
            .border(1.dp, GlassBorder, shape)
    } else {
        clip(shape).background(MaterialTheme.colorScheme.surface)
    }

@Composable
fun ShopCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface()
            .padding(16.dp),
    ) {
        content()
    }
}

enum class BottomNavTab(@androidx.annotation.StringRes val labelRes: Int, val icon: ImageVector) {
    Home(R.string.nav_home, Icons.Outlined.Home),
    Discover(R.string.nav_discover, Icons.Outlined.Explore),
    Customers(R.string.nav_customers, Icons.Outlined.Groups),
    Suppliers(R.string.nav_suppliers, Icons.Outlined.Inventory2),
    More(R.string.nav_more, Icons.Outlined.MoreHoriz),
}

/**
 * The tab bar (owner, 18 Oct 2026, "Kadai Bold"): a dark bar floating over the screen, every tab with its icon and name; the
 * open tab's icon sits in a green pill.
 */
@Composable
fun BottomNavBar(
    active: BottomNavTab,
    onTabSelected: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barShape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 16.dp, shape = barShape, ambientColor = Color(0xFF1A1530), spotColor = Color(0xFF1A1530))
                .clip(barShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF2A2350), Color(0xFF1A1530))))
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomNavTab.entries.forEach { tab ->
                val isActive = tab == active
                val label = stringResource(tab.labelRes)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onTabSelected(tab) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 30.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isActive) Brush.verticalGradient(listOf(Color(0xFF19B97F), Color(0xFF0B8A5C)))
                                else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = label,
                            tint = if (isActive) Color.White else Color(0xFF9C96B5),
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Text(
                        text = label,
                        fontSize = 10.5.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) Color.White else Color(0xFF9C96B5),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
fun EyebrowLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun StatNumber(
    label: String,
    amount: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        EyebrowLabel(text = label)
        Text(
            text = amount,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
    }
}
