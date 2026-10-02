package com.shopai.app.util

import com.shopai.app.data.model.PriorityItem
import com.shopai.app.data.model.TodayCashSummary

/**
 * Daily Voice Check-in — builds each of the 4 fixed-time slot messages from
 * real data only (existing PriorityItem list / TodayCashSummary), same
 * "never fabricate, never approximate mislabeled data" discipline as
 * DailyBriefVoice.kt. When a slot's real data doesn't exist yet, it speaks a
 * plain greeting only — never a guessed or proxied figure.
 */
enum class VoiceCheckinSlot(val hour: Int) {
    MORNING_8AM(8),
    NOON_12PM(12),
    EVENING_4PM(16),
    NIGHT_8PM(20),
}

object VoiceCheckinTextBuilder {
    private const val GREETING_MORNING = "காலை வணக்கம் ஓனர்."
    private const val GREETING_NOON = "மதியம் வணக்கம் ஓனர்."
    private const val GREETING_EVENING = "மாலை வணக்கம் ஓனர்."
    private const val GREETING_NIGHT = "இரவு வணக்கம் ஓனர்."

    private const val COLLECTION_DUE = "COLLECTION_DUE"

    /**
     * [priorities] is only read for MORNING_8AM/EVENING_4PM;
     * [todayCashSummary] only for NIGHT_8PM. NOON_12PM never has a real
     * same-day "sales" aggregate anywhere in this app, so it is always a
     * plain greeting — never a proxied or approximate figure.
     */
    fun build(
        slot: VoiceCheckinSlot,
        priorities: List<PriorityItem> = emptyList(),
        todayCashSummary: TodayCashSummary? = null,
    ): String = when (slot) {
        VoiceCheckinSlot.MORNING_8AM -> buildMorning(priorities)
        VoiceCheckinSlot.NOON_12PM -> GREETING_NOON
        VoiceCheckinSlot.EVENING_4PM -> buildEvening(priorities)
        VoiceCheckinSlot.NIGHT_8PM -> buildNight(todayCashSummary)
    }

    private fun buildMorning(priorities: List<PriorityItem>): String {
        val dueCount = priorities.count { it.kind == COLLECTION_DUE }
        if (dueCount == 0) return GREETING_MORNING
        return "$GREETING_MORNING இன்னைக்கு $dueCount பேருக்கு payment due இருக்கு."
    }

    private fun buildEvening(priorities: List<PriorityItem>): String {
        val topDue = priorities
            .filter { it.kind == COLLECTION_DUE && it.amount != null }
            .maxByOrNull { it.amount ?: 0.0 }
            ?: return GREETING_EVENING
        return "$GREETING_EVENING ${topDue.message} ${formatInr(topDue.amount ?: 0.0)} pending."
    }

    private fun buildNight(summary: TodayCashSummary?): String {
        if (summary == null || summary.entryCount == 0) return GREETING_NIGHT
        val net = summary.totalIn - summary.totalOut
        return "$GREETING_NIGHT இன்னைக்கு total ${formatInr(net)}. Cash box tally பண்ணுங்க."
    }

    private fun formatInr(amount: Double): String = "₹${Math.round(amount)}"
}
