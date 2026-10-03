package com.shopai.app.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A reminder notification the owner tapped: Kai Chat opens on it (Call / Snooze / Done). */
object KaiReminderInbox {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun open(id: String) { _pending.value = id }

    fun take(): String? = _pending.value.also { _pending.value = null }
}
