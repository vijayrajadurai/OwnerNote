# Kai Safe NLU — Test Matrix

New file: `app/src/test/java/com/shopai/app/brain/chat/KaiNaturalLanguageTest.kt`
- `KaiNaturalLanguageTest` has **57** parameterised cases.
  - Each case checks the meaning (`KaiSmallTalk.kindOf`).
  - For every casual case, it also checks voice-screen parity: `KaiIntents.classify` returns **CHAT**, so the Pesunga voice screen sends it to the same KaiAgent.
- `KaiNaturalLanguageAgentTest` has **18** tests. They run through the full `KaiAgent`, with test fakes:
  - replies;
  - language;
  - context;
  - business priority;
  - no writes without Confirm.

Run in the JVM harness: **75 / 75 PASS**.

## Meaning (KaiNaturalLanguageTest) — 57

| Area | Inputs | Expected | Result |
|---|---|---|---|
| A identity | nee yaaru?, Kai nee yaaru?, neenga yaaru?, who are you?, who r u, நீ யாரு?, நீங்க யார்? | WHO | 7 PASS |
| B name | un name enna?, unga name enna?, un peru enna?, unga peru enna?, Kai un name enna, what is your name?, whats ur name, உன் பேர் என்ன?, உங்க பெயர் என்ன? | NAME | 9 PASS |
| C status | dei Kai inniku romba tired ah iruka?, busy ah irukiya?, are you tired? → STATUS; epdi iruka Kai?, eppadi irukeenga? → HOW_ARE_YOU | as listed | 5 PASS |
| D + L eating, spellings | saptiya?, saptia?, saptya?, saaptiya?, saaptiyaa?, saaptacha?, saaptingala?, saptingala?, saapiteengala?, kai saptiya?, nee saptiya?, saptya Kai?, did you eat?, have you eaten?, சாப்டியா?, சாப்பிட்டியா? | ATE | 16 PASS |
| E greeting | hi, hiii Kai, vanakkam → HELLO; good morning → GREETING_MORNING | as listed | 4 PASS |
| F thanks | thanks Kai, thanku, tq, romba nandri | THANKS | 4 PASS |
| G praise | super Kai, semma | PRAISE | 2 PASS |
| ack | hmm, seri | OK | 2 PASS |
| N business never small talk | Kumar-ku 5000 kuduthen; Colgate 2 box add; Kumar-ku 10 minutes kalichi call pannanum; 25000 la 18% GST evlo?; Colgate stock evlo?; inniku sales evlo?; un name enna 5000; saptiya Kumar-ku call pannu | NONE | 8 PASS |

## Through the whole KaiAgent (KaiNaturalLanguageAgentTest) — 18

| # | Test | Covers | Result |
|---|---|---|---|
| 1 | eatingQuestionEverySpelling | D, L: 6 spellings → same reply; the learner does not ask | PASS |
| 2 | nameInTheOwnersLanguage | B, H, I, J: Tanglish / English / Tamil-script replies | PASS |
| 3 | identityTypedAndSpoken | A, H: typed Tanglish + Tamil script | PASS |
| 4 | statusQuestionIsAboutKai | C: "tired ah iruka?" is about Kai; the owner's own "romba tired" keeps the old answer | PASS |
| 5 | greetingThanksPraise | E, F, G, K (mixed "Kai, your name enna?") | PASS |
| 6 | paymentStillDraftsAndWaitsForConfirm | O, N | PASS |
| 7 | stockStillDrafts | P (2 box = 24 pieces) | PASS |
| 8 | reminderStillAsksToConfirm | Q | PASS |
| 9 | calculatorUnchanged | R | PASS |
| 10 | smallTalkDoesNotStealAnOpenDraft | M: "saptia?" in the middle of a payment draft; "500 dhaan" still corrects it | PASS |
| 11 | receivableFollowUpPreserved | M: Kumar ₹20,000 → "eppa?" | PASS |
| 12 | stockFollowUpRefersToTheLastProduct | M: Colgate 20 in (confirmed) → "athula 5 pochu" = a Colgate stock-out **draft** | PASS |
| 13 | namedProductBeatsReference | M: a named product (Rice) always wins over "adhu" | PASS |
| 14 | personalLearningStillWorks | S: 'potti' = 1 box → Colgate 2 potti = 24 pieces draft | PASS |
| 15 | ambiguousMoneyIsNotGuessed | T: "Kumar 5000" → no plan, no write; "kai vali" → non-blank | PASS |
| 16 | unknownInputNeverSilent | U: 8 unclear inputs → never blank, no writes | PASS |
| 17 | namesAreNeverRespelled | Safety: "Saaravanan" stays as typed; the casual key is internal only | PASS |
| 18 | spokenTamilAndTypedTanglishMeanTheSame | Text/voice parity: Tamil STT and typed Tanglish → same kind | PASS |

## Regression

| Scope | Before | After |
|---|---|---|
| Existing suites (47) | 539 pass / 0 fail | **539 pass / 0 fail** (identical per suite) |
| New suites (2) | — | 75 pass / 0 fail |
| **Total** | 539 | **614 pass / 0 fail** |

These include:
- KaiAgentTest 27;
- KaiTimeTest 8;
- KaiConversationHardeningTest 32;
- KaiPersonalLearningTest 21;
- KaiUnitConversionTest 13;
- StockVoiceParserTest 21 / StockVoiceResolverTest 9;
- KaiReminderFlowTest 10 / KaiReminderConfirmTest 8 / KaiReminderUnderstandingTest 7;
- KaiUrgentVoiceTest 8 / KaiUrgentVoiceScriptTest 6;
- MorningWorkEngineTest 29 / KaiMorningWorkIntentTest 11;
- KaiCalculatorTest 5;
- KaiFinalFixesTest 19;
- KaiProductionFixesTest 20.

All unchanged and green.

## Pixel 8 (BLOCKED here — no emulator in this environment)

| Check | Status |
|---|---|
| Text: saptiya? saptia? saptya? saaptiya? un name enna? nee yaaru? epdi iruka Kai? super Kai, thanks Kai | BLOCKED (9) |
| Voice (Kai Chat mic + Pesunga) | BLOCKED (1) |
| Critical flows: payment, stock, calculator, reminder, context continuation, personal learning | BLOCKED (6) |

A ready-to-run Pixel 8 script is in REPORT.md §10.
