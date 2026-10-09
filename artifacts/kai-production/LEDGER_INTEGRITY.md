# Ledger integrity

The chain Kai must never break:

```
owner words → meaning → direction → CONFIRMED entity → draft (nothing written) → Confirm → books engine
→ books.changed() (cache dropped) → readBack() → "Save aagiduchu … Ippo balance ₹X" → every later answer reads the books
```

## Rules enforced in code

| Rule | Where | Verified by |
|---|---|---|
| A draft never writes | `KaiTools.prepare` only builds an `ActionPlan`; `confirm` is the only write | matrix `payment-language` (100 rows assert `saved.isEmpty()` before Confirm), `topic-switch`, `negative` |
| Write only to a CONFIRMED record | every write path resolves via `KaiAgent.resolveParty` → `KaiEntityResolver` (One = CONFIRMED); AMBIGUOUS / UNKNOWN are asked | `KaiOwnerReferenceTest.bare_name_write_*`, matrix `entity` (bare writes save nothing), journey 6 |
| Direction from grammar, not the verb alone | `KaiPaymentDirection` (owes) + `KaiCommands.payment` (paid / received) — now with the English payer-first rule ("Kumar paid 500" = in) | matrix `payment-direction` (54), `payment-language` (100), `mixed-stt` |
| Existing + new entries aggregate | balances are the books' sum of open entries minus payments (`LegacyBridge.summaries`); Kai never keeps its own balance | matrix `persistence` (credit +a → books = old + a, read back in the reply and in a later question), journeys 1, 2, 4 |
| Payments reduce balances | payment in / out → engine payment against open documents | matrix `persistence` (payment rows), journeys 4, 6, 8 |
| "Saved" only after read-back | `KaiAgent.confirm` → `books.changed()` → `readBack(plan, outcome)`; failure → "anuppitten, aanaa check panna mudiyala" | matrix `persistence` (engine failure rows: no save claim), existing `KaiPaymentSavePersistenceTest` |
| Restart keeps data | answers read the books, not the conversation | matrix `persistence` (restart rows), journeys 1, 2, 4, `KaiOwnerReferenceTest.reference_survives_a_restart…` |
| Read questions never write | questions go to the Brain; no `prepare` on that path | `KaiOwnerReferenceTest.read_questions_never_write`, matrix `calculator` (no draft), journey 5 |
| A minus amount is never a payment | `KaiCommands.payment`, add-entry path | matrix `negative` ("Kumar -500 kuduthaan" saved 500 before this branch) |
| One identity per same-named record | owner references pin the canonical id; bare short name asked; "rendu Kumar" lists each record; total only when asked | `KaiOwnerReferenceTest` (18 tests) |

## Bugs found by the matrix and fixed at the root (this branch)

| Found | Root cause | Fix |
|---|---|---|
| "Kumar paid 500" / "Kumar 500 rs paid today" drafted **Credit** to Kumar (owner gave money) | `paid` is in the owner-paid list; no subject check | payer-first rule in `KaiCommands.payment` (the person named before paid/gave/sent is the payer, unless "I/naan", "to X" or "X-ku") |
| "Kumar -500 kuduthaan" saved ₹500 | `amountsIn` drops the sign | a minus amount is not a payment amount — Kai asks "Evlo amount?" |
| "Lokesh-ku 500 add pannu" answered the balance, wrote nothing | add-request only handled with no amount | add-entry request → a draft on the side of the record (customer credit / supplier debit) |
| Owner reference ignored; every "Kumar House" write asked "endha Kumar?" | `KaiPrivateMemory.apply` reported every saved alias as used | report an alias only after it was actually replaced in the words |

## Owner questions: who pays, when, and on time or late (`fix/kai-ledger-questions`)

`KaiLedgerHabitTest` (9 tests, JVM) — the owner's own words, against a ledger with real payment history:

| Said | Before | Now |
|---|---|---|
| "Yeppa tharanum" (after "Innaiku yar payment tharanum") | "Yeppa kitta evlo vaanganum?" — `Yeppa` read as a person | the same list with each person's due date |
| "Nan yaruku payment tharanum yeppa tharanum" | "Yeppa-ku evlo kudukkanum?" | payables with dates (ABC Traders ₹10,000 — innaikku, Murugan Stores ₹4,000 — 15 Oct) |
| "Innaiku yaruku payment tharanum" | "Innai kitta evlo vaanganum?" | payables due today only |
| "Avanga correct date la … late ah payment pannuvangala" | the overdue list (someone else) | habit of the people just listed: Kumar 2 of 2 late (avg 8 days), Ramesh on time |
| "General ah yarlam late ah payment pannuvanga" | the overdue list | late payers from history (Selvam, Kumar) and on-time payers (Ramesh, Lakshmi); no-history parties are counted, not judged |
| "Kumar … correct date pannuvara illa late ah payment pannuvara" | Kumar's balance | "Kumar usually late-aa dhaan tharuvaanga: 2 thadava-um … (10, 6 naal late; sarasari 8 naal)" |
| "next friday tharuvaan" to an open credit draft | "Friday kitta evlo vaanganum?" — nothing saved | the draft gets that due date; Confirm saves it with the due date |
| "naalaiku 10 maniku call remind pannu" | reminder for "Maniku" | no invented name |

How the habit is counted (`KaiBusinessBrain.habitOf`, read from `KaiBooks.history`): a settled entry with a due date is on time when
its last payment came on or before that date, late otherwise (days late = last payment − due date); an open entry whose due date has
passed is late now. Entries without a due date say nothing. No history → Kai says it can't tell.

## What is NOT verified here

The Collect / Pay / Transactions / Dashboard **screens** read the same `PartyRepository` summaries the fake books model, but the
screens themselves, `AppKaiTools.confirm` against the real Room/legacy DB and `RepositoryKaiBooks` are Android code that this cloud
environment cannot compile or run. **Pixel 8 = BLOCKED.**

## One shop, a whole day of owner lines (`fix/kai-owner-sweep`)

`KaiOwnerSweepTest` plays **193 owner lines** — 60 money entries, 21 reminders, 38 stock lines, 62 questions, 12 short conversations
(correct the amount, cancel, "save pannitiya?", follow-ups, topic switch) — each on a fresh shop, checked in the books / inventory /
reminders, never on Kai's words. Nothing may be written before Confirm; a question must write nothing.

Found and fixed at the root in this round:

| Said | Before | Now |
|---|---|---|
| "Kumar gpay la 1500 anuppinan", "Kumar 500 kuduthaaru", "Selvam anna 2000 kuduthar" | Kumar's balance | payment in (any spelling of anuppu / kudu / thaa, 3rd person past) |
| "Kumar 500 gpay", "ABC Traders 2000 cash" (no verb) | balance | payment by the record: customer → in, supplier → out; a new name is asked |
| "Kumar 1000 credit", "Ravi ku 2500 kadan", "Kumar 1000 ku saamaan credit la eduthutu ponaan" | balance / "idhu enna?" | entry on that record (customer credit, supplier debit); "credit" said for a new name is drafted directly |
| "Muthu 2000 tharanum 15th", "Ravi enakku 2000 tharanum naalaiku" | due date dropped | due date 15 Oct / tomorrow on the draft |
| "Lux 12 vandhuchu" (Lux Soap in stock), "bhaniyan 20" (Baniyan) | a second, new product | that product — only when the name is said right before its quantity and exactly one product fits |
| "Arisi stock evlo", "அரிசி 2 மூட்டை வந்தது" (Rice in stock) | "Arisi illa" / new product | Rice (common Tamil grocery names ↔ English) |
| "Colgate 10 vithuchu", "கோல்கேட் 10 வித்துச்சு" | not understood | stock out 10 |
| "Rice 25 kg moota 2 vandhuchu" | 25 kg + 2 bags = 75 kg | 2 bags = 50 kg |
| "Endha product kammiya irukku" | "product-um illa" | low stock list |
| "Naalaiku yaar tharanum" | "Naalaiku-nu customer illa" | tomorrow's dues |
| "Kumar last ah eppo kuduthan" | due date | last payment (6 Oct) |
| "Yaar yaar late ah tharanga" | overdue list only | late payers from history |
| "Kumar phone number" | balance | the number on the record (or "records-la illa") |

Kept on purpose (Kai asks, never guesses): "Lakshmi akka 500 kuduthanga" asks Lakshmi herself or her sister; "Colgate 2 petti" asks
box or pieces (petti stays an owner-taught word); "Maggi 2 box" asks pieces per box when the product has no box size.
