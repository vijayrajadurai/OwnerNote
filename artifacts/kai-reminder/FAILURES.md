# Kai Reminder — FAILURES / BLOCKERS

No failing test in what could be run here (JVM: 502 / 502).

Blockers to a full PASS (real, not hidden):

1. **Pixel 8 validation not done.** This environment has no Android emulator or Android build
   (Google Maven blocked). Lock-screen / full-screen, notification fallback, TTS, animation, Call Now,
   Done, Snooze on the device are **NOT VERIFIED**. Run `scripts/kai-reminder-pixel8.ps1`; it prints
   `FULL_SCREEN_VERIFIED` or `FULL_SCREEN_NOT_PERMITTED_FALLBACK_VERIFIED` only if that actually happened.
2. **Robolectric `KaiReminderEngineTest` not run here** (needs Google Maven). Run in Android Studio:
   `gradlew :app:testDebugUnitTest --tests "com.shopai.app.notifications.KaiReminderEngineTest"`.
3. **Android-only files not compiled with the real Android toolchain here:** `AppKaiTools.kt`,
   `AppContainer.kt`, `KaiChatScreen.kt`, `KaiChatSession.kt`, manifest. The engine, the Urgent Action
   Mode activity and the device test were type-checked against android-all API 34 + Compose stubs.
4. **Emulator needs a screen lock** (Swipe or PIN) for the locked test — otherwise the device test stops
   with `BLOCKER: the emulator has no lock screen`.
