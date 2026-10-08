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

## What is NOT verified here

The Collect / Pay / Transactions / Dashboard **screens** read the same `PartyRepository` summaries the fake books model, but the
screens themselves, `AppKaiTools.confirm` against the real Room/legacy DB and `RepositoryKaiBooks` are Android code that this cloud
environment cannot compile or run. **Pixel 8 = BLOCKED.**
