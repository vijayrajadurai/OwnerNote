# Reminder integrity

## Flow

1. Words → `KaiReminderUnderstanding` (person / task / time via `KaiTime.parse`, Tamil / Tanglish / English).
2. Missing time → Kai asks ("Eppa remind pannanum?"); nothing is stored.
3. Draft → "… reminder set pannalama?" [Confirm] [Edit] [Cancel] — nothing stored before Confirm.
4. Confirm → `KaiReminderSchedule.build` → `KaiTools.createReminder` → `KaiReminderEngine.create` (dedup, SharedPreferences, exact alarm).
5. **New in this branch — read-back:** `KaiTools.reminderStored(id)` (`AppKaiTools` → `KaiReminderEngine.find(id)`). Only when the
   store gives the reminder back does Kai say "Done Owner ✅ … remind pannuren". If it does not: "reminder save aanadha confirm panna
   mudiyala — Reminders screen-la paarunga", logged as FAILED. A store that cannot be read back (`null`) keeps the old behaviour.
6. Fire: AlarmManager → `KaiReminderReceiver` → notification / full-screen `KaiReminderActivity` → Done / Snooze / Call; re-armed on boot,
   time change and app update (`rearmAll`) — unchanged in this branch.

## Verified (JVM)

| Check | Test |
|---|---|
| Nothing stored before Confirm (20 phrasings × times) | matrix `reminder` |
| Exact trigger time for "10 minutes la", "2 minutes la", "1 hour la", "30 minutes la", "5 nimishathula" | matrix `reminder` |
| "naalaikku kaalaila 10 manikku", "tomorrow 10 AM", "saayangalam 6 manikku", "today 6 PM", "naalaikku 9 AM" | matrix `reminder` |
| A store that loses the reminder → no "remind pannuren" claim (5 rows) | matrix `reminder` ("lost by the store") |
| No time → not stored (5 rows) | matrix `reminder` |
| Receivable + due date + reminder + query in one journey | journey 3 |
| Existing reminder suites (confirm, personal, urgent mode, NL) | `KaiReminderConfirmTest`, `KaiPersonalReminderTest`, … (all pass) |

## Not verified here

The alarm actually ringing, the notification / full-screen activity, Done / Snooze from the notification, re-arm after reboot:
Android-only. **Pixel 8 = BLOCKED.**
