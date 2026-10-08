# Kai — Critical fix: a payment save must persist

Branch: `feature/kai-payment-save-persistence-fix`, created from `feature/kai-context-followup-hardening`. Both start from 1c48922.
Status: reviewed, then committed and fast-forward merged to main and master at the owner's request (no force-push).

## Save pipeline trace (done before changing any code)

The path a message takes: input → `KaiChatSession.send` → `KaiAgent.ask` → intent → direction → draft → confirm → `KaiTools` → repository / DB → Business Brain → screens.

| Point | Before the fix | After the fix |
|---|---|---|
| **A. Draft creation** | "Mahesh enaku 2000 tharanum" went to `contextualReceivable`. That function only stores session state and replied "note pannikiren". **No draft was ever created.** | Save words → `draftStated` → the existing `prepared()` → `tools.prepare` (writes nothing for Credit/Debit). |
| **B. Confirm detection** | Only the Confirm button. "seri" or "save panniko" reached small talk or the generic "clear-ah sollunga". | Button, or a confirmation word / save words read against the open draft (see the followup report). |
| **C. Save call** | Never reached for stated payments. | `confirm(key)` → `tools.confirm(plan)`. The plan is removed from `plans` first, so it can only be confirmed once. |
| **D. Repository function** | — | `AppKaiTools.confirm` → `TransactionRepository.createCredit` / `createDebit`. These are the canonical paths also used by the Credit/Debit screens. |
| **E. Record created** | — | `LegacyBridge.createCredit` → `engine.postSale` (receivable). `createDebit` → `engine.postPurchase` (payable). The party is resolved by id, or created by name. |
| **F. ownerId / businessId** | — | The books session (`s.ctx.businessId`) and the existing `BooksModule` session. Unchanged. |
| **G. Direction stored** | — | Receivable → `CREDIT_GIVEN` → customer Sale document. Payable → `DEBIT_TAKEN` → supplier Purchase document. |
| **H. Due date stored** | `ActionPlan` had no due date, so it was always dropped. | `ActionPlan.dueDate` (new, defaults to null) → `CreateCreditInput.dueDate` / `CreateDebitInput.dueDate` (fields that already existed) → `SaleInput.dueDate` / `PurchaseInput.dueDate`. |
| **I. Save result checked** | — | Only `ActionOutcome.Done` sets `lastSaved` and produces "Save aagiduchu…". `Failed` produces "Owner, save aagala. Naan amount-a save pannala." |
| **J. UI reload** | — | Customers, Home, and Collections read the DB when they open (`LaunchedEffect`). After a voice confirm, the old Confirm card is now closed (`KaiChatSession.closeSpentDrafts`). |

## ROOT CAUSE

The stated payment was **session context only**:
- `contextualReceivable` + `askDueDate` / `dueDateResolved` never called `prepare` or `confirm`.
- The reply still said "note pannikiren". To the owner, that read as "saved".
- "save panniko" had no route, so it fell through to the generic "konjam clear-ah sollunga".
- The due date had nowhere to go: `ActionPlan` had no `dueDate` field.

## FIX

These changes reuse the existing canonical write path. There is no new repository, engine, table, DB, or LLM.

1. **Save words → the existing draft card.** "Mahesh — ₹2,000 / Credit — avar ungalukku tharanum / Due date / Puthu customer-ah add aagum / Confirm · Edit · Cancel". Nothing is written before Confirm.
2. **Confirm (button or "seri" / "ama" / "save pannu") → `tools.confirm`.** `createCredit` / `createDebit` run through the books engine.
3. **The due date is carried** on `ActionPlan.dueDate` into `CreateCreditInput` / `CreateDebitInput`.
4. **Idempotency:**
   - The plan is removed before the engine call, so a second Confirm (button after voice) returns null.
   - After a save, `stated` is cleared. "save panniko" again → "Owner, adhu already save aagiduchu … Thirumba add pannala."
   - A new statement discards the old stated draft.
5. **Cancel:** "venam" or the Cancel button on the draft → `discard`, nothing written. "venam" before any draft → the stated payment is dropped.
6. **Edit:** "3000" while the draft is open → the draft is redrafted with the same person, direction, and due date. Only the final amount is saved.
7. **Failure:**
   - "Owner, save aagala. Naan amount-a save pannala. (reason)"
   - If the books are unreachable, no draft is made.
   - "add pannitiya?" never says "pannitten" unless the engine returned Done.

## Results

Unit results are from the JVM harness with an in-memory books store that Kai's tools write and the Business Brain reads. They are not from a device.

| Check | Result | Notes |
|---|---|---|
| DATABASE | PASS (unit) | One entry, ₹2,000, customer side (receivable) |
| BUSINESS BRAIN | PASS (unit) | "Mahesh enaku evlo tharanum?" → "Mahesh ungalukku ₹2,000 tharanum owner." |
| COLLECTION | PASS (unit) | The snapshot that Customers and Collections read has Mahesh, ₹2,000, due 5 Nov |
| HOME | PASS (unit) | The total to collect includes the new ₹2,000 |
| APP RESTART | PASS (unit) | A new `KaiAgent` over the same books still answers ₹2,000 |
| DUPLICATE | PASS | Repeated "save panniko", or Confirm after a voice confirm → 1 entry |
| CANCEL | PASS | "venam", the Cancel button, or "venam" before a draft → 0 entries |
| EDIT | PASS | 2000 → 3000 (→ 3500) → only the final amount is saved |
| DATE | PASS | Nov 5 / July 6 2027 / "next month 10" said while the draft is open are all saved on the entry |
| PIXEL 8 | **BLOCKED** | This cloud environment cannot build Android or run a device |

`LegacyBridge.createCredit(dueDate=…)` itself is covered by the existing Robolectric `BooksIntegrationTest`. That test does not run in this harness. The string that `AppKaiTools` now sends (`LocalDate.toString()`) is verified to parse back to the same day with `parseIsoToLocalDate`.

**Pixel 8 steps** (owner, Kai Chat):
- **19.** "Mahesh enaku 2000 tharanum" → "save panniko" → check the card → "seri" → expect "Save aagiduchu Owner. Mahesh — ₹2,000 (S-…)".
- **20.** Open Customers → Mahesh ₹2,000. Open Home / Collections → the ₹2,000 to collect is shown. Ask Kai "Mahesh enaku evlo tharanum?" → ₹2,000.
- **21.** Force-stop the app and reopen it. Mahesh ₹2,000 is still in Customers. "save panniko" again → no second entry.

## Unit tests

- Before: **737** pass.
- After: **799** pass, 0 failures.
- New: **62**. That is 36 in `KaiContextFollowupTest` and 26 in `KaiPaymentSavePersistenceTest`.

## Files

- `app/src/main/java/com/shopai/app/brain/chat/KaiAgent.kt`: state, routing, stated → draft → confirm, saved answers.
- `app/src/main/java/com/shopai/app/brain/tools/KaiTools.kt`: `ActionPlan.dueDate` (default null).
- `app/src/main/java/com/shopai/app/data/kai/AppKaiTools.kt`: passes `dueDate` to `createCredit` / `createDebit`.
- `app/src/main/java/com/shopai/app/ui/kaichat/KaiChatSession.kt`: closes a draft card that was already confirmed or cancelled by voice.
- `app/src/test/java/com/shopai/app/brain/chat/KaiContextFollowupTest.kt` (new)
- `app/src/test/java/com/shopai/app/brain/chat/KaiPaymentSavePersistenceTest.kt` (new)

`AppKaiTools.kt` is Android-only and was not compiled here. Its change is two named arguments of existing `String?` fields. `KaiChatSession.kt` is compiled by the harness.

## Diff

```
 app/.../brain/chat/KaiAgent.kt          | ~311 +++++++++++++++++-
 app/.../brain/tools/KaiTools.kt         |    2 +
 app/.../data/kai/AppKaiTools.kt         |    6 +-
 app/.../ui/kaichat/KaiChatSession.kt    |   10 +
 + 2 new test files
```
