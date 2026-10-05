package com.shopai.app.live

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderStatus
import com.shopai.app.notifications.KaiReminderEngine
import com.shopai.app.ui.reminder.KaiReminderActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.time.LocalDate

/**
 * PIXEL 8 ACCEPTANCE TEST — Kai Smart Persistent Reminder on the real phone / emulator.
 *
 * t1 (the acceptance test, REAL alarm, ~2.5 min):
 *    Kai chat "Praba-ku 2 minutes-la call pannanum nyabagam paduthu" → Confirm → the alarm is
 *    registered with Android (dumpsys alarm) → screen OFF + LOCKED → the REAL AlarmManager rings →
 *    Kai Urgent Action Mode over the lock screen (full-screen intent) — or, when Android does not
 *    permit full-screen, the lock-screen notification fallback. Evidence: canUseFullScreenIntent,
 *    keyguard state while shown, two screenshots (animation), the buttons and words read from the
 *    screen, Kai's voice, SNOOZE tapped on the real screen. Prints exactly one
 *      RESULT=FULL_SCREEN_VERIFIED  or  RESULT=FULL_SCREEN_NOT_PERMITTED_FALLBACK_VERIFIED
 *    or fails with "BUG: …" / "BLOCKER: …". Never a PASS without the locked screen.
 * t2: the fallback, forced on this phone (appops deny USE_FULL_SCREEN_INTENT), with a real alarm;
 *     tapping the notification opens Kai Urgent Action Mode. Restores the original setting.
 * t3: CALL NOW (dialer only, never "called") and DONE tapped on the real screen; Done stops every ring.
 * t4: retry every 5 minutes, snooze, attempt counter, max 5 → EXHAUSTED (test clock, real store).
 * t5: a cancelled reminder never rings.
 * t6: unlocked, app open: Kai opens directly, no banner over him, the opening line once (also after recreate).
 * t7: the cinematic screen: Kai large (head to sandals, most of the screen), compact controls in one row,
 *     Kai's body moving, the voice continuing with a second line, and everything silent right after DONE.
 *
 * Every line is logged under KaiReminderLive; screenshots go to
 * /sdcard/Android/data/<app>/files/kai-reminder/ (scripts/kai-reminder-pixel8.ps1 pulls them).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KaiReminderPixelTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val container get() = (ctx.applicationContext as ShopAiApplication).container
    private val engine: KaiReminderEngine get() = container.kaiReminders
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val keyguard = ctx.getSystemService(KeyguardManager::class.java)
    private val notifications = ctx.getSystemService(NotificationManager::class.java)
    private val created = mutableListOf<String>()
    private val shots = File(ctx.getExternalFilesDir(null), "kai-reminder").apply { mkdirs() }

    private class NoBooks : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    // ------------------------------------------------------------ helpers

    private fun report(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return true
            Thread.sleep(250)
        }
        return runCatching(check).getOrDefault(false)
    }

    private fun urgentScreen(): Activity? {
        var found: Activity? = null
        inst.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is KaiReminderActivity }
        }
        return found
    }

    private fun lockScreen() {
        shell("input keyevent KEYCODE_SLEEP")
        assertTrue("BLOCKER: the screen did not turn off", waitFor(5_000) { !power.isInteractive })
        Thread.sleep(1_500)
        report("screenOff=${!power.isInteractive} keyguardLocked=${keyguard.isKeyguardLocked}")
        assertTrue(
            "BLOCKER: the emulator has no lock screen (Settings → Security → Screen lock → Swipe or PIN) — locked behaviour can't be tested",
            keyguard.isKeyguardLocked,
        )
    }

    private fun unlock() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    private fun alarmRegistered(action: String): Boolean =
        shell("dumpsys alarm").lineSequence().any { it.contains(ctx.packageName) && it.contains(action) } ||
            shell("dumpsys alarm").let { it.contains(action) }

    private fun nodes(text: String): List<AccessibilityNodeInfo> =
        inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()

    private fun tap(text: String): Boolean {
        val node = nodes(text).firstOrNull() ?: return false
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun screenshot(name: String): Bitmap? = runCatching {
        inst.uiAutomation.takeScreenshot()?.also { bmp ->
            File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }.getOrNull()

    /** Share of sampled pixels that changed in the top half (where Kai is) — > 0 means Kai is moving. */
    private fun motion(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        var changed = 0
        var total = 0
        for (y in 0 until a.height / 2 step 6) for (x in 0 until a.width step 6) {
            total++
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            val d = maxOf(kotlin.math.abs((p shr 16 and 255) - (q shr 16 and 255)), kotlin.math.abs((p shr 8 and 255) - (q shr 8 and 255)), kotlin.math.abs((p and 255) - (q and 255)))
            if (d > 24) changed++
        }
        return if (total == 0) 0.0 else changed.toDouble() / total
    }

    /** Dark premium background: the corners of the screen are near-black. */
    private fun nearBlack(b: Bitmap): Boolean {
        val pts = listOf(b.width / 30 to b.height * 3 / 10, b.width - b.width / 30 to b.height * 3 / 10, b.width / 30 to b.height * 6 / 10, b.width - b.width / 30 to b.height * 6 / 10)
        return pts.all { (x, y) -> val p = b.getPixel(x, y); (p shr 16 and 255) < 40 && (p shr 8 and 255) < 40 && (p and 255) < 50 }
    }

    private fun urgentNotification(person: String) = notifications.activeNotifications.firstOrNull {
        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.contains(person) == true
    }

    private fun praba(id: String, at: Long, person: String = "Praba") = System.currentTimeMillis().let { now ->
        KaiReminder(
            id = id, title = "Call $person", task = "$person-ku call panna", action = ReminderAction.CALL, person = person, phone = "+919000000011",
            triggerAt = at, zone = java.time.ZoneId.systemDefault().id,
            notificationMessage = "Owner, $person-ku call panna sonneenga.", sourceText = "$person-ku 2 minutes-la call pannanum nyabagam paduthu",
            createdAt = now - 120_000, lang = KaiLang.TANGLISH,
        ).also { created += it.id }
    }

    /** The full Urgent Action Mode check while the phone is locked: screen, words, buttons, motion, voice. */
    private fun checkUrgentMode(r: KaiReminder, tag: String) {
        val shownLocked = keyguard.isKeyguardLocked
        report("$tag urgentScreenResumed=true keyguardLockedWhileShown=$shownLocked screenOn=${power.isInteractive}")
        assertTrue("BUG: Kai Urgent Action Mode is showing but the phone is no longer locked — not verified over the lock screen", shownLocked)
        Thread.sleep(1_200)
        val a = screenshot("$tag-1")
        Thread.sleep(700)
        val b = screenshot("$tag-2")
        val moving = if (a != null && b != null) motion(a, b) else -1.0
        val dark = a?.let(::nearBlack)
        report("$tag screenshots=${shots.absolutePath} kaiMotion=${"%.4f".format(moving)} darkBackground=$dark")
        assertTrue("BUG: no screenshot of the locked screen could be taken", a != null && b != null)
        assertTrue("BUG: the background is not the black premium Urgent Action Mode", dark == true)
        assertTrue("BUG: Kai is not animating (two frames are identical)", moving > 0.0)
        val words = listOf("REMINDER", "Praba-ku call panna vendiya neram aachu", "Ippo call pannalama?", "CALL NOW", "DONE", "SNOOZE 5 MIN", "Reminder 1 of 5")
        val seen = words.associateWith { nodes(it).isNotEmpty() }
        report("$tag onScreen=$seen")
        assertTrue("BUG: Kai Urgent Action Mode is missing: ${seen.filterValues { !it }.keys}", seen.values.all { it })
        // Not Home, not Chat: the resumed screen is the dedicated activity.
        assertTrue("BUG: another screen opened instead of Kai Urgent Action Mode", urgentScreen() is KaiReminderActivity)
        val claimed = waitFor(6_000) { engine.find(r.id)?.let(engine::wasSpoken) == true }
        val audible = waitFor(10_000) { container.naturalTtsSpeaker.speaking.value }
        report("$tag ttsTriggered=$claimed ttsSpeaking=$audible (speech: Owner, Praba-ku call panna vendiya neram aachu. Ippo call pannalama?)")
        assertTrue("BUG: Kai did not start the reminder voice", claimed)
    }

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        unlock()
        // Leftovers of an earlier run (only this test's own reminders).
        engine.open().filter { it.id.startsWith("LIVE-") || it.sourceText == PHRASE }.forEach { engine.cancel(it.id) }
    }

    @After
    fun tearDown() {
        created.forEach { id -> runCatching { if (!engine.complete(id)) engine.cancel(id) } }
        unlock()
    }

    // ------------------------------------------------------------ t1: the acceptance test (real alarm)

    @Test
    fun t1_chatConfirmLockRealAlarmKaiUrgentActionMode() = runBlocking {
        val canFullScreen = engine.fullScreenAllowed()
        val exact = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else true
        report("device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} targetSdk=${ctx.applicationInfo.targetSdkVersion} " +
            "canUseFullScreenIntent=$canFullScreen exactAlarms=$exact notificationsEnabled=${notifications.areNotificationsEnabled()}")

        // 3 + 4: Kai chat, then Confirm.
        val kai = KaiAgent(KaiBusinessBrain(NoBooks()), NoBooks(), container.kaiTools)
        val ask = kai.ask(PHRASE)
        report("kai: ${ask.reply.text} ${ask.card?.buttons?.map { it.label }}")
        assertEquals("BUG: Kai must ask before setting the reminder", "Seri Owner. Praba-ku 2 minutes-la call reminder set pannalama?", ask.reply.text)
        assertTrue("BUG: a reminder was saved before Confirm", engine.open().none { it.person == "Praba" && it.sourceText.contains("2 minutes-la") })
        val confirm = ask.card!!.buttons.first { it.action is KaiAction.ConfirmReminder }.action
        val done = kai.act(confirm, KaiLang.TANGLISH)!!
        report("kai: ${done.reply.text}")
        val r = engine.open().lastOrNull { it.person == "Praba" } ?: throw AssertionError("BUG: no reminder after Confirm")
        created += r.id
        val inSec = (r.triggerAt - System.currentTimeMillis()) / 1000
        report("scheduled id=${r.id} inSec=$inSec status=${r.status}")
        assertTrue("BUG: not 2 minutes away ($inSec s)", inSec in 100..125)

        // 5: really registered with Android's AlarmManager.
        val registered = alarmRegistered(KaiReminderEngine.ACTION_FIRE)
        report("alarmRegisteredWithAndroid=$registered")
        assertTrue("BUG: the reminder alarm is not registered with AlarmManager", registered)

        // 6 + 7: lock, then let the REAL alarm ring (no shortcut).
        lockScreen()
        val waitMs = (r.triggerAt - System.currentTimeMillis()).coerceAtLeast(0) + 45_000
        report("waiting ${waitMs / 1000}s for the real alarm while locked…")

        if (canFullScreen) {
            val shown = waitFor(waitMs) { urgentScreen() != null }
            report("presentation=${engine.lastPresentation()}")
            assertTrue("BUG: full-screen is permitted but Kai Urgent Action Mode did not appear over the lock screen (presentation=${engine.lastPresentation()})", shown)
            assertTrue("BUG: presentation was not FULL_SCREEN_INTENT: ${engine.lastPresentation()}", engine.lastPresentation()?.contains("FULL_SCREEN_INTENT") == true)
            val rung = engine.find(r.id)!!
            assertEquals("BUG: the ring was not attempt 1", 1, rung.attemptCount)
            assertEquals(ReminderStatus.RANG, rung.status)
            checkUrgentMode(rung, "t1")
            // No duplicate: the banner for this ring is gone while Kai is on screen.
            val noBanner = waitFor(4_000) { urgentNotification("Praba") == null }
            report("t1 noDuplicateBannerOverKai=$noBanner")
            assertTrue("BUG: the reminder notification is still showing on top of Kai Urgent Action Mode", noBanner)

            // SNOOZE tapped on the real screen → SNOOZED, the next ring armed in 5 minutes, the screen closes.
            assertTrue("BUG: SNOOZE 5 MIN could not be tapped", tap("SNOOZE 5 MIN"))
            assertTrue("BUG: Snooze did not save", waitFor(5_000) { engine.find(r.id)?.status == ReminderStatus.SNOOZED })
            val s = engine.find(r.id)!!
            val next = (s.snoozedUntil!! - System.currentTimeMillis()) / 1000
            report("snoozed status=${s.status} nextRingInSec=$next snoozeAlarmRegistered=${alarmRegistered(KaiReminderEngine.ACTION_FIRE_SNOOZE)}")
            assertTrue("BUG: the next ring is not ~5 minutes away ($next s)", next in 270..305)
            assertTrue("BUG: the urgent screen stayed open after Snooze", waitFor(5_000) { urgentScreen() == null })
            report("RESULT=FULL_SCREEN_VERIFIED")
        } else {
            val posted = waitFor(waitMs) { urgentNotification("Praba") != null }
            val n = urgentNotification("Praba")?.notification
            report("presentation=${engine.lastPresentation()} notificationPosted=$posted visibility=${n?.visibility} category=${n?.category} " +
                "actions=${n?.actions?.map { it.title }} fullScreenIntent=${n?.fullScreenIntent != null} channelImportance=${n?.channelId?.let { notifications.getNotificationChannel(it)?.importance }}")
            assertTrue("BUG: no reminder notification while locked", posted && n != null)
            assertEquals("BUG: not visible on the lock screen", Notification.VISIBILITY_PUBLIC, n!!.visibility)
            assertTrue("BUG: CALL NOW / DONE / SNOOZE buttons missing", (n.actions?.size ?: 0) >= 3)
            assertNull("BUG: a full-screen intent was set although Android does not permit it", n.fullScreenIntent)
            // Tapping the notification opens Kai Urgent Action Mode (over the lock screen).
            sendTap(n)
            val opened = waitFor(10_000) { urgentScreen() != null }
            report("tapOpensKaiUrgentMode=$opened")
            assertTrue("BUG: tapping the notification did not open Kai Urgent Action Mode", opened)
            report("RESULT=FULL_SCREEN_NOT_PERMITTED_FALLBACK_VERIFIED")
        }
    }

    /** Opens Kai Urgent Action Mode the way the notification does (the activity is not exported). */
    private fun openUrgent(id: String) {
        val pi = android.app.PendingIntent.getActivity(ctx, 0x4B41, KaiReminderEngine.urgentIntent(ctx, id),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 34) {
            val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(ctx, 0, null, null, null, null, opts.toBundle())
        } else {
            pi.send()
        }
    }

    private fun sendTap(n: Notification) {
        val pi = n.contentIntent ?: throw AssertionError("BUG: the notification has no tap action")
        if (Build.VERSION.SDK_INT >= 34) {
            val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(ctx, 0, null, null, null, null, opts.toBundle())
        } else {
            pi.send()
        }
    }

    // ------------------------------------------------------------ t2: the fallback, forced on this phone

    @Test
    fun t2_fallbackWhenFullScreenIsNotPermitted() {
        assumeTrue("Android 14+ only (full-screen is a manifest permission before 14)", Build.VERSION.SDK_INT >= 34)
        val before = shell("appops get ${ctx.packageName} USE_FULL_SCREEN_INTENT").trim()
        report("t2 appopsBefore='$before'")
        shell("appops set ${ctx.packageName} USE_FULL_SCREEN_INTENT deny")
        try {
            val denied = !engine.fullScreenAllowed()
            report("t2 canUseFullScreenIntent after deny=${!denied}")
            assumeTrue("BLOCKER: could not switch full-screen off with appops on this image", denied)
            val r = praba("LIVE-FALLBACK-${System.currentTimeMillis()}", System.currentTimeMillis() + 20_000, person = "Kumar")
            engine.create(r)
            lockScreen()
            val posted = waitFor(70_000) { urgentNotification("Kumar") != null }
            val n = urgentNotification("Kumar")?.notification
            report("t2 presentation=${engine.lastPresentation()} notificationPosted=$posted visibility=${n?.visibility} actions=${n?.actions?.map { it.title }} fullScreenIntent=${n?.fullScreenIntent != null}")
            assertTrue("BUG: no fallback notification while locked", posted && n != null)
            assertTrue("BUG: the fallback still opened the screen by itself", urgentScreen() == null)
            assertEquals("BUG: fallback not visible on the lock screen", Notification.VISIBILITY_PUBLIC, n!!.visibility)
            assertNull("BUG: full-screen intent set while not permitted", n.fullScreenIntent)
            assertTrue("BUG: presentation was not NOTIFICATION_ONLY", engine.lastPresentation()?.contains("NOTIFICATION_ONLY") == true)
            sendTap(n)
            val opened = waitFor(10_000) { urgentScreen() != null }
            report("t2 tapOpensKaiUrgentMode=$opened lockedWhileShown=${keyguard.isKeyguardLocked}")
            assertTrue("BUG: tapping the fallback notification did not open Kai Urgent Action Mode", opened)
            Thread.sleep(1_500)
            report("t2 fallbackNotificationKept=${urgentNotification("Kumar") != null}")
            assertTrue("BUG: the fallback notification must stay (it is the reminder when full-screen is not allowed)", urgentNotification("Kumar") != null)
            report("FALLBACK_VERIFIED (forced)")
        } finally {
            // Back to how this phone had it.
            val mode = Regex("""USE_FULL_SCREEN_INTENT:\s*(\w+)""").find(before)?.groupValues?.get(1) ?: "default"
            shell("appops set ${ctx.packageName} USE_FULL_SCREEN_INTENT $mode")
            report("t2 appopsRestored=$mode canUseFullScreenIntent=${engine.fullScreenAllowed()}")
        }
    }

    // ------------------------------------------------------------ t3: CALL NOW and DONE on the real screen

    @Test
    fun t3_callNowOpensDialerAndDoneStopsEverything() {
        assumeTrue("full-screen not permitted — covered by t1/t2 fallback", engine.fullScreenAllowed())
        val r = praba("LIVE-DONE-${System.currentTimeMillis()}", System.currentTimeMillis() - 1_000)
        engine.create(r)
        lockScreen()
        // The receiver's own entry point (what the alarm calls) — this test is about the buttons, not the timing.
        engine.fired(r.id, snooze = false)
        assertTrue("BUG: Kai Urgent Action Mode did not appear", waitFor(10_000) { urgentScreen() != null })
        assertTrue("BUG: CALL NOW could not be tapped", tap("CALL NOW"))
        val dialer = waitFor(8_000) { shell("dumpsys activity activities").lineSequence().any { it.contains("mResumedActivity") && it.contains("dialer", true) } }
        val note = waitFor(3_000) { nodes("Call screen open pannitten Owner.").isNotEmpty() }
        report("t3 callNow dialerOpened=$dialer kaiSaid='Call screen open pannitten Owner.'=$note statusAfterCall=${engine.find(r.id)?.status}")
        assertEquals("BUG: Call Now must not complete the reminder", ReminderStatus.RANG, engine.find(r.id)!!.status)
        // Back to Kai and DONE.
        openUrgent(r.id)
        assertTrue("BUG: could not return to Kai Urgent Action Mode", waitFor(8_000) { urgentScreen() != null })
        assertTrue("BUG: DONE could not be tapped", tap("DONE"))
        assertTrue("BUG: Done did not complete the reminder", waitFor(5_000) { engine.find(r.id)?.status == ReminderStatus.COMPLETED })
        assertTrue("BUG: the screen stayed open after Done", waitFor(5_000) { urgentScreen() == null })
        engine.fired(r.id, snooze = true)
        engine.fired(r.id, snooze = false)
        assertEquals("BUG: rang again after Done", 1, engine.find(r.id)!!.attemptCount)
        assertNull("BUG: a retry is still armed after Done", engine.find(r.id)!!.snoozedUntil)
        report("t3 DONE_VERIFIED")
    }

    // ------------------------------------------------------------ t4 / t5: retry, max attempts, cancel

    @Test
    fun t4_retrySnoozeAndMaxFiveAttemptsOnTheDevice() {
        var t = System.currentTimeMillis()
        // The phone's own reminder store, a test clock: five minutes pass in a line of code.
        val e = KaiReminderEngine(ctx) { t }
        val r = praba("LIVE-RETRY-${System.currentTimeMillis()}", System.currentTimeMillis() - 1_000)
        e.create(r)
        e.fired(r.id, snooze = false)
        var now = e.find(r.id)!!
        assertEquals(1, now.attemptCount)
        assertEquals(t + 300_000, now.snoozedUntil)
        report("t4 attempt 1 status=${now.status} nextRetryIn=${(now.snoozedUntil!! - t) / 60_000}min")
        val snoozed = e.snooze(r.id, KaiReminderEngine.SNOOZE_MINUTES)!!
        assertEquals(ReminderStatus.SNOOZED, snoozed.status)
        for (attempt in 2..5) {
            t += 300_000
            e.fired(r.id, snooze = true)
            now = e.find(r.id)!!
            report("t4 attempt $attempt status=${now.status}")
            assertEquals(attempt, now.attemptCount)
        }
        assertEquals("BUG: not EXHAUSTED after 5", ReminderStatus.EXHAUSTED, now.status)
        assertNull("BUG: a 6th retry is still armed", now.snoozedUntil)
        t += 300_000
        e.fired(r.id, snooze = true)
        assertEquals("BUG: rang a 6th time", 5, e.find(r.id)!!.attemptCount)
        assertNull("BUG: snooze allowed after the last attempt", e.snooze(r.id, 5))
        report("t4 RETRY_VERIFIED attempts=5 status=EXHAUSTED")
    }

    @Test
    fun t5_cancelledReminderNeverRings() {
        val r = praba("LIVE-CANCEL-${System.currentTimeMillis()}", System.currentTimeMillis() + 600_000)
        engine.create(r)
        assertTrue(engine.cancel(r.id))
        engine.fired(r.id, snooze = false)
        engine.fired(r.id, snooze = true)
        assertEquals(ReminderStatus.CANCELLED, engine.find(r.id)!!.status)
        assertEquals("BUG: a cancelled reminder rang", 0, engine.find(r.id)!!.attemptCount)
        assertNotNull(engine.find(r.id))
        report("t5 CANCEL_VERIFIED")
    }

    // ------------------------------------------------------------ t6: unlocked, app open — no banner, voice once

    @Test
    fun t6_unlockedNoBannerAndVoiceOncePerRing() {
        val r = praba("LIVE-OPEN-${System.currentTimeMillis()}", System.currentTimeMillis() - 1_000, person = "Ravi")
        engine.create(r)
        // The app in front (unlocked): the ring opens Kai directly.
        openUrgentHome()
        engine.fired(r.id, snooze = false)
        val shown = waitFor(10_000) { urgentScreen() != null }
        report("t6 presentation=${engine.lastPresentation()} urgentScreen=$shown")
        assertTrue("BUG: Kai Urgent Action Mode did not open on the unlocked phone", shown)
        val noBanner = waitFor(4_000) { urgentNotification("Ravi") == null }
        report("t6 noDuplicateBannerOverKai=$noBanner")
        assertTrue("BUG: a notification banner is on top of Kai Urgent Action Mode", noBanner)
        val rung = engine.find(r.id)!!
        assertTrue(waitFor(6_000) { engine.wasSpoken(rung) })
        // Rotation / pause-resume: the same ring is never spoken again, the intro doesn't replay.
        val before = engine.claimSpeech(rung)
        inst.runOnMainSync { urgentScreen()?.recreate() }
        assertTrue(waitFor(8_000) { urgentScreen() != null })
        Thread.sleep(1_500)
        report("t6 speechClaimedAgainAfterRecreate=$before (must be false)")
        assertTrue("BUG: the same ring could be spoken twice", !before)
        val s1 = screenshot("t6-1")
        Thread.sleep(700)
        val s2 = screenshot("t6-2")
        val moving = if (s1 != null && s2 != null) motion(s1, s2) else -1.0
        report("t6 kaiIdleMotion=${"%.4f".format(moving)} (idle continues after recreate)")
        assertTrue("BUG: Kai stopped moving after the screen was recreated", moving > 0.0)
        assertTrue(tap("DONE"))
        assertTrue(waitFor(5_000) { engine.find(r.id)?.status == ReminderStatus.COMPLETED })
        assertTrue("BUG: a notification was left after Done", waitFor(3_000) { urgentNotification("Ravi") == null })
        report("t6 NO_DUPLICATE_VERIFIED")
    }

    // ------------------------------------------------------------ t7: large acting Kai, voice loop, stop on Done

    @Test
    fun t7_largeActingKaiVoiceContinuesAndStopsOnDone() {
        val r = praba("LIVE-ACT-${System.currentTimeMillis()}", System.currentTimeMillis() - 1_000, person = "Mani")
        engine.create(r)
        openUrgentHome()
        engine.fired(r.id, snooze = false)
        assertTrue("BUG: Kai Urgent Action Mode did not open", waitFor(10_000) { urgentScreen() != null })
        Thread.sleep(2_500)
        val metrics = ctx.resources.displayMetrics
        // Kai's size: his own node (content description) against the screen.
        val kaiLabel = ctx.getString(com.shopai.app.R.string.kai_content_description)
        val kaiNode = nodes(kaiLabel).firstOrNull()
        val kaiBox = android.graphics.Rect().also { kaiNode?.getBoundsInScreen(it) }
        val share = kaiBox.height().toDouble() / metrics.heightPixels
        report("t7 kaiBox=$kaiBox screen=${metrics.widthPixels}x${metrics.heightPixels} kaiHeightShare=${"%.2f".format(share)}")
        assertTrue("BUG: Kai is small (${"%.2f".format(share)} of the screen height)", share >= 0.55)
        // Compact controls: one row, about 48 dp tall.
        val boxes = listOf("Call now", "Done", "Snooze 5 min").map { label ->
            var n: AccessibilityNodeInfo? = nodes(label).firstOrNull()
            while (n != null && !n.isClickable) n = n.parent
            android.graphics.Rect().also { n?.getBoundsInScreen(it) }
        }
        val tallestDp = boxes.maxOf { it.height() } / metrics.density
        val oneRow = boxes.all { kotlin.math.abs(it.centerY() - boxes[0].centerY()) < 8 * metrics.density }
        report("t7 controls=$boxes tallestDp=${"%.0f".format(tallestDp)} oneRow=$oneRow")
        assertTrue("BUG: the controls are not compact (${tallestDp}dp)", tallestDp in 44f..64f)
        assertTrue("BUG: Call now / Done / Snooze are not one row", oneRow)
        // Kai himself moves (idle body motion), not only the background.
        val a = screenshot("t7-1")
        Thread.sleep(900)
        val b = screenshot("t7-2")
        val moving = if (a != null && b != null) motion(a, b) else -1.0
        report("t7 kaiBodyMotion=${"%.4f".format(moving)}")
        assertTrue("BUG: Kai is not moving", moving > 0.0)
        // The voice continues: a second line after the opening (natural pause, not every 2 s).
        val voice = container.kaiUrgentVoice
        val starts = linkedSetOf<Long>()
        val continued = waitFor(40_000) { voice.cue.value?.let { starts += it.startedAt }; starts.size >= 2 }
        val gap = starts.toList().let { if (it.size >= 2) it[1] - it[0] else -1 }
        report("t7 voiceLines=${starts.size} secondLineAfterMs=$gap speakingRing=${voice.speakingRing}")
        assertTrue("BUG: Kai said the reminder once and went silent", continued)
        assertTrue("BUG: Kai repeats too fast (${gap} ms)", gap >= 5_000)
        screenshot("t7-3-speaking")
        // DONE: the loop stops at once; only the short answer, then silence.
        assertTrue(tap("Done"))
        assertTrue(waitFor(5_000) { engine.find(r.id)?.status == ReminderStatus.COMPLETED })
        assertNull("BUG: the voice loop is still running after Done", voice.speakingRing)
        val afterDone = voice.cue.value?.startedAt
        Thread.sleep(20_000)
        val later = voice.cue.value?.startedAt
        val quiet = !container.naturalTtsSpeaker.speaking.value
        report("t7 afterDoneCue=$afterDone 20sLaterCue=$later silentAfterDone=$quiet")
        assertEquals("BUG: Kai kept talking after Done", afterDone, later)
        assertTrue("BUG: speech still playing 20 s after Done", quiet)
        report("t7 CINEMATIC_VOICE_VERIFIED")
    }

    /** Brings the app to the front (unlocked), so a ring finds the owner using it. */
    private fun openUrgentHome() {
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(ctx, 0x4B42, launch, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            } else pi.send()
        }
        Thread.sleep(2_500)
    }

    private companion object {
        const val TAG = "KaiReminderLive"
        const val PHRASE = "Praba-ku 2 minutes-la call pannanum nyabagam paduthu"
    }
}
