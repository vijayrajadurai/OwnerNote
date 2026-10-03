# Kai — personal owner learning (private business memory)

Kai learns how THIS owner talks. The global Kai core (calculator, reminders, stock,
payments, bill scan, business questions, accounting rules) is unchanged; the owner's
words are a layer in front of it.

## Pipeline (voice and text are the same path)

```
Voice → STT text ─┐
Typed text ───────┴→ KaiAgent.ask
   → KaiMemoryAssistant.before   (answers to Kai's question, teaching, forget/change/list)
   → KaiMemoryAssistant.apply    (owner's CONFIRMED words → words the core reads; context-checked)
   → KaiSpokenWords.normalize    (Tamil script → Tanglish)
   → correction used? → "… payment illa-nu neenga sollirukeenga" (nothing recorded)
   → unknown unit after a quantity? → "Owner, `petti` na box-ah? Packet-ah?"
   → existing router: reminder / stock / bill / call / money / question
   → existing DRAFT → CONFIRM → engine (learning never bypasses it)
```

## Storage (existing store, no new database)

`KaiMemory` records in the owner's `KaiMemoryBook` (`kai_memory/<business>__<owner>.json`,
`KaiMemoryFileStore`): one file per (business, owner) — every load / save names both, and
`onlyOwn()` filters again on the way in and out. Owner-wide words (said with "ella
kadaiyilum" / "all my shops") live in the owner's book under `KaiPrivateMemory.OWNER_SCOPE`.
Lookup order: **business memory → owner-wide memory → global Kai** (never reversed).

Fields: id, ownerId, businessId, triggerPhrase (term), normalizedPhrase, meaningType /
meaningValue (meaning), `category` (WORD, PHRASE, UNIT_ALIAS, REMINDER_TERM, PRODUCT_ALIAS,
CUSTOMER_ALIAS, SUPPLIER_ALIAS, PAYMENT_TERM, STOCK_TERM, BUSINESS_TERM), examples,
sourceText (the teaching sentence only), confidence, status, learningState, usageCount,
createdAt, updatedAt, lastUsedAt, correctedFrom.

States: UNKNOWN → OBSERVED → SUGGESTED (= learned, pending confirmation) →
OWNER_CONFIRMED; CORRECTED (confirmed again with a new meaning; `correctedFrom` keeps the
old one as history); DISABLED / DELETED. Only OWNER_CONFIRMED / CORRECTED are used.

## Teaching (always confirmed first)

| Owner says | Kai |
|---|---|
| Kai, enga kadaiyila 'potti' na 1 box | Sari Owner 👍 Indha business-ku `potti` = 1 box-nu purinjukitten. Save pannava? [Save] [Not now] |
| 'பொட்டி'ன்னா box | சரி ஓனர் 👍 இந்த கடைக்கு `பொட்டி` = 1 box-னு புரிஞ்சுகிட்டேன். சேமிக்கட்டுமா? |
| maal na stock / naan 'maal' nu sonna stock meaning | Seri Owner 😄 `maal` = `stock` nu save pannava? |
| Enga kadaiyila packet-ku 'cover' nu solvom | `cover` = packet … |
| Here we call cartons 'boxes' | Okay Owner 👍 For this business, `boxes` = 1 carton. Save it? |
| 'kaasu pottaan' means customer payment received | `kaasu pottaan` = Payment In — idhu business meaning-aa save pannava? |
| 'konjam nerathula' na 10 minutes | `konjam nerathula` = 10 minutes-nu … remember pannava? |
| anna na Kumar / main supplier na Sri Lakshmi Traders | nickname → the record's id |
| Colgate 2 petti vandhiruku (petti unknown) | Owner, `petti` na box-ah? Packet-ah? Vera meaning-ah? → Box → Save? → aama → draft |

"From now on … / inimel … / … remember pannu" is itself the owner's instruction and is
saved at once (as before). Every other teaching waits for Save / "aama".

## Correct / forget / list

- "potti meaning change pannu" → "`potti`-ku new meaning enna Owner?" → "carton" → update pannava? → CORRECTED.
- "potti meaning box illa carton" → update pannava?
- "Illai Kai, avan bill mattum kuduthaan" right after a draft → the draft is dropped →
  "`bill kuduthaan` = bill handed over, payment illa-nu update pannava?" → next time
  "Kumar bill kuduthaan 300" records nothing and Kai says why.
- "potti meaning forget" / "potti marandhudu" / "Idha marandhudu" / "Don't remember this anymore"
  → "Sure Owner. `potti` memory remove pannava?" [Forget] [Cancel].
- "Kai naan enna teach panniruken?" → numbered list of this owner's confirmed words with
  [Edit] [Forget] [Keep].
- "potti na enna?" / "anna yaaru?" → only this owner's meaning; another owner gets
  "Enakku theriyala Owner 🙂 …".

## Safety

- Unknown / ambiguous words that could change money, stock, balances or records are asked,
  never guessed; nothing is drafted from them.
- A learned meaning only changes words; stock / payment still go draft → Confirm → engine.
- Context: a learned stock / money word right after a time ("2 mani aachu") is not used;
  "2 mani pochu" is never a stock change.
- Action log: `LEARN_PERSONAL_TERM`, result "potti = box", CONFIRMED, reference = memory id,
  input = null (no conversation text stored).

## Tests

`app/src/test/java/com/shopai/app/brain/chat/KaiPersonalLearningTest.kt` (the 15 spec cases +
correction-after-draft, context, reminder term, action log), and the updated
`KaiPrivateMemoryTest.kt` (teaching now confirmed before saving).
