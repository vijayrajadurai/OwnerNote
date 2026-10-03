package com.shopai.app.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopai.app.data.kai.KaiActionLog
import com.shopai.app.data.repository.AuthRepository
import com.shopai.app.data.repository.CapturedDocumentRepository
import com.shopai.app.data.repository.HandwrittenNotesRepository
import com.shopai.app.notifications.KaiReminderEngine
import com.shopai.app.notifications.ReminderAlarms
import kotlinx.coroutines.launch

class MoreViewModel(
    private val authRepository: AuthRepository,
    private val reminderAlarms: ReminderAlarms,
    private val kaiReminders: KaiReminderEngine,
    private val kaiActionLog: KaiActionLog,
    private val capturedDocumentRepository: CapturedDocumentRepository,
    private val handwrittenNotesRepository: HandwrittenNotesRepository,
) : ViewModel() {

    val primaryItems: List<MoreMenuEntry> = MoreMenuCatalog.primary
    val toolItems: List<MoreMenuEntry> = MoreMenuCatalog.tools

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            reminderAlarms.clear()
            kaiReminders.clear()
            runCatching { kaiActionLog.clear() }
            runCatching { capturedDocumentRepository.clear() }
            runCatching { handwrittenNotesRepository.clear() }
            onComplete()
        }
    }

    class Factory(
        private val authRepository: AuthRepository,
        private val reminderAlarms: ReminderAlarms,
        private val kaiReminders: KaiReminderEngine,
        private val kaiActionLog: KaiActionLog,
        private val capturedDocumentRepository: CapturedDocumentRepository,
        private val handwrittenNotesRepository: HandwrittenNotesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MoreViewModel(
                authRepository,
                reminderAlarms,
                kaiReminders,
                kaiActionLog,
                capturedDocumentRepository,
                handwrittenNotesRepository,
            ) as T
    }
}
