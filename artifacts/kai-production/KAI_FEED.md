# Feeding Kai — no outside model, no paid API

Kai understands owners through its own rules and its own word feed. Nothing leaves the phone and nothing is paid for.
To make Kai understand more, **feed it** — two files, plain lines.

## 1. Words — `app/src/main/java/com/shopai/app/brain/tools/KaiFeed.kt`

One entry per line inside the list it belongs to. Nothing here removes a word Kai already knew.

| List | Means | Example line |
|---|---|---|
| `receivedWords` | the owner got money ("Kumar 500 ___") | `anuppichan` |
| `paidWords` | the owner paid ("ABC-ku 500 ___") | `kattinen` |
| `stockInWords` | stock came in | `irakkinen` |
| `stockOutWords` | stock went out (sold / damaged) | `udanjiduchu` |
| `productNames` | local name = shop's product name | `arisi = rice` |
| `units` | unit word = Kai's unit | `pottalam = PACKET` |
| `notNames` | never a person's name | `yeppa` |
| `everydayWords` | known words (Kai won't ask what they mean) | `settle` |

An owner can also teach Kai on the phone ("petti na box", "paste na Colgate") — that stays with that owner only.

## 2. Owner lines — `app/src/test/resources/kai/owner-lines.txt`

What an owner says and what must happen, one line each:

```
ENTRY  | Kumar 1000 anuppichan        | IN Kumar 1000
STOCK  | ennai 1 box vandhuchu        | Oil 252
REMIND | innaiku 4 manikku ABC Traders ku call panna remind pannu | 2026-10-08 16:00 ABC Traders
ASK    | Kumar evlo baaki             | ₹4,000
```

`KaiOwnerSweepTest` plays every line on a fresh shop and checks the books / stock / reminders (never Kai's words alone; nothing
saved before Confirm). A line that fails is a bug to fix or a word to add to `KaiFeed.kt` — never a line to delete.

**The loop:** owner says something Kai gets wrong → add that line to `owner-lines.txt` → add the missing word to `KaiFeed.kt`
(or fix the rule) → the test passes → it can never break again.

Today: 214 owner lines (193 in the test + 21 in the feed file), all passing.

## 3. Spoken words — `app/src/main/java/com/shopai/app/brain/tools/KaiSpokenWords.kt`

Kai Chat's mic writes Tamil script. Every word Kai's rules read in Tanglish needs its spoken form here
(`"அனுப்பியாச்சு" to "anuppiachu"`). Every owner line is also played as the mic writes it (`KaiMicForm`, in the tests):
a word with no spoken form shows up there as a failing `[mic: …]` line. See `CLAUDE.md`.

