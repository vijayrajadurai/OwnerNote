# Kai — owner understanding, context memory, entity resolution, continuous conversation

**BASE COMMIT:** 3134e41 (main = master)
**BRANCH:** `feature/kai-owner-understanding-memory`
**Status:** reviewed, then committed and fast-forward merged to main and master at the owner's request (no force-push).

**BASELINE TESTS:** 799 / 799
**FINAL TESTS:** 866 / 866 (JVM harness)
**NEW TESTS:** 67, all in `KaiOwnerUnderstandingTest`. No test was deleted or weakened.

| Area | Result |
|---|---|
| Context continuation | PASS |
| Pronoun resolution | PASS |
| Entity resolution | PASS |
| Duplicate-name disambiguation | PASS |
| Location disambiguation | PASS |
| Phone disambiguation | PASS |
| Payment direction | PASS |
| Date context | PASS |
| Save persistence | PASS (unit, in-memory books) |
| Business queries | PASS |
| Personal memory | PASS (the existing `KaiPrivateMemory`) |
| Tamil / Tanglish | PASS |
| Voice / text parity | PASS (one pipeline: `KaiSpokenWords.normalize` → the same `KaiAgent.ask`) |
| Pixel 8 | **BLOCKED** (no Android build or device in this cloud environment) |

## Root causes (each reproduced with a probe before changing code)

1. **A draft overwrote the payment's direction.** After the draft card, `prepared()` stored the cash-flow direction ("OUT" for a Credit entry). So "avan eppa tharuvaan?" read the payment as payable. Also, a due date that was already given was answered with "record-la illa".
2. **One name was treated as one person.** Same-named records showed as three identical buttons ("Lokesh · ₹0"). `PartyMatch` had no town or address, so nothing could tell them apart.
3. **"avanukku" was read as a name ("Avanuk").** There was no reference resolution, and the Business Brain only received the raw words.
4. **Two people, one "avan".** "Kumar and Ramesh …" answered only Ramesh, and the later "avan" guessed Ramesh.
5. **"Colgate 20 pieces irukku" was treated as a stock-in question.** Also, "adhu low-aa?" was answered in English, because short Tanglish was detected as English.
6. **Information given in pieces was lost.** "Mahesh" → learner question, "3000" → "clear-ah sollunga", "enakku tharanum" → the collections list.
7. **Phone and speech-to-text gaps.** "Lokesh 9876543210-ku" got the wrong direction (a number with a dative ending was not seen as the payee). "tharan" / "vangan" (clipped speech) were not understood.

## What changed (existing architecture reused)

**One conversation state.** New fields were added to the existing `KaiConversationState`:
- `focusPartyId`: the exact record being talked about
- `mentionedPeople`: who was just named
- `entityChoice`: which records Kai asked the owner to pick from
- `referentQuestion`: the question waiting for "Kumar-aa Ramesh-aa?"
- `fragmentPerson` / `fragmentAmount`: pieces of a payment said one message at a time

`KaiStatedPayment` also carries `partyId` + `label`. No new brain, no new database, no LLM.

**`KaiEntityResolver` (new, pure functions, holds no state).** Its signal order is:
1. phone number
2. exact name + town, or address / shop words
3. the record already in the conversation
4. otherwise, ask

How it behaves:
- It only sees what `tools.parties(name)` returns. That is the books' search for that name, in the signed-in business only.
- Similar names (Kumaran / Kumar, Lokeshwaran / Lokesh) are never matched.
- It asks: "Owner, Lokesh-nu rendu records irukku. Chennai Lokesh-aa illa Nagapattinam Lokesh-aa?"
- The owner's answer ("Nagapattinam", a phone number, or "rendavadhu") continues the same payment.

**`PartyMatch` gains `city` and `details`** (address + notes), both defaulting to null. `AppKaiTools.parties` fills them from the existing `PartyEntity` fields. There is no schema change.

**Reference resolution.**
- "avan / avar / avanga / avanukku / andha customer / same person …" are rewritten to the person in the conversation, keeping the case ending ("avanukku" → "Lokesh-ku").
- After that, every existing step reads one plain sentence: payment, question, reminder, call. The Business Brain also receives the resolved words.
- If two people were just named, Kai asks "Kumar-aa Ramesh-aa Owner?".
- If nobody is in the conversation, nothing is invented.

**Statements about an exact record:**
- "Lokesh Nagapattinam-ku 500 tharanum", "Lokesh Sri Vinayaga Hardware-ku …", "Lokesh 9876543210-ku …" resolve at the statement.
- The draft uses that record's id, and the card says "Nagapattinam Lokesh — ₹500".
- The existing `payment()` path (for example "Lokesh Trichy-ku 500 kuduthen") uses the same resolver and town labels.

**"X pathi pesuren"** sets the focus record. A later "avanukku 500 tharanum" goes to that record.

**Follow-ups:**
- They keep the stated payment's own direction.
- A due date already given is said back, with "Innum save pannala" until the books confirm the save.
- After a save, the saved date is said back.

**Pieces:** "Mahesh" → "3000" → "enakku tharanum" → "next month" combine into one stated payment. Pieces expire after a few turns.

**Shelf statement:** "Colgate 20 pieces irukku" is compared with the books (nothing is written), and Colgate becomes the "adhu".

**Natural wording:**
- "Seri Owner, Kumar kitta irundhu ₹3,000 collect pannanum. Due date eppa?"
- The words "collect" / "pay" / "Due date" are kept, because existing tests check for them.

**Direction:** a phone number with a dative ending ("9876543210-ku") marks the payee. The clipped spoken verbs tharan / kudukan / vangan are understood.

**Unchanged:** the safety order (understand → draft → confirm → engine → verify) is the same. A save is still only claimed after the engine's Done.

## Privacy and performance

- Entity resolution only calls the existing business-scoped `tools.parties(name)`. It does not load all customers.
- The existing conversation reset on a business/owner change also clears the new fields. There is a test for this: another business never sees A's conversation.
- Logs add only a count and a name ("entity: 3 records named Lokesh"), never the conversation.

## Known limits (honest)

- **An unknown name with a town can be misread.** For a person not in the books, "Lokeshwaran Nagapattinam-ku" takes the dative word ("Nagapattinam") as the name, because Kai has no town list for people not in the books. A known name with a town works.
- **Lone names must be typed with a capital letter.** A single typed word is taken as a name only if it is capitalised or is a known party. A lowercase unknown word still goes to the existing learner question.
- **Business-name matching uses the record's address / notes text.** There is no separate "shop name" field on a party.
- **AppKaiTools.kt was not compiled here** (it is Android-only). The change is two named arguments that read existing `PartyEntity` fields.

## Pixel 8 steps (owner, Kai Chat)

1. "Kumar enaku 3000 tharanum" → "eppa?" → "10" → "next month" → "note panniko" → "avan eppa tharuvaan?" — expect Kumar ₹3,000, adutha maasam 10, "Innum save pannala".
2. "Colgate stock evlo?" → "adhu low-aa?" → "athula 5 pochu" — expect a Colgate stock-out draft.
3. "Mahesh enaku 2000 tharanum" → "saptiya?" → "seri" → "eppa?" — expect the Mahesh payment context.
4. "Mahesh enaku 2000 tharanum" → "save panniko" → "seri" → restart the app → "Mahesh enaku evlo tharanum?" — expect ₹2,000.
5. Add three suppliers named Lokesh with towns Chennai, Nagapattinam, Trichy. Say "Lokesh Nagapattinam-ku 500 tharanum" — expect "Nagapattinam Lokesh".
6. "Lokesh-ku 500 tharanum" → expect the which-Lokesh question → "Nagapattinam" → the flow continues.

## Files changed

- `app/src/main/java/com/shopai/app/brain/chat/KaiAgent.kt`
- `app/src/main/java/com/shopai/app/brain/chat/KaiEntityResolver.kt` (new)
- `app/src/main/java/com/shopai/app/brain/tools/KaiTools.kt`: `PartyMatch.city` / `details`
- `app/src/main/java/com/shopai/app/brain/tools/KaiPaymentDirection.kt`
- `app/src/main/java/com/shopai/app/data/kai/AppKaiTools.kt`
- `app/src/test/java/com/shopai/app/brain/chat/KaiOwnerUnderstandingTest.kt` (new, 67 tests)

## Diff summary

```
 brain/chat/KaiAgent.kt                 | 387 ++++++++++++++++++---
 brain/tools/KaiPaymentDirection.kt     |   9 +-
 brain/tools/KaiTools.kt                |   8 +-
 data/kai/AppKaiTools.kt                |   4 +-
 + KaiEntityResolver.kt (new), KaiOwnerUnderstandingTest.kt (new)
```
