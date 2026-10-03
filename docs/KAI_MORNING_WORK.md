# Kai — Do My Morning Work

An additive Kai capability: the owner opens OwnerNote, Kai prepares today's
important work from the real records, and the owner reviews, picks an action
and confirms. Kai never pays, posts, sends or deletes anything on its own.

## Flow

```
Existing business data (read only)
  PartyRepository (books / backend ledger) · books open bills · stock movements
  · product min / reorder levels · batches · reminders · open drafts
        ↓  MorningWorkSources (data/morning)
MorningSnapshot
        ↓  MorningAnalyzer — fixed, explainable priority rules
Candidate tasks
        ↓  de-duplication (business_id + task_type + source_id + business_date)
Today's list (MorningTaskStore — one JSON file per business, history kept)
        ↓  owner: tap / type / speak  →  MorningCommands (ta / Tanglish / en)
MorningWorkEngine  (ONE engine for voice and text)
        ↓  MorningEffect — dialer, WhatsApp draft, reminder, screen, confirmed payment
Existing integrations (MorningWorkActions)
  ACTION_DIAL · WhatsApp chat · ReminderRepository + ReminderAlarms ·
  BillDrafts → BooksEngine.postPaymentIn/Out
        ↓  MorningResult
Task status (PENDING / IN_PROGRESS / COMPLETED / POSTPONED / SKIPPED / FAILED)
```

## Priority rules

| Priority | Rule |
|---|---|
| CRITICAL | money overdue · out of stock (level set) · expired batch · owner reminder past its day |
| HIGH | money due today · stock below reorder / minimum level · reminder time already passed today |
| MEDIUM | payment due in ≤ 3 days · partially paid bill · batch expiring in ≤ 7 days · reminder later today · draft waiting |
| LOW | customer balance with no due date |

Order inside a priority: oldest due date, then bigger amount, then name. At
most 15 open tasks are listed (`MorningWorkEngine.MAX_TASKS`).

## Safety

- Generating tasks reads only; it never changes balances, stock, invoices or reminders.
- Payments: draft → review card (mode, account, amount, reference) → explicit
  "Yes"/Confirm → posted through the books engine with the draft id as the
  idempotency key. Silence or unclear words are never a confirmation.
  Staff need the `PAYMENTS` permission (owner / manager / accountant can).
- WhatsApp: the message is shown for Edit / Send / Cancel; Send opens the
  customer's WhatsApp chat — the owner sends it there.
- Call: opens the dialer; Kai says "call panna ready", never "call completed".
- Remind later: the existing reminder engine (backend reminder + phone alarm
  at the exact time). Offline, the reminder waits on the task and is created
  when the connection is back.
- A task whose condition disappears from the records (money received, stock
  bought) becomes COMPLETED with `RESOLVED_IN_BOOKS` — only with current data,
  never from an offline copy.

## Voice and text

Both go through `MorningWorkEngine.handle(text, mode)`. The request that
starts the session sets the reply mode (voice → Kai speaks, text → Kai types);
it changes only on "Type-la sollu" / "Text-la reply" / "Voice-la sollu" /
"Voice reply". Kai Chat and Pesunga hand over only clear morning-work requests
(`MorningCommands.morningRequest`), so their existing answers stay the same.

## Tests

`app/src/test/java/com/shopai/app/brain/morning/MorningWorkEngineTest.kt`
(pure JVM, no Android).

## Morning Work in Kai's core brain (MORNING_WORK intent)

Morning Work is no longer a Kai Chat shortcut. Every entry point goes through
Kai's one intent router and the one `MorningWorkEngine`:

```
Typed text ─┐
Voice (STT) ┴→ KaiSpokenWords.normalize → KaiAgent.ask / KaiIntents.classify
              → MORNING_WORK (unless the owner explicitly asked for a reminder)
              → KaiMorningAccess (AppContainer.kaiMorning)
              → MorningWorkSources.snapshot()   ← signed-in business only (books session / login)
              → MorningWorkEngine.generate(snapshot, lang, MANUAL)
                   = prepare() (same task list as the Morning Work screen) + MorningBriefs.build()
              → Kai reply (text; spoken too when the owner spoke) + follow-up card

Scheduler (future) → MorningWorkSources.snapshot() → MorningWorkEngine.generate(…, SCHEDULED)
                   → MorningBriefs.notification(brief) → notification
```

- Detection: `MorningCommands.morningRequest` (the only detector), used by the
  router; "remind / reminder / nyabagam" always wins → CREATE_REMINDER.
- Brief: sections Collections → Payments → Stock → Expiry → Reminders → Drafts,
  at most 3 lines each, ordered by `MorningAnalyzer.sort` (fixed priority rules);
  empty sections are not shown; "First priority" = the top task.
- Follow-up card: "Owner, first Kumar collection follow-up pannalama?"
  [View Kumar] [Remind Me] [Call Kumar] [Skip] [Start Morning Work]. View opens
  the record, Call opens the dialer, Remind Me asks the time, Add Stock asks the
  quantity — nothing is written without the owner.
- Read only: no payment, sale, purchase, stock change or reminder is created by
  Morning Work itself. Balances come from the ledger, stock from stock
  movements, reminders from the reminder engine (via the snapshot).
- Tests: `app/src/test/java/com/shopai/app/brain/chat/KaiMorningWorkIntentTest.kt`.

## Production hardening — phase 2 (A scheduler · B owner routine · C performance · D owner isolation)

```
Kai → KaiIntents / KaiAgent (one router) → MORNING_ROUTINE | MORNING_WORK
    → MorningWorkEngine ← MorningWorkSources.snapshot()
          ├─ business data: BooksMorningQueries (bounded SQL, MorningSql)   [shared by the business]
          ├─ owner routine: MorningRoutines.load(owner's Kai memory)        [private: owner + business]
          └─ reminders: ReminderRepository (its saved copy offline)
    → MorningBriefs.build(plan, …, routine) → Kai chat / voice
                                            → MorningScheduler → MorningWorkReceiver → notification
```

### A. Daily Morning Work notification
- Settings → **Morning Work Notification**: OFF by default; the owner picks the time
  (TimePicker). Android 13+ asks the notification permission when it is turned on.
  The result is always shown: scheduled (exact) / may be a few minutes late (no
  exact-alarm permission) / notifications off (+ Open notification settings) / failed.
- `MorningScheduler` (pure): `nextTrigger` = the owner's wall-clock time in the
  phone's zone (DST gap → moved forward, overlap → first), tomorrow when passed.
  One alarm id on the phone (`ALARM_ID`) — arming replaces, never duplicates.
  At most one notification per owner per day (`lastShownDay`).
- Restored on app start, Home (login / account switch), boot, app update, clock /
  time-zone change. Logout cancels it. A ring armed for another owner / business
  than the signed-in one shows nothing (`MorningFire.Skip`).
- When it rings, `MorningWorkReceiver` reads the snapshot NOW and runs the one
  engine (`generate(…, SCHEDULED)`): "Good morning Owner ☀️" / "Your Morning Work is
  ready." Empty business: "Nothing urgent right now. You're all clear." (no ₹0).
  Records unavailable / last-synced copy: only "ready" (never "all clear").
  Tapping it opens Morning Work (`EXTRA_OPEN_MORNING_WORK` → `MorningWorkInbox`).

### B. Owner Morning Routine
- `MorningRoutineParser`: "Morning-la first collections paakanum. Appuram stock.
  Last-la reminders.", "Morning brief-la stock first venum", "Reminders-a last-la
  podu", "Stock first, collection next", "Collections first venam, stock first",
  "Default morning order-ku change pannu", "Morning routine change pannu".
  "First enakku pending payments kaatu" counts only right after the brief.
  An amount, a time or a person → not a routine (payment / reminder).
- Kai asks: "Owner, இதை உங்க Morning Routine-ஆ save பண்ணவா?" / for one section
  "Owner, next time Morning Work-la stock-a first kaattava?" with [Save] [Not now].
  Only Save (or "aama" right after the question) changes it.
- Stored as `MorningRoutinePreference` = a PREFERENCE record in the owner's Kai
  memory (`kai_memory/<business>__<owner>.json`) — scoped by owner + business.
  Reset → removed → the default priority order.
- Applied to section order and task order (inside a section the normal priority
  order); the day's list is still trimmed by priority, so nothing important is dropped.

### C. Performance (books)
- No full customer / supplier / product lists. Each read is bounded SQL with WHERE /
  ORDER BY / LIMIT (`MorningSql`): parties due by the upcoming window (+ customers'
  part-paid follow-ups) in MorningAnalyzer's order; low stock; expiring batches
  with stock; open drafts; totals by aggregate queries. The open-bill figures are
  the same rows the ledger screens use (`openDocsAll`'s definition).
- Measured on a real SQLite (`MorningSqlPerformanceTest`, 10,000 customers /
  50,000 transactions / 10,000 products): 14 queries, 264 rows to the app, ~390 ms
  (JVM SQLite) vs. the previous read: 210 queries, 80,293 rows, ~555 ms. Results are
  exactly the top of the full calculation.
- Indexes: none added. EXPLAIN QUERY PLAN shows the existing indexes used
  (txns.partyId, allocations.toTxnId, parties PK, stock_movements(businessId,productId,…));
  candidate indexes ((businessId,status,partyId), (businessId,status,type,partyId),
  allocations(active,toTxnId)) gave no gain, so no migration.
- Limitation: reminders still come from the backend API (no server-side limit).
  Before the books import, the backend repositories are used as before.

### D. Owner isolation
- `MorningOwner(businessId, ownerId)` from the authenticated session only (books
  session user, else the token's business + owner, else offline the last login).
- Shared: business records (dues, stock, drafts, morning tasks of the business).
  Private: routine, Kai memory, notification setting / schedule.
- Another owner on the same phone: the engine session restarts, Kai's
  conversation resets, the morning alarm is re-armed for the new owner only.

Tests: `KaiMorningRoutineTest`, `MorningSchedulerTest`, `MorningSqlPerformanceTest`.
