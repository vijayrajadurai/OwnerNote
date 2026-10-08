# Data flow (inspected)

## 1–6. Message → meaning
1. Text enters `KaiChatSession.send` (chat text/mic) or `VoiceEntryScreen.parseInput` (Pesunga).
2. `KaiAgent.ask`: language → open-draft handling (correction / due venam / cancel / confirm / date) → saved? → entity choice →
   same-or-new → due-date answers → save words → owner memory (`KaiMemoryAssistant.apply`) → `KaiSpokenWords.normalize`.
3. Intent: pending context first, then list follow-up / choice, references, fragments, stated payments, stock, reminders,
   `KaiCommands.route`, and finally the Business Brain for questions.
4. Entities: `KaiEntityResolver` (books records by id; phone, place / shop, context); the Brain's `find` for questions.
5. Direction: `KaiPaymentDirection` (grammar: enakku / naan / X-ku / en kitta / past tense in `KaiCommands`).
6. Amount / date: `KaiUnderstanding.amountsIn`, `KaiTime.parse` (+ year question for a just-passed day).

## 7–11. Context → draft → confirm → save → verify
7. Context: `KaiConversationState` (stated, statedOtherSide, lastSaved, focusPartyId, mentionedPeople, pending*), Brain (`lastParty`, `lastList`, choices).
8. Draft: `prepared()` → `tools.prepare` → `ActionPlan` + card. Nothing is written.
9. Confirm: card button or "seri / ama / save pannu / confirm" to the draft just shown; the plan is removed before the engine call (single use).
10. Save: `AppKaiTools.confirm` → `TransactionRepository.createCredit/createDebit` → `LegacyBridge` → `engine.postSale/postPurchase`;
    payments → `engine.postPaymentIn/Out` from the engine's own payment draft.
11. Verify: `books.changed()` (drops `KaiBrain`'s 60 s snapshot + history cache) → `readBack()` (party on the right side, balance = engine's).

## 12–13. Reads
12. Brain: `KaiBooks.snapshot()` = `PartyRepository.getCustomers/getSuppliers` = `LegacyBridge.summaries` (sum of open documents per party).
13. Collect / Pay / Customers / Home: the same `PartyRepository` / books session, read when the screen opens.

## 14–15. Reminders
14. Create: `KaiReminderAssistant` draft → owner confirm → `tools.createReminder` → `KaiReminderEngine.create` (dedup, SharedPreferences, exact alarm).
15. Fire: AlarmManager → `KaiReminderReceiver` → notification / full-screen `KaiReminderActivity` → Done / Snooze / Call; boot → `rearmAll`.

## 16. Owner memory
`KaiPrivateMemory` (business + owner), `teach*` only after the owner confirms; `apply` rewrites the owner's words before understanding.

## 17. Later questions
Every balance / list / history question reads `KaiBooks` at answer time (after a Kai save the cache is dropped).

## 18. Where stale / in-memory state exists
| State | Where | Risk |
|---|---|---|
| Ledger snapshot (60 s) | `KaiBrain.memory()` | Fixed earlier: `forget()` on every save path incl. Kai Chat |
| Party histories (60 s) | `RepositoryKaiBooks.histories` | Fixed earlier: keyed by ledger version |
| Entities for memory (60 s) | `AppKaiMemoryAccess.entities()` | A party created a minute ago may not resolve a nickname yet |
| Conversation (drafts, stated, lists) | `KaiAgent` / `KaiBusinessBrain` fields | Lost on process death (by design: never written without Confirm) |
| Pesunga's own form | `VoiceEntryScreen` (`parsed`) | Was a separate engine (R1). Now every Pesunga intent goes to `KaiAgent`; the old form path is only reached for `usesShopLanguage == false && handledByKai == false`, which no intent returns |

## After this branch — the identity flow (owner references)

1. Owner: "Kumar House enaku 600 tharanum".
2. `KaiMemoryAssistant.apply` → `KaiPrivateMemory.apply`: "Kumar House" (CUSTOMER_ALIAS → c2) is replaced by the record name "Kumar";
   `AppliedMemory.entities` = [("kumar house", c2)] — only aliases actually present in the words.
3. `KaiAgent.ask`: `turnRefs` = {"kumar" → c2} (dropped if two different references to two "Kumar"s were said in one message);
   `refLabels` = stored references (id → phrase) overlaid with this message's own words.
4. Every identity decision calls `resolveParty(name, said, candidates, context)` → `KaiEntityResolver.resolve(..., referenceId = c2)` →
   `Result.One(c2, "owner reference")` (CONFIRMED). Without a reference: phone → place/shop words → only one → context (not for a bare
   name the owner said while keeping the records apart by references) → `Result.Many` (AMBIGUOUS, asked) / `None` (UNKNOWN).
5. Writes use the CONFIRMED id (`ActionPlan.partyId`); the draft and the save line use the owner's label ("Kumar House — ₹600").
6. Confirm → `tools.confirm` → `books.changed()` → `readBack()` → "Save aagiduchu Owner. Kumar House — ₹600 … Ippo balance ₹600".
7. Questions: `hintSameNames` gives the Brain the CONFIRMED id (or none → the Brain asks "1. Kumar Anna — ₹500 / 2. Kumar House — ₹600").

## Reminder flow (after this branch)
Draft → owner Confirm → `tools.createReminder` → **`tools.reminderStored(id)` read-back** → only then "Done Owner ✅ … remind pannuren";
a store that did not keep it → "confirm panna mudiyala — Reminders screen-la paarunga" (logged FAILED, nothing claimed).
