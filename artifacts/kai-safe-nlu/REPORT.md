# Kai — Safe Natural Language Understanding Upgrade: Report

- Branch: `feature/kai-safe-natural-language`, from `origin/main` @ b4a5660.
- Nothing has been committed, pushed or merged. main and master were not touched.

## 1. Baseline result
- 539 / 539 JVM unit tests pass across 47 suites. There are no pre-existing test failures.
- 17 Android/Robolectric suites are **BLOCKED** here (no Android SDK).
- One pre-existing **context bug** was found ("athula 5 pochu").
- 7 natural-language failures were reproduced. Details: BASELINE.md.

## 2. Current routing architecture
- Text and Kai-Chat voice go to `KaiChatSession.send` → **one** `KaiAgent.ask`.
- Pesunga voice goes to `KaiIntents.classify`. Only "Kai" intents, CHAT included, are sent to the same KaiAgent; everything else goes to the older `KaiBrain.hear`.
- Small talk is `KaiSmallTalk`. It is reached only after every business route has declined the message. Details: ARCHITECTURE.md.

## 3. Root cause of the natural-language failures
Kai **never went blank**. It gave wrong answers, for three reasons:
1. `KaiSmallTalk` matched exact spellings only. So "saptia", "saptya", "saaptiyaa" and "un name enna" fell through to Personal Learning, which asked "`saptia` nu sonnadhu enna meaning-la?".
2. Rule order: "saptya Kai?" matched only the HELLO rule (through "kai").
3. Tamil-script forms were missing. Also, `KaiLexicon` lacked Tamil pronouns, so the learner's "X yaaru?" path treated "நீ யாரு?" as "what is நீ?".

The status question ("tired ah iruka?") was a different problem: it was taken as the owner being tired.

## 4. Text / voice difference
- Kai Chat text and voice: identical path.
- Pesunga voice: a casual spelling that `KaiSmallTalk` did not recognise was **not** sent to KaiAgent. It went to the older voice-entry brain.
- Because Pesunga's gate uses the same `KaiSmallTalk.kindOf`, making `kindOf` spelling-tolerant fixes both paths at once. **No STT or screen code changed.**

## 5. Changes made (3 production files, +80 / −19 lines)

| File | Change |
|---|---|
| `brain/chat/KaiSmallTalk.kt` | Each kind keeps its exact regex. It gains an optional **spelling family** matched on an internal casual key: lower case, doubled letters once, "-ya"/"-ia" → "-iya". New kinds: **NAME** ("En peru Kai Owner 😊 Naan unga business assistant.") and **STATUS** ("Konjam busy dhaan Owner 😄 …"). Tamil-script forms were added for eat / name / who. |
| `brain/chat/KaiLexicon.kt` | 9 Tamil-script pronouns (நான் நீ நீங்க நீங்கள் உன் உங்க உங்கள் என் எனக்கு), so the learner doesn't try to learn them. |
| `brain/chat/KaiAgent.kt` | `stockChange`: "athula / adhu / idhula …" refers to the last product, **only** when no product and no person is named. The result is still a draft that needs Confirm. |
| `test/.../KaiNaturalLanguageTest.kt` | New: 57 + 18 tests. |

## 6. Why the changes are safe
- Small talk is still reached **only** after payment, stock, reminder, bill, call, calculator and question routes all decline the message. The existing guards are unchanged: digits, business words, more than 8 words.
- The casual key is used only inside `KaiSmallTalk` for matching. It is never returned, logged or passed on, so names, amounts, products, dates and units are never re-spelled. There is a test for this.
- The exact regexes and their order are kept, so every old match still gives the same kind. The existing 539 tests are unchanged and green.
- Nothing in the small-talk path can write. All writes are still drafts confirmed only by `KaiAgent.act`.
- Each reference-word stock rule is narrow and resolves to an existing product, and it is draft-only.

## 7. Existing engines reused
KaiAgent, KaiCommands, KaiSmallTalk, KaiSpokenWords (Tamil STT normaliser), KaiMemoryAssistant / Personal Learning, KaiConversationState, KaiStock, KaiUnits, KaiReminderAssistant, KaiCalculator, KaiBusinessBrain, KaiIntents (Pesunga gate) and the confirmation cards. **No duplicate brain.**

## 8. New semantic layer
There is no new module. The "layer" is the existing `KaiSmallTalk`, made spelling-tolerant.
- It answers in the owner's language: Tamil script, Tanglish or English.
- Internal kind names are never shown.
- The conversational kinds are now ATE, NAME, WHO, STATUS, HOW_ARE_YOU, DOING, BUSY, TIRED, greetings, HELLO, THANKS, PRAISE, OK and BYE.

## 9. Test results
- New: **75 / 75 PASS** (57 meaning + parity, 18 full-agent). The matrix is in TEST_MATRIX.md.

## 10. Pixel 8 results
**BLOCKED.** There is no emulator or Android SDK in this environment, so I am not claiming any device result.

Owner checklist for the Pixel 8:
1. Install from this branch: `./gradlew :app:installDebug`.
2. In Kai Chat, type each of these and confirm Kai gives a natural, non-blank reply:
   - saptiya?, saptia?, saptya?, saaptiya?
   - un name enna?, nee yaaru?
   - epdi iruka Kai?
   - super Kai, thanks Kai
3. Say the same phrases on the Kai Chat mic and on Pesunga. The reply should match the typed one.
4. Regression, each of which must still show a **draft** with Confirm:
   - Kumar-ku 5000 kuduthen
   - Colgate 2 box add
   - Kumar-ku 10 minutes kalichi call pannanum (confirm card)
5. These must still answer as before:
   - 25000 la 18% GST evlo? → "GST ₹4,500. Total ₹29,500."
   - Kumar enakku 20000 tharanum → eppa? (due-date question)
   - Colgate stock 20 vandhiruku → Confirm → athula 5 pochu → Colgate stock-out draft
   - 'potti' na 1 box → Save → Colgate 2 potti vandhudhu → 24 pieces draft
6. Run `./gradlew :app:testDebugUnitTest` to cover the 17 blocked suites.

## 11. Regression results
614 / 614 PASS: the existing 539 with identical per-suite counts, plus the 75 new tests. No existing test was modified or deleted.

## 12. Known limitations
- Android build, the Android unit tests and the device were not run here (see §10).
- Voice depends on the STT engine. A transcript outside the spelling families still falls back to the existing "clear-ah sollunga" or the learner's question. It never goes blank and never writes.
- A message sent while Kai is still "thinking" (about 650 ms) is ignored by `KaiChatScreen` (FAILURES P8). This is unchanged.
- The eating-reply and unknown-reply wording were kept because existing tests lock them. Your decision is in FAILURES.md.

## 13. Dependencies added
**None.**

## 14. API / LLM usage
- **None.** The fix is deterministic. No network call is added and no conversation is sent anywhere.
- An LLM fallback was not needed.

## 15. Database changes
**None.** No schema, table or migration. No financial or stock record is touched.

## 16. Performance impact
- Small talk is checked only for messages that already reached the final `Question` branch.
- It adds one short string normalisation and a handful of precompiled regexes, about microseconds per message.
- No network or LLM call is added.

## Counts

| PASS | FAIL | BLOCKED | INCONCLUSIVE |
|---|---|---|---|
| **614** unit tests | **0** | **17** Android unit-test suites + **16** Pixel 8 checks | **1** (the Android Gradle build of the app was not compiled with AGP here; the changed files are pure Kotlin and compiled in the JVM harness) |

## Acceptance criteria

| # | Criterion | Status |
|---|---|---|
| 1 | Existing features still work | ✅ unit level · device BLOCKED |
| 2 | Existing tests remain green | ✅ 539 / 539 |
| 3 | New NL tests pass | ✅ 75 / 75 |
| 4 | Text and voice reach the same intent | ✅ (`classify` = CHAT; Tamil STT = typed Tanglish) |
| 5 | saptiya / saptia / saptya / saaptiya | ✅ |
| 6 | un name enna / un peru enna / nee yaaru | ✅ |
| 7 | Unknown messages never silent | ✅ agent level (the UI "thinking" window is a known limit) |
| 8 | Ambiguous money never auto-executes | ✅ |
| 9 | Context preserved | ✅ (and "athula" fixed) |
| 10 | Personal learning works | ✅ |
| 11 | No duplicate brain | ✅ |
| 12 | No unnecessary LLM calls | ✅ (none) |
| 13 | No business engine replaced | ✅ |
| 14 | No mutation without confirmation | ✅ |
