package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shopai.app.R
import com.shopai.app.data.model.BusinessCategories
import com.shopai.app.ui.theme.ShopAiThemeColors

/**
 * The owner's business categories (owner, 20 Oct 2026): the 9 popular ones as cards, a search over all of them (names
 * and what they sell), "View all categories" for the full list, several picks allowed, and a name for "Other Product
 * Businesses". [selected] holds [BusinessCategories] codes in the order picked. [allowed] is the categories the business's
 * types pick from (a salon sees the service shops, a retailer the product businesses — [com.shopai.app.data.model.FeatureSet.categoriesFor]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BusinessCategoryPicker(
    selected: List<String>,
    otherName: String,
    onSelectedChange: (List<String>) -> Unit,
    onOtherNameChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowed: List<BusinessCategories.Category> = BusinessCategories.all,
) {
    val tamil = LocalConfiguration.current.locales[0].language == "ta"
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    fun toggle(code: String) = onSelectedChange(if (code in selected) selected - code else selected + code)
    fun name(c: BusinessCategories.Category) = if (tamil) c.ta else c.en
    val allowedCodes = remember(allowed) { allowed.map { it.code }.toSet() }
    val other = BusinessCategories.otherFor(allowed)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.business_category_many), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
        // 3 × 3 cards of the popular categories.
        BusinessCategories.popularFor(allowed).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { c -> CategoryCard(c.icon, name(c), c.code in selected, Modifier.weight(1f)) { toggle(c.code) } }
            }
        }
        ShopTextField(
            label = stringResource(R.string.business_category_search),
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.business_category_search_hint),
        )
        if (query.isNotBlank()) {
            val hits = BusinessCategories.search(query).filter { it.code in allowedCodes }
            if (hits.isEmpty()) {
                Text(stringResource(R.string.business_category_none_found), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
                CategoryRow(BusinessCategories.of(other)!!, tamil, other in selected) {
                    if (other !in selected) onSelectedChange(selected + other)
                    if (otherName.isBlank()) onOtherNameChange(query.trim())
                    query = ""
                }
            } else {
                hits.take(8).forEach { c -> CategoryRow(c, tamil, c.code in selected) { toggle(c.code) } }
            }
        }
        OutlinedButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.business_category_view_all, allowed.size))
        }
        // Every pick (cards, search or the full list) shown together; tap one to take it off.
        if (selected.isNotEmpty()) {
            Text(stringResource(R.string.business_category_selected, selected.size), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = ShopAiThemeColors.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selected.mapNotNull(BusinessCategories::of).forEach { c -> CategoryChip(label = "${c.icon} ${name(c)}  ✕", selected = true, onClick = { toggle(c.code) }) }
            }
        }
        if (BusinessCategories.namedOther(selected) != null) {
            ShopTextField(
                label = stringResource(R.string.business_category_other_name),
                value = otherName,
                onValueChange = onOtherNameChange,
                placeholder = stringResource(R.string.business_category_other_hint),
                error = if (otherName.isNotEmpty() && otherName.trim().length < 2) stringResource(R.string.business_category_other_short) else null,
            )
        }
    }

    if (showAll) {
        AllCategoriesDialog(selected, allowed, tamil, onToggle = ::toggle, onDone = { showAll = false })
    }
}

/** The full list: product businesses, then service shops, with its own search; the picks carry over as they are made. */
@Composable
private fun AllCategoriesDialog(selected: List<String>, allowed: List<BusinessCategories.Category>, tamil: Boolean, onToggle: (String) -> Unit, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.business_category_all_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = ShopAiThemeColors.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text(stringResource(R.string.business_category_done, selected.size)) }
            }
            ShopTextField(
                label = stringResource(R.string.business_category_search),
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.business_category_search_hint),
            )
            val shown = if (query.isBlank()) allowed else BusinessCategories.search(query).filter { it in allowed }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                val (products, services) = shown.partition { !it.service }
                products.forEach { c -> CategoryRow(c, tamil, c.code in selected) { onToggle(c.code) } }
                if (services.isNotEmpty()) {
                    Text(stringResource(R.string.business_category_services), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    services.forEach { c -> CategoryRow(c, tamil, c.code in selected) { onToggle(c.code) } }
                }
                if (shown.isEmpty()) Text(stringResource(R.string.business_category_none_found), color = ShopAiThemeColors.onSurfaceVariant)
            }
            PrimaryButton(label = stringResource(R.string.business_category_done, selected.size), onClick = onDone)
        }
    }
}

@Composable
private fun CategoryCard(icon: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .heightIn(min = 96.dp)
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
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** One category in a list: its icon, name and what it sells, with a tick box. */
@Composable
private fun CategoryRow(c: BusinessCategories.Category, tamil: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        Text(c.icon, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (tamil) c.ta else c.en, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = ShopAiThemeColors.onSurface)
            Text(c.subcategories.take(4).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant, maxLines = 1)
        }
        Checkbox(checked = selected, onCheckedChange = { onClick() })
    }
}
