package com.shopai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.data.network.presentApiError
import com.shopai.app.data.model.FundingOpportunity
import com.shopai.app.data.model.LeadQualificationInput
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.launch

@Composable
fun FundingQualificationScreen(
    container: AppContainer,
    opportunityId: String,
    onComplete: () -> Unit,
) {
    var opportunity by remember { mutableStateOf<FundingOpportunity?>(null) }
    var workingCapital by remember { mutableStateOf("") }
    var fundingMax by remember { mutableStateOf("") }
    var callbackTime by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    var loadingOpp by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val errorFundingLoad = stringResource(R.string.error_funding_load)
    val errorFundingSubmit = stringResource(R.string.error_funding_submit)
    val fundingTitleFallback = stringResource(R.string.funding_title_fallback)

    LaunchedEffect(opportunityId) {
        runCatching {
            opportunity = container.fundingRepository.getOpportunity(opportunityId)
        }.onFailure {
            container.presentApiError(it, errorFundingLoad, { msg -> error = msg }, { msg -> alertError = msg })
        }
        loadingOpp = false
    }

    fun submit(intent: String) {
        scope.launch {
            loading = intent
            error = null
            runCatching {
                container.fundingRepository.createLead(
                    opportunityId,
                    LeadQualificationInput(
                        workingCapitalRequirement = workingCapital.toDoubleOrNull(),
                        fundingRequirementMax = fundingMax.toDoubleOrNull(),
                        preferredCallbackTime = callbackTime.trim().ifBlank { null },
                        userIntent = intent,
                    ),
                )
                onComplete()
            }.onFailure {
                container.presentApiError(it, errorFundingSubmit, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = null
        }
    }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        if (loadingOpp) {
            CircularProgressIndicator(color = ShopAiThemeColors.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
            return
        }

        val opp = opportunity
        Text(
            opp?.title ?: fundingTitleFallback,
            style = MaterialTheme.typography.headlineMedium,
            color = ShopAiThemeColors.onSurface,
            fontWeight = FontWeight.Bold,
        )
        ShopCard(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(opp?.explanation ?: "", color = ShopAiThemeColors.onSurfaceVariant)
        }

        Text(
            stringResource(R.string.funding_details_optional),
            color = ShopAiThemeColors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ShopTextField(
            stringResource(R.string.funding_working_capital),
            workingCapital,
            { workingCapital = it.filter { ch -> ch.isDigit() } },
        )
        ShopTextField(
            stringResource(R.string.funding_amount),
            fundingMax,
            { fundingMax = it.filter { ch -> ch.isDigit() } },
        )
        ShopTextField(
            stringResource(R.string.funding_callback),
            callbackTime,
            { callbackTime = it },
            placeholder = stringResource(R.string.funding_callback_placeholder),
        )

        if (error != null) Text(error!!, color = Danger, modifier = Modifier.padding(bottom = 8.dp))

        PrimaryButton(
            label = stringResource(R.string.funding_call_me),
            loading = loading == "TALK_TO_SOMEONE",
            enabled = loading == null,
            onClick = { submit("TALK_TO_SOMEONE") },
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            label = stringResource(R.string.funding_explore),
            loading = loading == "EXPLORE_OPTIONS",
            enabled = loading == null,
            onClick = { submit("EXPLORE_OPTIONS") },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.funding_disclaimer),
            style = MaterialTheme.typography.bodyMedium,
            color = ShopAiThemeColors.onSurfaceVariant,
        )
    }
}
