# Kai — answers from the complete business ledger

- **Branch:** `feature/kai-complete-ledger-query`
- **Base:** 105765c (main = master)
- **Status:** committed and fast-forward merged to main and master at the owner's request (no force-push). Pixel 8 not yet tested.

## 1. Root cause: Lokesh showed ₹2,000 instead of ₹2,500

The save worked and the books aggregate correctly. Kai read a stale copy of the books.

1. **The save was correct.** The Confirm card showed ₹2,000 → ₹2,500 because the books engine reported that after posting.
2. **The balance is computed correctly.** `LegacyBridge.summaries` adds up every open document of a party: ₹2,000 + ₹500 = ₹2,500.
3. **The snapshot is cached.** Kai reads balances through `RepositoryKaiBooks.snapshot()` → `KaiBrain.memory()`, which keeps the snapshot for **60 seconds**.
4. **Other screens refresh it; Kai Chat did not.** Credit, Payment, Bill and the other screens call `kaiBrain.forget()` after they save. The Kai Chat confirm path (`KaiAgent.confirm` → `AppKaiTools.confirm`) never did.
5. **Result.** For up to a minute after a Kai save, "lokesh enakku evlo tharanum" was answered from the pre-save copy (₹2,000). Party histories had the same 60-second cache.

## 2. Root cause: "12 பேருடைய details சொல்லு" got "Yaar pathi?"

- The today / overdue answers named only the first three people.
- They kept no result: neither the list, nor whose list it was, nor any structured rows.
- "details sollu" therefore went down the single-person path ("details" = a person's history). With no single person in context, it asked who.
- "avanga" was also rewritten to the last single person before the Brain saw it.

## 3. The ledger query architecture used

There is no new engine, brain, database or parser. The existing pipeline is used:

> words → `KaiChatUnderstanding` (one semantic class per question type) → entity resolution (`KaiEntityResolver`) → `KaiBusinessBrain` over `KaiBooks` (the ledger) → plain-code aggregation → a structured result → Tamil / Tanglish / English text → the result kept for follow-ups

### Fresh reads after a save
- `KaiBooks.changed()` is new, with a default no-op.
- After every engine **Done**, `KaiAgent.confirm` calls it. `RepositoryKaiBooks` implements it as `forget()`.
- `KaiBrain.forget()` now bumps a `version`. Cached party histories go stale the moment *any* screen saves, not after a minute.

### Read-back before "saved"
After Done, Kai re-reads the books and checks two things:
- the party is there, on the entry's side;
- its balance equals the balance the engine reported.

**Only then** does Kai say "Save aagiduchu". Otherwise it says: "entry anuppitten (TXN), aanaa books-la thirumba padichu check panna mudiyala — Collect / Pay screen-la paarunga, thirumba save pannaadheenga". It never says "saved", and it never drafts the same thing again.

### Structured list result (`LedgerList`)
- Every list answer (today, period, overdue, all pending, no due date, highest / lowest, payments in a period) is kept in the Brain as:
  - kind
  - side
  - period
  - rows (party, amount, date)
- `KaiAgent` asks `brain.listFollowUp()` first, before the pronoun rewrite.
- A follow-up is answered from the same people, **re-read from the ledger now**:
  - "details sollu"
  - "avanga yaar yaar?"
  - "avanga total evlo?"
  - "due date-um sollu"
  - "12 பேருடைய details"
  - "ellaaroda details"
- **Recency rules:**
  - A stock, calculator or small-talk turn in between keeps the list.
  - A person question in between makes a bare "details sollu" about that person; "ellaaroda details" still means the list.
  - A new scope ("overdue customers details", "ellaa payable list") is asked fresh, not read off the old list.

### Query classes
These are semantic classes, not phrase lists:
- the side of a list (payable / receivable)
- overdue = "overdue / thaandi" or "due / date" + a passed-verb
- today + overdue together, as two separate blocks
- all pending
- pending with no due date, read from the entries (the summary dates an entry with no due date by its bill date)
- highest / lowest pending
- payments made in a past period (last N days, last week, last month, yesterday), from the entries' payments
- the period's total for "innaikku collect / pay panna vendiya total"
- the supplier side for "naan … kuduthen / vanginen"

### Person history
- History now lists each entry (date, amount, paid) and each payment.
- Supplier wording is right: "neenga kuduthadhu", "vaangirukkeenga".
- Paid, total billed and outstanding are kept apart.

### Entity resolution in questions
- Before a Brain question, `KaiAgent` runs the existing `KaiEntityResolver` over the same-named records. It uses place, shop, phone, or the record already being talked about.
- It passes the Brain the record it resolved to, plus a label for each same-named record.
- "Lokesh enakku evlo tharanum?" → "1. Chennai Lokesh — ₹2,000; 2. Nagapattinam Lokesh — ₹700".
- "Nagapattinam" or "rendavadhu" → that record. A wrong one is never picked silently.
- List lines name each duplicate the same way.

### Payment and confirm wording
- "Kumar 2000 kuduthutaan" (and similar past-tense forms) now goes to the existing payment-in draft. Before, it was answered as a balance question.
- "confirm" said to a stated payment shows its card first. The card's Confirm still does the save.

### Deterministic money
List totals are summed in `BigDecimal` (₹0.10 + ₹0.20 + ₹1,000.05 = ₹1,000.35). The order is deterministic: due date, then amount, then name.

## 4. Existing engines reused

- `KaiAgent`
- `KaiChatUnderstanding`
- `KaiBusinessBrain`
- `KaiUnderstanding`
- `KaiPaymentDirection`
- `KaiCommands`
- `KaiEntityResolver`
- `KaiBooks` / `RepositoryKaiBooks`, with `KaiBrain` (the ledger snapshot) and `PartyRepository` (entries and payments)
- `KaiTools` / `AppKaiTools`: `TransactionRepository.createCredit` / `createDebit` and the engine's payment-in / payment-out. This is the same save path as the Collect / Pay screens.
- `KaiReminderUnderstanding`

Kai Chat has no accounting store of its own.

## 5. Files changed

| File | Change |
|---|---|
| `brain/chat/KaiBusinessBrain.kt` | `KaiBooks.changed()`; `LedgerList` and `listFollowUp`; list answers; `pendingList`; `paymentsInPeriod`; history lines; supplier wording; `hint` / `answerChoice`; labels |
| `brain/chat/KaiAgent.kt` | `books.changed()` + `readBack()` before "saved"; follow-up / choice hook; `hintSameNames`; "confirm" as save words |
| `brain/chat/KaiChatUnderstanding.kt` | `PENDING_LIST`, `RECEIVED_PAYMENTS`, `ListScope`, `fullList`, `withOverdue`; "last N days" / "last week"; overdue / passed words; side of lists; "pay me" |
| `brain/KaiMemory.kt` | `pendingOn` public, with a name tie-break |
| `brain/KaiBrain.kt` | `version` bumped on `forget()` |
| `brain/chat/RepositoryKaiBooks.kt` | `changed()`; history cache checks the ledger version |
| `brain/tools/KaiCommands.kt` | past-tense "he gave" words → payment in |
| `brain/tools/KaiReminders.kt` | "list / due / overdue … sollu" is a report, not a reminder |
| `test/.../KaiContextFollowupTest.kt` | the fake books now show confirmed entries (see below) |
| `test/.../KaiCompleteLedgerQueryTest.kt` | new |

All paths are under `app/src/main/java/com/shopai/app/` or `app/src/test/java/com/shopai/app/brain/chat/`.

## 6. New tests

`KaiCompleteLedgerQueryTest`: **68 tests.**

**Ledger used:** entry-level, with payments. It is the same aggregation as the app's party summaries.

**Groups:**
- A — person balance: existing, new entries, payments, payable, paid / total / outstanding, history
- `cache01–02` — the Lokesh root cause on a caching book
- B — draft vs saved: draft, confirm once, cancel, edit, "confirm" → card
- P — persistence:
  - books don't show the entry → no save claim
  - a failed save
  - restart
  - due date saved, and no due date saved
  - payable list
  - a new party
- C — today
- D — overdue: days, "Due date illa", payables, today and overdue kept separate
- E — follow-ups: Tamil "12 பேருடைய", "avanga", total, due dates, re-read after a payment, new scope, no list
- F — entity resolution: labels, place, ordinal, place in the question, context
- G — topic switch: stock, calculator, casual, person in between
- H–L — Tamil, Tanglish, English, mixed, STT typos
- M — the query classes
- N — reads never write; a list is not a reminder; exact paisa

**Mutation check:** with `books.changed()` removed, `cache01` and `cache02` fail. Kai answers the stale ₹3,000, and the read-back refuses to claim the save.

**One existing test fixture changed** (no assertion changed). `KaiContextFollowupTest`'s fake books never showed confirmed entries. With read-back, Kai now (correctly) refuses to say "saved" for an entry the books don't show. The fake now lists the entries its tools confirmed, as the real ledger does.

## 7. Full suite

**1147 / 1147 pass** (1079 before + 68 new). No test was deleted, and no assertion was weakened.

`KaiBrain.kt` and `RepositoryKaiBooks.kt` are Android-side. They are not compiled by the JVM harness, so they were reviewed by hand. The change is small: a `version` counter and a cache key.

## 8. Pixel 8

**BLOCKED.** This cloud environment cannot build Android or run a device. Device tests A–C and 1–5 from the request are still to be done on the phone:

- **A.** Lokesh ₹2,000 → "Lokesh-ku 500 add pannu" → Confirm → "Lokesh enakku evlo tharanum?" (expected ₹2,500, at once). Check the Collect screen and Transactions, then restart.
- **B / C.** Kumar ₹5,000 confirm and cancel.
- **1–5.** Today summary → "details sollu"; overdue → "avanga details sollu"; a topic switch; Kumar's balance and payment history.

## 9. Remaining limitations

1. **"Today" and "overdue" use each party's earliest open date**, as the Collect / Home screens do. A party with one entry due today and one overdue appears as overdue, with its full balance.
2. **An entry with no due date counts by its bill date** (the app's existing rule). Kai shows it as "Due date illa" and never invents a date.
3. **Reading entries costs one read per party.** Payments in a period, no-due-date lists and row labels read each party's entries (cached until the next save). Row labels stop after 40 rows.
4. **`AppKaiTools.confirm` returns no balance when its own read of the new document fails.** The read-back then checks only that the party is on the right side.
5. **Several statements in one turn are not supported.** "Lokesh-ku 500 add pannu" with two Lokeshes still asks which one (the existing `identify`).
6. **Collect / Pay screens are not checked by a test.** They read the database when they open. Here they are represented by the same snapshot; there is no UI test.
