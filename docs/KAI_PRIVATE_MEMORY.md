# Kai — Private shop language

Kai learns how EACH shop owner speaks — without ever mixing it into the
global Kai, into another owner, or into another business. All learning
happens inline in Kai Chat (there is no separate language screen).

```
GLOBAL KAI CORE (same for everyone)          PRIVATE OWNER MEMORY (one per owner, per business)
KaiCommands · KaiChatUnderstanding ·         brain/memory/KaiPrivateMemory
KaiCalculator · KaiStock · reminders ·       data/kai/KaiMemoryFileStore → kai_memory/<businessId>__<ownerId>.json
books / inventory / reminder engines
```

## Pipeline (voice and text are the same)

```
Voice → speech-to-text ┐
Text ──────────────────┴→ KaiChatSession.send(text, voice)
                          → KaiAgent.ask
                            1. KaiMemoryAssistant.before  — answer to Kai's question / teach / forget
                            2. KaiMemoryAssistant.apply   — the business's confirmed words → global words
                            3. stock in / out · payments · reminders · questions (global core)
                            4. unknown phrase in an action-like message → Kai ASKS (never guesses)
                          → draft → owner Confirm → existing engine
Reply: text in Kai Chat; spoken too when the owner spoke (🎙).
```

Pesunga (the voice screen) sends shop-language messages to the same
`KaiChatSession`, so teaching by voice works in text and the other way round,
and the conversation continues in Kai Chat.

## Rules

- Every memory has a business id and an owner id; an owner loads only their
  own file. Logging out closes it; another login (another owner on the same
  phone) opens only that owner's memory.
- A phrase is used only when OWNER_CONFIRMED (Kai asked and the owner said
  yes) or OWNER_CREATED ("thooki kudu na payment out", "from now on …").
  UNKNOWN → OBSERVED → SUGGESTED → OWNER_CONFIRMED; never straight to confirmed.
- Priority: this conversation's instruction ("today pottudu means stock out")
  → the owner's memory → global rules → ask.
- Nicknames are stored with the product / customer / supplier ID.
- Memory changes only the words. A payment or stock change is still a draft
  that the owner confirms; nothing in memory touches balances, GST, stock or rules.
- A similar spelling ("thooki kudunga") is asked about, then saved as a variant.

## Owner commands

| Owner says | Kai does |
|---|---|
| `thooki kudu na payment out` / `From now on X means Y` | remembers (this shop) |
| `Illai, pottudu-na stock IN` | corrects |
| `Today pottudu means stock out` | uses it now, asks whether to keep it |
| `thooki kudu marandhudu` / `forget X` / `Idha marandhudu` | forgets |
| `Idha remember pannu` | keeps what was just suggested |
| `Enna enna kathukitta?` | shows what it learned |
| `'ramba' nu enna meaning?` | says the meaning taught, or asks: the owner answers `romba nu sonna mari` → Kai asks "save pannava?" → saved after yes |

Everything is managed inline in Kai Chat by talking (teach, correct, forget,
show). A word whose meaning is a money / stock action ("kuduthen maadhiri")
is never saved as a plain word — it gets the action question and its own
confirmation.

## Tests

`app/src/test/java/com/shopai/app/brain/chat/KaiPrivateMemoryTest.kt`,
`app/src/test/java/com/shopai/app/brain/chat/KaiProductionFixesTest.kt`
