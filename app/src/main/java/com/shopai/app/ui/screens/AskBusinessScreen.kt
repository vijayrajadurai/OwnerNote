package com.shopai.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.AskAnswer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.CategoryChip
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.launch

private data class SuggestionChip(val labelRes: Int, val sampleRes: Int)

// Each chip's sample question is chosen to land on a REAL supported backend
// intent (BUSINESS_SITUATION / WHO_TO_COLLECT / INVENTORY_STOCK_QUERY /
// MONTH_COMPARISON / CASH_SHORTAGE) — never a question that would only ever
// produce the generic UNKNOWN fallback. "Stock" without naming a product
// still honestly answers "couldn't find that product" (see
// OwnerNote-Backend's askMyBusiness.ts) rather than fabricating a number.
private val SUGGESTION_CHIPS = listOf(
    SuggestionChip(R.string.ask_chip_today, R.string.ask_sample_today),
    SuggestionChip(R.string.ask_chip_collections, R.string.ask_sample_collections),
    SuggestionChip(R.string.ask_chip_stock, R.string.ask_sample_stock),
    SuggestionChip(R.string.ask_chip_sales, R.string.ask_sample_sales),
    SuggestionChip(R.string.ask_chip_expenses, R.string.ask_sample_expenses),
)

@Composable
fun AskBusinessScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenVoiceEntry: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<AskAnswer>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val errorAsk = stringResource(R.string.ask_error)

    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || loading) return
        scope.launch {
            loading = true
            error = null
            runCatching { container.insightsRepository.askMyBusiness(trimmed) }
                .onSuccess { answer ->
                    history = listOf(answer) + history
                    question = ""
                }
                .onFailure {
                    container.presentApiError(it, errorAsk, { msg -> error = msg }, { msg -> alertError = msg })
                }
            loading = false
        }
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(title = stringResource(R.string.ask_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.ask_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = ShopAiThemeColors.onSurfaceVariant,
            )

            ShopTextField(
                label = stringResource(R.string.ask_title),
                value = question,
                onValueChange = { question = it },
                placeholder = stringResource(R.string.ask_placeholder),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    label = stringResource(R.string.ask_send),
                    onClick = { submit(question) },
                    modifier = Modifier.weight(1f),
                    loading = loading,
                    enabled = question.isNotBlank(),
                )
                OutlinedButton(onClick = onOpenVoiceEntry) {
                    Icon(imageVector = Icons.Default.Mic, contentDescription = stringResource(R.string.ask_speak))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ask_speak))
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SUGGESTION_CHIPS.forEach { chip ->
                    val sample = stringResource(chip.sampleRes)
                    CategoryChip(
                        label = stringResource(chip.labelRes),
                        selected = false,
                        onClick = { submit(sample) },
                    )
                }
            }

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurface)
            }

            history.forEach { qa ->
                ShopCard {
                    Text(
                        text = qa.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShopAiThemeColors.onSurfaceVariant,
                    )
                    Text(
                        text = qa.answer,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ShopAiThemeColors.onSurface,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
