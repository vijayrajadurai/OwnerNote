package com.shopai.app.live

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.shopai.app.ShopAiApplication
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.ReminderAction
import com.shopai.app.brain.tools.ReminderStatus
import com.shopai.app.notifications.KaiReminderEngine
import com.shopai.app.ui.reminder.KaiReminderActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * PIXEL 8 ACCEPTANCE TEST — Kai Smart Persistent Reminder on the real phone / emulator.
 *
 * t1: screen OFF + device LOCKED → the reminder rings → Kai Urgent Action Mode.
 *     Checks NotificationManager.canUseFullScreenIntent and reports exactly one of
 *       RESULT=FULL_SCREEN_VERIFIED                       (KaiReminderActivity resumed over the keyguard)
 *       RESULT=FULL_SCREEN_NOT_PERMITTED_FALLBACK_VERIFIED (lock-screen-visible urgent notification with actions)
 *     and fails (never "PASS") if neither actually happened.
 * t2: retry every 5 minutes, snooze, attempt counter, max 5 → EXHAUSTED — on the device's
 *     real alarm store, with a test clock (no real 5-minute waits).
 * t3: Done / Cancel stop every future ring.
 *
 * Every line is logged under the tag KaiReminderLive (scripts/kai-reminder-pixel8.ps1 prints them).
 * The ring is triggered through the engine's own alarm entry point (fired) — the "safe test clock";
 * the 2-minute wait itself is the AlarmManager's job and is not re-tested here.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KaiReminderPixelTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val engine: KaiReminderEngine get() = (ctx.applicationContext as ShopAiApplication).container.kaiReminders
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val keyguard = ctx.getSystemService(KeyguardManager::class.java)
    private val notifications = ctx.getSystemService(NotificationManager::class.java)
    private val created = mutableListOf<String>()

    private fun report(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    private fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(200)
        }
        return check()
    }

    private fun urgentScreen(): Activity? {
        var found: Activity? = null
        inst.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is KaiReminderActivity }
        }
        return found
    }

    private fun praba(id: String) = System.currentTimeMillis().let { now ->
        KaiReminder(
            id = id, title = "Call Praba", task = "Praba-ku call panna", action = ReminderAction.CALL, person = "Praba", phone = "+919000000011",
            triggerAt = now - 1_000, zone = java.time.ZoneId.systemDefault().id,
            notificationMessage = "Owner, Praba-ku call panna sonneenga.", sourceText = "Praba-ku 2 minutes-la call pannanum nyabagam paduthu",
            createdAt = now - 120_000, lang = KaiLang.TANGLISH,
        ).also { created += it.id }
    }

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        shell("input keyevent KEYCODE_WAKEUP")
    }

    @After
    fun tearDown() {
        created.forEach { id -> runCatching { if (!engine.complete(id)) engine.cancel(id) } }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @Test
    fun t1_lockedScreenReminderOpensKaiUrgentActionMode() {
        val canFullScreen = engine.fullScreenAllowed()
        report("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} canUseFullScreenIntent=$canFullScreen")

        // PHONE SCREEN OFF + DEVICE LOCKED
        shell("input keyevent KEYCODE_SLEEP")
        assertTrue("BLOCKER: screen did not turn off", waitFor(5_000) { !power.isInteractive })
        Thread.sleep(1_500)
        val locked = keyguard.isKeyguardLocked
        report("screenOff=${!power.isInteractive} keyguardLocked=$locked")
        assertTrue(
            "BLOCKER: the emulator has no lock screen (Settings → Security → Screen lock → Swipe or PIN), so locked behaviour can't be tested",
            locked,
        )

        // REMINDER TIME REACHED
        val r = praba("LIVE-LOCK-${System.currentTimeMillis()}")
        engine.create(r)
        engine.fired(r.id, snooze = false)
        val presentation = engine.lastPresentation()
        report("presentation=$presentation")
        val rung = engine.find(r.id)!!
        assertEquals("BUG: the ring was not attempt 1 (TRIGGERED)", 1, rung.attemptCount)
        assertEquals(ReminderStatus.RANG, rung.status)

        if (canFullScreen) {
            // Kai must appear full-screen OVER the lock screen — not just a notification.
            val shown = waitFor(10_000) { urgentScreen() != null }
            val lockedWhenShown = keyguard.isKeyguardLocked
            report("urgentScreenResumed=$shown keyguardLockedWhileShown=$lockedWhenShown screenOn=${power.isInteractive}")
            assertTrue("BUG: presentation was not FULL_SCREEN_INTENT: $presentation", presentation?.contains("FULL_SCREEN_INTENT") == true)
            assertTrue("BUG: full-screen is permitted but Kai Urgent Action Mode did not appear over the lock screen", shown && lockedWhenShown)
            val spoke = waitFor(6_000) { engine.find(r.id)?.let(engine::wasSpoken) == true }
            report("ttsStarted=$spoke")
            assertTrue("BUG: Kai did not start speaking the reminder", spoke)
            report("RESULT=FULL_SCREEN_VERIFIED")
        } else {
            // The strongest supported fallback: lock-screen-visible, high priority, sound, actions; tap opens Kai.
            val posted = waitFor(5_000) { urgent() != null }
            val n = urgent()?.notification
            report("notificationPosted=$posted visibility=${n?.visibility} category=${n?.category} actions=${n?.actions?.size} fullScreenIntent=${n?.fullScreenIntent != null} tapOpensKai=${n?.contentIntent != null}")
            assertTrue("BUG: no reminder notification on the lock screen", posted && n != null)
            assertEquals("BUG: not visible on the lock screen", Notification.VISIBILITY_PUBLIC, n!!.visibility)
            assertTrue("BUG: Done / Call buttons missing", (n.actions?.size ?: 0) >= 2)
            assertNotNull("BUG: tapping the notification must open Kai Urgent Action Mode", n.contentIntent)
            assertTrue("BUG: presentation was not NOTIFICATION_ONLY: $presentation", presentation?.contains("NOTIFICATION_ONLY") == true)
            report("RESULT=FULL_SCREEN_NOT_PERMITTED_FALLBACK_VERIFIED")
        }

        // DONE: completed, the screen closes, no future ring.
        assertTrue(engine.complete(r.id))
        assertEquals(ReminderStatus.COMPLETED, engine.find(r.id)!!.status)
        assertTrue("BUG: Kai Urgent Action Mode stayed open after Done", waitFor(5_000) { urgentScreen() == null })
        engine.fired(r.id, snooze = true)
        assertEquals("BUG: rang again after Done", 1, engine.find(r.id)!!.attemptCount)
        // CALL NOW uses the phone's dialer (only opened — never "called").
        val dialer = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+919000000011")), 0)
        report("dialerAvailable=${dialer != null} doneVerified=true")
    }

    private fun urgent() = notifications.activeNotifications.firstOrNull {
        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.contains("Praba") == true
    }

    @Test
    fun t2_retrySnoozeAndMaxFiveAttemptsOnTheDevice() {
        var t = System.currentTimeMillis()
        // The phone's own reminder store, a test clock: five minutes pass in a line of code.
        val e = KaiReminderEngine(ctx) { t }
        val r = praba("LIVE-RETRY-${System.currentTimeMillis()}")
        e.create(r)
        e.fired(r.id, snooze = false)
        var now = e.find(r.id)!!
        assertEquals(1, now.attemptCount)
        assertEquals(t + 300_000, now.snoozedUntil)
        report("attempt 1 status=${now.status} nextRetryIn=${(now.snoozedUntil!! - t) / 60_000}min")

        val snoozed = e.snooze(r.id, KaiReminderEngine.SNOOZE_MINUTES)!!
        assertEquals(ReminderStatus.SNOOZED, snoozed.status)
        report("snooze → ${snoozed.status} for ${KaiReminderEngine.SNOOZE_MINUTES} min")
        for (attempt in 2..5) {
            t += 300_000
            e.fired(r.id, snooze = true)
            now = e.find(r.id)!!
            report("attempt $attempt status=${now.status} presentation=${e.lastPresentation()}")
            assertEquals(attempt, now.attemptCount)
        }
        assertEquals("BUG: not EXHAUSTED after 5", ReminderStatus.EXHAUSTED, now.status)
        assertNull("BUG: a 6th retry is still armed", now.snoozedUntil)
        t += 300_000
        e.fired(r.id, snooze = true)
        assertEquals("BUG: rang a 6th time", 5, e.find(r.id)!!.attemptCount)
        assertNull("BUG: snooze allowed after the last attempt", e.snooze(r.id, 5))
        report("RETRY_VERIFIED attempts=5 status=EXHAUSTED")
    }

    @Test
    fun t3_cancelledReminderNeverRings() {
        val e = engine
        val r = praba("LIVE-CANCEL-${System.currentTimeMillis()}").copy(triggerAt = System.currentTimeMillis() + 600_000)
        e.create(r)
        assertTrue(e.cancel(r.id))
        e.fired(r.id, snooze = false)
        e.fired(r.id, snooze = true)
        assertEquals(ReminderStatus.CANCELLED, e.find(r.id)!!.status)
        assertEquals("BUG: a cancelled reminder rang", 0, e.find(r.id)!!.attemptCount)
        report("CANCEL_VERIFIED")
    }

    private companion object {
        const val TAG = "KaiReminderLive"
    }
}
