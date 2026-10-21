package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.model.BusinessKind
import com.shopai.app.ui.theme.ShopAiThemeColors

/**
 * The business's types (owner, 21 Oct 2026): Service, Retail, Wholesale / Distributor — several allowed. The types decide which
 * features show ([com.shopai.app.data.model.FeatureSet]) and which business categories are offered.
 */
@Composable
fun BusinessTypePicker(
    selected: Set<BusinessKind>,
    onSelectedChange: (Set<BusinessKind>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.business_type_many), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BusinessKind.entries.forEach { kind ->
                val (icon, label, caption) = when (kind) {
                    BusinessKind.SERVICE -> Triple("✂️", R.string.business_type_service, R.string.business_type_service_hint)
                    BusinessKind.RETAIL -> Triple("🛒", R.string.business_type_retail, R.string.business_type_retail_hint)
                    BusinessKind.WHOLESALE -> Triple("🏬", R.string.business_type_wholesale, R.string.business_type_wholesale_hint)
                }
                TypeCard(icon, stringResource(label), stringResource(caption), kind in selected, Modifier.weight(1f)) {
                    onSelectedChange(if (kind in selected) selected - kind else selected + kind)
                }
            }
        }
        Text(stringResource(R.string.business_type_both_hint), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
    }
}

@Composable
private fun TypeCard(icon: String, label: String, caption: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .heightIn(min = 112.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd, modifier = Modifier.fillMaxWidth()) {
            Text(icon, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center))
            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = ShopAiThemeColors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
