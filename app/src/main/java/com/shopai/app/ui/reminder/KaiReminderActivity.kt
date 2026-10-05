package com.shopai.app.ui.reminder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.ShopAiApplication
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiReminderFlow
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
 */
class KaiReminderActivity : ComponentActivity() {

    private val container get() = (application as ShopAiApplication).container
    private var reminderId by mutableStateOf<String?>(null)
    /** Done / Snooze was pressed — leaving the screen is the answer, not "left unanswered". */
    private var answered = false

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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(KaiReminderEngine.EXTRA_ID)?.let { reminderId = it; answered = false }
    }

    override fun onResume() {
        super.onResume()
        // Kai is on screen: the banner for the same ring would cover him — removed (the fallback keeps its notification).
        reminderId?.let { runCatching { container.kaiReminders.urgentScreenShown(it) } }
    }

    override fun onStop() {
        // Back / home / the dialer without Done or Snooze: the reminder is still waiting — a quiet notification keeps it.
        if (!answered && !isChangingConfigurations) reminderId?.let { runCatching { container.kaiReminders.urgentScreenLeft(it) } }
        super.onStop()
    }

    override fun onDestroy() {
        // Kai stops talking when his screen goes away (Done / Snooze / back) — the reminder itself stays as it is.
        if (isFinishing) runCatching { container.naturalTtsSpeaker.stop() }
        super.onDestroy()
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
        val tick by engine.changes.collectAsState()
        val reminder = remember(id, tick) { engine.find(id) }
        var note by remember(id) { mutableStateOf<String?>(null) }
        val exit = remember(id) { Animatable(0f) }
        var acknowledging by remember(id) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // Done / Snooze / Cancel from anywhere (notification, chat): this screen closes.
        val ringing = reminder != null && (reminder.status == ReminderStatus.RANG || reminder.status == ReminderStatus.EXHAUSTED ||
            (reminder.status == ReminderStatus.ACTIVE && reminder.lastTriggeredAt != null && reminder.attemptCount > 0))
        LaunchedEffect(ringing) { if (!ringing && !answered) finish() }
        if (reminder == null || (!ringing && !answered)) return

        val lang = reminder.lang
        val words = KaiUrgentWords.text(reminder, lang)
        val speaker = container.naturalTtsSpeaker
        val speaking by speaker.speaking.collectAsState()
        val mouth by speaker.mouthLevel.collectAsState()
        val ringKey = KaiReminderFlow.speechKey(reminder)

        // Kai speaks this attempt once (not again on rotation, a second tap, resume or a process restart).
        LaunchedEffect(ringKey) {
            if (engine.claimSpeech(reminder)) {
                val code = if (lang == KaiLang.ENGLISH) "en-IN" else "ta-IN"
                speaker.speakNatural(words.speech, languageCode = code, fallbackText = words.speech, fallbackLanguage = code)
                Log.i(TAG, "spoke $ringKey")
            }
        }

        /** The answer is saved first; then Kai settles out (never delays the action). */
        fun leave(snooze: Boolean, saved: Boolean, message: String) {
            answered = true
            speaker.stop()
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
            headline = words.headline,
            question = words.question,
            header = words.header,
            setAgo = KaiUrgentWords.setAgo(reminder.createdAt, System.currentTimeMillis(), lang),
            attemptLine = words.attemptLine,
            callLabel = words.callLabel.takeIf { reminder.action == ReminderAction.CALL && reminder.person != null },
            doneLabel = words.doneLabel,
            snoozeLabel = words.snoozeLabel.takeIf { KaiReminderFlow.canSnooze(reminder) },
            note = note,
            speaking = speaking,
            mouthLevel = mouth,
            exit = exit.value,
            acknowledging = acknowledging,
            onCall = {
                speaker.stop()
                callNow(reminder) { note = it }
            },
            onDone = { leave(snooze = false, saved = engine.complete(reminder.id), message = KaiUrgentWords.done(lang)) },
            onSnooze = {
                val saved = engine.snooze(reminder.id, KaiReminderEngine.SNOOZE_MINUTES) != null
                leave(snooze = true, saved = saved, message = KaiUrgentWords.snoozed(KaiReminderEngine.SNOOZE_MINUTES, lang))
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
private val UrgentSurface = Color(0xFF12141A)
private val UrgentText = Color(0xFFF2F2F5)
private val UrgentSecondary = Color(0xFF9A9AA6)

private fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** A word block that fades and slides up at its moment in the intro (no typewriter). */
private fun Modifier.entrance(intro: Float, at: Long, exit: Float, risePx: Float): Modifier = graphicsLayer {
    alpha = KaiUrgentMotion.textAlpha(intro, at) * (1f - exit)
    translationY = risePx
}

@Composable
private fun PressScale(pressed: Boolean): Float {
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    return s
}

@Composable
private fun UrgentContent(
    reminder: KaiReminder,
    ringKey: String,
    headline: String,
    question: String,
    header: String,
    setAgo: String,
    attemptLine: String,
    callLabel: String?,
    doneLabel: String,
    snoozeLabel: String?,
    note: String?,
    speaking: Boolean,
    mouthLevel: Float,
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
    val intro by rememberIntroClock(ringKey, still)
    val wake = if (still) 1f else KaiUrgentMotion.wake(intro)
    fun rise(at: Long) = with(density) { KaiUrgentMotion.textRiseDp(intro, at).dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UrgentBackground)
            // Atmosphere: an almost invisible green depth behind Kai, nothing more.
            .background(Brush.radialGradient(listOf(UrgentAccent.copy(alpha = 0.07f * wake * (1f - exit)), Color.Transparent), radius = 1_400f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            KaiUrgentStage(
                intro = intro,
                attempt = reminder.attemptCount.coerceIn(1, reminder.maxAttempts),
                speaking = speaking,
                mouthLevel = mouthLevel,
                exit = exit,
                acknowledging = acknowledging,
                still = still,
                size = 290.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                header,
                color = UrgentAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.entrance(intro, KaiUrgentMotion.HEADER_AT, exit, rise(KaiUrgentMotion.HEADER_AT)).semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                headline,
                color = UrgentText,
                fontSize = 27.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.entrance(intro, KaiUrgentMotion.HEADLINE_AT, exit, rise(KaiUrgentMotion.HEADLINE_AT))
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                question, color = UrgentText.copy(alpha = 0.86f), fontSize = 18.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
                modifier = Modifier.entrance(intro, KaiUrgentMotion.QUESTION_AT, exit, rise(KaiUrgentMotion.QUESTION_AT)),
            )
            Spacer(Modifier.height(6.dp))
            Text(setAgo, color = UrgentSecondary, fontSize = 13.sp, modifier = Modifier.entrance(intro, KaiUrgentMotion.DETAILS_AT, exit, 0f))
            note?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = UrgentAccent, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Spacer(Modifier.height(26.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().entrance(intro, KaiUrgentMotion.BUTTONS_AT, exit, rise(KaiUrgentMotion.BUTTONS_AT)),
            ) {
                if (callLabel != null) {
                    val source = remember { MutableInteractionSource() }
                    val pressed by source.collectIsPressedAsState()
                    Button(
                        onClick = {
                            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                            onCall()
                        },
                        interactionSource = source,
                        colors = ButtonDefaults.buttonColors(containerColor = UrgentAccent, contentColor = Color.White),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).scale(PressScale(pressed)),
                    ) { Text(callLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) }
                }
                val doneSource = remember { MutableInteractionSource() }
                val donePressed by doneSource.collectIsPressedAsState()
                val doneClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onDone()
                }
                if (callLabel == null) {
                    Button(
                        onClick = doneClick,
                        interactionSource = doneSource,
                        colors = ButtonDefaults.buttonColors(containerColor = UrgentAccent, contentColor = Color.White),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).scale(PressScale(donePressed)),
                    ) { Text(doneLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                } else {
                    OutlinedButton(
                        onClick = doneClick,
                        interactionSource = doneSource,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = UrgentSurface, contentColor = UrgentText),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).scale(PressScale(donePressed)),
                    ) { Text(doneLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
                if (snoozeLabel != null) {
                    val snoozeSource = remember { MutableInteractionSource() }
                    val snoozePressed by snoozeSource.collectIsPressedAsState()
                    TextButton(
                        onClick = onSnooze,
                        interactionSource = snoozeSource,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).scale(PressScale(snoozePressed)),
                    ) { Text(snoozeLabel, color = UrgentSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(attemptLine, color = UrgentSecondary, fontSize = 13.sp, modifier = Modifier.entrance(intro, KaiUrgentMotion.BUTTONS_AT, exit, 0f))
            Spacer(Modifier.height(8.dp))
        }
    }
}
