# Kai — Phase 2: context continuation, confirmation, and date resolution

Branch: `feature/kai-context-followup-hardening`, created from `feature/kai-context-payment-date-fix` at 1c48922.
Status: reviewed, then committed and fast-forward merged to main and master at the owner's request (no force-push).

> The save-persistence fix (Spec B) is in the same commit. See
> `artifacts/kai-payment-save-persistence-fix/REPORT.md`. Spec A and Spec B share one mechanism:
> stated payment → existing draft → Confirm.

## What was broken (reproduced before any code change)

The first column is what the owner said; the second is what Kai replied before this fix.

| Said | Kai before the fix |
|---|---|
| "Mahesh enaku 2000 tharanum" | "…collect pannanum-nu **note pannikiren**…" — this claims a note was made, but nothing was saved |
| → "save panniko" / "note panniko" / "add pannitiya?" | "Owner, konjam clear-ah sollunga." |
| → "save panniko" → "seri" | "Seri Owner 👍" — no draft was made and nothing was saved |
| "ama next month" in a clean flow | Worked, but only on the very next turn. One "saptiya?" in between lost it. |

## The fix (reuses KaiAgent, KaiTime, KaiConversationSemantics, the existing draft/confirm path, and KaiBusinessBrain)

**New state, all in the existing `KaiConversationState`:**
- `stated` holds the stated payment: person, amount, direction, and due date.
- `statedDraftKey` is the draft made from it.
- `lastSaved` is set only when the engine returns `ActionOutcome.Done`.
- `draftShownTurn` records the turn in which the draft was shown.

**Routing order in `ask()`:**
1. Open draft: amount correction → cancel → confirmation word → "pannitiya?" → a date said for the draft.
2. "pannitiya?" when no draft is open.
3. Pending date or month clarification.
4. Save words.
5. "venam" before any draft.
6. Business, action, calculator, casual (unchanged).

**Save words** go to the **existing** `prepared()` draft card and are written only on Confirm. They are: note panniko, note pannu, save pannu, save panniko, add panniko, add pannu, record pannu, record panniko, kanakku la podu, kanakkula podu, account la podu, account-la podu, serthu vidu, and the Tamil-script forms. A receivable becomes `CREDIT_GIVEN` (the customer owes the owner). A payable becomes `DEBIT_TAKEN` (the owner owes the supplier).

**Confirmation words:** ama, aama, yes, seri, correct, ok, okay, seri add pannu, save pannu, and the rest.
- A bare "ama" confirms a draft only on the turn right after the draft was shown. If Kai asked something else in between (for example, a question about a word), that question gets the "ama".
- Save words confirm the open draft only when they stand alone ("Kumar 500 add pannu" and "Colgate stock add pannu" are not confirmations).
- A draft the engine blocked is never confirmed by voice.

**"add pannitiya?" / "pannitiya?" / "save aagiducha?":**
- Draft open → "Innum save pannala Owner. Confirm pannunga, save pannidren."
- Payment stated but no draft → the same reply, plus the Confirm card.
- Saved (only after the engine's Done) → "ஆம் Owner, add pannitten. Mahesh — ₹2,000 (ref)."
- Nothing stated → "Owner, innum edhuvum save pannala."

**Month answer:**
- "ama next month", "yes next month", "next month", "indha month": works right after the question, and with the month word up to 4 turns later (for example, after "saptiya?").
- A bare "ama" to "indha maasam 5-aa, illa adutha maasam 5-aa?" gets the two choices again, short. It never gets "clear-ah sollunga".

**Explicit dates** (July 6, july 6, 6 July, July 6th, next July 6) come from KaiTime and resolve to the next upcoming July 6 (2027 when today is 8 Oct 2026). The payment context is kept. A date said while the draft is open goes onto the draft.

**Names are kept as said:** "Kumaran" is never matched to "Kumar".
- On save, Kai asks: "Owner, Kumaran-nu separate customer-aa? Kumar-a?"
- The buttons are: Kumaran — puthu customer / Kumar · balance / Cancel.
- The same applies to Sivakumar and Siva.

**Business query** ("Mahesh enaku evlo tharanum?" and its variations) uses the existing Business Brain balance answer, unchanged:
- Found → "Kumar ungalukku ₹3,000 tharanum owner." This uses the Brain's existing wording, which has the same facts as "Kumar kitta irundhu ₹X வாங்கணும்".
- Not found → "Owner, Mahesh-nu customer record enakku kidaikala."

**Wording:** "note pannikiren" became "purinjudhu", because nothing is saved at that point.

## Tests

- Baseline: **737 / 737** pass (JVM harness).
- After: **799 / 799** pass. That is 737 + 36 (`KaiContextFollowupTest`) + 26 (`KaiPaymentSavePersistenceTest`).
- No test was deleted or weakened.

`KaiContextFollowupTest` (36) covers:
- ama / yes / plain next month
- indha month
- bare ama
- all day-only forms
- all day+month forms
- small talk between
- 5 July forms
- July after the month question
- a payable keeping its direction
- the July year being relative to today
- 13 save phrases, each making a draft and never saving
- statement plus save in one line
- payable → Debit
- 11 confirm words
- a bare yes with no draft saving nothing
- a late "ama" not confirming an old draft
- an amount or person inside save words
- stock words not being taken as a payment save
- 4 "pannitiya" states
- no save claim while unsaved
- 9 business-query variations
- an unknown person
- Kumaran vs Kumar (ask, new, existing)
- Sivakumar vs Siva
- an exact name
- small talk → eppa
- calculator → "avan eppa tharuvaan?"
- the date going onto the draft
- never "clear-ah"
- save with nothing stated
- the semantics unit checks

## Pixel 8 — BLOCKED here (no Android build or emulator in this cloud environment)

Owner steps (Kai Chat, typed or voice):
- **A.** "Mahesh enaku 5000 tharanum" → "5" → "ama next month" → expect "…adutha maasam 5-m thethi vaanganum."
- **B.** "Mahesh enaku 5000 tharanum" → "July 6" → expect "…July 6th 2027 vaanganum."
- **C.** "Mahesh enaku 2000 tharanum" → "add pannitiya?" → expect "Innum save pannala Owner…" plus the Confirm card.
- **D.** → "seri" → expect "Save aagiduchu Owner…". Then "add pannitiya?" → expect "ஆம் Owner, add pannitten…"
- **E.** "Kumaran enaku 3000 tharanum" → "save panniko" (with only Kumar in Customers) → expect the separate-customer question.
- **F.** "Kumaran enaku 3000 tharanum" → "saptiya?" → "eppa?" → expect the due-date question for Kumaran ₹3,000.
