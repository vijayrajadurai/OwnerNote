package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiLang

/**
 * A new reminder is set only after the owner confirms ("… reminder set pannalama?" → Confirm).
 * These helpers ask (or tap) and then tap Confirm when Kai asks — for tests about what happens after.
 */
suspend fun KaiAgent.askConfirmed(text: String): KaiTurn = confirmIfAsked(ask(text))

suspend fun KaiAgent.actConfirmed(action: KaiAction, lang: KaiLang = KaiLang.TANGLISH): KaiTurn? = act(action, lang)?.let { confirmIfAsked(it) }

suspend fun KaiAgent.confirmIfAsked(t: KaiTurn): KaiTurn {
    val confirm = t.card?.buttons.orEmpty().map { it.action }.filterIsInstance<KaiAction.ConfirmReminder>().firstOrNull() ?: return t
    return act(confirm, KaiLang.TANGLISH) ?: t
}
