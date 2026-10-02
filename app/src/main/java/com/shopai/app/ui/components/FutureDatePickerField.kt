package com.shopai.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.util.FutureSelectableDates
import com.shopai.app.util.formatLocalDateForDisplay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
private class PastSelectableDates(private val maxDate: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(maxDate)

    override fun isSelectableYear(year: Int): Boolean = year <= maxDate.year
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FutureDatePickerField(
    label: String,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    allowEmpty: Boolean = true,
    // For dates something already happened on (bill date): today or earlier.
    pastOnly: Boolean = false,
    // Any date, past or future (dates written on handwritten notes).
    anyDate: Boolean = false,
) {
    var showDialog by remember { mutableStateOf(false) }
    val displayText = selectedDate?.let { formatLocalDateForDisplay(it) } ?: ""

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            placeholder = {
                Text(
                    text = placeholder,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                IconButton(onClick = { showDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = stringResource(R.string.select_date),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDialog = true },
            singleLine = true,
            isError = error != null,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
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

    if (showDialog) {
        val today = LocalDate.now()
        // The picker works in UTC days; local midnight in IST is the previous
        // UTC day, which highlighted the wrong date.
        val initialMillis = (selectedDate ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            selectableDates = when {
                anyDate -> DatePickerDefaults.AllDates
                pastOnly -> PastSelectableDates(today)
                else -> FutureSelectableDates(today)
            },
        )

        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            colors = DatePickerDefaults.colors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis
                        if (millis != null) {
                            val picked = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            val allowed = when {
                                anyDate -> true
                                pastOnly -> !picked.isAfter(today)
                                else -> !picked.isBefore(today)
                            }
                            if (allowed) {
                                onDateSelected(picked)
                            }
                        }
                        showDialog = false
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                Row {
                    if (allowEmpty && selectedDate != null) {
                        TextButton(
                            onClick = {
                                onDateSelected(null)
                                showDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.clear_date))
                        }
                    }
                    TextButton(onClick = { showDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        ) {
            DatePicker(
                state = datePickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    }
}
