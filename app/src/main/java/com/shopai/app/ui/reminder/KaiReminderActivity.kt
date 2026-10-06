package com.shopai.app.ui.reminder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.ShopAiApplication
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiReminderFlow
import com.shopai.app.brain.tools.KaiUrgentVoiceScript
import com.shopai.app.brain.tools.KaiUrgentWords
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderStatus
import com.shopai.app.notifications.KaiReminderEngine
import kotlinx.coroutines.launch

/**
 * KAI URGENT ACTION MODE — the dedicated screen a reminder opens when it rings
 * (never Home, never Kai Chat, never a generic alarm). Shown over the lock
 * screen through Android's own full-screen-intent / show-when-locked APIs
 * (no overlay, no keyguard bypass): Call Now asks Android to unlock first.
 *
 * One reminder engine behind it: Done / Snooze / Call go through
 * [KaiReminderEngine]; the screen closes itself when the reminder is no
 * longer ringing (Done or Snooze from the notification, Cancel from chat).
 * While Kai is on screen the same ring's banner is removed (no duplicate);
 * leaving without an answer puts a quiet notification back.
 *
 * The screen is Kai's: he fills it, acts the reminder with his body ([KaiActing]) and keeps
 * reminding by voice ([KaiUrgentVoice]) until Call / Done / Snooze, which stop him at once.
 */
class KaiReminderActivity : ComponentActivity() {

    private val container get() = (application as ShopAiApplication).container
    private var reminderId by mutableStateOf<String?>(null)
    /** Done / Snooze was pressed — leaving the screen is the answer, not "left unanswered". */
    private var answered = false
    /** The ring on screen (its voice pauses when the screen goes away unanswered). */
    private var ringOnScreen: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        reminderId = intent?.getStringExtra(KaiReminderEngine.EXTRA_ID)
        Log.i(TAG, "Kai Urgent Action Mode opened for $reminderId (locked=${getSystemService(KeyguardManager::class.java)?.isKeyguardLocked})")
        setContent {
            val id = reminderId
            if (id == null) {
                LaunchedEffect(Unit) { finish() }
            } else {
                UrgentScreen(id)
            }
        }
        // Kai starts speaking as the screen opens — not after the first frames are drawn (that can take
        // seconds on a slow phone, and the voice would wait for it).
        beginVoice()
    }

    private fun ringing(r: KaiReminder) = r.status == ReminderStatus.RANG || r.status == ReminderStatus.EXHAUSTED ||
        (r.status == ReminderStatus.ACTIVE && r.lastTriggeredAt != null && r.attemptCount > 0)

    /**
     * Kai keeps reminding until the owner acts: one voice loop for this ring (not again on rotation,
     * recomposition, resume or a second tap; the opening line once per attempt, even across a restart).
     */
    private fun beginVoice() {
        if (answered) return
        runCatching {
            val r = reminderId?.let { container.kaiReminders.find(it) }?.takeIf(::ringing) ?: return
            val ringKey = KaiReminderFlow.speechKey(r)
            ringOnScreen = ringKey
            val first = container.kaiReminders.claimSpeech(r)
            val started = container.kaiUrgentVoice.start(
                ringKey, KaiUrgentVoiceScript.lines(r, r.lang), KaiUrgentVoiceScript.languageCode(r.lang), openingAlreadySpoken = !first,
            )
            if (started && first) Log.i(TAG, "spoke $ringKey")
        }.onFailure { Log.w(TAG, "voice not started: ${it.message}") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(KaiReminderEngine.EXTRA_ID)?.let { reminderId = it; answered = false }
    }

    override fun onResume() {
        super.onResume()
        // Back on screen without an answer: Kai's voice continues (never from the top, never twice).
        beginVoice()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Kai is really on screen now (over the lock screen too): the banner for the same ring would cover
        // him — removed. Not earlier (resumed is not yet visible), so the ring is never left with neither.
        if (hasFocus) reminderId?.let { runCatching { container.kaiReminders.urgentScreenShown(it) } }
    }

    override fun onStop() {
        // Back / home / power / the dialer without Done or Snooze: the reminder is still waiting — a quiet
        // notification keeps it, and Kai's voice pauses (rotation keeps it going: same ring, same loop).
        if (!answered && !isChangingConfigurations) {
            reminderId?.let { runCatching { container.kaiReminders.urgentScreenLeft(it) } }
            ringOnScreen?.let { runCatching { container.kaiUrgentVoice.pause(it) } }
        }
        super.onStop()
    }

    /** Android's supported lock-screen APIs only (setShowWhenLocked / setTurnScreenOn). */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Composable
    private fun UrgentScreen(id: String) {
        val engine = container.kaiReminders
        val voice = container.kaiUrgentVoice
        val tick by engine.changes.collectAsState()
        val reminder = remember(id, tick) { engine.find(id) }
        var note by remember(id) { mutableStateOf<String?>(null) }
        val exit = remember(id) { Animatable(0f) }
        var acknowledging by remember(id) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // Done / Snooze / Cancel from anywhere (notification, chat): this screen closes.
        val isRinging = reminder != null && ringing(reminder)
        LaunchedEffect(isRinging) { if (!isRinging && !answered) finish() }
        if (reminder == null || (!isRinging && !answered)) return

        val lang = reminder.lang
        val words = KaiUrgentWords.text(reminder, lang)
        val controls = KaiUrgentVoiceScript.controls(KaiReminderEngine.SNOOZE_MINUTES, lang)
        val code = KaiUrgentVoiceScript.languageCode(lang)
        val speaker = container.naturalTtsSpeaker
        val mouth by speaker.mouthLevel.collectAsState()
        val cue by voice.cue.collectAsState()
        val ringKey = KaiReminderFlow.speechKey(reminder)
        val openedAt = remember(ringKey) { KaiUrgentClock.openedAt(ringKey) }

        // A new attempt while the screen stays open: its own voice cycle (beginVoice does nothing if already speaking).
        LaunchedEffect(ringKey) { beginVoice() }

        /** The answer is saved first; Kai's voice stops at once (one short reply); then he settles out. */
        fun leave(snooze: Boolean, saved: Boolean, message: String, ack: String) {
            answered = true
            voice.answer(ringKey, ack.takeIf { saved }, code)
            if (saved) toast(message)
            acknowledging = snooze
            scope.launch {
                exit.animateTo(1f, tween(KaiUrgentMotion.EXIT_MS.toInt()))
                finish()
            }
        }

        UrgentContent(
            reminder = reminder,
            ringKey = ringKey,
            openedAt = openedAt,
            headline = words.headline,
            question = words.question,
            header = words.header,
            meta = KaiUrgentWords.setAgo(reminder.createdAt, System.currentTimeMillis(), lang) + "  ·  " + words.attemptLine,
            callLabel = controls.call.takeIf { reminder.action == ReminderAction.CALL && reminder.person != null },
            doneLabel = controls.done,
            snoozeLabel = controls.snooze.takeIf { KaiReminderFlow.canSnooze(reminder) },
            note = note,
            mouthLevel = mouth,
            speech = cue?.let { SpeechCue(it.startedAt - openedAt, it.endedAt?.minus(openedAt), it.gesture) },
            exit = exit.value,
            acknowledging = acknowledging,
            onCall = {
                // Voice stops now; Kai says he is opening the call screen; the dialer opens (never "called").
                voice.answer(ringKey, KaiUrgentVoiceScript.callAck(reminder, lang), code)
                callNow(reminder) { note = it }
            },
            onDone = {
                leave(snooze = false, saved = engine.complete(reminder.id), message = KaiUrgentWords.done(lang), ack = KaiUrgentVoiceScript.doneAck(lang))
            },
            onSnooze = {
                val saved = engine.snooze(reminder.id, KaiReminderEngine.SNOOZE_MINUTES) != null
                leave(
                    snooze = true, saved = saved, message = KaiUrgentWords.snoozed(KaiReminderEngine.SNOOZE_MINUTES, lang),
                    ack = KaiUrgentVoiceScript.snoozeAck(KaiReminderEngine.SNOOZE_MINUTES, lang),
                )
            },
        )
    }

    /**
     * Call Now: the existing dialer (ACTION_DIAL — the owner presses call). Over the lock screen
     * Android asks the owner to unlock first. Never "called": only "call screen open pannitten".
     */
    private fun callNow(r: KaiReminder, say: (String) -> Unit) {
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (r.phone ?: ""))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = {
            runCatching { startActivity(dial) }
                .onSuccess { say(if (r.phone == null) KaiUrgentWords.noNumber(r.person, r.lang) else KaiUrgentWords.callOpened(r.lang)) }
                .onFailure { Log.w(TAG, "dialer not opened: ${it.message}") }
            Unit
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isKeyguardLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open()
            })
        } else {
            open()
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "KaiReminder"
    }
}

// ------------------------------------------------------------------ UI

private val UrgentBackground = Color(0xFF050608)
private val UrgentSurface = Color(0xFF15161C)
private val UrgentHairline = Color(0xFF2A2A33)
private val UrgentText = Color(0xFFF2F2F5)
private val UrgentSecondary = Color(0xFF9A9AA6)

private fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** A word block that fades and slides up at its moment in the intro (no typewriter). */
private fun Modifier.entrance(shown: Float, at: Long, exit: Float, risePx: Float): Modifier = graphicsLayer {
    alpha = KaiUrgentMotion.textAlpha(shown, at) * (1f - exit)
    translationY = risePx
}

/** Milliseconds this ring's screen has been showing — ticks only while the words are still arriving. */
@Composable
private fun rememberShownMs(openedAt: Long, still: Boolean): Float {
    val endOfWords = KaiUrgentMotion.BUTTONS_AT + KaiUrgentMotion.TEXT_FADE_MS
    var shown by remember(openedAt) { mutableLongStateOf(if (still) endOfWords else SystemClock.elapsedRealtime() - openedAt) }
    LaunchedEffect(openedAt, still) {
        while (shown < endOfWords) withFrameMillis { shown = SystemClock.elapsedRealtime() - openedAt }
    }
    return shown.toFloat()
}

private enum class ControlStyle { PRIMARY, SECONDARY, QUIET }

/** A compact premium control: small to look at, a full 48 dp to touch. */
@Composable
private fun UrgentControl(
    label: String,
    icon: ImageVector,
    style: ControlStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    val shape = RoundedCornerShape(percent = 50)
    val (fill, content) = when (style) {
        ControlStyle.PRIMARY -> UrgentAccent to Color.White
        ControlStyle.SECONDARY -> UrgentSurface to UrgentText
        ControlStyle.QUIET -> Color.Transparent to UrgentText.copy(alpha = 0.78f)
    }
    Row(
        modifier = modifier
            .scale(press)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(fill, shape)
            .then(if (style == ControlStyle.PRIMARY) Modifier else Modifier.border(1.dp, UrgentHairline, shape))
            .clickable(interactionSource = source, indication = ripple(color = content), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun UrgentContent(
    reminder: KaiReminder,
    ringKey: String,
    openedAt: Long,
    headline: String,
    question: String,
    header: String,
    meta: String,
    callLabel: String?,
    doneLabel: String,
    snoozeLabel: String?,
    note: String?,
    mouthLevel: Float,
    speech: SpeechCue?,
    exit: Float,
    acknowledging: Boolean,
    onCall: () -> Unit,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val still = remember { reducedMotion(context) }
    val shown = rememberShownMs(openedAt, still)
    fun rise(at: Long) = with(density) { KaiUrgentMotion.textRiseDp(shown, at).dp.toPx() }

    val kai: @Composable (Modifier) -> Unit = { modifier ->
        KaiUrgentActor(
            ringKey = ringKey,
            attempt = reminder.attemptCount.coerceIn(1, reminder.maxAttempts),
            openedAt = openedAt,
            mouthLevel = mouthLevel,
            speech = speech,
            exit = exit,
            acknowledging = acknowledging,
            still = still,
            modifier = modifier,
        )
    }
    val words: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                header, color = UrgentAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                modifier = Modifier.entrance(shown, KaiUrgentMotion.HEADER_AT, exit, rise(KaiUrgentMotion.HEADER_AT)).semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                headline, color = UrgentText, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.entrance(shown, KaiUrgentMotion.HEADLINE_AT, exit, rise(KaiUrgentMotion.HEADLINE_AT))
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                question, color = UrgentText.copy(alpha = 0.86f), fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
                modifier = Modifier.entrance(shown, KaiUrgentMotion.QUESTION_AT, exit, rise(KaiUrgentMotion.QUESTION_AT)),
            )
            Spacer(Modifier.height(4.dp))
            Text(meta, color = UrgentSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.entrance(shown, KaiUrgentMotion.DETAILS_AT, exit, 0f))
            note?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = UrgentAccent, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).entrance(shown, KaiUrgentMotion.BUTTONS_AT, exit, rise(KaiUrgentMotion.BUTTONS_AT)),
        ) {
            if (callLabel != null) {
                UrgentControl(callLabel, Icons.Filled.Call, ControlStyle.PRIMARY, modifier = Modifier.weight(1.15f), onClick = {
                    view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                    onCall()
                })
            }
            UrgentControl(
                doneLabel, Icons.Filled.Check, if (callLabel == null) ControlStyle.PRIMARY else ControlStyle.SECONDARY, modifier = Modifier.weight(1f),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onDone()
                },
            )
            if (snoozeLabel != null) {
                UrgentControl(snoozeLabel, Icons.Filled.Snooze, ControlStyle.QUIET, modifier = Modifier.weight(1.25f), onClick = onSnooze)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(UrgentBackground)) {
        if (maxWidth <= maxHeight) {
            // Portrait: Kai fills everything above the words — head to sandals, ~70 % of the screen.
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                kai(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
                Spacer(Modifier.height(10.dp))
                words()
                Spacer(Modifier.height(16.dp))
                actions()
                Spacer(Modifier.height(12.dp))
            }
        } else {
            // Landscape: Kai on the left, the words and controls beside him.
            Row(Modifier.fillMaxSize().systemBarsPadding()) {
                kai(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                ) {
                    words()
                    Spacer(Modifier.height(16.dp))
                    actions()
                }
            }
        }
    }
}
