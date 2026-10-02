package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.CreateReminderRequest
import com.shopai.app.data.model.ReminderItem
import com.shopai.app.notifications.ReminderAlarms

class ReminderRepository(
    private val api: ShopAiApi,
    // Kept in sync so the daily due-date alarm also works offline.
    private val alarms: ReminderAlarms,
) {
    suspend fun listReminders(): List<ReminderItem> =
        api.listReminders().data.also { alarms.cache(it) }

    suspend fun createReminder(title: String, dueDate: String): ReminderItem =
        api.createReminder(CreateReminderRequest(title, dueDate)).data.also { created ->
            alarms.updateCached { list -> list.filter { it.id != created.id } + created }
        }

    suspend fun markDone(id: String): ReminderItem =
        api.markReminderDone(id).data.also {
            alarms.updateCached { list -> list.map { item -> if (item.id == id) item.copy(isDone = true) else item } }
            alarms.dismiss(id)
        }
}
