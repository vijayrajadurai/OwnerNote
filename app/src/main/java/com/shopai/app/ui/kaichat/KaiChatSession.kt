package com.shopai.app.ui.kaichat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.chat.ChatIntent
import com.shopai.app.brain.chat.ChatReply
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiAgent
import com.shopai.app.brain.chat.KaiCard
import com.shopai.app.brain.chat.KaiTurn
import com.shopai.app.brain.tools.ActionPlan
import java.math.BigDecimal

/** One message in Kai Chat; Kai's may carry a card (draft details and buttons). */
data class KaiChatMessage(
    val id: Long,
    val fromOwner: Boolean,
    val text: String,
    val mood: KaiMood? = null,
    val card: KaiCard? = null,
    /** The card's buttons were used (confirmed / cancelled / chosen): shown, no longer tappable. */
    val cardClosed: Boolean = false,
    val lang: KaiLang = KaiLang.TANGLISH,
)

/**
 * The Kai Chat conversation, kept for the app session (leaving the screen
 * and coming back keeps the messages and Kai's context). Text only: Kai
 * replies are never spoken.
 */
class KaiChatSession(private val agent: KaiAgent) {
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
        val lang = KaiLanguage.forChat(question)
        respond(lang) { agent.ask(question) }
    }

    /**
     * A card button. Money / reminder actions go to Kai; phone actions (dialer,
     * scanner, settings) and Edit are returned for the screen to perform.
     */
    suspend fun tap(messageId: Long, action: KaiAction): KaiAction? {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0 || messages[index].cardClosed || thinking) return null
        val lang = messages[index].lang
        return when (action) {
            is KaiAction.Dial, KaiAction.OpenScanner, KaiAction.OpenAlarmSettings, KaiAction.OpenNotificationSettings, is KaiAction.EditPlan -> action
            else -> {
                close(index)
                respond(lang) { agent.act(action, lang) ?: KaiTurn(ChatReply("…", KaiMood.NEUTRAL, ChatIntent.UNKNOWN)) }
                null
            }
        }
    }

    fun plan(key: String): ActionPlan? = agent.plan(key)

    /** A reminder notification was tapped: Kai shows it with Call / Snooze / Done. */
    suspend fun showRang(id: String) {
        val lang = KaiLanguage.forAppLocale()
        val turn = agent.rang(id, lang) ?: return
        messages += KaiChatMessage(nextId++, fromOwner = false, text = turn.reply.text, mood = turn.reply.mood, card = turn.card, lang = lang)
        lastMood = turn.reply.mood
        lastReplyAt = System.currentTimeMillis()
    }

    /** Edit: the draft is prepared again with the owner's changes (the old card is closed). */
    suspend fun revise(messageId: Long, key: String, name: String, amount: BigDecimal, mode: PaymentMode, outgoing: Boolean) {
        val index = messages.indexOfFirst { it.id == messageId }
        val lang = messages.getOrNull(index)?.lang ?: KaiLang.TANGLISH
        if (index >= 0) close(index)
        respond(lang) { agent.revise(key, name, amount, mode, outgoing, lang) }
    }

    private fun close(index: Int) {
        messages[index] = messages[index].copy(cardClosed = true)
    }

    private suspend fun respond(lang: KaiLang, turn: suspend () -> KaiTurn) {
        thinking = true
        val started = System.currentTimeMillis()
        val result = runCatching { turn() }.getOrElse {
            KaiTurn(ChatReply(
                when (lang) {
                    KaiLang.TAMIL -> "ஓனர், கொஞ்சம் தெளிவா சொல்லுங்க."
                    KaiLang.TANGLISH -> "Owner, konjam clear-ah sollunga."
                    KaiLang.ENGLISH -> "Owner, could you say that a little more clearly?"
                },
                KaiMood.CLARIFY, ChatIntent.UNKNOWN,
            ))
        }
        // A short, visible moment of thinking (the brain itself is instant).
        kotlinx.coroutines.delay((650 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
        messages += KaiChatMessage(nextId++, fromOwner = false, text = result.reply.text, mood = result.reply.mood, card = result.card, lang = lang)
        lastMood = result.reply.mood
        lastReplyAt = System.currentTimeMillis()
        thinking = false
    }

    fun clear() {
        messages.clear()
        agent.reset()
        lastMood = null
    }
}
