package com.shopai.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shopai.app.R
import com.shopai.app.billing.BillingConfig
import com.shopai.app.data.AppContainer
import com.shopai.app.data.local.AppLanguage
import com.shopai.app.data.local.AppThemeMode
import com.shopai.app.data.local.room.VoiceCheckinPrefsEntity
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.util.VoiceCheckinSlot
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSubscription: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            container.preferencesRepository,
            context.packageManager,
            context.packageName,
        ),
    )
    val state by viewModel.uiState.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var voiceCheckinPrefs by remember { mutableStateOf<VoiceCheckinPrefsEntity?>(null) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        voiceCheckinPrefs = container.voiceCheckinRepository.getPrefs()
    }
    val exactAlarmsAllowed = container.voiceCheckinRepository.canScheduleExactAlarms()

    DetailScaffold(title = stringResource(R.string.settings), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.choose_app_language), color = MaterialTheme.colorScheme.onSurfaceVariant)
            ShopCard {
                LanguageOption(
                    label = stringResource(R.string.english),
                    selected = state.language == AppLanguage.ENGLISH,
                    onSelect = { viewModel.setLanguage(AppLanguage.ENGLISH) },
                )
                LanguageOption(
                    label = stringResource(R.string.tamil),
                    selected = state.language == AppLanguage.TAMIL,
                    onSelect = { viewModel.setLanguage(AppLanguage.TAMIL) },
                )
            }

            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ShopCard {
                ThemeOption(
                    label = stringResource(R.string.appearance_system),
                    selected = state.theme == AppThemeMode.SYSTEM,
                    onSelect = { viewModel.setTheme(AppThemeMode.SYSTEM) },
                )
                ThemeOption(
                    label = stringResource(R.string.appearance_dark),
                    selected = state.theme == AppThemeMode.DARK,
                    onSelect = { viewModel.setTheme(AppThemeMode.DARK) },
                )
            }

            Text(stringResource(R.string.voice_checkin_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.voice_checkin_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            ShopCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.voice_checkin_master_toggle), modifier = Modifier.weight(1f))
                    Switch(
                        checked = voiceCheckinPrefs?.enabled == true,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                voiceCheckinPrefs = container.voiceCheckinRepository.setEnabled(checked)
                            }
                            if (checked) {
                                val powerManager = context.getSystemService(PowerManager::class.java)
                                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                                    showBatteryDialog = true
                                }
                            }
                        },
                    )
                }
                if (voiceCheckinPrefs?.enabled == true) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    VoiceCheckinSlotRow(
                        label = stringResource(R.string.voice_checkin_slot_8am),
                        checked = voiceCheckinPrefs?.slot8AmEnabled ?: true,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                voiceCheckinPrefs = container.voiceCheckinRepository
                                    .setSlotEnabled(VoiceCheckinSlot.MORNING_8AM, checked)
                            }
                        },
                    )
                    VoiceCheckinSlotRow(
                        label = stringResource(R.string.voice_checkin_slot_12pm),
                        checked = voiceCheckinPrefs?.slot12PmEnabled ?: true,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                voiceCheckinPrefs = container.voiceCheckinRepository
                                    .setSlotEnabled(VoiceCheckinSlot.NOON_12PM, checked)
                            }
                        },
                    )
                    VoiceCheckinSlotRow(
                        label = stringResource(R.string.voice_checkin_slot_4pm),
                        checked = voiceCheckinPrefs?.slot4PmEnabled ?: true,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                voiceCheckinPrefs = container.voiceCheckinRepository
                                    .setSlotEnabled(VoiceCheckinSlot.EVENING_4PM, checked)
                            }
                        },
                    )
                    VoiceCheckinSlotRow(
                        label = stringResource(R.string.voice_checkin_slot_8pm),
                        checked = voiceCheckinPrefs?.slot8PmEnabled ?: true,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                voiceCheckinPrefs = container.voiceCheckinRepository
                                    .setSlotEnabled(VoiceCheckinSlot.NIGHT_8PM, checked)
                            }
                        },
                    )
                    if (!exactAlarmsAllowed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.voice_checkin_exact_alarm_denied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Text(stringResource(R.string.about_app), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ShopCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.cd_app_icon),
                        modifier = Modifier.size(48.dp),
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.about_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.versionInfo?.let { info ->
                            Text(
                                stringResource(R.string.version_format, info.versionName, info.versionCode),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                LinkRow(stringResource(R.string.privacy_policy)) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BillingConfig.PRIVACY_POLICY_URL)))
                }
                LinkRow(stringResource(R.string.terms_and_conditions)) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BillingConfig.TERMS_URL)))
                }
            }

            ShopCard(modifier = Modifier.clickable(onClick = onOpenSubscription)) {
                Text(stringResource(R.string.subscription), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.more_subscription_caption),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text(stringResource(R.string.voice_checkin_battery_dialog_title)) },
            text = { Text(stringResource(R.string.voice_checkin_battery_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }) {
                    Text(stringResource(R.string.voice_checkin_battery_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) {
                    Text(stringResource(R.string.voice_checkin_battery_dialog_dismiss))
                }
            },
        )
    }
}

@Composable
private fun VoiceCheckinSlotRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
