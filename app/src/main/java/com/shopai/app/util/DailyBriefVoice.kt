package com.shopai.app.util

import com.shopai.app.data.model.BusinessHealth
import com.shopai.app.data.model.PriorityItem
import java.time.LocalTime

private const val NOTHING_URGENT = "இன்னைக்கு எதுவும் அவசரம் இல்ல. எல்லாம் சரியா இருக்கு."

/** Builds the short Tamil voice greeting spoken once per session on Home (mirrors RN daily brief voiceText). */
object DailyBriefVoice {
    fun buildVoiceText(priorities: List<PriorityItem>, health: BusinessHealth?): String {
        val greeting = greetingForHour(LocalTime.now().hour)
        val body = priorities.firstOrNull()?.message
            ?: if (health?.status == "STABLE") NOTHING_URGENT else health?.explanation
            ?: NOTHING_URGENT
        return "$greeting $body"
    }

    private fun greetingForHour(hour: Int): String = when {
        hour in 5..11 -> "காலை வணக்கம்."
        hour in 12..16 -> "மதிய வணக்கம்."
        hour in 17..20 -> "மாலை வணக்கம்."
        else -> "இரவு வணக்கம்."
    }
}
