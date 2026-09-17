package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.CreateReminderRequest
import com.shopai.app.data.model.ReminderItem

class ReminderRepository(private val api: ShopAiApi) {
    suspend fun listReminders(): List<ReminderItem> = api.listReminders().data

    suspend fun createReminder(title: String, dueDate: String): ReminderItem =
        api.createReminder(CreateReminderRequest(title, dueDate)).data

    suspend fun markDone(id: String): ReminderItem =
        api.markReminderDone(id).data
}
