# Kai — production readiness report

Branch `feature/kai-production-hardening` (from main/master e6ead81). Merged to main and master on the owner's instruction (8 Oct 2026) after a final JVM run of 1190 tests, 0 failures. Device validation is still pending, so the status below is unchanged.

## STATUS: NEEDS_MORE_WORK

The ledger / identity / direction / reminder chain is JVM-verified end to end (1190 tests, 0 failures; 667 matrix cases + 9
journey tests). It is **not** production-ready: nothing was run on Android or a real phone (**Pixel 8 = BLOCKED**), one changed Android file
(`AppKaiTools.kt`) was not compiled here, and the "Ask My Business" screen still has its own answer engine.

---

## Part A — Production readiness (18 items)

**1. Architecture inspected.** Kai Chat (text + mic), Pesunga, Ask My Business, reminder receiver / activity, Morning Work; the pipeline
in `KaiAgent.ask`; `KaiBusinessBrain`; `KaiBooks → RepositoryKaiBooks → KaiBrain.memory → PartyRepository → LegacyBridge`; `KaiTools →
AppKaiTools → TransactionRepository / books engine / inventory / KaiReminderEngine`; `KaiPrivateMemory`. See ARCHITECTURE.md, DATA_FLOW.md,
RISK_REGISTER.md.

**2. Existing engines reused.** `KaiAgent`, `KaiChatUnderstanding`, `KaiBusinessBrain`, `KaiPaymentDirection`, `KaiCommands`,
`KaiEntityResolver`, `KaiPrivateMemory` / `KaiMemoryAssistant`, `KaiTime`, `KaiUnderstanding`, `KaiReminderAssistant` / `KaiReminderEngine`,
the existing draft → confirm → books → read-back path. **No new engine, parser, memory store, DB table, model or LLM.**

**3. Files changed.**
- `brain/chat/KaiAgent.kt` — one `resolveParty` for every identity decision (owner reference → … → ask); `turnRefs` / `refLabels` /
  `ownerWords`; reference teaching / correction; owner labels on drafts and save lines (`ownerName`, `chosenLabels`); add-entry request;
  spoken / typed payment choice (`planChoice`); product stock questions; person + money words go to payments, not stock.
- `brain/chat/KaiBusinessBrain.kt` — "rendu / both / ellaarum <name>" lists each record; total only when asked; `awaitingChoice()`.
- `brain/chat/KaiEntityResolver.kt` — `Result.confidence` (CONFIRMED / AMBIGUOUS / UNKNOWN); owner-reference step; `referenceIn`; labels via a function.
- `brain/chat/KaiMemoryAssistant.kt` — `lastReferences`, `referenceLabels`, `learnReference`, `dropReference`.
- `brain/memory/KaiPrivateMemory.kt` — `apply` reports only aliases actually said (root cause of F1).
- `brain/chat/KaiReminderAssistant.kt` — reminder read-back before "remind pannuren".
- `brain/tools/KaiTools.kt` — `reminderStored(id): Boolean?` (default null = can't read back).
- `brain/tools/KaiCommands.kt` — payment lexicon gaps; English payer-first rule; minus amount not a payment.
- `brain/KaiUnderstanding.kt` — STT spellings of Tamil hundreds.
- `brain/tools/KaiIntents.kt` — every Pesunga intent through `KaiAgent`.
- `data/kai/AppKaiTools.kt` — `reminderStored` → `KaiReminderEngine.find` (Android; not compiled here).
- Tests (new): `KaiOwnerReferenceTest.kt` (18), `KaiProductionMatrixTest.kt` (16 categories / 667 cases + 8 journeys).
- Docs: `artifacts/kai-production/*.md`.

**4. Ledger data flow.** Books DB → `LegacyBridge.summaries` (open documents per party) → `PartyRepository` → `KaiBrain.memory()` (cached;
dropped on every save) → `KaiBooks.snapshot()` → Brain answers. Kai never keeps a balance of its own. LEDGER_INTEGRITY.md.

**5. Chat → ledger flow.** words → owner memory → normalise → intent → direction → `resolveParty` (CONFIRMED only) → `tools.prepare`
(draft, no write) → Confirm → `tools.confirm` → engine → `books.changed()` → `readBack()` → "Save aagiduchu … Ippo balance ₹X".

**6. Voice → ledger flow.** Kai Chat mic: same `KaiChatSession.send` → same `KaiAgent`. Pesunga: `VoiceEntryScreen` → `kaiChat.send`
for **every** intent now (was: payments / questions / calculator via the old `KaiBrain.hear`). Same draft / confirm / read-back.
Choices are now spoken in the reply text, so a voice owner hears them.

**7. Reminder flow.** understand → ask missing time → draft → Confirm → `createReminder` → **read-back `reminderStored(id)`** → "remind
pannuren" only if stored → alarm / notification / full-screen activity (unchanged). REMINDER_INTEGRITY.md.

**8. Owner memory flow.** taught → Kai repeats → Save → `KaiPrivateMemory` (business + owner) → `apply` before understanding. Owner
references carry the canonical id through the turn; learned from explicit context / a pick; dropped and re-asked on correction;
per business; survive restart. OWNER_CONTEXT.md.

**9. Test counts.** Full suite **1190** JUnit tests (base 1147 + 43 new methods). Matrix: **667 cases** in 16 categories (targets 650)
+ **8 journeys (9 tests)**. Owner references: 18 tests.

**10. Passed.** 1190 / 1190 JUnit tests; 667 / 667 matrix cases; 9 / 9 journey tests.

**11. Failed.** 0 on the final run. During this work the matrix and journeys exposed 14 real defects (FAILURES.md F1–F14); all were
fixed in the engines, not by changing expectations — except two expectations that were wrong in my test, corrected honestly:
the due date is checked on the plan the books receive at Confirm (not the pre-Confirm draft object), and "15th" is answered with
"indha maasam" because Kai asks this-month-or-next by design.

**12. Blocked.** Real device / emulator (Pixel 8), Android compilation of `AppKaiTools.kt` / `VoiceEntryScreen` / `RepositoryKaiBooks`,
Collect / Pay / Transactions / Dashboard screens, alarms and notifications.

**13. JVM result.** PASS — 1190 tests, 0 failures (`brain/` compiled as-is with fakes only at the books / tools / memory-store edge).

**14. Android result.** NOT RUN — no Android SDK here. Android-side change is one line (`AppKaiTools.reminderStored`), reviewed by hand
(`KaiReminderEngine.find(id)` is public and already used inside the engine).

**15. Pixel 8 result.** **PIXEL 8 = BLOCKED.**

**16. Known limitations.** (Owner-approved: "seri" saves the open draft after a detour, unless Kai's last reply asked something else — then "seri" answers that.) Understanding is rule / lexicon based (unknown phrasings are asked, never guessed); a bare day is asked this-month-or-next; the 60 s entity cache (R7); Ask My Business
screen (R5).

**17. Critical risks.**
- Unverified on device: the real DB write + read-back through `AppKaiTools` and the screens that show balances.
- `AppKaiTools.kt` change not compiled here (low risk, one line).
- Ask My Business can still give an answer that is not Kai's (R5).

**18. Production recommendation.** Do not ship as production-ready yet. Next: build the branch in Android Studio, run the unit tests
there, then on Pixel 8 walk journeys 1–8 (Kai Chat text, Kai Chat mic, Pesunga), checking Collect / Pay / Transactions / Dashboard after
each save and after an app restart, and one reminder ringing. If those pass, route Ask My Business to Kai (R5) and re-gate.

### Production gate (Phase 23)

| Item | JVM | Device |
|---|---|---|
| Ledger writes / reads correct | ✅ | BLOCKED |
| Receivable / payable direction | ✅ | BLOCKED |
| Existing + new entries aggregate; payments reduce balances | ✅ | BLOCKED |
| Drafts don't pollute ledger; cancel doesn't save; duplicate confirm doesn't duplicate | ✅ (existing suites + matrix) | BLOCKED |
| Save read-back verified | ✅ | BLOCKED |
| Collect / Pay / Transactions / Dashboard match ledger | summaries only | BLOCKED |
| App restart preserves data | ✅ (books re-read) | BLOCKED |
| Today / overdue / details follow-ups | ✅ | BLOCKED |
| Date / time; reminders | ✅ | BLOCKED (alarm) |
| Owner memory; entity resolution; context; topic switching | ✅ | BLOCKED |
| Tamil / Tanglish / English; voice-text parity | ✅ (same agent) | BLOCKED (mic / STT) |
| Calculator exact; negative tests; regression suite | ✅ | — |
| Real-device validation | — | **BLOCKED** |

→ **NEEDS_MORE_WORK**

---

## Part B — Entity understanding + owner references (15 items)

**1. Existing architecture inspected.** `KaiEntityResolver` (phone > place > context > ask), `KaiPrivateMemory` aliases with
`referenceEntityId`, `KaiMemoryAssistant.apply`, the Brain's `find` / `hint`, `KaiTools.parties` (books ids).

**2. Entity resolution architecture used.** The existing `KaiEntityResolver`, called through one `KaiAgent.resolveParty` everywhere.
Order: exact id → owner reference (this message) → phone → place / shop words → only one → conversation context (not for a bare name the
owner said while keeping records apart by references) → ASK. `Result.confidence`: CONFIRMED / AMBIGUOUS / UNKNOWN; writes need CONFIRMED.

**3. Files changed.** See Part A item 3.

**4. Database / model changes.** None. No schema, table or migration. References use the existing `KaiPrivateMemory` alias records
(`CUSTOMER_ALIAS` / `SUPPLIER_ALIAS` + `referenceEntityId`). One interface method added: `KaiTools.reminderStored` (default null).

**5. Alias / reference persistence.** `KaiPrivateMemory` file store, scoped business + owner; `learnReference` (OWNER_CONFIRMED);
`dropReference` on correction. Tested: survives a restart (new memory over the same store); another business does not see it.

**6. Ledger integration.** The reference resolves to the record id on `ActionPlan.partyId`; the books engine writes by id; balances read
back from the books ("Kumar House +400 → ₹1,000" is the books' figure). Tested in the acceptance journey.

**7. Payment direction handling.** Unchanged design (`KaiPaymentDirection` + `KaiCommands`), with root-cause fixes: English payer-first,
lexicon gaps, minus amounts, "add pannu" entries on the record's side. Reference does not change direction.

**8. Ambiguity handling.** Bare short name with several records → "Owner, Kumar-nu rendu per irukkaanga. 1. Kumar Anna — ₹500; 2. Kumar
House — ₹600"; payment drafts say the choices in words too; a typed / spoken pick ("Chennai", "1", "Kumar House") resolves it.
"Rendu Kumar-oda" / "Both Kumar" → each listed; total only when asked.

**9. Context handling.** "avan / avar / avanga" → the record being discussed (CONFIRMED by context); a bare name said by the owner is not
silently resolved from context when the owner keeps those records apart by references.

**10. Text / voice parity.** Kai Chat text, Kai Chat mic and (now) every Pesunga intent go to the same `KaiAgent` and the same resolver.
JVM-verified at the agent; the microphone / STT itself is device-only (BLOCKED).

**11. Tests added.** `KaiOwnerReferenceTest` (18), `KaiProductionMatrixTest` (16 categories, 667 cases, + 8 journeys).

**12. Full test result.** 1190 / 1190 pass.

**13. Regression test result.** All 1147 pre-existing tests pass unchanged (none deleted, skipped or weakened).

**14. Remaining risks.** Device unverified; `AppKaiTools.kt` not compiled here; 60 s entity cache (a brand-new party's nickname may not
resolve for up to a minute); Ask My Business screen.

**15. Real-device validation status.** **PIXEL 8 = BLOCKED.** Not production-ready until the device pass in Part A item 18.
