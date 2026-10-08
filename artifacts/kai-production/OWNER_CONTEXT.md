# Owner context — references, memory, conversation

## Scope

| Kind | Stored in | Scope | Survives restart |
|---|---|---|---|
| Owner references ("Kumar Anna" → record c1) | `KaiPrivateMemory` `CUSTOMER_ALIAS` / `SUPPLIER_ALIAS` with `referenceEntityId` | business + owner | yes (file store per business/owner) |
| Owner words ("potti" = box) | `KaiPrivateMemory` (WORD / UNIT_ALIAS / …) | business, or owner-wide when said so | yes |
| Conversation (who was just discussed, last list, open draft, pending question) | `KaiConversationState`, `KaiBusinessBrain` fields | this chat | no — by design (nothing is written without Confirm) |

Priority when they meet: **this business > this owner > Kai's global understanding**. Another business never sees a reference or word.

## Entity resolution order (one resolver for chat text and voice)

1. exact canonical id (a pick / button / an id already on the plan)
2. the owner's established reference said in this message ("Kumar House" → c2)
3. phone number
4. place / shop words that only one record has ("Chennai Lokesh")
5. the only record with that name
6. the record being talked about — for "avan / avar / avanga" and continuations; **not** for a bare name the owner said while they
   keep same-named records apart by references
7. otherwise ASK ("Owner, Kumar-nu rendu per irukkaanga. 1. Kumar Anna — ₹500; 2. Kumar House — ₹600") — never a guess

`KaiEntityResolver.Result.confidence`: One → CONFIRMED, Many → AMBIGUOUS, None → UNKNOWN. Only CONFIRMED may carry money.

## Behaviours (all JVM-tested in `KaiOwnerReferenceTest`)

| Owner says | Kai |
|---|---|
| "Kumar Anna-ku 500 enaku tharanum" | c1 (reference), draft "Kumar Anna — ₹500", saved on Confirm |
| "Kumar evlo tharanum?" (two Kumars, both referenced) | asks: "1. Kumar Anna — ₹500; 2. Kumar House — ₹600" — even right after Kumar House was discussed |
| "Kumar House" (answer) | "Kumar House ungalukku ₹600 tharanum owner." |
| "Kumar House enaku 400 tharanum" → Confirm | new entry on c2 → "Ippo balance ₹1,000" (from the books) |
| "Rendu Kumar-oda balance sollu" / "Both Kumar details sollu" | each record on its own line, no total |
| "rendu perum total evlo?" | each record + "2 perum serthu ₹1,100" |
| "Avan innum 200 tharanum" (after Kumar House) | c2 (context, no name said) |
| "Kumar Mama-na Sri Stores Kumar" | learned: `Kumar Mama` = Kumar (Sri Stores) — only when one record fits |
| "Kumar Bro-na Kumar" | asks which; the pick is learned |
| "Illai bro, Kumar Anna vera Kumar" | reference dropped, asks which, the pick is learned |
| restart (new memory over the same store) | the reference still pins its record |
| another business | the reference is unknown there → asks |

## Personal memory ("potti")

Taught → Save → used ("Colgate 2 potti vandhudhu" → 24 pieces); not saved on "Not now"; another business never knows it; kept after
restart; meaning changed ("potti meaning change pannu" → "carton" → 96 pieces). Matrix `memory` (33 rows) and journey 7.
