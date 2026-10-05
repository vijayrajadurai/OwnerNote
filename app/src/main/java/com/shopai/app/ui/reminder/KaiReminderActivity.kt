package com.shopai.app.ui.reminder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import com.shopai.app.ui.kai.KaiCharacter
import com.shopai.app.ui.kai.KaiState

/**
 * KAI URGENT ACTION MODE — the dedicated screen a reminder opens when it rings
 * (never Home, never Kai Chat, never a generic alarm). Shown over the lock
 * screen through Android's own full-screen-intent / show-when-locked APIs
 * (no overlay, no keyguard bypass): Call Now asks Android to unlock first.
 *
 * One reminder engine behind it: Done / Snooze / Call go through
 * [KaiReminderEngine]; the screen closes itself when the reminder is no
 * longer ringing (Done or Snooze from the notification, Cancel from chat).
 */
class KaiReminderActivity : ComponentActivity() {

    private val container get() = (application as ShopAiApplication).container
    private var reminderId by mutableStateOf<String?>(null)

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
        intent.getStringExtra(KaiReminderEngine.EXTRA_ID)?.let { reminderId = it }
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

        // Done / Snooze / Cancel from anywhere (notification, chat): this screen closes.
        val ringing = reminder != null && (reminder.status == ReminderStatus.RANG || reminder.status == ReminderStatus.EXHAUSTED ||
            (reminder.status == ReminderStatus.ACTIVE && reminder.lastTriggeredAt != null && reminder.attemptCount > 0))
        LaunchedEffect(ringing) { if (!ringing) finish() }
        if (reminder == null || !ringing) return

        val lang = reminder.lang
        val words = KaiUrgentWords.text(reminder, lang)
        val speaker = container.naturalTtsSpeaker
        val speaking by speaker.speaking.collectAsState()
        val mouth by speaker.mouthLevel.collectAsState()

        // Kai speaks this attempt once (not again on rotation, a second tap or a process restart).
        LaunchedEffect(KaiReminderFlow.speechKey(reminder)) {
            if (engine.claimSpeech(reminder)) {
                val code = if (lang == KaiLang.ENGLISH) "en-IN" else "ta-IN"
                speaker.speakNatural(words.speech, languageCode = code, fallbackText = words.speech, fallbackLanguage = code)
                Log.i(TAG, "spoke ${KaiReminderFlow.speechKey(reminder)}")
            }
        }

        UrgentContent(
            reminder = reminder,
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
            onCall = {
                speaker.stop()
                callNow(reminder) { note = it }
            },
            onDone = {
                speaker.stop()
                if (engine.complete(reminder.id)) toast(KaiUrgentWords.done(lang))
                finish()
            },
            onSnooze = {
                speaker.stop()
                if (engine.snooze(reminder.id, KaiReminderEngine.SNOOZE_MINUTES) != null) toast(KaiUrgentWords.snoozed(KaiReminderEngine.SNOOZE_MINUTES, lang))
                finish()
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

private val UrgentBackground = Color(0xFF07080B)
private val UrgentSurface = Color(0xFF14161C)
private val UrgentText = Color(0xFFF2F2F5)
private val UrgentSecondary = Color(0xFF9A9AA6)
private val UrgentGreen = Color(0xFF2FBE7C)
private val UrgentGlow = Color(0xFFF0A93E)

private fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
private fun UrgentContent(
    reminder: KaiReminder,
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
    onCall: () -> Unit,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
) {
    val context = LocalContext.current
    val still = remember { reducedMotion(context) }
    // A little more urgent with each attempt — slower and softer at first, never flashing.
    val attempt = reminder.attemptCount.coerceIn(1, reminder.maxAttempts)
    val period = (2_600 - (attempt - 1) * 250).coerceAtLeast(1_600)
    val depth = 0.04f + (attempt - 1) * 0.012f
    val motion = rememberInfiniteTransition(label = "urgent")
    val breathe by motion.animateFloat(1f, 1f + depth, infiniteRepeatable(tween(period / 2), RepeatMode.Reverse), label = "breathe")
    val ring by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing)), label = "ring")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D0F14), UrgentBackground))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            // Kai, awake: his reminder face, breathing, a soft pulse ring behind him, lips moving with his voice.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(listOf(UrgentGlow.copy(alpha = 0.28f), Color.Transparent), center = center, radius = r),
                        radius = r,
                    )
                    if (!still) {
                        drawCircle(
                            color = UrgentGlow.copy(alpha = (1f - ring) * 0.35f),
                            radius = r * (0.55f + 0.45f * ring),
                            center = Offset(center.x, center.y),
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                }
                KaiCharacter(
                    state = if (speaking) KaiState.SPEAKING else KaiState.REMINDER,
                    speakingAs = KaiState.REMINDER,
                    mouthLevel = mouthLevel,
                    size = 200.dp,
                    modifier = Modifier.scale(if (still) 1f else breathe),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                header,
                color = UrgentGlow,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                headline,
                color = UrgentText,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Spacer(Modifier.height(8.dp))
            Text(question, color = UrgentText.copy(alpha = 0.86f), fontSize = 18.sp, lineHeight = 26.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(setAgo, color = UrgentSecondary, fontSize = 13.sp)
            note?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = UrgentGreen, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (callLabel != null) {
                    Button(
                        onClick = onCall,
                        colors = ButtonDefaults.buttonColors(containerColor = UrgentGreen, contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text(callLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                }
                val doneFirst = callLabel == null
                if (doneFirst) {
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(containerColor = UrgentGreen, contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text(doneLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                } else {
                    OutlinedButton(
                        onClick = onDone,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = UrgentSurface, contentColor = UrgentText),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(doneLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
                if (snoozeLabel != null) {
                    TextButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(snoozeLabel, color = UrgentSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(attemptLine, color = UrgentSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }
    }
}
