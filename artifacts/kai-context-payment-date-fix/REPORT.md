# Kai: context, payment direction, date follow-up and business query hardening

## Branch and base

- **Branch:** `feature/kai-context-payment-date-fix`
- **Base commit:** `2b43440` (the validated Kai natural-voice work on `feature/kai-reminder-natural-voice`). That work is **not in main**, so the branch starts from it and nothing is lost.
- **Git:** no commit, no push, no merge. `main` and `master` were not touched (both still at `e391ea4`).
- **Stop rule:** no BLOCKED condition was hit:
  - no KaiAgent rewrite;
  - no schema or accounting-model change;
  - no new engine and no LLM;
  - the existing `KaiConversationState`, `KaiTime`, `KaiBusinessBrain`, `KaiCommands` and reminder engine were reused.

## Root causes, from probing the real code

| Problem | Cause |
|---|---|
| **A/B: direction** | `contextualReceivable` decided the direction from one word. It checked for `enakku` (Latin only), then treated any `tharanum` as "owner pays". So the following came out as PAYABLE: Tamil `குமார் எனக்கு 3000 தரணும்` (voice input is Tamil script), `Kumar 3000 tharanum`, and `Kumar enna 3000 tharanum`. `kudukanum`, `vanganu` and `pay pannanum` were not recognised at all, so those sentences fell through to a balance answer. |
| **Wrong person** | `knownPerson` matched the name as a prefix, so "Kumaran" became the "Kumar" in the books. After that was fixed, "enaku" was read as a person called "Ena". |
| **C: "10"** | The due-date follow-up only accepted what `KaiTime` resolves to a day. A bare "10" fell through to "Konjam clear-ah sollunga". "10th" was silently taken as this month. |
| **D: "indha month 10"** | `KaiTime` had no "this month + day" form. |
| **E: "Innaikku yaar payment tharanum?"** | The reminder understanding treated "payment" + "innaikku" as a reminder. Its question guard only covered evlo / enna / what, not yaar / yaarukku / who. |
| **H: who-questions** | "yaarukku … pannanum", "yarukku cash kudukanum", "yar kitta cash vanganu", "yaroda due" did not map to payables or receivables. "Yaroda" was read as a customer name. |
| **F/G: local discovery** | No intent existed. "pakkathula hardware kadai" fell to the business summary because of "kadai"; "supermarket enga irukku" fell to "clear-ah sollunga". |

## What changed

- **`KaiPaymentDirection`** (new, `brain/tools`): the single place that decides direction, from grammar.
  - A taking verb (vaanganum / collect / varanum) means **RECEIVABLE**. Exception: "en kitta vaanganum" (the person takes from the owner) is **PAYABLE**.
  - A giving verb (tharanum / kudukanum / pay pannanum / kattanum / owe), checked in this order:
    1. owner is the receiver (enakku / எனக்கு / to me) → **RECEIVABLE**
    2. owner is the subject (naan / நான் / I) → **PAYABLE**
    3. the person takes the dative ending (Kumar-ku / குமாருக்கு) → **PAYABLE**
    4. otherwise (the person is the giver) → **RECEIVABLE**
  - Handles Tamil script, the spoken mix ("குமார்-ku"), and misspellings (enaku, enna+amount, kudukanum, vanganu, 3k).
- **`KaiAgent`**: `contextualReceivable` now uses the model.
  - Questions ("eppo", "evlo", "?") are no longer captured as statements.
  - A missing amount is asked for ("Kumar-ku evlo kudukkanum?").
  - Name fallback: "Kumaran" stays "Kumaran".
- **Due-date follow-up** (same `KaiConversationState`; new fields `pendingDay`, `pendingMonthOffset`, `pendingLang`, `pendingAskedTurn`, and pending question `AMOUNT`):
  - "10" / "10th" / "10 தேதி" / "10-ம் தேதி" → "Owner, indha maasam 10-aa, illa adutha maasam 10-aa?" (Tamil: "இந்த மாதம் 10-ஆ Owner, அடுத்த மாதம் 10-ஆ?"). Kai never guesses the month.
  - "next month" / "adutha maasam" / "indha maasam" then completes the date. Month first, then day, also works.
  - "next month 10", "indha month 10", "naalaiku" and "Friday-ku" go through `KaiTime`.
  - "amount 5000" corrects the amount and keeps the rest of the context.
  - Reply: "Seri Owner, Kumaran kitta irundhu ₹3,000 adutha maasam 10-m thethi vaanganum."
  - A short answer keeps the language the payment was stated in.
  - Safety: a bare "10", "next month" or "5000" counts as an answer only on the turn right after Kai asked. Later it may answer something else, such as a stock count. A full date always works.
- **`KaiTime`**: "this / indha / intha month (maasam) N" and "இந்த மாதம் N".
- **`KaiReminders`**: who-questions (yaar, yaarukku, yaroda, who, யார்) are questions, not reminders. "Kumar payment remind pannu" is still a reminder.
- **`KaiChatUnderstanding`** and **`KaiBusinessBrain`**:
  - "to whom" (yaarukku / யாருக்கு) plus payment means **payables**, filtered to the period said ("innaikku").
  - "yaar kitta … vanganu" and "yaroda due / pending" mean **receivables**.
  - Answers come only from the records; with no data the answer is "record illa".
- **`KaiLocalDiscovery`** (new): a place word plus near / where gives an honest reply:
  - "Owner, pakkathula irukka hardware kadai thedi kudukkura vasathi Kai-kku innum illa. Google Maps-la "hardware shop near me"-nu thedunga. Naan kadai per edhuvum guess panni solla maatten."
  - The app has **no** nearby-search or location-search feature (only the shop's own location in the profile). So Kai does not ask for location permission, which would mislead, and never invents a shop.
  - Questions about stock, bills, keys or something "kadai-la" (inside your own shop) are excluded.
- **`KaiUnderstanding.knownPerson`**: the whole name, or the name plus a case ending ("Kumar-ku", "Kumarkitta", "Kumara"). Never the start of a longer name.
- **`KaiCommands.notNames`**: ena, enak, enna, yaar, yar…

## Existing test changed (not weakened)

In `KaiAgentTest.pendingDueDateAcceptsNaturalCalendarAnswersUsingKaiTime`, the cases "10th", "10th date" and "10 தேதி" expected this month's date to be guessed silently. The spec now says to ask. These three cases moved to a new, stricter test, `dayWithoutMonthAsksWhichMonthThenResolves`:

- it asserts that Kai asks;
- that no date is set yet;
- that the payment is still pending;
- and that "next month" / "indha month" then resolves the right date and keeps the direction.

No test was deleted.

## Tests

| | Pass | Fail |
|---|---|---|
| Baseline at `2b43440` (JVM harness, real repo code) | 698 | 0 |
| Final | **737** | 0 |
| New tests | 39 (`KaiContextPaymentDateTest` 32, `KaiPaymentDirectionTest` 6, `KaiAgentTest` +1) | 0 |

The new tests cover:
- direction: A, B, every receivable and payable variation, Tamil, typos;
- the spec's TEST 1–12;
- eppa / 10 / next month 10 / indha month 10;
- day then month, month then day;
- the payable path;
- a Tamil answer staying Tamil;
- naalaiku and Friday;
- amount correction and a missing amount;
- a calculator question in the middle;
- a later bare "10" not taken as the day;
- business who-questions and their variations;
- no records → no invented names or amounts;
- action vs question;
- local discovery, and what is *not* local discovery;
- casual chat, GST, and the reminder still working;
- **nothing drafted, posted or scheduled** by any of these conversations.

The Robolectric / Android suites (`./gradlew :app:testDebugUnitTest`) are **BLOCKED here**: there is no Android SDK. Run them on the PC.

## Pixel 8

**BLOCKED.** There is no emulator in this cloud session and the branch is not pushed (stop for review). Steps are below.

| Check | JVM | Pixel 8 |
|---|---|---|
| Payment direction | PASS | BLOCKED |
| Date follow-up | PASS | BLOCKED |
| Month clarification | PASS | BLOCKED |
| Business payment query | PASS | BLOCKED |
| Local discovery | PASS | BLOCKED |
| Tamil / Tanglish | PASS | BLOCKED |
| Existing regression | PASS | BLOCKED |

**Manual device steps (Kai Chat, after the branch is pushed):**

| Test | Say | Expect |
|---|---|---|
| A | "Kumaran enaku 3000 tharanum" | "…Kumaran kitta ₹3,000 collect…" |
| | "10" | "indha maasam 10-aa, illa adutha maasam 10-aa?" |
| | "next month 10" | "Seri Owner, Kumaran kitta irundhu ₹3,000 adutha maasam 10-m thethi vaanganum." |
| B | "nan kumaran ku 3000 tharanum" | "…pay…" |
| | "10" | the month question |
| C | "Innaikku yaar payment tharanum?" | the names and amounts due today from your records (or "record illa"), with **no** reminder prompt |
| D | "pakkathula hardware kadai irukka?" | the honest "vasathi innum illa" reply, no ₹ amounts |
| E | saptiya / saptia / saptya / un name enna / nee yaaru | casual replies |
| F | "25000 la 18% GST evlo?" | GST ₹4,500, Total ₹29,500 |
| G | "Kumar-ku 10 minutes kalichi call pannanum" | "…reminder set pannalama?" |

## Limitations

- **Payment context is session-only, as before.** "Seri Owner … vaanganum" is not written to the ledger, and no reminder is created. Turning it into a credit entry or a due reminder would be a separate, confirm-first feature.
- **Local discovery cannot list shops.** That needs a maps / places integration that does not exist in the app. Adding one is a product decision (permissions, API keys).
- **A name the books don't know** is taken from the start of the sentence only when it is in Latin letters ("Kumaran"). A Tamil-script unknown name is not guessed.
- **"Kumar 3000 tharanum" is read as Kumar owes the owner.** This is the spec's rule: the person is the subject. Spoken "naan Kumar-ku…" or "Kumar-ku…" gives PAYABLE.
