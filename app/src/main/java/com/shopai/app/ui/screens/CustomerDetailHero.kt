package com.shopai.app.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.util.formatInr

private val HeroInk = Color(0xFF111827)

/**
 * Customer detail, version 4b (owner, 22 Oct 2026): who it is, the balance big on a dark card with what came in so far,
 * "Panam vandhuchu" and "Kadan kudunga", one green WhatsApp reminder button, then Statement PDF and Sale bill. Each button
 * opens the same flow the screen already had — nothing here saves by itself.
 */
@Composable
fun CustomerHeroSection(
    name: String,
    phone: String?,
    entries: Int,
    pending: Double,
    total: Double,
    lateDays: Int,
    onPaymentIn: (() -> Unit)?,
    onCredit: () -> Unit,
    onWhatsApp: () -> Unit,
    onStatement: () -> Unit,
    onSaleBill: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFFDE7CF)),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.trim().take(1).uppercase(), color = Color(0xFF92400E), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.weight(1f)) {
                Text(name, color = HeroInk, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(phone?.takeIf { it.isNotBlank() }, stringResource(R.string.party_customer_label), stringResource(R.string.party_entries_count, entries)).joinToString(" · "),
                    color = Color(0xFF6B7280),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x2E111827), spotColor = Color(0x2E111827))
                .clip(RoundedCornerShape(24.dp))
                .background(HeroInk)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.party_to_receive).uppercase(),
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.weight(1f),
                )
                if (lateDays > 0) {
                    Text(
                        stringResource(R.string.party_days_late, lateDays),
                        color = Color(0xFFFCA5A5),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0x29EF4444)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(formatInr(pending), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            if (total > 0) {
                val paid = (total - pending).coerceAtLeast(0.0)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x1FFFFFFF))) {
                        Box(
                            Modifier
                                .fillMaxWidth((paid / total).toFloat().coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF34D399)),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.party_came_in, formatInr(paid)), color = Color(0xFFD1D5DB), fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.party_total_label, formatInr(total)), color = Color(0xFFD1D5DB), fontSize = 12.5.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onPaymentIn != null) {
                    HeroButton(stringResource(R.string.party_payment_in), Icons.Filled.ArrowDownward, Color(0xFF15803D), Color.White, HeroInk, Modifier.weight(1f), onPaymentIn)
                }
                HeroButton(stringResource(R.string.party_give_credit), Icons.Filled.ArrowUpward, Color(0xFFFCD34D), Color(0x1FFFFFFF), Color.White, Modifier.weight(1f), onCredit)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF15803D))
                .clickable(onClick = onWhatsApp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("🟢  " + stringResource(R.string.party_wa_remind), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineTile(stringResource(R.string.party_statement_pdf), Icons.Outlined.Description, Modifier.weight(1f), onStatement)
            if (onSaleBill != null) OutlineTile(stringResource(R.string.party_sale_bill), Icons.AutoMirrored.Outlined.ReceiptLong, Modifier.weight(1f), onSaleBill)
        }
    }
}

@Composable
private fun HeroButton(label: String, icon: ImageVector, iconTint: Color, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OutlineTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFECEAE5), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = HeroInk, modifier = Modifier.size(20.dp))
        Text(label, color = HeroInk, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The WhatsApp reminder, ready in the shop's language: the owner reads it, then WhatsApp opens with it typed in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppReminderSheet(name: String, message: String, onSend: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.party_wa_sheet_title, name), color = HeroInk, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                message,
                color = HeroInk,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)).background(Color(0xFFE7F7EE)).padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF15803D)).clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.party_wa_send), color = Color.White, fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.party_wa_not_now), color = Color(0xFF6B7280), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
