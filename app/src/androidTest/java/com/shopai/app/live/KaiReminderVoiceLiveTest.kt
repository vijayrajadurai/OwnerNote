package com.shopai.app.live

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.shopai.app.ShopAiApplication
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiAgent
import com.shopai.app.brain.chat.KaiBooks
import com.shopai.app.brain.chat.KaiBusinessBrain
import com.shopai.app.brain.chat.KaiTurn
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiUrgentVoiceScript
import com.shopai.app.brain.tools.ReminderStatus
import com.shopai.app.data.tts.WavJoin
import com.shopai.app.notifications.KaiReminderEngine
import com.shopai.app.ui.reminder.KaiReminderActivity
import com.shopai.app.ui.reminder.KaiUrgentDebug
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * LIVE, fully automatic: Kai Reminder natural voice + personal reminders on the Pixel 8 emulator.
 * The owner only watches the screen and listens — nothing to type or tap.
 *
 *   v1 (D) chat only: "Paiyana 4 manikku …" → Confirm → "Time maathu" → "5 mani" → the SAME reminder at 5 PM.
 *   v2 (A) "Kumar-ku 2 minutes-la call panna remind pannu" → Confirm → REAL alarm → the opening turn is
 *          ONE continuous clip (measured on the real natural-voice audio) → CALL NOW while Kai is speaking
 *          → the voice stops at once, nothing queued plays.
 *   v3 (B) "2 minutes-la paiyana school-la irundhu kootitu vara nyabagam paduthu" → … → DONE mid-sentence.
 *   v4 (C) "5 minutes-la Amma-ku call panna remind pannu" → … → SNOOZE mid-sentence.
 *
 * Measured: the silences at the joins of the real Sarvam clips (target ~0.25–0.5 s, then ~0.7–1.2 s),
 * the silence the old way would have had (clip padding + 6.5 s), whether the turn played as one
 * uninterrupted utterance, and how fast Call / Done / Snooze cut the voice.
 *
 * Lines are logged under KaiVoiceLive; a summary + screenshots go to
 * /sdcard/Android/data/<app>/files/kai-voice-live/ (scripts/kai-voice-pixel8.ps1 pulls them).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KaiReminderVoiceLiveTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val container get() = (ctx.applicationContext as ShopAiApplication).container
    private val engine: KaiReminderEngine get() = container.kaiReminders
    private val speaker get() = container.naturalTtsSpeaker
    private val created = mutableListOf<String>()
    private val out = File(ctx.getExternalFilesDir(null), "kai-voice-live").apply { mkdirs() }
    private val summary = File(out, "summary.txt")

    private class NoBooks : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: java.time.LocalDate, to: java.time.LocalDate) = null
    }

    // ------------------------------------------------------------ helpers

    private fun report(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
        summary.appendText(line + "\n")
    }

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    private fun waitFor(ms: Long, step: Long = 100, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return true
            Thread.sleep(step)
        }
        return runCatching(check).getOrDefault(false)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun speaking(): Boolean = speaker.speaking.value

    private fun urgentScreen(): Activity? {
        var found: Activity? = null
        inst.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is KaiReminderActivity }
        }
        return found
    }

    private fun nodes(text: String): List<AccessibilityNodeInfo> {
        val ua = inst.uiAutomation
        runCatching {
            val info = ua.serviceInfo
            if (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
                info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                ua.serviceInfo = info
            }
        }
        val roots = runCatching { ua.windows.mapNotNull { it.root } }.getOrDefault(emptyList()) + listOfNotNull(ua.rootInActiveWindow)
        return roots.flatMap { it.findAccessibilityNodeInfosByText(text).orEmpty() }
    }

    /** Taps the control labelled [text] on the real screen. */
    private fun tap(text: String): Boolean {
        nodes(text).firstOrNull()?.let { node ->
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if ((n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val box = KaiUrgentDebug.controls[text.lowercase()] ?: KaiUrgentDebug.controls.entries.firstOrNull { it.key.contains(text.lowercase()) }?.value
        if (box == null || box.isEmpty) return false
        shell("input tap ${box.centerX()} ${box.centerY()}")
        return true
    }

    private fun screenshot(name: String) = runCatching {
        inst.uiAutomation.takeScreenshot()?.let { bmp -> File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** The app in front, screen awake: the ring opens Kai directly where the owner is watching. */
    private fun appInFront() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(ctx, 0x4B43, launch, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            } else pi.send()
        }
        Thread.sleep(2_000)
    }

    private fun localTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()

    /** Kai chat: [said] → (pick the first contact if Kai asks which one) → Confirm. Returns the reminder saved by Confirm. */
    private fun setByChat(kai: KaiAgent, said: String): KaiReminder = runBlocking {
        val before = engine.open().map { it.id }.toSet()
        var turn: KaiTurn = kai.ask(said)
        report("chat: \"$said\" → ${turn.reply.text} ${turn.card?.buttons?.map { it.label }}")
        repeat(3) {
            val actions = turn.card?.buttons.orEmpty().map { it.action }
            val confirm = actions.filterIsInstance<KaiAction.ConfirmReminder>().firstOrNull()
            if (confirm != null) {
                assertTrue("BUG: a reminder was saved before Confirm", engine.open().none { it.id !in before })
                turn = kai.act(confirm, KaiLang.TANGLISH)!!
                report("chat: [Confirm] → ${turn.reply.text}")
                val r = engine.open().firstOrNull { it.id !in before } ?: throw AssertionError("BUG: nothing saved after Confirm")
                created += r.id
                return@runBlocking r
            }
            val pick = actions.filterIsInstance<KaiAction.PickContact>().firstOrNull()
                ?: throw AssertionError("BUG: Kai did not ask to confirm: ${turn.reply.text}")
            turn = kai.act(pick, KaiLang.TANGLISH)!!
            report("chat: [pick contact] → ${turn.reply.text}")
        }
        throw AssertionError("BUG: no Confirm after picking a contact")
    }

    // ------------------------------------------------------------ the natural-voice measurements

    private fun cacheFile(text: String, lang: String): File {
        val key = java.security.MessageDigest.getInstance("SHA-1").digest("$lang|$text".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.cacheDir, "tts_cache"), "$key.wav")
    }

    private fun quietMs(pcm: WavJoin.Pcm, fromEnd: Boolean): Long {
        val frames = pcm.data.size / pcm.frameBytes
        fun quiet(f: Int) = (0 until pcm.channels).all { c ->
            val i = f * pcm.frameBytes + c * 2
            abs(((pcm.data[i].toInt() and 0xFF) or (pcm.data[i + 1].toInt() shl 8)).toShort().toInt()) <= WavJoin.SILENCE_LEVEL
        }
        var n = 0
        if (fromEnd) { var f = frames - 1; while (f >= 0 && quiet(f)) { n++; f-- } } else { var f = 0; while (f < frames && quiet(f)) { n++; f++ } }
        return n * 1000L / pcm.sampleRate
    }

    /**
     * The opening turn as it really sounds: the kept natural-voice sentences joined exactly like the app
     * joins them. Reports the silence at each join (new way) and what the old way had (padding + 6.5 s).
     * Returns the joined clip's length (ms), or -1 when the natural voice isn't available.
     */
    private fun measureTurn(r: KaiReminder, tag: String): Long {
        val line = KaiUrgentVoiceScript.lines(r).first()
        val lang = KaiUrgentVoiceScript.languageCode(r.lang)
        report("$tag turn1 parts=${line.parts} gapsMs=${line.gapsMs}")
        val files = line.parts.map { cacheFile(it, lang) }
        if (!waitFor(15_000) { files.all { it.length() > 44 } }) {
            report("$tag NOT_MEASURED: natural-voice audio not on the phone (${files.count { it.length() > 44 }}/${files.size}) — proxyConfigured=${com.shopai.app.BuildConfig.TTS_PROXY_URL.isNotBlank()} problem=${speaker.lastProxyProblem}")
            return -1
        }
        val clips = files.map { it.readBytes() }
        val pcms = clips.map { WavJoin.parse(it) ?: throw AssertionError("BUG: the natural voice returned a clip WavJoin can't read") }
        val pads = pcms.map { quietMs(it, false) to quietMs(it, true) }
        report("$tag sarvamClipPadding(leadMs,tailMs)=$pads")
        val oldGap = pads[0].second + KaiUrgentVoiceScript.pauseBefore(1) + pads[1].first
        val joined = WavJoin.join(clips, line.gapsMs) ?: throw AssertionError("BUG: the turn's clips could not be joined (formats differ?)")
        val pcm = WavJoin.parse(joined)!!
        // The silence at each join: the trimmed sentences' ends + the gap.
        val trimmed = pcms.map { WavJoin.trim(it).durationMs }
        var at = 0L
        val joinGaps = mutableListOf<Long>()
        val silences = WavJoin.silences(pcm, minMs = 50)
        for (i in 0 until trimmed.size - 1) {
            at += trimmed[i]
            val mid = at + line.gapsMs[i] / 2
            joinGaps += silences.firstOrNull { (s, len) -> mid in s..(s + len) }?.second ?: -1
            at += line.gapsMs[i]
        }
        File(out, "$tag-turn1.wav").writeBytes(joined)
        report("$tag JOIN_GAPS_MS=$joinGaps (targets 250..500, 700..1200) BEFORE_FIX_SILENCE_MS=$oldGap clipMs=${pcm.durationMs}")
        assertTrue("BUG: the pause after the first sentence is ${joinGaps[0]} ms (target 250–500)", joinGaps[0] in 250L..500L)
        if (joinGaps.size > 1) assertTrue("BUG: the pause before the question is ${joinGaps[1]} ms (target 700–1200)", joinGaps[1] in 700L..1_200L)
        return pcm.durationMs
    }

    /**
     * Watches the voice: waits for the opening turn to start, then how long it plays without a break.
     * Returns (startedAfterMs, playedMs, breaksDuringTurn).
     */
    private fun watchOpening(tag: String, ringAt: Long): Triple<Long, Long, Int> {
        assertTrue("BUG: Kai's voice did not start within 20 s of the ring", waitFor(20_000, 20) { speaking() })
        val started = SystemClock.elapsedRealtime()
        report("$tag voiceStartAfterRingMs=${started - ringAt}")
        // Speaking until a silence longer than 1.5 s (the turn is over; the next turn comes ~6.5 s later).
        var breaks = 0
        var lastTrue = started
        var wasSpeaking = true
        while (SystemClock.elapsedRealtime() - lastTrue < 1_500 && SystemClock.elapsedRealtime() - started < 30_000) {
            val s = speaking()
            if (s) lastTrue = SystemClock.elapsedRealtime()
            if (wasSpeaking && !s) breaks++
            wasSpeaking = s
            Thread.sleep(20)
        }
        val played = lastTrue - started
        // The final stop is not a break inside the turn.
        return Triple(started - ringAt, played, (breaks - 1).coerceAtLeast(0))
    }

    /** Taps [label] while Kai is speaking; returns how fast the voice stopped (ms) or -1. */
    private fun tapWhileSpeaking(label: String, tag: String): Long {
        assertTrue("BUG: Kai was not speaking when $label was to be tapped", waitFor(20_000, 20) { speaking() })
        Thread.sleep(400) // mid-sentence (a follow-up line is short)
        val speakingAtTap = speaking()
        val tapped = SystemClock.elapsedRealtime()
        assertTrue("BUG: $label could not be tapped", tap(label))
        val stopped = waitFor(3_000, 10) { !speaking() }
        val latency = if (stopped) SystemClock.elapsedRealtime() - tapped else -1
        report("$tag tapped '$label' speakingAtTap=$speakingAtTap → voiceStoppedAfterMs=$latency")
        screenshot("$tag-after-$label")
        assertTrue("BUG: the voice did not stop within 1 s of $label ($latency ms)", latency in 0..1_000)
        return latency
    }

    /** After an answer: only the short answer, then silence — nothing queued plays. */
    private fun silentAfterAnswer(tag: String) {
        Thread.sleep(4_000) // the short answer ("Seri Owner…")
        val cue = onMain { container.kaiUrgentVoice.cue.value?.startedAt }
        Thread.sleep(15_000)
        val later = onMain { container.kaiUrgentVoice.cue.value?.startedAt }
        val log = shell("logcat -d -s KaiReminder:I")
        val stopIdx = log.lastIndexOf("voice stop (answered")
        val linesAfter = if (stopIdx < 0) -1 else Regex("voice line \\d+").findAll(log.substring(stopIdx)).count()
        report("$tag afterAnswer: sameCue=${cue == later} speakingNow=${speaking()} voiceLinesAfterStop=$linesAfter")
        assertEquals("BUG: Kai kept talking after the answer", cue, later)
        assertTrue("BUG: speech still playing 19 s after the answer", !speaking())
        assertTrue("BUG: a queued line played after the answer ($linesAfter)", linesAfter <= 0)
    }

    private fun engineLine(tag: String) {
        val log = shell("logcat -d -s KaiReminder:I NaturalTtsSpeaker:I")
        val joined = Regex("voice turn: .*").findAll(log).map { it.value.trim() }.toList()
        val engines = Regex("voice (turn )?engine=\\S+.*").findAll(log).map { it.value.trim() }.distinct().toList()
        report("$tag log: ${joined.joinToString(" | ").ifBlank { "(no 'voice turn' line)" }}")
        report("$tag log: ${engines.joinToString(" | ").ifBlank { "(no engine line)" }}")
    }

    /** One ring, end to end: chat → Confirm → real alarm → measured turn → [answer] mid-sentence → silence. */
    private fun liveRing(tag: String, said: String, minutes: Long, answer: String, after: (KaiReminder) -> Unit) {
        shell("logcat -c")
        KaiUrgentDebug.reset()
        val kai = KaiAgent(KaiBusinessBrain(NoBooks()), NoBooks(), container.kaiTools)
        val r = setByChat(kai, said)
        val inSec = (r.triggerAt - System.currentTimeMillis()) / 1000
        report("$tag saved id=${r.id} task='${r.task}' person=${r.person} action=${r.action} rings=${localTime(r.triggerAt)} (in ${inSec}s)")
        assertTrue("BUG: not ~$minutes minutes away ($inSec s)", inSec in (minutes * 60 - 20)..(minutes * 60 + 5))
        appInFront()
        report("$tag waiting for the REAL alarm (${inSec}s)… watch the emulator")
        val shown = waitFor((r.triggerAt - System.currentTimeMillis()).coerceAtLeast(0) + 45_000, 50) { urgentScreen() != null }
        val ringAt = SystemClock.elapsedRealtime()
        assertTrue("BUG: Kai Urgent Action Mode did not open when the alarm rang (presentation=${engine.lastPresentation()})", shown)
        val rung = engine.find(r.id)!!
        report("$tag RANG attempt=${rung.attemptCount} status=${rung.status} presentation=${engine.lastPresentation()}")
        screenshot("$tag-screen")
        val (startMs, playedMs, breaks) = watchOpening(tag, ringAt)
        val clipMs = measureTurn(rung, tag)
        report("$tag TURN1 startAfterRingMs=$startMs playedContinuouslyMs=$playedMs breaksInsideTurn=$breaks joinedClipMs=$clipMs")
        assertEquals("BUG: the first turn stopped and restarted $breaks time(s) — not one continuous utterance", 0, breaks)
        if (clipMs > 0) assertTrue("BUG: played ${playedMs} ms but the joined turn is ${clipMs} ms — the sentences were not played as one clip",
            abs(playedMs - clipMs) <= 1_200)
        // The next line comes after the pause between turns (not straight away, not never).
        val nextAt = waitFor(20_000, 20) { speaking() }
        val pause = SystemClock.elapsedRealtime() - (ringAt + startMs + playedMs)
        report("$tag NEXT_TURN started=$nextAt pauseBetweenTurnsMs≈$pause")
        engineLine(tag)
        tapWhileSpeaking(answer, tag)
        after(engine.find(r.id)!!)
        silentAfterAnswer(tag)
    }

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        shell("svc power stayon true")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        engine.open().filter { it.sourceText in PHRASES }.forEach { engine.cancel(it.id) }
    }

    @After
    fun tearDown() {
        created.forEach { id -> runCatching { if (!engine.complete(id)) engine.cancel(id) } }
        shell("svc power stayon false")
    }

    // ------------------------------------------------------------ v1 (D): Time maathu → the same reminder

    @Test
    fun v1_timeMaathuMovesTheSameReminder() = runBlocking {
        summary.writeText("KAI REMINDER VOICE — LIVE ${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}\n")
        val kai = KaiAgent(KaiBusinessBrain(NoBooks()), NoBooks(), container.kaiTools)
        val r = setByChat(kai, PICKUP_4PM)
        report("D saved id=${r.id} task='${r.task}' rings=${localTime(r.triggerAt)}")
        assertEquals("BUG: the task lost words", "Paiyana school-la irundhu kootitu vara", r.task)
        assertEquals("BUG: not 4 PM", 16, localTime(r.triggerAt).hour)
        val q = kai.ask("Time maathu")
        report("chat: \"Time maathu\" → ${q.reply.text}")
        val a = kai.ask("5 mani")
        report("chat: \"5 mani\" → ${a.reply.text}")
        assertTrue("BUG: no 'maathitten' reply: ${a.reply.text}", a.reply.text.contains("5:00 PM-ku maathitten"))
        val same = engine.open().filter { it.task == r.task }
        report("D remindersWithThisTask=${same.size} sameId=${same.singleOrNull()?.id == r.id} rings=${same.map { localTime(it.triggerAt) }}")
        assertEquals("BUG: a duplicate was created", 1, same.size)
        assertEquals("BUG: not the same reminder", r.id, same.single().id)
        assertEquals("BUG: not moved to 5 PM", 17, localTime(same.single().triggerAt).hour)
        report("D RESULT=PASS")
    }

    // ------------------------------------------------------------ v2 (A): Kumar call, CALL NOW mid-sentence

    @Test
    fun v2_kumarCallOneTurnThenCallNowStopsAtOnce() = liveRing("A", KUMAR, 2, "Call now") { r ->
        report("A after CALL NOW status=${r.status} (Call Now never completes it)")
        // Back from the dialer to the app for the next test.
        shell("input keyevent KEYCODE_HOME")
        report("A RESULT=PASS")
    }

    // ------------------------------------------------------------ v3 (B): son's pickup, DONE mid-sentence

    @Test
    fun v3_paiyanPickupPersonalVoiceThenDoneStopsAtOnce() = liveRing("B", PICKUP_2MIN, 2, "Done") { r ->
        assertTrue("BUG: Done did not complete it", waitFor(5_000) { engine.find(r.id)?.status == ReminderStatus.COMPLETED })
        report("B after DONE status=${engine.find(r.id)?.status}")
        report("B RESULT=PASS")
    }

    // ------------------------------------------------------------ v4 (C): Amma call, SNOOZE mid-sentence

    @Test
    fun v4_ammaCallThenSnoozeStopsAtOnce() = liveRing("C", AMMA, 5, "Snooze 5 min") { r ->
        assertTrue("BUG: Snooze did not save", waitFor(5_000) { engine.find(r.id)?.status == ReminderStatus.SNOOZED })
        val next = (engine.find(r.id)!!.snoozedUntil!! - System.currentTimeMillis()) / 1000
        report("C after SNOOZE status=SNOOZED nextRingInSec=$next")
        report("C RESULT=PASS")
    }

    private companion object {
        const val TAG = "KaiVoiceLive"
        const val PICKUP_4PM = "Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu"
        const val KUMAR = "Kumar-ku 2 minutes-la call panna remind pannu"
        const val PICKUP_2MIN = "2 minutes-la paiyana school-la irundhu kootitu vara nyabagam paduthu"
        const val AMMA = "5 minutes-la Amma-ku call panna remind pannu"
        val PHRASES = setOf(PICKUP_4PM, KUMAR, PICKUP_2MIN, AMMA)
    }
}
