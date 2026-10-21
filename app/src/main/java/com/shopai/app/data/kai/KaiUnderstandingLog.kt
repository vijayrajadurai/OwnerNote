package com.shopai.app.data.kai

import android.content.Context
import com.shopai.app.brain.chat.KaiUnderstandingCounts
import com.shopai.app.brain.chat.KaiUnderstood
import org.json.JSONArray

/**
 * How well Kai understood the owner, counted on the phone only (owner, 21 Oct 2026: "sari puriyalana shop owners app ah use panna
 * matanga"): a count per outcome and the last [MAX_MISSED] lines Kai didn't get the first time — the owner's words, never audio,
 * never sent anywhere. Kai answers "Kai evlo purinjuchu" from it.
 */
class KaiUnderstandingLog(context: Context) {
    private val prefs = context.getSharedPreferences("kai_understanding", Context.MODE_PRIVATE)

    @Synchronized
    fun note(outcome: KaiUnderstood, said: String) {
        val e = prefs.edit().putInt(outcome.name, prefs.getInt(outcome.name, 0) + 1)
        if (outcome != KaiUnderstood.UNDERSTOOD && said.isNotBlank()) {
            val missed = (missed() + said.trim().take(120)).takeLast(MAX_MISSED)
            e.putString(MISSED, JSONArray(missed).toString())
        }
        e.apply()
    }

    @Synchronized
    fun counts(): KaiUnderstandingCounts =
        KaiUnderstandingCounts(KaiUnderstood.entries.associateWith { prefs.getInt(it.name, 0) }, missed())

    private fun missed(): List<String> = runCatching {
        val a = JSONArray(prefs.getString(MISSED, "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private companion object {
        const val MISSED = "missed"
        const val MAX_MISSED = 30
    }
}
