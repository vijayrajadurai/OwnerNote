# Kai Smart Persistent Reminder — REPORT

Status: **implemented; JVM-tested here; Android build and Pixel 8 behaviour NOT yet verified.**
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
