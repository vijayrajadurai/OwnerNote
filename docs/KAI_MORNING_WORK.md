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
