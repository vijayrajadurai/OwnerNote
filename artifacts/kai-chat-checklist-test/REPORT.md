# Kai Chat — Complete Test Checklist (run in code)

- **Branch:** `feature/kai-chat-checklist-test`
- **Base:** 2288cfe (main = master)
- **Status:** reviewed, then committed and fast-forward merged to main and master at the owner's request (no force-push).

## How it was run

Every checklist line was sent, in order, to the same `KaiAgent` that the Kai Chat screen uses for both typed and mic input. Each section was one conversation.

The books were an in-memory store:
- Kai's tools write to it only on Confirm.
- The Business Brain reads from it.
- "Restart" means a new Kai over the same books and the same owner memory.

**What the test data contained:**
- Kumar: ₹3,000, due 20 Oct
- Ramesh: ₹2,500
- Three suppliers named Lokesh: Chennai, Nagapattinam (Sri Vinayaga Hardware), Trichy
- Colgate: 20 pcs, reorder level 25

**Limits:**
- Results are from the JVM harness, not from a phone. **Pixel 8: BLOCKED** — this cloud environment cannot build Android or run a device.
- **Section 16:** the Tamil lines in the PDF did not extract (they came out as "IIIIII"). Equivalent Tamil sentences were used:
  - குமார் எனக்கு 3000 தரணும்
  - அவன் எப்போ தருவான்?
  - அடுத்த மாதம் 10
  - நான் ரமேஷுக்கு 500 தரணும்

## Results

| # | Section | First run | After fixes |
|---|---|---|---|
| 1 | Casual / identity (12 lines) | PASS | PASS |
| 2 | Payment direction (5) | PASS | PASS |
| 3 | Context / date | PASS | PASS |
| 4 | Date + save ("add pannitiya?" → Confirm card, not saved) | PASS | PASS |
| 5 | Save + persistence | **PARTIAL** | PASS |
| 6 | Casual interruption | PASS | PASS |
| 7 | Calculator interruption (₹1,625) | PASS | PASS |
| 8 | Stock / product | PASS | PASS |
| 9 | Topic switch + return | PASS | PASS |
| 10 | Duplicate name → ask → "Nagapattinam" | PASS | PASS |
| 11 | Business-name disambiguation | PASS | PASS |
| 12 | Business payment queries | PASS | PASS |
| 13 | Reminder (call in 10 min → ama → time change → 5 mani) | PASS (5:00 PM) | PASS |
| 14 | Owner memory ("potti na box" → used) | **FAIL** | PASS |
| 15 | Memory correction ("illa, potti na packet") | **FAIL** | PASS |
| 16 | Tamil script | PASS | PASS |
| 17 | Mixed Tanglish ("…3000 tharanum, next month 10-ku") | **FAIL** | PASS |
| 18 | Ambiguous reference → "Kumar-aa Ramesh-aa Owner?" | PASS | PASS |
| 19 | Unknown input (no crash, no action, useful question) | PASS | PASS |
| 20 | Final stress (14 lines) | PASS | PASS |

## Root causes and fixes

**Section 5 — the unsaved draft was not mentioned.**
- "save panniko" shows the Confirm card, which is correct: nothing is saved without Confirm.
- But "Mahesh enaku evlo tharanum?" then only said "record kidaikala". It never said the ₹2,000 draft was still waiting.
- Fix (`withUnsaved`): the records' answer plus "Neenga sonna Mahesh ₹2,000 innum save aagala — Confirm pannunga."
- After Confirm, ₹2,000 is in the books and survives a restart.

**Section 14 — the taught word was used before it was saved.**
- The conversation was "potti na box" (Kai asks "Save pannava?") → "Colgate 2 potti vandhudhu".
- Kai asked the box / packet question all over again.
- Fix (`KaiMemoryAssistant.answer`): Kai now asks to save `potti` = box first and keeps the sentence. After "aama" the word is saved and the sentence is read again: 2 boxes = 24 pieces stock-in draft, which still needs Confirm.

**Section 15 — "illa, …" broke the teaching parser.**
- `KaiPersonalTeaching` did not strip the "illa," in front of a teaching sentence.
- Fix: it is now treated like "Kai, …" / "Owner, …".
- Also, "potti" said alone after it was taught now explains its meaning ("`potti`-na `packet`"). Before, the stored meaning replaced the word and Kai asked what "packet" means.

**Section 17 — the date's day was read as a second amount.**
- In "3000 tharanum, next month 10-ku", the 10 was taken as an amount. Kai saw two amounts and asked "evlo?".
- Fix: the date phrase is taken out before the amount is read, and parsed on its own.
- Result: ₹3,000, due adutha maasam 10, and the draft carries both.

**Section 13** passed in the app's code. The first probe failed only because the test's fake tools were missing `updateReminder`. The test fixture now has it.

## Tests

| | Count |
|---|---|
| Before | 995 / 995 |
| After | 1017 / 1017 |
| New | 22 (`KaiChatChecklistTest`: one per section, plus 5b, 15b and memory variants) |

No test was deleted or weakened.

## Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/shopai/app/brain/chat/KaiAgent.kt` | date phrase separated from the amount; `withUnsaved` |
| `app/src/main/java/com/shopai/app/brain/chat/KaiMemoryAssistant.kt` | taught word used before Save; a lone taught word explained |
| `app/src/main/java/com/shopai/app/brain/memory/KaiPersonalTeaching.kt` | "illa," lead word |
| `app/src/test/java/com/shopai/app/brain/chat/KaiChatChecklistTest.kt` | new |

## Still to check on the phone (Pixel 8)

- **Saved data on the Collect / Pay screens:** it is in the books in this test, but the screens themselves were not opened.
- **Voice:** Kai Chat's mic goes through the same `KaiAgent` path. Pesunga still sends payments and business questions to the old voice-entry parser. That is a separate change, not made here.
