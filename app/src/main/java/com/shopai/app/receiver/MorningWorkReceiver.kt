package com.shopai.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shopai.app.ShopAiApplication
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.morning.MorningBriefs
import com.shopai.app.brain.morning.MorningFire
import com.shopai.app.brain.morning.MorningOwner
import com.shopai.app.brain.morning.MorningTrigger
import com.shopai.app.notifications.MorningWorkAlarms
import kotlinx.coroutines.launch

/**
 * The morning alarm rang. The notification is built NOW from the signed-in
 * owner's records (the same MorningWorkEngine as Kai Chat) — never from a
 * stored copy — and only when the alarm was armed for that same owner +
 * business. Then tomorrow's alarm is armed.
 */
class MorningWorkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MorningWorkAlarms.ACTION_FIRE) return
        val container = (context.applicationContext as? ShopAiApplication)?.container ?: return
        val firedFor = intent.getStringExtra(MorningWorkAlarms.EXTRA_BUSINESS)?.let { MorningOwner(it, intent.getStringExtra(MorningWorkAlarms.EXTRA_OWNER)) }
        val done = goAsync()
        container.appScope.launch {
            try {
                val current = container.signedInMorningOwner()
                val decision = container.morningScheduler.onFire(firedFor, current)
                if (decision is MorningFire.Show) {
                    val lang = KaiLanguage.forAppLocale().let { if (it == KaiLang.TAMIL) it else KaiLang.TANGLISH }
                    // The snapshot is read for the signed-in owner; a mismatch (another login meanwhile) shows only the plain note.
                    val brief = runCatching {
                        container.morningSources.snapshot()
                            ?.takeIf { it.businessId == decision.owner.businessId && it.ownerId == decision.owner.ownerId }
                            ?.let { snap ->
                                container.morningWork.role = container.morningSources.role()
                                container.morningWork.generate(snap, lang, MorningTrigger.SCHEDULED)
                            }
                    }.getOrNull()
                    val (title, body) = MorningBriefs.notification(brief)
                    container.morningAlarms.show(title, body)
                }
            } finally {
                done.finish()
            }
        }
    }
}
