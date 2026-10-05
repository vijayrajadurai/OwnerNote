# Kai Smart Persistent Reminder — REPORT

## FINAL RESULT: **FULL_SCREEN_VERIFIED** (owner-observed on Pixel 8, manual)

The owner ran the reminder on the **Pixel 8 emulator (API 37.2)** and reported that Kai Urgent
Action Mode appeared full-screen **with the screen off/locked and with it on** (screenshot from the
owner: black screen, Kai, REMINDER, "Praba-ku call panna vendiya neram aachu", "Ippo call pannalama?",
CALL NOW / DONE / SNOOZE 5 MIN, "Reminder 1 of 5"). This is a manual observation; the automated
`scripts/kai-reminder-pixel8.ps1` run (logcat + screenshots) has not been sent back yet.

The owner also found a defect, fixed in round 3: on the unlocked screen the reminder's notification
banner stayed on top of Kai.

## Round 3 — no duplicate banner + cinematic Kai (branch `feature/kai-reminder-cinematic-urgent-mode`)

**Notification, before → after**
- Before: every ring posted the alerting notification (with full-screen intent) and, on an unlocked
  phone with the app open, also opened Kai → the heads-up banner covered Kai.
- After (same one notification id, same engine):
  - FULL_SCREEN_INTENT (locked / background, permitted): notification with full-screen intent →
    Android opens Kai → when Kai's screen resumes, that ring's notification is cancelled.
  - DIRECT_ACTIVITY (app open, unlocked): notification posted **silently** (no heads-up, no full-screen
    intent) and Kai opens directly; cancelled once Kai is on screen.
  - NOTIFICATION_ONLY (full-screen not permitted): unchanged full alert notification, **never** cancelled
    by Kai's screen (it is the reminder).
  - Left without Done/Snooze (back, home, dialer): a quiet notification is put back so the reminder is
    not lost; the 5-minute retry still rings.
  - Alarms, retry, snooze, Done, cancel, persistence and boot handling untouched.

**Animation, before → after**
- Before: Kai with a breathing scale and one amber pulse ring.
- After (layered around the existing animated `KaiCharacter` — no new asset):
  ARRIVAL 0–650 ms (from black: fade, 92 %→100 %, 28 dp rise, ease-out, no bounce) → WAKE 650–1550 ms
  (soft green energy field, slow orbit arc, 22 drifting light particles) → ATTENTION 1550–2300 ms
  (lean toward the owner ≤ 4° and back, one soft pulse wave, Kai's GREETING gesture) → IDLE (slow
  breathing, gentle sway, a small nod every 7 s, slow orbit; slightly quicker per attempt, capped).
  Words: REMINDER → headline slides up → question → details → buttons (fade/slide, no typewriter).
  Buttons: press-scale, haptic on CALL NOW / DONE; Done / Snooze save first, then Kai settles out in 260 ms.
  Black background, Owner Note green the only accent. Reduced motion → still Kai, no particles.
  The intro plays once per ring; rotation / resume shows the end state; the voice is not repeated.

**Files:** `notifications/KaiReminderEngine.kt`, `ui/reminder/KaiReminderActivity.kt`,
`ui/reminder/KaiUrgentStage.kt` (new), `ui/reminder/KaiUrgentMotion.kt` (new),
`brain/tools/KaiReminderFlow.kt`; tests `KaiUrgentMotionTest` (new), `KaiReminderFlowTest`,
`KaiReminderEngineTest`, `KaiReminderPixelTest` (+ t6, no-banner checks).

**Round 3 verification:** JVM 508 / 508 pass; engine, Urgent screen, stage and device test type-check
against android-all + Compose. **Not yet seen on the Pixel 8** — the banner removal and the new
animation need the device run (`scripts/kai-reminder-pixel8.ps1`, or the manual steps).

---


## Round 2 result (before the owner's device run): BLOCKER (no emulator in the build environment)

Pixel 8 validation could not be run from this environment:
- no Android SDK / adb / emulator here, and `dl.google.com` (SDK, emulator images, Google Maven
  artifacts) is denied by this environment's network policy (HTTP 403 at the proxy);
- no hardware virtualisation (`/dev/kvm` absent, no vmx/svm), so an Android emulator cannot boot here.

Nothing below is claimed as verified on a Pixel 8. The result changes only when
`scripts/kai-reminder-pixel8.ps1` is run on the Pixel 8 emulator (screen lock set) and prints its
FINAL line — `FULL_SCREEN_VERIFIED`, `FULL_SCREEN_NOT_PERMITTED_FALLBACK_VERIFIED`, `BUG` or `BLOCKER`.

## This round (branch `feature/kai-reminder-pixel8-validation`, from main `015d83a`)

Static review of the full-screen path (no defect found, so no production code changed):

| Item | Status in code |
|---|---|
| targetSdk / compileSdk | 36 / 36 (minSdk 26) |
| `USE_FULL_SCREEN_INTENT` | declared in the manifest |
| `NotificationManager.canUseFullScreenIntent()` | checked on every ring (Android 14+), logged with `sdk`, `interactive`, `locked`, presentation |
| Channel | `kai_reminders_urgent_v1`, IMPORTANCE_HIGH, lock-screen PUBLIC, sound + vibration |
| Notification | CATEGORY_REMINDER, PRIORITY_MAX, VISIBILITY_PUBLIC, `setFullScreenIntent(open, true)` only when permitted |
| PendingIntent | `getActivity` → `KaiReminderActivity`, FLAG_IMMUTABLE |
| Activity | not exported, `showWhenLocked` + `turnScreenOn` (manifest and `setShowWhenLocked/setTurnScreenOn`), singleTask, own task |
| Alarm | `setExactAndAllowWhileIdle` when exact alarms are allowed (USE_EXACT_ALARM declared), else inexact and said |
| Save before show | the ring is saved before the notification is posted (the screen reads it on open) |

Pixel 8 test strengthened (`app/src/androidTest/.../KaiReminderPixelTest.kt`):
- **t1 (acceptance):** Kai chat "Praba-ku 2 minutes-la call pannanum nyabagam paduthu" → Confirm →
  alarm checked in `dumpsys alarm` → screen OFF + keyguard LOCKED → waits for the **real** AlarmManager
  ring (no shortcut) → full-screen path: Kai Urgent Action Mode resumed while still locked, two
  screenshots (black background, Kai moving), REMINDER / headline / question / CALL NOW / DONE /
  SNOOZE 5 MIN / "Reminder 1 of 5" read from the screen, voice triggered (and whether audio started),
  SNOOZE tapped on the screen → SNOOZED, next ring in ~5 min, snooze alarm registered.
  Fallback path (when not permitted): lock-screen PUBLIC notification, 3 actions, no full-screen
  intent, tap opens Kai Urgent Action Mode.
- **t2:** fallback forced on the same phone (`appops … USE_FULL_SCREEN_INTENT deny`, restored after),
  real alarm, tap opens Kai.
- **t3:** CALL NOW tapped (dialer only; reminder stays RANG; "Call screen open pannitten Owner."),
  DONE tapped → COMPLETED, screen closes, no further ring.
- **t4:** retry every 5 min / snooze / attempts 1…5 → EXHAUSTED (test clock on the real store).
- **t5:** cancelled reminder never rings.
- `scripts/kai-reminder-pixel8.ps1` pulls the screenshots + log into `artifacts/kai-reminder/pixel8/`
  and prints exactly one FINAL label.

---


## Earlier round (implementation, commit 015d83a)

Status then: implemented; JVM-tested; Android build and Pixel 8 behaviour not verified.
This environment cannot build the Android app or run an emulator (Google's Maven repository is
blocked here). Every "Pixel 8" item below is **NOT VERIFIED** until
`scripts/kai-reminder-pixel8.ps1` is run on the Pixel 8 emulator and prints its RESULT line.

## Phase 0 — what already existed (reused, nothing duplicated)

| Question | Finding | Reused as |
|---|---|---|
| 1. Storage | `KaiReminderEngine`: JSON list in SharedPreferences `kai_reminder_engine` (no Room table) | Same store; new fields added to the same JSON (old records read with defaults — no migration needed) |
| 2. Scheduling | `AlarmManager.setExactAndAllowWhileIdle` (or `setAndAllowWhileIdle` without exact permission), one alarm + one snooze alarm per reminder, fixed request codes | Same path; retries use the existing snooze-alarm slot (never a 2nd alarm) |
| 3. Notifications | `KaiReminderEngine.show()`, channel `kai_reminders_v1` | Replaced by `present()` on a new urgent channel `kai_reminders_urgent_v1` (same engine) |
| 4. Completion | `complete(id)` | Now goes through `KaiReminderFlow.complete` |
| 5. Cancellation | `cancel(id)` | Now goes through `KaiReminderFlow.cancel` |
| 6. Call Now | `Intent.ACTION_DIAL` (KaiChatScreen + notification action) | Same `ACTION_DIAL` (owner presses call) |
| 7. TTS | `NaturalTtsSpeaker.speakNatural` (Sarvam proxy → device TTS), lip-sync flows | Same speaker; `ta-IN` / `en-IN` like Kai chat |
| 8. Target SDK | targetSdk 36, compileSdk 36, minSdk 26 | — |
| 9. Channels | `kai_reminders_v1` (reminders), morning, check-in | + `kai_reminders_urgent_v1` (HIGH, public on lock screen, sound, vibration) |
| 10. Permissions | POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM(≤32), USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED … | + `USE_FULL_SCREEN_INTENT` |
| 11. Tests | `KaiReminderEngineTest` (Robolectric), `KaiReminderUnderstandingTest`, chat reminder tests | Extended |
| 12. ReminderActivity | None (tap opened Kai Chat) | New `KaiReminderActivity` (Kai Urgent Action Mode) |
| 13. Scheduler abstraction | `KaiReminderEngine` is the one engine; boot / time / zone / update receivers call `rearmAll()` | Same; `rearmAll()` also re-arms pending retries / snoozes |
| Parser | `KaiReminderUnderstanding` + `KaiTime` | Same parser (no new time parser) |

## Files changed

- `brain/tools/KaiReminders.kt` — states SNOOZED / EXHAUSTED, action STOCK, fields ownerId, amount,
  attemptCount, maxAttempts (5), snoozeCount, lastTriggeredAt, lang, `nextTriggerAt`; amount only when said.
- `brain/tools/KaiReminderFlow.kt` (new) — the state machine, urgent words (all actions, 3 languages,
  attempt 1 / 2 / 3+), full-screen decision, owner/business scope.
- `brain/chat/KaiReminderAssistant.kt` — confirm-first: "Seri Owner. Praba-ku 2 minutes-la call reminder
  set pannalama?" [Confirm] [Edit] [Cancel]; typed aama / venam / maathu; nothing saved before Confirm;
  full-screen-not-allowed note + settings button.
- `brain/chat/KaiAgent.kt` — actions ConfirmReminder, EditReminderRequest, OpenFullScreenSettings.
- `brain/tools/KaiTools.kt` — `fullScreenAllowed()`.
- `notifications/KaiReminderEngine.kt` — injectable clock; ring → `KaiReminderFlow.trigger` + retry alarm;
  `present()` checks `NotificationManager.canUseFullScreenIntent()` (Android 14+) and logs it every ring;
  full-screen intent / strongest notification / direct screen; speech guard; JSON for the new fields.
- `ui/reminder/KaiReminderActivity.kt` (new) — Kai Urgent Action Mode.
- `data/kai/AppKaiTools.kt`, `data/AppContainer.kt` — reminders stamped and filtered by the session's
  business + owner (from Kai memory's open login, never from a screen).
- `ui/kaichat/KaiChatScreen.kt`, `KaiChatSession.kt` — open the full-screen-alerts setting.
- `AndroidManifest.xml` — USE_FULL_SCREEN_INTENT; KaiReminderActivity (showWhenLocked, turnScreenOn,
  singleTask, own task, excluded from recents).
- `app/build.gradle.kts` — `testInstrumentationRunner` (device tests could not run without it).
- Tests: `KaiReminderFlowTest` (10), `KaiReminderConfirmTest` (8), `KaiReminderEngineTest` (+4, Robolectric),
  `KaiReminderPixelTest` (device, 3), confirm step added to 17 existing chat tests (`ReminderTestSupport.askConfirmed`).
- `scripts/kai-reminder-pixel8.ps1` — runs the Pixel 8 test and prints the RESULT.

## Database changes

None. The reminder store is the existing JSON in SharedPreferences; new fields have defaults,
so reminders saved by the old version still load.

## Behaviour

- **States:** ACTIVE (= SCHEDULED) → RANG (= TRIGGERED) → COMPLETED / SNOOZED / CANCELLED; 5th ring →
  EXHAUSTED (kept in history, never rings again). Repeating reminders keep their series (attempts per occurrence).
- **Retry:** every ring arms one retry in 5 minutes in the reminder's single snooze slot; Snooze 5 min uses
  the same slot. Max 5 rings in total. Done / Cancel cancel the slot.
- **Scheduler:** exact alarm when allowed; otherwise `setAndAllowWhileIdle` and Kai says it may be late
  (`ScheduleResult.APPROXIMATE`, existing). Boot / app update / time / zone change → `rearmAll()`.
- **Presentation (logged on every ring, tag `KaiReminder`):**
  foreground + unlocked → Urgent Action Mode directly; locked or background → full-screen intent **only if**
  `canUseFullScreenIntent()` is true; otherwise high-priority, lock-screen-public notification with sound,
  vibration, CALL NOW / DONE / SNOOZE, tap opens Urgent Action Mode. No overlays, no keyguard bypass;
  Call Now over the lock screen asks Android to unlock (`requestDismissKeyguard`).
- **Urgent Action Mode:** near-black screen, Kai (existing `KaiCharacter`, REMINDER face, lip-sync) with a
  breathing scale and a soft pulse ring that grows slightly faster per attempt; reduced-motion → still.
  REMINDER · headline · question · "Set 2 minutes ago" · CALL NOW / DONE / SNOOZE 5 MIN · "Reminder 1 of 5".
- **Voice:** "Owner, Praba-ku call panna vendiya neram aachu. Ippo call pannalama?" once per attempt
  (persisted key `id#attempt#time`), always also on screen. Stops on Done / Snooze / leaving.
- **Call Now:** dialer only; "Call screen open pannitten Owner." — never "called"; not auto-completed.

## Results

| Check | Result |
|---|---|
| JVM tests (whole brain suite, incl. 18 new reminder tests) | **502 / 502 pass** |
| Engine + Urgent Action Mode + device test: type-checked against android-all API 34 + Compose | compiles (stub harness) |
| `KaiReminderEngineTest` (Robolectric) | **not run here** (needs Google Maven) |
| Android Studio build | **not run here** |
| Pixel 8: exact-alarm capability | **NOT VERIFIED** |
| Pixel 8: lock-screen / full-screen | **NOT VERIFIED** — run the script |
| Pixel 8: notification fallback | **NOT VERIFIED** |
| Pixel 8: TTS / animation | **NOT VERIFIED** |

## Remaining limitations

- Full-screen over the lock screen depends on Android: on Android 14+ Google Play may revoke
  USE_FULL_SCREEN_INTENT for apps that are not alarm / calling apps; then the notification fallback is used
  and Kai says so when a reminder is set (with a button to the setting).
- The device test triggers the ring through the engine's own alarm entry point (test clock); the real
  2-minute AlarmManager wait is Android's job and is not re-timed by the test.
- "Call answered" is never detected (no verified call-completion mechanism exists).
- Owner/business stamping uses the Kai memory's open login; reminders created before Kai memory opens
  in a session are stored without ids (visible to that phone's login, and cleared on logout as before).
