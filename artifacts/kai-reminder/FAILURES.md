# Kai Reminder — FAILURES / BLOCKERS

## FINAL: FULL_SCREEN_VERIFIED (owner-observed, manual — Pixel 8 API 37.2)

Open items (real, not hidden):

1. **Fixed in round 3, not yet re-verified on the device:** the reminder's notification banner stayed on
   top of Kai Urgent Action Mode on the unlocked screen (owner's screenshot). Needs the Pixel 8 re-run.
2. **New cinematic animation not yet seen on the device** (type-checked and timing-tested only).
3. **Automated Pixel 8 evidence not yet collected** (logcat + screenshots from
   `scripts/kai-reminder-pixel8.ps1`); the build environment here has no emulator.
4. **Robolectric `KaiReminderEngineTest` not run here** (needs Google Maven):
   `gradlew :app:testDebugUnitTest --tests "com.shopai.app.notifications.KaiReminderEngineTest"`.

No failing automated test here: JVM 508 / 508.

---

## Earlier (round 2)


## FINAL: BLOCKER

1. **No Pixel 8 here (BLOCKER).** This environment has no Android SDK, adb or emulator;
   `dl.google.com` is denied by the network policy (proxy 403), and there is no `/dev/kvm`, so an
   emulator cannot boot even with the SDK. Locked-screen behaviour, full-screen capability, fallback,
   animation, TTS, Call Now, Done, Snooze, retry and max attempts on the device are **NOT VERIFIED**.
   Run `scripts/kai-reminder-pixel8.ps1` on the Pixel 8 emulator (screen lock set to Swipe or PIN).
2. **Robolectric `KaiReminderEngineTest` not run here** (needs Google Maven):
   `gradlew :app:testDebugUnitTest --tests "com.shopai.app.notifications.KaiReminderEngineTest"`.
3. **No production-code defect found** in the static review of the full-screen path; none was changed.
   Any real defect will show up as `FINAL: BUG` with the failing check from the device run.

No failing automated test here: JVM 502 / 502.
