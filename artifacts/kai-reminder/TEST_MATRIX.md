# Kai Reminder — TEST MATRIX

JVM = runs here (`KaiReminderFlowTest`, `KaiReminderConfirmTest`, updated chat tests) — **passing**.
ROBO = `KaiReminderEngineTest` (Robolectric) — written, **not run here**.
DEVICE = `KaiReminderPixelTest` on Pixel 8 via `scripts/kai-reminder-pixel8.ps1` — **not run yet**.

| # | Spec test | Where | Status |
|---|---|---|---|
| 1 | Create reminder | JVM `praba2MinutesCallIsAskedThenScheduledOnConfirm` | PASS |
| 2 | Confirmation required | JVM same + `naturalVariations` | PASS |
| 3 | Schedule only after confirmation | JVM same (nothing scheduled before Confirm) | PASS |
| 4 | Correct person | JVM (`Praba`, phone from contacts) | PASS |
| 5 | Correct action | JVM (CALL / PAYMENT / STOCK) | PASS |
| 6 | Correct delay | JVM (2 / 5 / 10 minutes from the Confirm) | PASS |
| 7 | Correct date/time | JVM `tomorrowMorningTen` | PASS |
| 8 | Cancel before trigger | JVM `cancelBeforeConfirm`, `doneAndCancelStopFutureRings`; ROBO; DEVICE t3 | JVM PASS · others not run |
| 9 | Trigger | JVM `retriesEveryFiveMinutesThenExhausts`; ROBO; DEVICE t1 | JVM PASS · others not run |
| 10 | DONE | JVM; ROBO `doneAndCancelStopRetries`; DEVICE t1 | JVM PASS · others not run |
| 11 | CALL NOW | JVM words (`replyWordsNeverClaimACall`); DEVICE t1 checks a dialer exists | JVM PASS · device not run |
| 12 | SNOOZE | JVM `snoozeSchedulesExactlyOneNextRing`; ROBO `snoozeFiveAndSpeakOnce`; DEVICE t2 | JVM PASS · others not run |
| 13 | Retry after 5 minutes | JVM; ROBO `retriesEveryFiveMinutesThenStops`; DEVICE t2 (test clock) | JVM PASS · others not run |
| 14 | Maximum 5 attempts | JVM; ROBO; DEVICE t2 | JVM PASS · others not run |
| 15 | EXHAUSTED | JVM; ROBO; DEVICE t2 | JVM PASS · others not run |
| 16 | Duplicate prevention | JVM `duplicateIsPrevented`; ROBO `exactAlarmAtTheTriggerTime` | JVM PASS · ROBO not run |
| 17 | App restart | ROBO `ringsOnceEvenAfterRestart`, `newFieldsSurviveRestart` | not run |
| 18 | Device reboot / reschedule | `rearmAll()` (BootCompletedReceiver) — ROBO restart test | not run |
| 19 | Timezone | existing `rearmAll` zone handling; JVM time tests | JVM PASS (time parsing) |
| 20 | Owner isolation | JVM `ownerAndBusinessIsolation` | PASS |
| 21 | Business isolation | JVM `ownerAndBusinessIsolation` | PASS |
| 22 | TTS duplicate prevention | JVM `speechKeyIsPerAttempt`; ROBO `snoozeFiveAndSpeakOnce` | JVM PASS · ROBO not run |
| 23 | Full-screen capability detection | JVM `presentationFollowsTheRealCapability`; DEVICE t1 logs `canUseFullScreenIntent` | JVM PASS · device not run |
| 24 | Notification fallback | JVM decision; DEVICE t1 (when not permitted) | JVM PASS · device not run |
| 25 | No false claim that call happened | JVM `replyWordsNeverClaimACall`, `praba2Minutes…` | PASS |
| 26 | Foreground reminder | JVM decision (DIRECT_ACTIVITY) | PASS (logic) · device not run |
| 27 | Background reminder | JVM decision | PASS (logic) · device not run |
| 28 | Locked-screen fallback | JVM decision; DEVICE t1 | JVM PASS · device not run |
| 29 | Repeated reminder state | JVM `urgentWordsPerAttemptAndAction`, `repeatingReminderCountsPerOccurrence` | PASS |
| 30 | Cancelled reminder cannot retrigger | JVM; ROBO; DEVICE t3 | JVM PASS · others not run |

Regression: all 484 earlier brain tests still pass (17 now confirm the reminder first, as required).
