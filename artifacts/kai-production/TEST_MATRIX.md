# Test matrix

All numbers below are from the JVM harness (Kotlin + JUnit, the app's `brain/` code compiled as-is; the books / tools / memory store at
the edge are fakes that change only on Confirm). Android code is not compiled here. **Pixel 8 = BLOCKED.**

## How it counts

`KaiProductionMatrixTest` runs **one JUnit method per category**. Each method runs every row of its table as a separate case on a fresh
shop and reports every failing row (not just the first), then asserts the row count. So: **25 JUnit methods** in that file
(16 categories + 9 journey tests) carry **667 matrix cases + 8 journeys**. The suite total below counts JUnit methods, not rows.

## Matrix (`KaiProductionMatrixTest`) — final run

| Category | Target | Cases | Passed | What each row asserts |
|---|---|---|---|---|
| payment language | 100 | 100 | 100 | 20 phrasings × 5 customers → draft PAYMENT_IN, right record id, right amount, nothing saved before Confirm, no save claim |
| payment direction | 50 | 54 | 54 | receivable statements → CREDIT_GIVEN; payable → DEBIT_TAKEN; owner paid supplier → PAYMENT_OUT |
| entity | 50 | 50 | 50 | duplicate Lokesh asked / place-resolved / picked; "Kumaran" ≠ Kumar; unknown names invent nothing; bare duplicate write saves nothing; case / spacing |
| ledger aggregation | 50 | 50 | 50 | balance = entries − payments for 7 parties × phrasings; settled party; total receivable ₹16,973.10; total payable ₹10,500; count |
| persistence | 50 | 50 | 50 | credit / payment saved → books changed by exactly that amount → read back in reply and later question; engine failure → no claim; restart keeps it |
| date | 40 | 40 | 40 | due-date answer (naalaikku, tomorrow, nalaiku, day after tomorrow, next Monday, 15th, October 20, 20 Oct) → the date given to the books on Confirm |
| reminder | 40 | 40 | 40 | not stored before Confirm; exact trigger time; store that loses it → no claim; no time → not stored |
| stock | 40 | 40 | 40 | stock questions (incl. "Colgate evlo irukku?", "How much Colgate in stock?"); stock in / out drafted, changed only on Confirm |
| memory | 30 | 33 | 33 | potti / dabba / petti taught → used; not saved on "Not now"; other business never knows; kept after restart |
| calculator | 30 | 30 | 30 | exact sums / GST / units; a sum never becomes a draft |
| context | 30 | 30 | 30 | "evlo?", "avan evlo tharanum?", "due eppa?", "avan 200 kuduthaan", "avan innum 300 tharanum" after a person; two named → asks |
| topic switch | 30 | 30 | 30 | an open payment draft survives stock / calculator / greeting detours; nothing saved; never moves to another person |
| negative | 30 | 30 | 30 | 30 incomplete / ambiguous / malformed inputs + "seri" → nothing saved, no save claim (incl. "-500", "0", "abc", unknown / duplicate names) |
| Tamil | 30 | 30 | 30 | Tamil-script names + questions / payments ("குமார் 500 குடுத்தான்") |
| Tanglish | 30 | 30 | 30 | Tanglish spellings ("evvalavu", "ewlo baaki", "rooba", "da") |
| mixed / STT | 30 | 30 | 30 | lower case, number words ("ainnooru", "five hundred"), "500 rs paid today", mixed script, English questions |
| **total** | **650** | **667** | **667** | |

## Journeys (`KaiProductionMatrixTest.j1…j8`)

| # | Journey | Result (JVM) | Not covered here |
|---|---|---|---|
| 1 | "Lokesh enakku 2000 tharanum" → confirm → ₹2,000 → restart → ₹2,000 | pass | — |
| 2 | existing ₹2,000 → "Lokesh-ku 500 add pannu" → confirm → ₹2,500 → Collect summary → restart → ₹2,500 | pass (failed first: add-request answered the balance — fixed) | Collect *screen* (device) |
| 3 | "Kumar enakku 5000 tharanum" → due naalaikku → save → reminder → query | pass | notification ringing (device) |
| 4 | "Kumar 2000 kuduthutaan" → ₹5,000 → ₹3,000 → Collect summary → query → restart | pass | Collect *screen* |
| 5 | today's list → count/total → details → topic switch → details again | pass | — |
| 6 | duplicate Lokesh → clarification → "Chennai" → payment → save → query | pass (failed first: typed "Chennai" not understood; choices not spoken — fixed) | — |
| 7 | memory learn → confirm → use → correction → corrected meaning used | pass | — |
| 8 | payment draft → casual question → calculator → back → save | pass — "seri" after the calculator's answer saves the draft (owner's choice); `j8b`: when Kai's last reply asked something else ("which Lokesh?"), "seri" does not save and "confirm" does | — |

## Other new tests

`KaiOwnerReferenceTest` — 18 JUnit tests: the acceptance journey (Kumar Anna 500 / Kumar House 600 / asks / ₹600 / +400 → ₹1,000 from
the books), pinning, bare-name ask, pronoun context, payment via reference, rendu / both / total, read never writes, learning from
context, ask-then-learn, correction, restart, business scope, memory reports only said aliases, resolver confidence, reference words.

## Full suite

| Run | JUnit tests | Failures |
|---|---|---|
| Branch base e6ead81 (start of this work) | 1147 | 0 |
| Final (this branch) | **1190** (+18 owner-reference, +16 matrix categories, +9 journey tests) | **0** |

No existing test was deleted, skipped or weakened in this branch.
