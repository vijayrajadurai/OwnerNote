# Kai Safe NLU: Architecture Audit (from the actual code)

## 1. Where text enters Kai
`KaiChatScreen.send(text, voice = false)` calls `KaiChatSession.send(text, voice)` (`ui/kaichat/KaiChatSession.kt:66`), which calls `KaiAgent.ask(text)` (`brain/chat/KaiAgent.kt`).

## 2. Where voice transcripts enter Kai
- **Kai Chat mic:**
  - `DeviceSpeechRecognizer` (Android `SpeechRecognizer`, `ta-IN`) produces the transcript.
  - The transcript goes to the **same** `send(spoken, voice = true)` → `KaiAgent.ask`.
  - Only how the reply is delivered differs: it is also spoken via `KaiSpeech.forReply` + `KaiBrain.say`.
- **Pesunga (VoiceEntryScreen):**
  - The transcript first goes to `KaiIntents.classify(...)`.
  - It reaches `container.kaiChat.send(...)` (the same KaiAgent) **only** if the owner's own words are involved, or the intent is in `KaiIntents.handledByKai`. CHAT is one of those intents.
  - Otherwise it goes to the older voice-entry brain `KaiBrain.hear`.
  - For small talk, CHAT is decided by `KaiSmallTalk.kindOf(text)`.

## 3. Do both use the same KaiAgent?
- Kai Chat: **yes**, text and voice both use it.
- Pesunga: **yes, but only for small talk that `KaiSmallTalk.kindOf` recognises.** A variant spelling that was not recognised ("saptia", "un name enna") did **not** reach KaiAgent on Pesunga. It went to `KaiBrain.hear`. That is the text/voice difference.

## 4. Where exact keyword matching happens
- `KaiSmallTalk.rules`: regexes per kind, using exact spellings.
- `KaiCommands.route`: deterministic command grammar (payment / call / calculate / stock / reminder).
- `KaiStock.understand`, `KaiReminderUnderstanding`, `KaiChatUnderstanding` (business questions), `KaiIntents.isBillScan`.
- `KaiSpokenWords.normalize`: maps Tamil-script STT words to Tanglish (shared voice/text normaliser).

## 5. Where casual conversation is handled
`KaiAgent.conversation()` calls `KaiSmallTalk.reply()`. It is reached **only** in the `KaiCommand.Question` branch, after every business route has declined the message. `kindOf` also refuses messages that have digits, a business word, or more than 8 words.

## 6. What happens to unknown input
In the `Question` branch, in this order:
1. `conversation()`;
2. `learner.unknown(...)`;
3. `questionOrLearn`:
   - business question → `KaiBusinessBrain`;
   - if the message has exactly one word Kai doesn't know, the learner asks: "`X` nu sonnadhu enna meaning-la?";
   - otherwise the reply is "Owner, konjam clear-ah sollunga."

So **the agent never returned blank** in any probed case. The failures were *wrong* answers. Kai tried to "learn" a casual word, or matched the wrong small-talk kind.

## 7. Where context is stored
- `KaiAgent.conversationState` (`KaiConversationState`): pending draft, pending question (due date), last person/amount/product, topic.
- Plus agent fields: `plans`, `stockPlans`, `lastProduct`, `incompletePayment`, `stockQuestion`, `unitQuestion`, `lastDraft`.

## 8. Where personal learning intercepts messages
In `KaiAgent.ask`, in this order:
1. `learner.correctionAfter` (just after a draft);
2. `learner.before` (answers to Kai's question, teaching, "X na enna?", "X yaaru?" via `shortQuestion`);
3. `learner.apply` (owner/business-scoped words → global words);
4. later `learner.unknownUnit` and `learner.unknown` / `unknownWord`.

`shortQuestion` skips words found in `KaiLexicon`. The Tanglish "nee" was in it, but the Tamil-script "நீ" was not. That is why "நீ யாரு?" was treated as "what is நீ?".

## 9. Where business actions are classified
`KaiAgent.ask`, in priority order:

pending draft (correction / cancel)
→ pending due date
→ learner
→ contextual receivable
→ product follow-up
→ reminder / stock / payment continuation
→ Morning routine / Morning Work
→ reminder
→ stock (`stockChange`)
→ bill scan
→ `KaiCommands.route`, which returns Calculate / Payment / Reminder / Call / Stock / Money / Question.

## 10. Where confirmation happens
- Every write is a **draft with a card**: ConfirmPlan, ConfirmStock, ConfirmReminder.
- It is executed only by `KaiAgent.act(...)` from a button, through `KaiTools.confirm` / `changeStock` / `createReminder`.
- Nothing in the small-talk path can reach `act`.

## The new layer (where it sits)

```
TEXT ─► KaiChatSession.send ─┐
VOICE (Kai Chat) ─► STT ─────┼─► KaiAgent.ask ─► [all existing business routes, unchanged]
VOICE (Pesunga) ─► STT ─► KaiIntents.classify ─(CHAT)─┘                │ only if every route says "Question"
                                   ▲                                   ▼
                                   └──── KaiSmallTalk.kindOf ◄── conversation()
                                          (existing; now spelling-tolerant)
```

- **No new brain.** The "semantic layer" is the existing `KaiSmallTalk`. Each kind keeps its exact `said` regex and gains an optional **family** pattern.
- The family pattern is matched against a **casual key**: lower case, repeated letters collapsed, "-ya"/"-ia" written as "-iya".
- The key is used **only for comparison inside `KaiSmallTalk`**. It is never returned, logged or passed to any engine.
- Business messages never get there. The existing guards are unchanged: digits, business words, more than 8 words, and the fact that `conversation()` only runs after every business route has declined the message.
