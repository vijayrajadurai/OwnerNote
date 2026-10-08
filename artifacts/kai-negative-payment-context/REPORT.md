# Kai — negative / adversarial payment context testing

- **Branch:** `feature/kai-negative-payment-context`
- **Base:** 861f17a (main = master)
- **Status:** reviewed, then committed and fast-forward merged to main and master at the owner's request (no force-push).

## The phone case

Kumar owes ₹2,000, due 30 Sep. The owner asked "avar yenakku evlo tharanum".

| | Reply |
|---|---|
| Before | read as the owner paying Kumar, and the ₹2,000 answer also carried "September 30 due — date thaandiduchu" |
| Now | **"Kumar ungalukku ₹2,000 tharanum owner."** No due sentence; the due date is given only when it is asked ("eppa due?") |

## Root causes

1. **"yenakku" was not read as "to me".**
   - `KaiPaymentDirection.ownerReceives` knew enakku / enaku / ennaku, but not the yenakku / yenaku / yennaku spellings.
   - "yenakku" then matched the person-dative rule ("yenak" + "-ku"), so the sentence became PAYABLE.
   - `KaiCommands.personIn` and `KaiUnderstanding.personIn` also took "Yenak" as a person's name. When Kumar was not in the books, Kai answered "Yenak-nu customer record kidaikala".
2. **Business Brain questions had no side.**
   - "na avarukku evlo tharanum" (what the owner owes Kumar) was answered with Kumar's receivable ₹2,000, i.e. the reverse.
   - `ChatQuery` now carries `side`. The Brain answers only from that side of the books, or says "Owner, Kumar-ku neenga kudukkanum-nu pending amount illa."
   - The side is taken only from explicit words (enakku / naan / Kumar-ku / en kitta). This uses the new `KaiPaymentDirection.explicitOf`.
   - "Ramesh enna tharanum?" names no side, so the Brain answers from whichever side Ramesh is on.
3. **The balance answer always added the due sentence.**
   - Removed from `balance()`. The amount question gets the amount; "eppa / due eppa / entha date-la" gets the date.
   - "entha date-la" / "thethi" / "தேதி" now count as due-date questions. Before, they were answered only because the balance reply happened to contain the date.
4. **"due eppa?" after a side query asked "yaar pathi?"** The Brain did not keep the person in focus when that side had nothing pending. Now it does. A bare "due eppa?" / "eppa due?" is also read as "<last person> due eppa?".
5. **The unsaved statement was lost or reversed on a query.**
   - `withUnsaved` filtered nothing by side. `recordedPending` matched Kumar's customer id even when the payable side was asked.
   - When Kumar was not in the books, `withUnsaved` could not find "Kumar" in the question at all.
   - Now:
     - the stated person is recognised even without a books record;
     - only the asked side is used;
     - if the records have nothing on that side, the owner's own unsaved amount leads: "Owner, Kumar ungalukku ₹2,000 tharanum-nu neenga sonneenga — innum save aagala. Records-la Kumar pending illa / record kidaikala."
6. **One stated slot.** "Kumar enakku 3000" then "na Kumar-ku 3000" dropped the first statement.
   - `statedOtherSide` now keeps the earlier, opposite-side statement for the same person, so each side answers its own amount.
   - Neither statement is saved without Confirm. Save words still save only the current statement.
7. **"September 30-ku evlo?"** is now answered from the records' due dates ("Owner, September 30th due: Kumar ₹2,000 tharanum."). If nothing falls due that day, Kai asks who the owner means. It never invents an amount and never creates an entry.

## The 20 cases (JVM harness, in-memory books)

| # | Case | Result |
|---|---|---|
| R | avar yenakku evlo tharanum → Kumar ₹2,000, no due | PASS |
| 1 | na avarukku evlo tharanum → "Kumar-ku neenga kudukkanum-nu pending amount illa." | PASS |
| 2 | statement, then both sides → opposite answers | PASS |
| 3 | saptiya / seri in between → still Kumar | PASS |
| 4 | Colgate in between → Kumar, not the product | PASS |
| 5 | amount without due; "eppa due?" → Sept 30; "evlo?" → ₹2,000 | PASS |
| 6 | a passed due date ≠ paid / clear | PASS |
| 7 | a pronoun with no context → "yaar pathi kekkureenga?" | PASS |
| 8 | Kumar + Selvam → "Kumar-aa Selvam-aa Owner?" | PASS |
| 9 | Kumaran ≠ Kumar | PASS |
| 10 | receivable + payable statements, each side alone | PASS (after fix 6) |
| 11 | four direction sentences | PASS |
| 12 | queries create no draft / entry | PASS |
| 13 | "due venam" changes only the draft's date; never clears the account | PASS |
| 14 | avan / avar after a statement → Kumar; with no context → asks | PASS |
| 15 | Tamil script | PASS |
| 16 | mixed ("2k", enaku) | PASS |
| 17 | "September 30-ku evlo?" | PASS |
| 18 | unknown Ravikumar → not found, never Kumar's | PASS |
| 19 | save, then both sides, then restart | PASS |
| 20 | 12-line stress sequence | PASS |

The statement cases run on two kinds of books:
- **Kumar ₹0** — `Kumar.NONE`
- **Kumar not in the books** — `Kumar.ABSENT`

The phone / query cases run on books where Kumar owes ₹2,000, due 30 Sep 2026 (`Kumar.BOOKS`).

## Follow-up fixes (owner asked: "Itha fix pannitiya" → "Pannu")

### 1. A due date that has just passed — which year?

**Before:** "September 30" said on 8 Oct 2026 was silently taken as 30 Sep 2027 (KaiTime picks the next upcoming day).

**Now:** when the day passed this year within the last month and no year was said, Kai asks:

> "Owner, September 30th 2026 already thaandiduchu. Andha date-aa (2026), illa adutha varusham September 30th 2027-aa?"

- "2026" / "andha date" / "indha varusham" → 30 Sep 2026.
- "2027" / "adutha varusham" / "next year" → 30 Sep 2027.
- "ama" / "seri" picks neither, so Kai asks again.
- Nothing is set until the owner answers. "due eppa?" before an answer never invents a date.
- It applies both to the answer to "Due date eppa?" and to a date in the statement itself ("Kumar enakku 2000 tharanum September 30").
- **Not asked when:**
  - a year is said ("September 30 2026");
  - the day is far back ("January 5" → the coming 5 Jan 2027);
  - the day is ahead ("December 25").
- The amount is never read as the year ("Kumar enakku 2026 tharanum September 30").
- **Why the window is one month:** an existing test (`KaiAgentTest`, 25 Nov → "October 10" = 10 Oct 2027) keeps the automatic roll-over for a day 46 days back. 30 Sep on 8 Oct (8 days back) is asked.
- **Limit:** "…September 30 save panniko" in one sentence (with Kumar not in the books) still goes straight to the draft card. The card shows the year ("September 30th 2027"), and nothing is saved without Confirm.

### 2. The same amount already in the books — same or new?

**Before:** the books had Kumar ₹2,000 and the owner said "Kumar enakku 2000 tharanum" + save. That always made a new ₹2,000 draft (₹2,000 → ₹4,000).

**Now:** before any draft card, Kai asks:

> "Owner, records-la already Kumar ₹2,000 tharanum-nu irukku. Adhey ₹2,000-aa, illa pudhu ₹2,000-aa?"

- "pudhu" / "pudhusu" / "new" / "innoru" → the draft card. Confirm still saves.
- "adhey" / "same" / "already" / "pazhaya" → "Seri Owner, pudhusa edhuvum add pannala. Records-la Kumar ₹2,000 apdiye irukku." Nothing is drafted or saved.
- "ama" / "seri" → asked again.
- **Covered paths:**
  - "save pannu"
  - "due venam"
  - save words in the same sentence
  - the payable side, in its own words ("Ramesh-ku ₹2,500 kudukkanum-nu irukku")
- **Not asked when:**
  - the amount differs from the books (₹3,000 → "₹2,000 → ₹5,000");
  - the owner already said it is new ("already 2000 … ippa oru 2000"). This sentence with two equal amounts used to be read as one amount; it is fixed in `existingAndNew`.

### 3. "Kumar paid ah?" in English

**Root cause:** `KaiLanguage.detect` did not count the Tamil question ending "ah?" / "-aa?". "paid" then made the chat English.

**Now:** "Kumar paid ah?" and "Kumar clear-aa?" are Tanglish; "Has Kumar paid?" stays English.

The test books now carry Kumar's ledger history:
- "Illa owner, Kumar idhuvarai edhuvum kudukkala. Pending ₹2,000."
- with ₹500 paid: what was paid and what is still pending.

A passed due date is never answered as paid or clear.

## Notes

- **The books already show Kumar ₹2,000 and the owner says "pudhu".** That is a new ₹2,000. The card shows "Balance: ₹2,000 → ₹4,000", and nothing is written until Confirm.

## Tests

| | Count |
|---|---|
| New | `KaiNegativePaymentContextTest`: 41 tests — the phone case, cases 1–20, n20b, the regressions, y1–y6 (year), d1–d6 (same or new), p1–p2 (paid ah) |
| Full suite | **1079 / 1079 pass** (1038 before + 41 new) |

No test was deleted, and no assertion was removed or weakened.

**Fixed in the code, not in the tests:**
- `s05_saveWithoutConfirmIsNotSaved`
- `paymentDueInEveryLanguageFromTheRecords`
- `testG_anotherPersonsQuestionSwitchesThePerson`
- `pendingDateUsesCalendarRolloverAndDoesNotInventFromTimeOrUnknownText` (handled by the one-month window)

**Changed because of fix 2.** Four tests repeat the exact amount their books already hold (Kumar ₹3,000). Kai now asks "adhey-aa, pudhusaa?", so each conversation gets one extra owner reply, "pudhusu". Every original assertion is unchanged (₹6,000, 3500, the due date, the said-back date). `s17` also gained an assertion on the new question.
- `KaiChatChecklistTest.s17_mixedTanglish`
- `KaiHumanUnderstandingTest.o01_editThenSave`
- `KaiHumanUnderstandingTest.q01_businessBrainAfterSave`
- `KaiOwnerUnderstandingTest.afterSavingTheSavedDateIsSaidBack`

## Pixel 8

**BLOCKED.** This cloud environment cannot build Android or run a device. To check on the phone, in Kai Chat with Kumar ₹2,000 due 30 Sep:
1. "avar yenakku evlo tharanum" (after mentioning Kumar)
2. "na avarukku evlo tharanum"
3. "due eppa?"
4. the stress sequence from case 20

## Files

- `app/src/main/java/com/shopai/app/brain/KaiLanguage.kt`: "ah?" / "-aa?" make a sentence Tanglish
- `app/src/main/java/com/shopai/app/brain/tools/KaiPaymentDirection.kt`: yenakku spellings; `explicitOf`
- `app/src/main/java/com/shopai/app/brain/tools/KaiCommands.kt`: "yenakku" is never a name
- `app/src/main/java/com/shopai/app/brain/KaiUnderstanding.kt`: na / naa / nan / enaku / yenakku are never names
- `app/src/main/java/com/shopai/app/brain/chat/KaiChatUnderstanding.kt`: `ChatQuery.side`; "date-la" / "thethi" are due questions
- `app/src/main/java/com/shopai/app/brain/chat/KaiBusinessBrain.kt`: side-filtered person answers, `noneOnThatSide`, no due sentence in a balance answer
- `app/src/main/java/com/shopai/app/brain/chat/KaiAgent.kt`:
  - side-aware `withUnsaved`
  - bare "evlo?" / "due eppa?"
  - `dueOnDay`
  - `statedOtherSide`
  - the year question (`justPassed`, `askWhichYear`, `yearPicked`)
  - the same-or-new question (`askIfDuplicate`, `duplicateAnswer`)
  - `existingAndNew` with two equal amounts
- `app/src/test/java/com/shopai/app/brain/chat/KaiChatChecklistTest.kt`, `KaiHumanUnderstandingTest.kt`, `KaiOwnerUnderstandingTest.kt`: one "pudhusu" reply each (see Tests)
- `app/src/test/java/com/shopai/app/brain/chat/KaiNegativePaymentContextTest.kt` (new)
