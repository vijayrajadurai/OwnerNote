# Kai — human-like owner understanding (context, memory, entities, reasoning, safe action)

- **BRANCH:** `feature/kai-human-owner-understanding`
- **BASE COMMIT:** ab9c21b (main = master)
- **Status:** reviewed, then committed and fast-forward merged to main and master at the owner's request (no force-push).

## Architecture inspected and reused

The previous phase already built most of the requested pipeline inside the existing code. This phase inspected it with probes and closed the gaps that were left.

The pipeline, in order:
1. Language / speech-to-text normalisation: `KaiSpokenWords.normalize`. Text and voice both go into the same `KaiAgent.ask`.
2. Conversation context: `KaiConversationState`. It holds the stated payment, the focus record, who was mentioned, the pending field, the pending choice, and message fragments.
3. Owner memory: `KaiMemoryAssistant` / `KaiPrivateMemory`.
4. Entity resolution: `KaiEntityResolver`.
5. Meaning: `KaiPaymentDirection` / `KaiChatUnderstanding` / `KaiCommands`.
6. Business data: `KaiBusinessBrain`.
7. Dates: `KaiTime`.
8. Drafts and saving: draft → Confirm → `KaiTools.confirm` → `createCredit` / `createDebit` → books engine.
9. Reminders: `KaiReminderAssistant`.
10. Stock: `KaiStock`. Calculator: `KaiCalculator`.

| Existing piece | Reused? |
|---|---|
| Context | YES — no new context class |
| Memory | YES — no second memory |
| Business Brain | YES |
| Action engines | YES |

Nothing new was added: no engine, no database, and no LLM. An LLM was not needed — every required conversation is handled deterministically.

## Tests

| | Pass | Fail |
|---|---|---|
| Baseline | 866 | 0 |
| Final | 995 | 0 |
| New tests (`KaiHumanUnderstandingTest`) | 129 | 0 |

The 129 new tests are:
- 11 required conversations
- 76 category tests covering A–AH
- 42 generalised scenarios with other people (Priya ×2 by town / shop / phone, Selvi, Arun, Basha, Karthik, Dinesh, Gopal), other products (Sugar, Rice, Tea Powder), other amounts and other phrasings

No existing test was deleted or weakened.

## Results

| Check | Result |
|---|---|
| Context understanding | PASS |
| Purpose understanding (question vs action vs reminder) | PASS |
| Pronoun resolution | PASS |
| Entity resolution | PASS |
| Duplicate-name disambiguation | PASS |
| Location disambiguation | PASS |
| Phone disambiguation | PASS |
| Payment direction | PASS |
| Date context | PASS |
| Topic switching | PASS |
| Return to previous topic | PASS (new in this phase) |
| Owner memory | PASS |
| Business queries | PASS |
| Save persistence | PASS (unit test, in-memory books + restart) |
| Tamil / Tanglish | PASS |
| Voice / text parity | PASS |
| Pixel 8 | **BLOCKED** — no Android build or device in this cloud environment |

## Root causes found in this phase

**1. Coming back to an earlier topic answered from the wrong source.**
- The conversation was: "Kumar enakku 3000 tharanum" → "Colgate stock evlo?" → "Kumar eppa tharuvaan?"
- Kai answered from the books' older ₹3,000 (due Oct 20) and forgot the payment the owner had just stated.
- The fix: `returnToStated` brings the stated payment back. Kai asks for its due date, or says it back if it was already given.
- What the books already hold for that person is said separately, so the new amount is never merged into the old one: "(Records-la Kumar ₹3,000 October 20th due already irukku.)"

**2. Messages Kai could not place got generic replies.**
- "same", "again", "no", a lone "10", or "adhu" with nothing open got "konjam clear-ah sollunga" or "`same` nu sonnadhu enna meaning-la?".
- The fix: `clarify` asks about whatever is open: "Owner, idhu Kumar ₹3,000 payment pathiyaa, illa vera vishayam pathiyaa?"
- When nothing is open, the clarification says what is missing:
  - a lone number: "10 — amount-aa, date-aa? Yaar pathi-nu sollunga."
  - "adhu" with no product: "Product per sollunga."
  - anything else: "Payment-aa, stock-aa, reminder-aa?"
- The Business Brain's own "unclear" reply is unchanged, because existing tests check it directly. Only the Kai Chat level replaces it, and only when the Brain reports UNKNOWN.

Everything else the spec asks for already passed in the probes. It was built in the previous phases:
- pronouns
- the Lokesh town / shop / phone resolution
- pieces of a payment said across several messages
- casual talk that keeps the context
- save → books → restart

## Files changed

- `app/src/main/java/com/shopai/app/brain/chat/KaiAgent.kt` (+93 / −1): `returnToStated`, `clarify`, `activeTopic`
- `app/src/test/java/com/shopai/app/brain/chat/KaiHumanUnderstandingTest.kt` (new, 129 tests)

## Diff summary

```
 app/.../brain/chat/KaiAgent.kt | 94 +++++++++++++++++++++-
 + KaiHumanUnderstandingTest.kt (new)
```

## Known limits

- **A town after an unknown name can be read as the name.** For a person who is not in the books, a town word after the name is taken as the name ("Lokeshwaran Nagapattinam-ku"). This is unchanged from the previous phase.
- **Business-name matching uses the party's address / notes text.** There is no shop-name field on a party.

## Pixel 8 steps (A–E)

- **A.** "Kumar enakku 3000 tharanum" → "eppa?" → "10" → "next month" → "avan eppa tharuvaan?" — expect Kumar, ₹3,000, adutha maasam 10.
- **B.** "Colgate stock evlo?" → "adhu low-aa?" → "athula 5 pochu" — expect a Colgate stock-out draft.
- **C.** "Mahesh enakku 2000 tharanum" → "saptiya?" → "seri" → "eppa?" — expect the Mahesh payment context.
- **D.** With three suppliers named Lokesh, say "Lokesh-ku 500 tharanum" — expect the which-Lokesh question. Then "Nagapattinam" — expect Nagapattinam Lokesh.
- **E.** "Mahesh enakku 2000 tharanum" → "save panniko" → "seri" → restart the app → "Mahesh enakku evlo tharanum?" — expect ₹2,000.
