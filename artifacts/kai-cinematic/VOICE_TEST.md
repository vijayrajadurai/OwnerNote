# Voice test

## Round 2 (after the owner's Pixel 8 test): natural Sarvam voice

The owner tested on the Pixel 8 and reported that the voice sounded robotic.

**Cause:** the reminder lines were sent to the Sarvam Tamil voice (`ta-IN`) as Tanglish in Latin
letters. Every other Kai reply sends Tamil **script**; `KaiResponder` notes that the Tamil voice can't
read Tanglish. So the voice either misread the Latin letters or fell back to the robotic Android voice.

**Fix:**
- For Tamil and Tanglish owners, the voice now speaks the Tamil-script lines (`KaiUrgentVoiceScript.spoken`).
  English loanwords are written as a Tamil speaker says them: call→கால், reminder→ரிமைண்டர், and so on.
- The owner's own task words are converted the same way ("Praba-ku call panna" → "Praba-க்கு கால் பண்ண").
- The screen text stays Tanglish.
- `NaturalTtsSpeaker` now records which voice played and why the natural voice was skipped. Logcat:
  `voice engine=sarvam` or `voice engine=device (natural voice not used: …)`.
- Device test t7 reports `voiceEngine` and fails if the Sarvam proxy is configured but the robotic voice played.

Example — what Kai says (Tanglish owner):

| # | Spoken (Tamil script, Sarvam) | Meaning on screen |
|---|---|---|
| 0 | ஓனர், Praba-க்கு கால் பண்ண வேண்டிய நேரம் ஆச்சு. இப்போ கால் பண்ணலாமா? | Owner, Praba-ku call panna vendiya neram aachu… |
| 1 | Praba-க்கு கால் பண்ணுங்க ஓனர். | Praba-ku call pannunga Owner. |
| 2 | சீக்கிரம் பண்ணுங்க ஓனர். | Seekiram pannunga Owner. |
| ack | சரி ஓனர், Praba-க்கு கால் ஸ்க்ரீன் திறக்குறேன். | Seri Owner, Praba-ku call screen open pannuren. |

The table below describes the first round. The written lines are unchanged; Kai now speaks them in Tamil script.

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
