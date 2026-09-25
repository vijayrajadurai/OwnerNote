package com.shopai.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.shopai.app.data.model.ReminderItem
import com.shopai.app.ui.components.ApiErrorAlertDialog
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.FutureDatePickerField
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.util.formatDisplayDate
import com.shopai.app.util.localDateToIsoInstant
import com.shopai.app.util.parseIsoToLocalDate
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class ReminderSectionType(@StringRes val titleRes: Int) {
    Today(R.string.reminder_section_today),
    Tomorrow(R.string.reminder_section_tomorrow),
    Upcoming(R.string.reminder_section_upcoming),
}

private data class ReminderSection(val type: ReminderSectionType, val items: List<ReminderItem>)

private fun groupReminders(items: List<ReminderItem>): List<ReminderSection> {
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    val todayItems = mutableListOf<ReminderItem>()
    val tomorrowItems = mutableListOf<ReminderItem>()
    val upcoming = mutableListOf<ReminderItem>()

    items.filter { !it.isDone }.forEach { item ->
        val due = parseIsoToLocalDate(item.dueDate) ?: return@forEach
        when {
            !due.isAfter(today) -> todayItems.add(item)
            due == tomorrow -> tomorrowItems.add(item)
            else -> upcoming.add(item)
        }
    }

    return listOfNotNull(
        if (todayItems.isNotEmpty()) ReminderSection(ReminderSectionType.Today, todayItems) else null,
        if (tomorrowItems.isNotEmpty()) ReminderSection(ReminderSectionType.Tomorrow, tomorrowItems) else null,
        if (upcoming.isNotEmpty()) ReminderSection(ReminderSectionType.Upcoming, upcoming) else null,
    )
}

@Composable
fun RemindersScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    var reminders by remember { mutableStateOf<List<ReminderItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    val scope = rememberCoroutineScope()
    val errorLoadReminders = stringResource(R.string.error_load_reminders)

    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                reminders = container.reminderRepository.listReminders()
            }.onFailure {
                container.presentApiError(it, errorLoadReminders, { msg -> error = msg }, { msg -> alertError = msg })
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    ApiErrorAlertDialog(message = alertError, onDismiss = { alertError = null })

    DetailScaffold(title = stringResource(R.string.reminders_title), onBack = onBack) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!isAdding) {
                PrimaryButton(label = stringResource(R.string.reminders_add), onClick = { isAdding = true })
            } else {
                ShopCard {
                    ShopTextField(
                        stringResource(R.string.reminder_title_label),
                        title,
                        { title = it },
                        placeholder = stringResource(R.string.reminder_title_placeholder),
                    )
                    FutureDatePickerField(
                        label = stringResource(R.string.reminder_date_label),
                        selectedDate = dueDate,
                        onDateSelected = { dueDate = it },
                        placeholder = stringResource(R.string.due_date_placeholder),
                        allowEmpty = false,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            label = stringResource(R.string.save),
                            modifier = Modifier.weight(1f),
                            enabled = title.isNotBlank() && dueDate != null,
                            onClick = {
                                val date = dueDate ?: return@PrimaryButton
                                val iso = localDateToIsoInstant(date)
                                scope.launch {
                                    runCatching {
                                        container.reminderRepository.createReminder(title.trim(), iso)
                                        title = ""
                                        dueDate = null
                                        isAdding = false
                                        reload()
                                    }
                                }
                            },
                        )
                        OutlinedButton(onClick = { isAdding = false }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                loading -> CircularProgressIndicator(color = ShopAiThemeColors.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
                error != null -> Text(error!!, color = Danger)
                else -> groupReminders(reminders).forEach { section ->
                    Text(
                        stringResource(section.type.titleRes),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = ShopAiThemeColors.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    section.items.forEach { item ->
                        ShopCard(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, color = ShopAiThemeColors.onSurface)
                            Text(formatDisplayDate(item.dueDate), color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
