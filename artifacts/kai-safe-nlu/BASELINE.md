# Kai Safe NLU — Baseline (before any change)

Branch `feature/kai-safe-natural-language`, created from `origin/main` @ b4a5660. The working tree was clean (no user changes).

## How tests were run (honest)

This environment has **no Android SDK** and no emulator. Instead, the repo's JVM unit tests run in a scratch Gradle/Kotlin-JVM harness. The harness compiles the real `app/src/main` and `app/src/test` files through symlinks, so it runs exactly the repo code, not copies.

- Tests that need the Android framework or Robolectric cannot run here. They are listed as **BLOCKED**.
- The real `./gradlew :app:testDebugUnitTest` was **not** run here.

## Result (before change)

| Suites run | Tests | Passed | Failed | Blocked (suites) |
|---|---|---|---|---|
| 47 | **539** | **539** | 0 | 17 |

**PRE-EXISTING failures: none** among runnable tests.

**Blocked (need Android/Robolectric):** BillEntryVerifyTest, BillTotalOcrTest, BooksAccountingTest, BooksBillingTest, BooksImportFormatsTest, BooksIntegrationTest, BooksValidationTest, CapturedDocumentGroupingTest, DocumentKindTest, HandwritingImageAnalysisTest, HandwrittenQuickFixTest, InventoryLocalStoreTest, KaiReminderEngineTest, NoteLedgerTest, SmsOtpAutofillTest, SubscriptionViewModelTest, UserPreferencesStoreTest.

## Per suite

| Suite | Tests | Result |
|---|---|---|
| BooksTaxTest | 12 | PASS |
| MorningSqlPerformanceTest | 5 | PASS |
| KaiBrainTest | 13 | PASS |
| KaiAgentTest | 27 | PASS |
| KaiBusinessBrainTest | 17 | PASS |
| KaiConversationHardeningTest | 32 | PASS |
| KaiDailyCashTest | 8 | PASS |
| KaiFinalFixesTest | 19 | PASS |
| KaiMorningRoutineTest | 10 | PASS |
| KaiMorningWorkIntentTest | 11 | PASS |
| KaiPersonalLearningTest | 21 | PASS |
| KaiPrivateMemoryTest | 20 | PASS |
| KaiProductionFixesTest | 20 | PASS |
| KaiReminderConfirmTest | 8 | PASS |
| KaiUnitConversionTest | 13 | PASS |
| MorningSchedulerTest | 9 | PASS |
| MorningWorkEngineTest | 29 | PASS |
| KaiCalculatorTest | 5 | PASS |
| KaiCommandsTest | 4 | PASS |
| KaiReminderFlowTest | 10 | PASS |
| KaiReminderUnderstandingTest | 7 | PASS |
| KaiTimeTest | 8 | PASS |
| KaiUrgentVoiceScriptTest | 6 | PASS |
| GroupBuyingLogicTest | 4 | PASS |
| InventoryLogicTest | 11 | PASS |
| StockVoiceParserTest | 21 | PASS |
| StockVoiceResolverTest | 9 | PASS |
| OffersLogicTest | 11 | PASS |
| KaiMatteTest | 3 | PASS |
| KaiMeshTest | 11 | PASS |
| KaiResponseTimingTest | 4 | PASS |
| KaiTest | 10 | PASS |
| KaiActingTest | 10 | PASS |
| KaiUrgentMotionTest | 4 | PASS |
| KaiUrgentVoiceTest | 8 | PASS |
| BillDirectionAndStatusTest | 4 | PASS |
| BillGstInvoiceTest | 10 | PASS |
| BillTextParserTest | 21 | PASS |
| DailyCashNoteTest | 7 | PASS |
| DocumentParsingTest | 11 | PASS |
| HandwrittenBillTest | 12 | PASS |
| HandwrittenNoteRegressionTest | 11 | PASS |
| HandwrittenOcrFixTest | 11 | PASS |
| HandwrittenTransactionParserTest | 22 | PASS |
| NameSoundTest | 3 | PASS |
| PartyLedgerTest | 3 | PASS |
| ProductLabelReaderTest | 4 | PASS |

## Known-working flows: baseline behaviour (probed through the real KaiAgent with test fakes)

| # | Flow | Input | Baseline result |
|---|---|---|---|
| 1 | Calculator | 25000 la 18% GST evlo? | "GST ₹4,500. Total ₹29,500." ✅ |
| 2 | Payment | Kumar-ku 5000 kuduthen | CREDIT_GIVEN draft, Confirm/Edit/Cancel, nothing saved ✅ |
| 3 | Receivable | Kumar enakku 20000 tharanum → eppa? | ₹20,000 from Kumar, due-date question kept ✅ |
| 4 | Stock | Colgate 2 box add | "Colgate — 2 boxes = 24 pieces stock-in draft" ✅ |
| 5 | Unit conversion | (same) 2 box = 24 pieces | ✅ |
| 6 | Personal learning | 'potti' na 1 box → Colgate 2 potti | covered by KaiPersonalLearningTest ✅ |
| 7 | Reminder | Kumar-ku 10 minutes kalichi call pannanum | confirm-first reminder card ✅ |
| 8 | Smart reminder | KaiReminderFlow/Confirm/Understanding/UrgentVoice tests | ✅ (KaiReminderEngineTest BLOCKED) |
| 9 | Morning Work | MorningWorkEngineTest, KaiMorningWorkIntentTest | ✅ |
| 10 | Context continuation | 500 dhaan after a draft | amount corrected ✅ |
| 10b | Context continuation | Colgate stock 20 vandhiruku → **athula 5 pochu** | ❌ **PRE-EXISTING bug:** "Athula inventory-la illa. Product create pannanuma?" |
| 11 | Text input | KaiChatSession.send → KaiAgent.ask | ✅ |
| 12 | Voice input | Kai Chat mic → same send(); Pesunga → KaiIntents.classify → Kai Chat only for "handled" intents | ⚠️ text/voice difference, see ARCHITECTURE.md. Emulator: BLOCKED |

## Natural-language baseline: the reported failures, reproduced

| Input | Baseline reply |
|---|---|
| un name enna? / unga name enna? | ❌ "Owner, `name` nu sonnadhu enna meaning-la?" (Kai tries to *learn* the word "name") |
| saptia? / saptya? / saaptiyaa? | ❌ "`saptia` nu sonnadhu enna meaning-la?" |
| saptya Kai? | ❌ "Vanakkam Owner ☀️ Enna help venum?" (Kai heard only "Kai") |
| dei Kai inniku romba tired ah iruka? | ❌ "Konjam rest edunga Owner ☕" (answered as if the owner is tired) |
| hmm | ❌ "`hmm` nu sonnadhu enna meaning-la?" |
| உன் பேர் என்ன? | ❌ "ஓனர், கொஞ்சம் தெளிவா சொல்லுங்க." |
| நீ யாரு? | ❌ "எனக்கு தெரியல ஓனர் 🙂 …-னா என்ன?" |
| saptiya?, un peru enna?, nee yaaru?, who are you? | ✅ already worked |
