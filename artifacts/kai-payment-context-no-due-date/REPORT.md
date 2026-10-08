# Kai — an existing balance plus a new payment, and "due venam"

- **Branch:** `feature/kai-payment-context-no-due-date`
- **Base:** 0d8c5da (main = master)
- **Status:** reviewed, then committed and fast-forward merged to main and master at the owner's request (no force-push).

## Root cause — Issue 1 ("already 3000 … ippa oru 2000")

**Two amounts in one sentence.** `contextualReceivable` takes a statement's amount only when the sentence has exactly one. With 3000 and 2000 there was no amount at all, so Kai asked "evlo vaanganum?". Nothing in the pipeline knew that the first amount was already owed and the second was new.

**A wrong direction in one variant.** In "Selvam kitta 3000 pending irukku, innum 2000 tharanum", `KaiPaymentDirection` read the "-kku" in "irukku" as a person's dative ("to someone"). That made the sentence PAYABLE.

**Why the phone showed a Confirm card.** On the phone the sentence produced a Confirm card instead of an amount question. This could not be reproduced here. Either way, the root cause above means the amount on that card could not be trusted.

## Root cause — Issue 2 ("due venam" → "account clear-ah irukku")

"due venam" was never read as the answer to Kai's "Due date eppa?":
- The pending-question handler only accepts a date, a day, a month, or an amount.
- So the message fell through to the Business Brain. The word "due" made the Brain treat it as a due / balance question about Selvam, and it answered from the books ("account clear-ah irukku. Pending illa").
- The payment being discussed was left hanging.

## Fixes (inside the existing pipeline)

No new parser, engine, or brain was added.

**"Already owed" plus "new" amount.** `existingAndNew` / `newOnTopOfExisting` detect the pattern:
- exactly two amounts;
- a "new" word between them (ippa / ippo / innum / innoru / oru / pudhusa / another …).

What happens then:
- The second amount becomes the stated payment, through the same path: `remember` → `identify` (entity resolution) → `draftStated` → `prepared` → Confirm → `createCredit` / `createDebit`.
- The first amount is only checked against the books, and is never edited.
- If the books disagree with what the owner said, Kai says so: "Records-la Selvam pending ₹0 dhaan irukku (neenga sonna ₹3,000 illa)".
- The draft card adds "Pudhu entry: ₹2,000 (pazhaya ₹3,000 maaraadhu)" and "Balance: ₹3,000 → ₹5,000". In the real app this is `plan.balanceBefore` from `AppKaiTools.prepare`.
- A due date in the same sentence goes onto the new entry.
- A question ("…2000 tharanum-aa?") stays a question.

**Direction fix.** `KaiPaymentDirection`: "irukku" / "irukkudhu" are no longer taken as a person's dative.

**"due venam" and its variants.** The phrases are: due venam / vendaam, due date venam / vendaam, date venam / vendaam, due illa, no due date (`KaiConversationSemantics.dropsDueDate`). Each of them keeps the stated payment and only clears its due date, then shows the same draft card:
- "Seri Owner 👍 Due date illa. Selvam kitta ₹5,000 collect panna vendiyadhu. Save pannava?"
- "ama" saves ₹5,000 with no due date, still pending.
- With the draft already open, the draft is re-prepared without its date.

These phrases never cancel, clear, mark paid, or create a ₹0 entry. "venam" on its own still cancels.

## Tests

`KaiPaymentContextNoDueDateTest`: 21 new tests. The fake books keep one row per entry, so "existing entry untouched" and "two distinct records" are checked directly.

| Group | What is checked |
|---|---|
| A1–A10 | existing + new amount: draft, separate entry, ₹5,000 total, restart, 5 variants, no duplicate, books disagree, payable, date in the sentence, question with two numbers |
| B1–B4 | due venam → draft kept, saved with no due date, balance ₹5,000, restart, older balance |
| C1–C2 | every no-due-date phrase; semantics checks |
| D1–D5 | negative cases: no clear / cancel / paid / delete / ₹0 entry; plain "venam" still cancels; the balance question is still a lookup; no save claim before Confirm |

**Full suite:** 1038 / 1038 pass (1017 before + 21 new). No test was deleted or weakened.

## Pixel 8

**BLOCKED.** This cloud environment cannot build Android or run a device. The phone steps (TEST 1, TEST 2) are in the request. They are still needed for:
- the Collect screen total
- "Due date: none" on the entry
- restart on a real device

## Files

- `app/src/main/java/com/shopai/app/brain/chat/KaiAgent.kt` (+125)
- `app/src/main/java/com/shopai/app/brain/tools/KaiPaymentDirection.kt` (+1)
- `app/src/test/java/com/shopai/app/brain/chat/KaiPaymentContextNoDueDateTest.kt` (new)
