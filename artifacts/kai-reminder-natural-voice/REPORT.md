# Kai Reminder: natural human voice and personal life reminders

- **Branch:** `feature/kai-reminder-natural-voice`, from `main` e391ea4.
- **Not committed, pushed or merged.** It is waiting for review.
- **main / master were not touched.**

## 1. Why the long pause happened

Kumar's reminder went like this:

> "Kumar-ku call panna vendiya neram aachu Owner." … long silence … "Kumar-ku call pannunga Owner."

This was **not** caused by TTS start-up, audio focus or the network on their own. Three things added up:

| # | Cause | Where | Effect |
|---|---|---|---|
| 1 | **A 6.5 s pause written into the script.** The opening line and the "call pannunga" line were two separate utterances, and `pauseBefore(1)` was 6.5 s. | `KaiUrgentVoiceScript.pauseBefore` | 6.5 s |
| 2 | **Each sentence was its own TTS request and its own `MediaPlayer`.** Each one was created, prepared and released, so every sentence started with fresh intonation. That is the "restart" feeling. | `KaiUrgentVoice` → `NaturalVoiceOut.say` → `NaturalTtsSpeaker.speakNatural` → `playWavFile` (`halt()` + `new MediaPlayer` + `prepareAsync`) | Sounds like a new speaker each time, plus prepare time |
| 3 | **Every Sarvam clip has silence padding** at its start and end. | The WAV returned by the proxy | Roughly 0.3–0.8 s more between sentences |

When a line was not cached yet, the network fetch (1–3 s) was added on top.

**Total before:** about 7.3 s or more of silence between the two sentences (tail padding + 6.5 s + head padding). The "Before the fix" test in `WavJoinTest` measures this.

## 2. What changed (the fix)

**One turn is one audio clip.** The first turn is three sentences, played as one continuous voice through one player:

```
"Owner... Kumar-ku call panna vendiya neram aachu."  ~0.39 s  "Call pannunga."  ~0.94 s  "Call pannalama?"
```

- `VoiceLine` now has `parts` (the sentences) and `gapsMs` (the pauses).
- **Gaps:**
  - `GAP_AFTER_FIRST_MS` = 300, plus the clip edges (30 + 60 ms), so about **390 ms**. The target was 250–500 ms.
  - `GAP_BEFORE_QUESTION_MS` = 850, plus the edges, so about **940 ms**. The target was 700–1200 ms.
- **`WavJoin`** (new, pure Kotlin, no Android):
  - reads each sentence's WAV;
  - trims the service's silence padding down to short edges (30 ms before, 60 ms after);
  - puts **exactly** the asked silence between the sentences;
  - writes one WAV.
  - If the clips have different formats or can't be read, it returns `null`, and the caller falls back.
- **`NaturalTtsSpeaker.speakTurn`:**
  - fetches all the sentences at once (usually already on disk from the prefetch, so no network);
  - joins them and plays them with **one** `MediaPlayer`.
  - **Fallback:** one request for the whole turn (Sarvam's own sentence pauses), then device TTS. It is still one utterance.
  - `stop()` (Call / Done / Snooze) drops it at any stage: fetching, joining or playing.
- **`KaiVoiceOut.sayTurn`:** the default says the turn as one text. `NaturalVoiceOut` overrides it to use `speakTurn`.
- **`KaiUrgentVoice`:** a turn with more than one sentence goes to `sayTurn`. A single line goes to `say`, as before.
- **Prefetch:** `prefetchTexts` now keeps **each sentence** separately, plus the next attempt's opening sentences. Joining at ring time therefore needs no network.

**Unchanged:**
- The pause **between turns** (6.5 s, 9 s, 11 s … 18 s cap). The owner still gets time to act.
- The retry count, retry interval, the 5-attempt limit, EXHAUSTED.
- Snooze / Done / Call Now.
- AlarmManager and exact alarms.
- The full-screen / lock-screen behaviour.
- The urgent screen's text (`KaiUrgentWords`).

## 3. Voice wording: before and after

| Kind | Before (Tanglish, as written) | After: turn 1 (one clip) |
|---|---|---|
| Call | "Owner, Kumar-ku call panna vendiya neram aachu. Ippo call pannalama?" … 6.5 s … "Kumar-ku call pannunga Owner." … "Owner, Kumar-ku call pannalama?" … "Kumar-ku call panna marakkadheenga Owner." (the name in 4 of 6 lines) | "Owner... Kumar-ku call panna vendiya neram aachu." · "Call pannunga." · "Call pannalama?" (the **name once**) |
| Payment | "Owner, Kumar-ku payment panna vendiya reminder. Mudinjadhum Done press pannunga." | "Owner... Kumar-ku payment panna vendiya time aachu." · "Idha check pannunga." · "Open pannalama?" (an amount **only** if the owner said one) |
| Personal (son) | "Owner, neenga remind panna sonna task pending-la irukku. “Paiyana school- irundhu kootitu vara”" | "Owner... 4 mani aachu." · "Paiyana school-la irundhu kootitu vara vendiya neram." · "Kelambalama?" |
| Current bill | (generic: "task pending-la irukku") | "Owner... current bill pay panna vendiya time aachu." · "Idha ippo pannunga." · "Open pannalama?" |
| Gym | (generic) | "Owner... 8 mani aachu." · "Gym poganum-nu reminder." · "Ready-a?" |
| Medicine | (generic) | "Owner... 9 mani aachu." · "Medicine edukkanum-nu reminder." · "Eduthukkalama?" |
| Office | (generic) | "Owner... 8:30 aachu." · "Office-ku kelambanum-nu reminder." · "Kelambalama?" |

### Follow-ups (between turns)

They are short and do not repeat the name.

| Attempt | Follow-ups |
|---|---|
| 1 | "Seekiram call pannunga Owner." · "Owner, call pannalama?" · "Marakkadheenga Owner." · "Owner, idha ippo mudichidalaama?" |
| 2 (more direct) | "Owner, ippo call pannunga." · "Konjam seekiram Owner." · … |
| 3 and later (urgent) | "Owner, udane call pannunga." · "Romba late aagudhu Owner." · … |

### Attempt escalation (turn 1)

| Attempt | Turn 1 |
|---|---|
| 1 | "Owner... Kumar-ku call panna vendiya neram aachu." |
| 2 | "Owner, Kumar-ku innum call pannala." · "Ippo call pannunga." |
| 3 and later | "Owner, Kumar call romba neram-a pending-la irukku." · "Udane call pannunga." · "Ippove call pannalama?" |

The urgency is in the wording, never in anger. A test checks for no "!", no all-caps lines and no harsh words.

### How it reaches the voice

For Tamil and Tanglish owners, the voice speaks Tamil script, as before. The word list now also covers personal-life words: paiyana, school, irundhu, kootitu, vara, gym, medicine, office, gate, current, bill, open, check, collection, and so on.

The case endings `-la` / `-nu` / `-a` become Tamil. For example, "school-la" is spoken as "ஸ்கூல்ல".

## 4. Personal reminders: how they work

Everything goes through the **same reminder engine**. There is no new engine, no new database field and no migration.

### Understanding fixes

Plain rules in the existing understanding code; no phrase is hardcoded.

**`cleanTask` (`KaiReminders.kt`)**
- "school-la" stays "school-la". It used to become "school-".
- Leftovers like "-ku" or "ku" from a removed time ("4 PM-ku son-a …"), "aagumbodhu", "solli", and a trailing "irukku" are removed.

**`KaiTime.strip`**
- "8:30-ku" and "PM-ku" are removed from the task.

**`KaiTime` daily words**
- "Every morning / evening / night" and "ovvoru kaalaiyum" now count as **DAILY**. "Every morning 8 medicine" used to become a one-time reminder.

**Bare "4-ku" (`spokenHour`)**
- Read as 4 o'clock **only** in a reminder or change sentence ("4-ku school pickup irukku, remind me"). Nothing else changes.

**`KaiCommands.notNames`**
- Errand, place and thing words (service, gym, car, medicine, current, documents, walking, college, temple, …) and "iruk" are never taken as a person's name. "car service-ku" used to make a person called "Service"; "irukku" used to make "Iruk".

**"Time maathu" / "neram maathu" / "5 manikku time change pannu"**
- When nothing else is said, this changes the reminder just talked about: `Update(Last)`.
- "Eppo-ku maathanum Owner?" → "5 mani" → **"Seri Owner, Innaikku 5:00 PM-ku maathitten — “…”"**.
- It is the same reminder (same id). It is never a duplicate.

### Asking only for what is missing

| Missing | Kai asks |
|---|---|
| Time | "Seri Owner. Eppa remind pannanum? (eg: …)" |
| Task | "Seri Owner. Enna nyabagam paduthanum?" |
| Exact time within a part of the day ("tomorrow morning gym") | "Morning-la exact time sollunga Owner." (this already existed) |

### Confirmation first

- Confirm / Edit / Cancel. This is unchanged: only Confirm schedules.

### Reminder kind (`KaiReminderKind`, new)

- **BUSINESS / PERSONAL / CALL / PAYMENT / TASK / FOLLOW_UP / GENERIC.**
- It is **worked out from the reminder each time, never stored**, so there is no schema or Gson risk.
- It is used only for Kai's wording and voice.
- **`personal()`** keeps business and personal apart:
  - a customer or supplier from the books, stock work or a collection → business;
  - family, home, health, school or errands, or a phone contact's call → personal.

### Minimal data and privacy

- A personal reminder stays a reminder only. A test checks that `prepare` / `confirm` on the books are **never** called.
- It is never turned into a customer, supplier, transaction, stock entry or payment entry.
- "Current bill" stays a reminder, with **no amount invented**.
- The reminder engine already scopes reminders by owner and business (`KaiReminderScope.visible`); a test covers this.
- Reminders do not feed Kai's shared memory. No code path from reminders to the learner was added.

### Recurring

- Uses the existing `Recurrence`: "Daily 7 walking", "Every morning 8 medicine", "Daily night 10 gate check", "Every Sunday car clean" (the time is asked), "Weekdays 8:30 office".

### Before vs after (the real understanding output)

| Owner said | Before (main) | After |
|---|---|---|
| Paiyana 4 manikku school-la irundhu kootitu vara nyabagam paduthu | task "Paiyana school- irundhu kootitu vara" | "Paiyana school-la irundhu kootitu vara", 4 PM |
| 4 PM-ku son-a school-la irundhu pickup pannanum, remind pannu | "ku son-a school- irundhu pickup pannanum" | "son-a school-la irundhu pickup pannanum", 4 PM |
| 4-ku school pickup irukku, remind me | time **not understood** (asked) | "school pickup", 4 PM |
| 4 mani aagumbodhu paiyana kootitu vara solli nyabagam paduthu | "aagumbodhu paiyana kootitu vara solli" | "paiyana kootitu vara", 4 PM |
| Sunday 10 manikku car service-ku kondu poganum | person = **"Service"** | no person, 11 Oct 10 AM |
| Every morning 8 manikku medicine | **ONCE** | **DAILY** 8 AM |
| Weekdays 8:30-ku office-ku kelambanum | task "8:30-ku office-ku kelambanum" | "office-ku kelambanum", Mon–Fri 8:30 |
| Time maathu → 5 mani | "konjam clear-ah sollunga" (failed) | the same reminder moved to 5 PM, no duplicate |

## 5. Files changed

**Main code**
- `brain/tools/KaiReminders.kt`: spokenHour, cleanTask, "Time maathu"
- `brain/tools/KaiTime.kt`: every-morning daily, strip "8:30-ku" / "PM-ku"
- `brain/tools/KaiCommands.kt`: notNames
- `brain/tools/KaiReminderKind.kt` (**new**): the kind, and personal vs business
- `brain/tools/KaiUrgentVoiceScript.kt`: VoiceLine parts / gaps, natural wording per kind, attempt escalation, Tamil word list, prefetch per sentence
- `brain/chat/KaiReminderAssistant.kt`: the missing-time / missing-task questions, "maathitten", "5-ku" in short replies
- `data/tts/WavJoin.kt` (**new**): joins the clips, trims the padding, adds exact gaps
- `data/tts/NaturalTtsSpeaker.kt`: `speakTurn`
- `ui/reminder/KaiUrgentVoice.kt`: `KaiVoiceOut.sayTurn`; one turn = one utterance
- `ui/reminder/NaturalVoiceOut.kt`: `sayTurn` → `speakTurn`

**Tests**
- **New:**
  - `KaiPersonalReminderTest` (18)
  - `KaiReminderNaturalVoiceTest` (8)
  - `WavJoinTest` (4)
  - `KaiUrgentVoiceTurnTest` (5)
- **Updated (expected wording only; no test removed):**
  - `KaiUrgentVoiceScriptTest`: new lines, 5 lines instead of 6; the Latin-word check now covers every attempt 1–5 and every sentence.
  - `KaiFinalFixesTest`, `KaiReminderConfirmTest`, `KaiProductionFixesTest`, `KaiMorningWorkIntentTest`: "Seri Owner. Eppa remind pannanum?" and "Seri Owner. Enna nyabagam paduthanum?".
  - `KaiAgentTest`: an update reply now starts with "Seri Owner,".

**Not changed**
- AlarmManager and exact alarms.
- `KaiReminderEngine`, `KaiReminderFlow` (attempts, retry, MAX_ATTEMPTS 5, snooze, done, EXHAUSTED).
- The notifications and full-screen intent.
- `KaiReminderActivity` and the urgent screen.
- The `KaiReminder` data class.
- `AppContainer`.

## 6. Test results (JVM harness, real repo code)

| | Result |
|---|---|
| Baseline on this branch before the changes | 662 / 662 pass |
| After | **697 / 697 pass** (662 existing + 35 new), 0 fail |
| Android type-check of `NaturalTtsSpeaker`, `NaturalVoiceOut`, `KaiUrgentVoice`, `WavJoin` (android-all) | compiles, 0 errors |
| `./gradlew :app:testDebugUnitTest` (Robolectric suites) | **BLOCKED here** (no Android SDK). Run on the PC. |

### Spec tests 1–29

| # | Test | Where | Result |
|---|---|---|---|
| 1 | Son pickup + NL variations | `sonSchoolPickup`, `naturalVariationsOfThePickup` | PASS |
| 2 | Parent call | `parentCall` | PASS |
| 3 | Medicine | `medicine` | PASS |
| 4 | Gym | `gymTomorrowMorningAsksOnlyTheTime` | PASS |
| 5 | Current bill | `currentBillIsAReminderNotAPayment` | PASS |
| 6 | Office task | `officeTaskAndCarService` | PASS |
| 7 | Generic | `genericAndWifeCall` | PASS |
| 8 | Daily | `dailyReminders` | PASS |
| 9 | Weekly | `weeklyAndWeekdays` | PASS |
| 10 | Missing time | `missingTimeIsAsked` | PASS |
| 11 | Missing task | `missingTaskIsAsked` | PASS |
| 12 | Confirmation | `confirmationAndCancel` | PASS |
| 13 | Cancel | `confirmationAndCancel` | PASS |
| 14 | Edit time | `editTimeBeforeConfirm` | PASS |
| 15 | "Time maathu" | `timeMaathuMovesTheSameReminder` | PASS |
| 16 | Duplicate prevention | `duplicateIsPrevented`, `timeMaathu…` | PASS |
| 17 | Owner isolation | `ownerIsolation`, `businessAndPersonalStaySeparate` | PASS |
| 18 | Call voice | `callVoice` | PASS |
| 19 | Payment voice | `paymentVoice` | PASS |
| 20 | Personal voice | `personalVoice` | PASS |
| 21 | Generic voice | `genericVoice` | PASS |
| 22 | First attempt | `firstAttemptTurnShape` | PASS |
| 23 | Repeated attempt | `repeatedAttemptsEscalate` | PASS |
| 24 | One utterance per turn | `openingIsOneUtteranceWithShortGaps` | PASS |
| 25 | No duplicate TTS | `noDuplicateTurn` | PASS |
| 26 | Interruption | `callDoneSnoozeStopTheTurnAtOnce` | PASS |
| 27 | Done / Call / Snooze stop speech | `callDoneSnoozeStopTheTurnAtOnce` | PASS |
| 28 | No queued audio | `nothingQueuedPlaysAfterAnAnswer` | PASS |
| 29 | Measured gaps | `WavJoinTest.turnIsOneClipWithTheAskedPauses` (+ the fallback is one utterance: `defaultSayTurnIsOneUtterance`) | PASS |

## 7. Real device test

**Status: BLOCKED.** This cloud session cannot build Android or run an emulator or phone.

The steps are in `DEVICE_TEST.md` (tests A, B and C).

## 8. Limitations (honest)

- **Never heard on a phone.**
  - The joined clip, the measured gaps and the escalation were verified with synthetic WAVs and fakes only.
  - How natural Sarvam sounds across a join (each sentence is still synthesized on its own, so the intonation of each sentence is its own) must be judged by ear. If it sounds choppy, the fallback is easy: one request for the whole turn (already the fallback path).
- **Assumed format:** WavJoin assumes all of Sarvam's clips have the same sample rate (16-bit PCM). If they don't, it falls back to one request.
- **Owner's own words in the Tamil voice:** they are spoken through a word list. A Tanglish word that is not on the list stays in Latin letters, and Sarvam may read it in an English way.
- **"7 manikku" with no morning/evening** is still taken as 7 AM. That is the timing engine's existing rule (7–11 is morning), left unchanged as the spec asked. The owner sees "7:00 AM" on the confirmation card and can use Edit.
- **"Open pannalama?" for payments** is the spec's wording. The urgent screen itself is unchanged (Call / Done / Snooze); there is no new "Open" button.
- **"Personal" is worked out from words, not stored.** An unusual personal task with none of the known words is GENERIC. That changes only the wording, never the scheduling.

## 9. Second pass: natural spoken Tamil (after the Pixel 8 live run)

**Live result (b752dc8, Pixel 8):** 4 / 4 PASS. The voice engine was Sarvam.

| Test | Join gaps | Before-fix silence | Breaks inside turn | Stop after button |
|---|---|---|---|---|
| A | [390, 920] ms | 6867 ms | 0 | 682 ms |
| B | [390, 930] ms | 6742 ms | 0 | 310 ms |
| C | [390, 920] ms | 6870 ms | 0 | 448 ms |

- Nothing played after any stop.
- The script's "BUG (crash)" was the emulator's `android.hardware.uwb-service`, not the app. **Fixed:** the script now counts only the app's own crashes.

**The owner's feedback:** "Kai pesum pothu natural Tamil maari illa." Four causes in the code, all fixed:

| # | Why it didn't sound like spoken Tamil | Fix |
|---|---|---|
| 1 | Each short sentence ("கால் பண்ணுங்க.") was synthesized **alone**, so each sounded like a separate announcement. | The statement's sentences are now **one request**, so the rhythm and intonation run on like speech. Then a ~0.9 s beat, then the question. |
| 2 | Names stayed in Latin letters, glued to a Tamil ending ("Kumar-க்கு"). | Names are written in Tamil letters with the spoken ending: **குமாருக்கு**, **பிரபாக்கு**, **ஆபீஸுக்கு**, **பையனை**. `KaiTamilVoice` uses plain rules plus a small pronunciation list for common names. |
| 3 | Digits are read formally ("4 மணி" is read "நான்கு மணி"). | Times are said the way people say them: **நாலு மணி**, **எட்டரை மணி**, **ஒம்பதே கால்**, **அஞ்சு நிமிஷம்**. |
| 4 | Bookish or repeated wording ("…-னு நினைவூட்டல்", "call pannunga … call pannalama"). | Spoken forms: "**கூட்டிட்டு வரணும்**", "**ஜிம் போகணும்**", "**பண்ணிடுங்க**", "**இப்போ பண்ணலாமா?**" |

**Now heard (Tamil script, exactly what is sent to the voice):**

- **Kumar call:** "ஓனர்... குமாருக்கு கால் பண்ண வேண்டிய நேரம் ஆச்சு. கால் பண்ணிடுங்க." · "இப்போ பண்ணலாமா?"
- **Son's pickup:** "ஓனர்... நாலு மணி ஆச்சு. பையனை ஸ்கூல்ல இருந்து கூட்டிட்டு வரணும்." · "கிளம்பலாமா?"
- **Office:** "ஓனர்... எட்டரை மணி ஆச்சு. ஆபீஸுக்கு கிளம்பணும்." · "கிளம்பலாமா?"
- **Payment:** "ஓனர்... குமாருக்கு பேமெண்ட் பண்ண வேண்டிய நேரம் ஆச்சு. ஒரு தடவை செக் பண்ணிடுங்க." · "ஓபன் பண்ணலாமா?"

**Tests**
- JVM: **698 / 698 pass**. One new test, `spokenTamilIsColloquial`; the voice expectations were updated.
- The live test now checks:
  - the beat before the question (700–1200 ms);
  - the voice's own breaths inside the statement (none over 1.2 s).

**Still to judge by ear:**
- names that are not on the pronunciation list (the rules guess long vowels: "Gokul" → கோகுல்);
- Sarvam's own pause after "ஓனர்...".
