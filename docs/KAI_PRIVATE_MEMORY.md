# Kai — Private shop language

Kai learns how EACH shop owner speaks — without ever mixing it into the
global Kai or into another business.

```
GLOBAL KAI CORE (same for everyone)          PRIVATE BUSINESS MEMORY (one per business)
KaiCommands · KaiChatUnderstanding ·         brain/memory/KaiPrivateMemory
KaiCalculator · KaiStock · reminders ·       data/kai/KaiMemoryFileStore → kai_memory/<businessId>.json
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

- Every memory has a business id; a business loads only its own file.
  Logging out closes it; another login opens only that business's memory.
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

Manage everything in **Kai Chat → My Kai language** (add, edit, switch off, delete).

## Tests

`app/src/test/java/com/shopai/app/brain/chat/KaiPrivateMemoryTest.kt`
