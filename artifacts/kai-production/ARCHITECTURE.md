# Kai architecture (as inspected on `feature/kai-production-hardening`, base e6ead81)

Kai is one agent (`KaiAgent`) over one Business Brain (`KaiBusinessBrain`), the real books (`KaiBooks` → `RepositoryKaiBooks` →
`KaiBrain.memory()` → `PartyRepository` → `LegacyBridge` → books DB), the action tools (`KaiTools` → `AppKaiTools` → books engine /
`TransactionRepository` / inventory / `KaiReminderEngine`) and the owner's private language (`KaiPrivateMemory` via `KaiMemoryAssistant`).
No LLM is involved anywhere in this path; every amount and date is computed in code.

## Entry points

| Surface | Code | Engine (before this branch) |
|---|---|---|
| Kai Chat — typed | `KaiChatScreen` → `KaiChatSession.send(text)` | `KaiAgent.ask` |
| Kai Chat — mic | same `send(text, voice = true)` | `KaiAgent.ask` |
| Pesunga — typed / mic | `VoiceEntryScreen.parseInput` → `KaiIntents.classify` | **reminders / stock / scan / chat / learning / morning → `KaiAgent`; payments, business questions, calculator, call, unknown → the old `KaiBrain.hear()` parser** |
| Ask My Business (More menu) | `AskBusinessScreen` | backend `insightsRepository.askMyBusiness` (server answer engine) |
| Reminder rang | `KaiReminderReceiver` → `KaiReminderEngine.fired` → notification / `KaiReminderActivity`; tap → `KaiChatSession.showRang` → `KaiAgent.rang` | `KaiAgent` |
| Morning Work | `MorningWorkEngine` over `MorningWorkSources` (read only) | own engine (reads the same books) |

## Modules (the one pipeline)

| Stage | Module |
|---|---|
| Language of the reply | `KaiLanguage.forChat` / `KaiConversationSemantics.phraseLang` / `shortLang` |
| Owner's private words | `KaiMemoryAssistant.apply` → `KaiPrivateMemory.apply` (business + owner scoped; confirmed memories only) |
| Spoken-Tamil normalisation | `KaiSpokenWords.normalize` |
| Pending context (drafts, due-date question, entity choice, same-or-new, year, list follow-up) | `KaiAgent.ask` (top of the pipeline) + `KaiConversationState` |
| Reference resolution ("avan", "avanga", bare "evlo?") | `KaiAgent.referenceResolved`, `KaiEntityResolver.peopleIn / withName` |
| Stated payments ("X enakku 3000 tharanum") | `KaiAgent.contextualReceivable` → `remember` → `identify` → `draftStated` |
| Actions | `KaiCommands.route` (calculator, payment in/out, reminder, call, stock, money) ; `KaiReminderUnderstanding` ; `KaiStock` |
| Direction | `KaiPaymentDirection.of / explicitOf` |
| Amounts / dates | `KaiUnderstanding.amountsIn`, `KaiTime.parse`, `KaiConversationSemantics.correctionAmount` |
| Entity identity | `KaiEntityResolver.resolve` (phone > place/shop words > context > ask) over `KaiTools.parties` (ids) |
| Questions | `KaiChatUnderstanding.understand` → `KaiBusinessBrain` (`personAnswer`, `businessAnswer`, `LedgerList` follow-ups) |
| Writes | `KaiTools.prepare` (draft, nothing written) → card → `KaiTools.confirm` → `AppKaiTools` → `TransactionRepository.createCredit/createDebit` or `engine.postPaymentIn/Out` |
| Read-back | `KaiAgent.readBack` after `books.changed()` (cache dropped) |
| Reminders | `KaiReminderAssistant` → `KaiTools.createReminder` → `KaiReminderEngine` (SharedPreferences + exact alarms; re-armed on boot / time change / update) |
| Owner memory | `KaiPrivateMemory` (file store per business/owner), `KaiPersonalTeaching`, `KaiTeaching` |
| Calculator | `KaiCalculator.solve` (deterministic) |

## Canonical identity

Customers / suppliers have a books id (`PartyMatch.id`, `PartyFacts.id`). Writes carry `ActionPlan.partyId`. A name is never the key
when an id is known. Owner nicknames are stored as `CUSTOMER_ALIAS` / `SUPPLIER_ALIAS` memories with `referenceEntityId`.

## After this branch (what changed in the architecture)

| Area | Before | Now |
|---|---|---|
| Pesunga voice / typed | payments, questions, calculator, calls, unknown → old `KaiBrain.hear()` | **every** intent → `KaiChatSession.send` → the same `KaiAgent` (`KaiIntents.handledByKai = true`); drafts confirmed by voice ("seri") or on the Kai Chat card |
| Owner references ("Kumar Anna" / "Kumar House") | alias rewritten to the bare name; the id was dropped; every alias in memory was reported as "used" | `KaiPrivateMemory.apply` reports only aliases actually said; `KaiAgent` pins this turn's reference → canonical id (`turnRefs`) and passes it to the resolver as the strongest evidence |
| Entity resolution | `KaiEntityResolver.resolve` called directly in ~6 places | one helper `KaiAgent.resolveParty` → `KaiEntityResolver.resolve(..., referenceId)`; priority **owner reference > phone > place/shop words > only one > conversation context > ASK**; `Result.confidence` = CONFIRMED / AMBIGUOUS / UNKNOWN |
| Bare name vs context | the last-discussed record answered a bare "Kumar" | when the owner keeps same-named records apart by their own references, a bare "Kumar" said by the owner is ASKED; "avan" (no name said) still means the record being discussed |
| Labels | city / shop / phone | the owner's reference first ("Kumar Anna — ₹500"), this message's words first; same-named records chosen in the conversation keep their label on the draft and the "Save aagiduchu" line ("Chennai Lokesh — ₹500") |
| "Rendu Kumar" / "Both Kumar" | asked "which one?" | each record listed separately (`KaiBusinessBrain.everyRecord`); a combined total only when "total / motham / serthu" is said |
| Learning references | only via the "Remember" button after a draft | also "Kumar Mama-na Sri Stores Kumar" (learned when one record fits, asked otherwise), "Illai bro, Kumar Anna vera Kumar" (dropped, asked again, re-learned from the pick) — stored per business + owner (`KaiPrivateMemory`), survives restart |
| Reminder save | trusted the engine's result | read back from the store (`KaiTools.reminderStored(id)` → `KaiReminderEngine.find`) before "remind pannuren" |
| Payment words | gaps ("Kumar paid 500" read as owner-paid; "GPay pannitaan", "anuppitaan", "pay pannitten", Tamil "குடுத்தான்", STT "ainnooru") | one lexicon in `KaiCommands` extended; English payer-first rule ("Kumar paid 500" = Kumar paid the owner); a minus amount is never a payment amount (asked) |
| "Colgate evlo irukku?" | treated as a customer name | a product of this shop named with a quantity question and no person → its stock |
| "Lokesh-ku 500 add pannu" | answered Lokesh's balance (no write) | a new entry on the side of Lokesh's record (customer → credit, supplier → debit), drafted, saved only on Confirm |
| "Lokesh 500 kuduthaan" (two Lokesh) | "Endha Lokesh?" with buttons only; a typed "Chennai" was not understood | the choices are said in the reply (voice can hear them) and a typed / spoken "Chennai" picks like the button |

No new engine, parser, memory store, database table or LLM was added. All changes are inside the existing modules above.
