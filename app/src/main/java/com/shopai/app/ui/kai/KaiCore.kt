package com.shopai.app.ui.kai

import com.shopai.app.brain.KaiMood

/**
 * KAI — OwnerNote's AI Business Companion. What KAI is doing right now.
 * [rigIndex] is the value of the "state" input in KAI's Rive state
 * machine (see docs/KAI_RIVE_SPEC.md) — never reorder; new states go last.
 */
enum class KaiState(val rigIndex: Int) {
    IDLE(0),
    GREETING(1),
    LISTENING(2),
    /** Thinking / analysing (hand to chin). */
    PROCESSING(3),
    /** Talking; the face follows what is being said (see [KaiScene.speakingAs]). */
    SPEAKING(4),
    CREDIT(5),
    DEBIT(6),
    SUCCESS(7),
    REMINDER(8),
    INSIGHT(9),
    FUNDING(10),
    CLARIFY(11),
    HAPPY(12),
    CONFIDENT(13),
    SURPRISED(14),
    THOUGHTFUL(15),
    CONCERNED(16),
    SERIOUS(17),
    ERROR(18);

    companion object {
        /** Spec name for the analysing state. */
        val THINKING: KaiState get() = PROCESSING
    }
}

/** How KAI reacts once he understood what the owner said (the state he shows while and after speaking). */
enum class KaiReaction(val state: KaiState?) {
    CREDIT(KaiState.CREDIT),
    DEBIT(KaiState.DEBIT),
    /** A plain answer: back to idle after speaking. */
    ANSWER(null),
    CLARIFY(KaiState.CLARIFY),
    REMINDER(KaiState.REMINDER),
    INSIGHT(KaiState.INSIGHT),
    HAPPY(KaiState.HAPPY),
    CONFIDENT(KaiState.CONFIDENT),
    SURPRISED(KaiState.SURPRISED),
    CONCERNED(KaiState.CONCERNED),
    SERIOUS(KaiState.SERIOUS),
    ERROR(KaiState.ERROR),
}

sealed interface KaiEvent {
    /** App opened / a screen greets the owner. */
    data object Greet : KaiEvent
    /** The owner tapped Pesunga and the microphone is open. */
    data object Listen : KaiEvent
    /** The owner finished speaking; KAI is working it out. */
    data object Think : KaiEvent
    /** KAI understood: he speaks, then shows [reaction]. */
    data class Understood(val reaction: KaiReaction) : KaiEvent
    /** KAI's voice started / finished playing. */
    data object SpeechStarted : KaiEvent
    data object SpeechEnded : KaiEvent
    /** The entry was saved. */
    data object Saved : KaiEvent
    /** Couldn't hear / understand / save: KAI asks again (never looks like a crash). */
    data object NeedsClarification : KaiEvent
    /** Reminder, insight or funding card KAI presents. */
    data class Present(val state: KaiState) : KaiEvent
    /** The owner cancelled (stopped the mic, dismissed the dialog). */
    data object Cancel : KaiEvent
    /** A timed state ([KaiScene.holdMillis]) is over. */
    data object HoldElapsed : KaiEvent
}

/**
 * KAI's conversation state. Pure: [on] returns the next scene, so the
 * whole flow — LISTEN → THINK → SPEAK → REACT → SUCCESS → IDLE — is testable
 * without Android.
 */
data class KaiScene(
    val state: KaiState = KaiState.IDLE,
    /** Reaction to show once KAI finishes speaking. */
    val pending: KaiReaction? = null,
) {
    fun on(event: KaiEvent): KaiScene = when (event) {
        KaiEvent.Greet -> KaiScene(KaiState.GREETING)
        KaiEvent.Listen -> KaiScene(KaiState.LISTENING)
        KaiEvent.Think -> KaiScene(KaiState.PROCESSING)
        is KaiEvent.Understood -> KaiScene(KaiState.SPEAKING, event.reaction)
        KaiEvent.SpeechStarted -> copy(state = KaiState.SPEAKING)
        KaiEvent.SpeechEnded -> when (val shown = pending?.state) {
            null -> if (state == KaiState.SPEAKING) KaiScene(KaiState.IDLE) else this
            else -> KaiScene(shown)
        }
        KaiEvent.Saved -> KaiScene(KaiState.SUCCESS)
        KaiEvent.NeedsClarification -> KaiScene(KaiState.CLARIFY)
        is KaiEvent.Present -> KaiScene(event.state)
        KaiEvent.Cancel -> KaiScene(KaiState.IDLE)
        KaiEvent.HoldElapsed -> if (holdMillis != null) KaiScene(KaiState.IDLE) else this
    }

    /** While speaking: the expression that fits what KAI is saying (null: his explaining gesture). */
    val speakingAs: KaiState? get() = if (state == KaiState.SPEAKING) pending?.state else null

    /**
     * States that play once and then return to IDLE after this long; null
     * for states that last until something happens (listening, thinking,
     * speaking, or a Credit/Debit card waiting to be saved).
     */
    val holdMillis: Long?
        get() = when (state) {
            KaiState.GREETING -> 3_200L
            KaiState.SUCCESS -> 1_800L
            KaiState.CLARIFY -> 2_600L
            KaiState.REMINDER, KaiState.INSIGHT, KaiState.FUNDING -> 5_000L
            KaiState.HAPPY, KaiState.CONFIDENT, KaiState.SURPRISED, KaiState.THOUGHTFUL,
            KaiState.CONCERNED, KaiState.SERIOUS, KaiState.ERROR -> 4_000L
            else -> null
        }
}
/** "₹2,000" / "₹1,24,500.50" — how KAI shows an amount (Indian grouping, paise only when there are any). */
fun kaiRupees(amount: Double): String {
    val paise = Math.round(amount * 100)
    val whole = (paise / 100).toString()
    val grouped = if (whole.length <= 3) whole else {
        whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
    }
    val rest = paise % 100
    return "₹" + grouped + if (rest == 0L) "" else "." + rest.toString().padStart(2, '0')
}

/** "2000" / "2000.50" — the amount as KAI's voice reads it. */
fun kaiSpokenAmount(amount: Double): String {
    val paise = Math.round(amount * 100)
    return if (paise % 100 == 0L) (paise / 100).toString() else "%d.%02d".format(paise / 100, paise % 100)
}

/** How Kai reacts after giving a Business Brain reply. */
fun KaiMood.reaction(): KaiReaction = when (this) {
    KaiMood.CREDIT -> KaiReaction.CREDIT
    KaiMood.DEBIT -> KaiReaction.DEBIT
    KaiMood.CLARIFY -> KaiReaction.CLARIFY
    KaiMood.REMINDER -> KaiReaction.REMINDER
    KaiMood.SERIOUS -> KaiReaction.SERIOUS
    KaiMood.CONCERNED -> KaiReaction.CONCERNED
    KaiMood.EXPLAINING -> KaiReaction.INSIGHT
    KaiMood.HAPPY -> KaiReaction.HAPPY
    KaiMood.SURPRISED -> KaiReaction.SURPRISED
    KaiMood.ERROR -> KaiReaction.ERROR
    KaiMood.SUCCESS, KaiMood.NEUTRAL -> KaiReaction.ANSWER
}

/** Kai's character state for a reply's mood. */
fun KaiMood.state(): KaiState = when (this) {
    KaiMood.CREDIT -> KaiState.CREDIT
    KaiMood.DEBIT -> KaiState.DEBIT
    KaiMood.SUCCESS -> KaiState.SUCCESS
    KaiMood.CLARIFY -> KaiState.CLARIFY
    KaiMood.REMINDER -> KaiState.REMINDER
    KaiMood.SERIOUS -> KaiState.SERIOUS
    KaiMood.CONCERNED -> KaiState.CONCERNED
    KaiMood.EXPLAINING -> KaiState.INSIGHT
    KaiMood.HAPPY -> KaiState.HAPPY
    KaiMood.SURPRISED -> KaiState.SURPRISED
    KaiMood.ERROR -> KaiState.ERROR
    KaiMood.NEUTRAL -> KaiState.IDLE
}