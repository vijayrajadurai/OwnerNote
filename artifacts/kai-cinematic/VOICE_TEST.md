# Voice test

Tanglish cycle for "Praba-ku call" (`KaiUrgentVoiceScript`). Each pause starts after the previous
line ends.

| # | Pause before | Line | Hand |
|---|---|---|---|
| 0 | 0 | Owner, Praba-ku call panna vendiya neram aachu. Ippo call pannalama? | ✓ |
| 1 | 6.5 s | Praba-ku call pannunga Owner. | ✓ |
| 2 | 9 s | Seekiram pannunga Owner. | – (brows) |
| 3 | 11 s | Owner, Praba-ku call pannalama? | ✓ |
| 4 | 13 s | Praba-ku call panna marakkadheenga Owner. | – |
| 5 | 15 s | Owner, idha ippo mudichidalaama? | ✓ |
| … | 18 s | then lines 1–5 again; never the same line twice in a row | |

The cycle also has Tamil-script and English versions, and versions for message and generic reminders.

Short answers:

| Action | Kai says |
|---|---|
| Call now | "Seri Owner, Praba-ku call screen open pannuren." (never "called"; the dialer opens at once) |
| Done | "Seri Owner." |
| Snooze | "Seri Owner, 5 minutes-ku remind pannuren." |

| Rule | How | Test |
|---|---|---|
| Exactly one voice loop | process-wide `KaiUrgentVoice`; `start()` for the same ring is a no-op | `oneLoopOnlyForTheSameRing` (mutation-checked) |
| No restart on rotation, recomposition, resume or notification tap | same as above; `onStop` with a configuration change doesn't pause | t6, t7 on the device |
| Stop immediately on Call, Done or Snooze | the loop is cancelled, the speaker is stopped, prepared audio is dropped (generation guard) | `actionStopsImmediatelyThenOneShortAnswer` (mutation-checked) |
| The answered ring never speaks again | the `answered` set | same test |
| Leaving without an answer pauses the voice; returning continues with the next line | `pause()` and the stored line index | `pauseAndComeBackContinuesNotFromTheTop` |
| The next attempt gets its own cycle ("Reminder 2 of 5") | the ring key includes the attempt | `nextAttemptStartsItsOwnCycle` |
| A voice that never reports done can't stall the loop | 30 s timeout per line | `aVoiceThatNeverReportsBackCannotStallIt` |
| Max 5 attempts, then EXHAUSTED | unchanged engine | t4, `KaiReminderFlowTest` |

Device evidence still needed (t7 plus a manual listen):
- the second line plays 6.5 s or more after the first;
- the voice sounds natural;
- the speaker is silent 20 s after Done;
- no overlap after rotation.
