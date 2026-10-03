package com.shopai.app.ui.kaichat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.chat.ChatReply
import com.shopai.app.brain.chat.KaiBusinessBrain

/** One message in Kai Chat. */
data class KaiChatMessage(
    val id: Long,
    val fromOwner: Boolean,
    val text: String,
    val mood: KaiMood? = null,
    /** A button under Kai's reply (Morning Work hand-over). */
    val action: KaiChatAction? = null,
)

enum class KaiChatAction { OPEN_MORNING_WORK, START_MORNING_WORK }

/**
 * The Kai Chat conversation, kept for the app session (leaving the screen
 * and coming back keeps the messages and Kai's context). Text only: Kai
 * replies are never spoken.
 */
class KaiChatSession(
    private val brain: KaiBusinessBrain,
    /** Kai's Morning Work greeting for a clear morning-work request (null = not available). */
    private val morningWork: suspend (String) -> String? = { null },
) {
    val messages = mutableStateListOf<KaiChatMessage>()
    var thinking by mutableStateOf(false)
        private set
    /** When the latest Kai reply arrived (for his answering animation). */
    var lastReplyAt by mutableLongStateOf(0L)
        private set
    var lastMood by mutableStateOf<KaiMood?>(null)
        private set
    private var nextId = 1L

    suspend fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || thinking) return
        messages += KaiChatMessage(nextId++, fromOwner = true, text = question)
        thinking = true
        val started = System.currentTimeMillis()
        // "Kai, morning work ready pannu": Kai answers with today's work and offers Start My Morning.
        val morning = com.shopai.app.brain.morning.MorningCommands.morningRequest(question)
        if (morning != null) {
            val greeting = runCatching { morningWork(question) }.getOrNull()
            if (greeting != null) {
                kotlinx.coroutines.delay((650 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                val action = if (morning == com.shopai.app.brain.morning.MorningCommand.Start) KaiChatAction.START_MORNING_WORK else KaiChatAction.OPEN_MORNING_WORK
                messages += KaiChatMessage(nextId++, fromOwner = false, text = greeting, mood = KaiMood.EXPLAINING, action = action)
                lastMood = KaiMood.EXPLAINING
                lastReplyAt = System.currentTimeMillis()
                thinking = false
                return
            }
        }
        val reply: ChatReply = runCatching { brain.ask(question) }.getOrElse {
            ChatReply("Owner, konjam clear-ah sollunga.", KaiMood.CLARIFY, com.shopai.app.brain.chat.ChatIntent.UNKNOWN)
        }
        // A short, visible moment of thinking (the brain itself is instant).
        kotlinx.coroutines.delay((650 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
        messages += KaiChatMessage(nextId++, fromOwner = false, text = reply.text, mood = reply.mood)
        lastMood = reply.mood
        lastReplyAt = System.currentTimeMillis()
        thinking = false
    }

    fun clear() {
        messages.clear()
        brain.reset()
        lastMood = null
    }
}
