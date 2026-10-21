# Kai's training — what it is and where it lives (owner, 21 Oct 2026)

The owner asked for Kai to be "trained" on Tamil + Tanglish, business knowledge, tool calling, reasoning, context, safety and a
dataset, for every feature of the app. Kai is not a trained AI model: it runs on the phone, with rules, and the facts (balances,
stock, entries) always come from the books. Its training is therefore two things kept together:

- **rules** — the words, phrasings and steps Kai understands, in the code; and
- **a dataset of owner lines** — every line played typed and as the mic writes it, with the result it must give. A new way an
  owner says something is a new line here first; a line that fails is a bug to fix in Kai, never a line to delete.

| What the owner asked for | Where it is in Kai | Proved by |
|---|---|---|
| 1. Tamil + Tanglish (any spelling, any mic slip) | `KaiSpokenWords` (mic Tamil → Tanglish), `KaiIntentWords` (an intent said in other words), `KaiFeed.spellings`, `KaiWordSound` (a word Kai can't read = the word of Kai's it sounds like: "taranum" = tharanum, "kensal" = cancel), `KaiProductSound` (product names by sound), `NameSound` (people's names across scripts), `KaiHeard` (of the mic's five guesses, the one Kai reads best) | `owner-lines.txt` "Said any way", `KaiWordSoundTest`, `KaiVoiceOwnersTest`, `KaiMicForm` replay of every line |
| 2. Business knowledge (ledger, credit / debit, payment, stock in / out, units, daily sales, expenses, profit, reminders, due dates, expiry, reports, services) | the rules in `brain/` (`KaiAgent`, `KaiCommands`, `KaiStock`, `KaiUnits`, `KaiDailySales`, `KaiReminders`, `KaiExpiry` …); numbers only from the books (`KaiBooks`, `KaiTools`) | `KaiOwnerSweepTest` (books / stock / reminder results), `KaiDailySalesTest`, `KaiInventoryChatTest`, `KaiBusinessKindsTest` |
| 3. Tool calling (the right action, its fields, success / failure) | every action is a `KaiTools` call behind a draft card — Confirm calls it, the answer comes from what it returned | `KaiOwnerSweepTest` (what was written), principle tests in `KAI_PRINCIPLES.md` |
| 4. Reasoning + clarification (ask, never guess) | a missing person / amount / direction is asked; a word Kai can't read is asked with buttons (Payment Out / Payment In / Kadan) and the pick is kept for that owner | `KaiOwnerSweepTest` principles, `KaiPrivateMemoryTest`, `KaiMeaningTest` |
| 5. Conversation context ("avarukku anuppu", "adha PDF aakku", "cancel pannu") | `ConversationState`, `KaiConversationCare`, `personOnTopic`, relation words (`KaiRelations`) | `KaiOwnerSweepTest.contextFollowUps`, `KaiTrainingLinesTest` |
| 6. Safety + error recovery (duplicates, wrong person, no action without Confirm, honest save failures, cancel) | nothing is written before Confirm; "Save aagiduchu" only after the books confirm; a duplicate is said before Confirm; over ₹99 crore is refused plainly | `KaiOwnerSweepTest` principles 5–7 |
| 7. The dataset | `app/src/test/resources/kai/owner-lines.txt` (ENTRY / STOCK / REMIND / ASK lines), the 1600-line check, the 10,000-line voice probe | `KaiOwnerSweepTest`, `KaiVoiceOwnersTest.probe` |

## How to add to it

1. Write the owner's line (and what must happen) in `owner-lines.txt`; run `KaiOwnerSweepTest` — it plays it typed and by mic.
2. If it fails, fix the rule it needs (a stem in `KaiIntentWords`, a spoken pair in `KaiSpokenWords` …). A misspelling of a word
   Kai already has needs nothing: `KaiWordSound` reads it — add the word of Kai's to its list only for a new intent word.
3. Test a word that is NOT in the list too: it must be asked about or said plainly, never turned into another answer.
