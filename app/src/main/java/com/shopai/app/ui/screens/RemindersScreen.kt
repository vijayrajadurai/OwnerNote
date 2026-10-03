package com.shopai.app.ui.screens

import androidx.annotation.StringRes
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.shopai.app.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private enum class ReminderSectionType(@StringRes val titleRes: Int, val color: Color? = null) {
    Overdue(R.string.reminder_section_overdue, Danger),
    Today(R.string.reminder_section_today),
    Tomorrow(R.string.reminder_section_tomorrow),
    Upcoming(R.string.reminder_section_upcoming),
}

private data class ReminderSection(val type: ReminderSectionType, val items: List<ReminderItem>)

private fun groupReminders(items: List<ReminderItem>): List<ReminderSection> {
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    val overdue = mutableListOf<ReminderItem>()
    val todayItems = mutableListOf<ReminderItem>()
    val tomorrowItems = mutableListOf<ReminderItem>()
    val upcoming = mutableListOf<ReminderItem>()

    items.filter { !it.isDone }.sortedBy { it.dueDate }.forEach { item ->
        val due = parseIsoToLocalDate(item.dueDate) ?: return@forEach
        when {
            due.isBefore(today) -> overdue.add(item)
            due == today -> todayItems.add(item)
            due == tomorrow -> tomorrowItems.add(item)
            else -> upcoming.add(item)
        }
    }

    return listOf(
        ReminderSection(ReminderSectionType.Overdue, overdue),
        ReminderSection(ReminderSectionType.Today, todayItems),
        ReminderSection(ReminderSectionType.Tomorrow, tomorrowItems),
        ReminderSection(ReminderSectionType.Upcoming, upcoming),
    ).filter { it.items.isNotEmpty() }
}

@Composable
fun RemindersScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenReminder: (id: String) -> Unit,
) {
    var reminders by remember { mutableStateOf<List<ReminderItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var alertError by remember { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var completingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var title by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    val scope = rememberCoroutineScope()
    val errorLoadReminders = stringResource(R.string.error_load_reminders)
    val errorSaveReminder = stringResource(R.string.error_save_reminder)
    val errorMarkDone = stringResource(R.string.error_mark_reminder_done)

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

    fun save() {
        val date = dueDate ?: return
        if (saving || title.isBlank()) return
        scope.launch {
            saving = true
            runCatching {
                container.reminderRepository.createReminder(title.trim(), localDateToIsoInstant(date))
            }.onSuccess { created ->
                reminders = reminders + created
                // Kai confirms from the saved reminder.
                container.kaiBrain.forget()
                container.kaiBrain.announce(
                    com.shopai.app.brain.KaiResponder.reminderSet(
                        created.title,
                        com.shopai.app.util.parseIsoToLocalDate(created.dueDate) ?: date,
                        com.shopai.app.brain.KaiLanguage.forAppLocale(),
                        java.time.LocalDate.now(),
                    ),
                )
                title = ""
                dueDate = null
                isAdding = false
            }.onFailure {
                container.presentApiError(it, errorSaveReminder, { msg -> alertError = msg }, { msg -> alertError = msg })
            }
            saving = false
        }
    }

    // Shows a spinner on the row, then hides it once the server confirms.
    fun markDone(item: ReminderItem) {
        if (item.id in completingIds) return
        scope.launch {
            completingIds = completingIds + item.id
            runCatching {
                container.reminderRepository.markDone(item.id)
            }.onSuccess {
                reminders = reminders.map { if (it.id == item.id) it.copy(isDone = true) else it }
            }.onFailure {
                container.presentApiError(it, errorMarkDone, { msg -> alertError = msg }, { msg -> alertError = msg })
            }
            completingIds = completingIds - item.id
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
                        enabled = !saving,
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
                            loading = saving,
                            onClick = { save() },
                        )
                        OutlinedButton(
                            onClick = { isAdding = false },
                            enabled = !saving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Kai's reminders (calls, tasks, daily / weekly) — from the same reminder engine Kai uses.
            KaiRemindersSection(container)

            val sections = groupReminders(reminders)
            when {
                loading && reminders.isEmpty() -> CircularProgressIndicator(
                    color = ShopAiThemeColors.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                error != null -> Text(error!!, color = Danger)
                sections.isEmpty() -> Text(
                    text = stringResource(R.string.reminders_empty),
                    color = ShopAiThemeColors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                )
                else -> sections.forEach { section ->
                    Text(
                        stringResource(section.type.titleRes),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = section.type.color ?: ShopAiThemeColors.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    section.items.forEach { item ->
                        ReminderRow(
                            item = item,
                            completing = item.id in completingIds,
                            overdue = section.type == ReminderSectionType.Overdue,
                            onOpen = { onOpenReminder(item.id) },
                            onDone = { markDone(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(
    item: ReminderItem,
    completing: Boolean,
    overdue: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
) {
    ShopCard(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = ShopAiThemeColors.onSurface)
                Text(
                    formatDisplayDate(item.dueDate),
                    color = if (overdue) Danger else ShopAiThemeColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (completing) {
                CircularProgressIndicator(
                    color = ShopAiThemeColors.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(20.dp),
                )
            } else {
                TextButton(onClick = onDone) {
                    Text(
                        stringResource(R.string.reminder_done),
                        fontWeight = FontWeight.SemiBold,
                        color = ShopAiThemeColors.primary,
                    )
                }
            }
        }
    }
}

/** Kai's open reminders with Done / Cancel — the reminder engine is the only source. */
@Composable
private fun KaiRemindersSection(container: AppContainer) {
    val engine = container.kaiReminders
    val changed by engine.changes.collectAsState()
    val items = remember(changed) { engine.open() }
    if (items.isEmpty()) return
    val zone = java.time.ZoneId.systemDefault()
    val today = LocalDate.now()
    val lang = com.shopai.app.brain.KaiLanguage.forAppLocale()
    Text(
        stringResource(R.string.kai_reminders_section),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = ShopAiThemeColors.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    items.forEach { r ->
        val at = java.time.Instant.ofEpochMilli(r.snoozedUntil ?: r.triggerAt).atZone(zone).toLocalDateTime()
        val repeat = when (r.recurrence.repeat) {
            com.shopai.app.brain.tools.Repeat.ONCE -> ""
            com.shopai.app.brain.tools.Repeat.DAILY -> " · " + stringResource(R.string.kai_reminder_daily)
            com.shopai.app.brain.tools.Repeat.WEEKLY -> " · " + stringResource(R.string.kai_reminder_weekly)
            com.shopai.app.brain.tools.Repeat.MONTHLY -> " · " + stringResource(R.string.kai_reminder_monthly)
        }
        ShopCard(modifier = Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium, color = ShopAiThemeColors.onSurface)
                    Text(
                        com.shopai.app.brain.KaiFormat.date(at.toLocalDate(), lang, today).replaceFirstChar { it.uppercase() } + " " +
                            at.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)) + repeat,
                        color = ShopAiThemeColors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(onClick = { engine.complete(r.id) }) { Text(stringResource(R.string.reminder_done), fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = { engine.cancel(r.id) }) { Text(stringResource(R.string.cancel), color = Danger) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
