package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R

/** Which friendly screen: no internet, a save that failed, nothing here yet, or Kai couldn't hear. */
enum class FriendlyKind { NET, SAVE, EMPTY, MIC }

/**
 * Error and empty screens, version 7b (owner, 22 Oct 2026): a calm icon, what happened in plain words, what is safe
 * ("Neenga pottadhu edhuvum azhiyala"), one main button and one other, and the technical detail only when asked for.
 */
@Composable
fun FriendlyState(
    kind: FriendlyKind,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    safe: String? = null,
    safeGood: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    details: String? = null,
) {
    var showDetails by remember { mutableStateOf(false) }
    val (tint, halo, ring) = when (kind) {
        FriendlyKind.NET -> Triple(Color(0xFFB45309), Color(0xFFFEF3C7), Color(0xFFFDE68A))
        FriendlyKind.SAVE -> Triple(Color(0xFFB91C1C), Color(0xFFFEE2E2), Color(0xFFFECACA))
        FriendlyKind.EMPTY -> Triple(Color(0xFF0F766E), Color(0xFFCCFBF1), Color(0xFF99F6E4))
        FriendlyKind.MIC -> Triple(Color(0xFF6D28D9), Color(0xFFEDE9FE), Color(0xFFDDD6FE))
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(128.dp).clip(CircleShape).background(halo), contentAlignment = Alignment.Center) {
            Box(Modifier.size(104.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
                Box(Modifier.size(84.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (kind) {
                            FriendlyKind.NET -> Icons.Outlined.CloudOff
                            FriendlyKind.SAVE -> Icons.Outlined.ErrorOutline
                            FriendlyKind.EMPTY -> Icons.Outlined.PersonAddAlt
                            FriendlyKind.MIC -> Icons.Outlined.MicOff
                        },
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color(0xFF111827), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Text(body, color = Color(0xFF4B5563), fontSize = 15.5.sp, lineHeight = 23.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        }
        safe?.let {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (safeGood) Color(0xFFECFDF5) else Color(0xFFFEF2F2))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ink = if (safeGood) Color(0xFF047857) else Color(0xFFB91C1C)
                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                Text(it, color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF111827)).clickable(onClick = onPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Text(primaryLabel, color = Color.White, fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (secondaryLabel != null && onSecondary != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFECEAE5), RoundedCornerShape(16.dp))
                        .clickable(onClick = onSecondary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(secondaryLabel, color = Color(0xFF111827), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!details.isNullOrBlank()) {
            Text(
                stringResource(if (showDetails) R.string.friendly_details_hide else R.string.friendly_details_show),
                color = Color(0xFF6B7280),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showDetails = !showDetails }.padding(8.dp),
            )
            if (showDetails) {
                Text(
                    details,
                    color = Color(0xFF6B7280),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, Color(0xFFECEAE5), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
