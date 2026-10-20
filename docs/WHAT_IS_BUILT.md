# OwnerNote: what is built (read this first)

Several Claude sessions work on this repo at the same time, each on its own `claude/…` branch. This page is the shared
memory: what the app already does, where it lives, and how work is done here. **Before starting:** read this page and
`git log --oneline origin/main | head -40` (another session may have just fixed the same thing). **When you finish a
feature or a fix:** add one line under its area below, in the same commit.

Last brought up to date: 20 Oct 2026 (main at PR #138).

---

## 1. How work is done here

- **The owner** speaks Tanglish or Tamil. Reply in Tamil script unless asked otherwise. The app launches on the Play
  Store soon, with door-to-door marketing to shop owners, so they are the users.
- **Rules:** `CLAUDE.md` (typed and mic are one Kai) and `docs/KAI_PRINCIPLES.md` (the seven principles). Short version:
  - Nothing is written before Confirm.
  - Kai never says "Save aagiduchu" before the database says so.
  - Every Kai fix works typed and spoken. Spoken means the phone's `ta-IN` mic, written Tamil included.
  - A word Kai can't read is asked about, never guessed.
- **Kai is rule-based and on the device.** There is no AI model or outside API for understanding. Words and feeds live
  in code (`KaiFeed`, `KaiSpokenWords`, `KaiIntentWords`, catalogues).
- **Branches and merges:**
  - Work on your session's `claude/…` branch.
  - Merge only when the owner says so ("Merg pannidu").
  - PRs go to `main`; `master` is kept level with `main` (a PR from `main` into `master`, or a twin PR).
  - Merge with merge commits. After a merge, restart your branch from `origin/main` for the next task.
- **Tests run on the JVM only.** There is no Android SDK in the cloud box:
  - `brain/**` and `util/**` tests run with plain Gradle and Kotlin.
  - Compose / Android UI code cannot be compiled there. Say so to the owner and ask for an Android Studio build.
  - Heavy suites take tens of minutes; run them in the background: `KaiCatalogue*`, `KaiHardwareShopTest`,
    `KaiSupermarketShopTest`, `KaiVegetableShopTest`, `KaiPharmacyBrands*`, `KaiMedicalShop*`.
  - `KaiOwnerSweepTest` plays every line of `app/src/test/resources/kai/owner-lines.txt` typed, by mic
    (`KaiMicForm`) and in written Tamil.
- **Commits:** no model names in commits or PRs. Write each commit message to say what the owner saw, then what changed.

## 2. Map of the code

| Area | Where |
|---|---|
| Kai's turn: everything the owner says goes here | `brain/chat/KaiAgent.kt` (`ask` → `answerOwner`; keep it under the JVM's 64 KB-per-method limit) |
| Chat screen, mic, actions | `ui/kaichat/KaiChatScreen.kt`, `KaiChatSession.send` |
| Mic Tamil → the Tanglish rules read | `brain/tools/KaiSpokenWords.kt` (`spoken`, `normalize`, `withNames`, `code`), product names by sound `KaiProductSound.kt`, people by sound `util/NameSound.kt` |
| Money entries (credit / debit / payment) | `brain/tools/KaiLedgerPhrases.kt` (other ways of saying an entry → one form), `KaiCommands.kt`, `KaiPaymentDirection.kt`, `brain/chat/KaiEntityResolver.kt` |
| Questions about the books | `brain/chat/KaiBusinessBrain.kt`, `KaiChatUnderstanding.kt`, `RepositoryKaiBooks.kt` |
| Stock by chat | `brain/tools/KaiStock.kt` (units, in / out words), `KaiInventory.kt`, `KaiUnits.kt`, `KaiExpiry.kt`, goods arriving `brain/chat/KaiGoodsArrival.kt` |
| Word families for intent | `brain/tools/KaiIntentWords.kt`, `KaiFeed.kt`, `brain/chat/KaiLexicon.kt` |
| Owner's own words (private memory) | `brain/memory/*` (`KaiPrivateMemory`, `KaiTeaching`), `brain/chat/KaiMemoryAssistant.kt` |
| Reminders and urgent voice | `brain/tools/KaiReminders.kt`, `KaiReminderFlow.kt`, `KaiTime.kt`, `KaiTamilVoice.kt`, `KaiUrgentVoiceScript.kt` |
| Daily Sales / Cash Note by chat | `brain/chat/KaiDailySales.kt`, `brain/tools/KaiServices.kt` (salon 272-service list, tea / hotel, rentals), `brain/chat/KaiMenu.kt` |
| Bills (photo / PDF / Excel / CSV) | `util/BillTextParser.kt`, `BillDirection.kt`, `BillStock.kt`, `BillSheet.kt`, `BillValidation.kt`, `DeviceTextRecognizer.kt` (Tesseract), UI `ui/screens/DocumentCaptureSection.kt` |
| Kai's note scene and cards | `brain/note/*`, `ui/kai/KaiNoteScene.kt`, `docs/KAI_NOTE_SCENES.md` |
| List import from another app's PDF | `books/integration/PdfText.kt`, `PartyListImport.kt`, `PartyListImportSave.kt`, `ui/books/PartyListImportScreen.kt` |
| Kai's tools (the only way Kai touches data) | `brain/tools/KaiTools.kt` (interface); Android: `data/kai/AppKaiTools.kt` |

## 3. What the app does, by area

### Kai understanding (typed and mic)
- One Kai for the text box and the mic. The mic's Tamil, its written Tamil ("தர வேண்டும்") and English said into the
  Tamil mic ("டுமாரோ", "பெய்டு") are all read as typed Tanglish.
- Intent by word families, not fixed phrases: stock in / out with the reason, reminders, credit / payment, number words,
  days, expiry.
- Not understood → "edha pathi sollureenga? Credit / debit (kudukkal vaangal), stock (kadaila irukkura porul),
  reminder?". The answer ("stock", "porul", "ஆமாம்", "illa") moves the flow on.
- A line Kai asked about that has a count ("pulungal arisi 10bgs") is kept. "stock" then "vandhuchu" finish it.
- Context across turns: the person, product, amount and saved entry said before. "avan", "andha aalu", "mudhal aal"
  (the first one in the list just shown). Return to an earlier topic.
- A word alone ("report", "Lux", "cash") gets a question, never an unasked report.
- One category at a time (owner, 20 Oct 2026): with a stock question waiting, a credit / debit line (or with a money question
  waiting, a stock line) gets "stock-aa, credit-aa, debit-aa?" (buttons + said) and goes the way picked. "stock add pannu" never
  confirms a money draft; "credit / debit add pannu" is the money flow, never a product. Goods mode keeps its own way.
- Plain answers for what Kai wasn't fed (advice, a shop holiday, utility bills as expenses); small talk answered kindly.
- The chat's language is kept: a one-word English reply never switches it.
- Speed: product and service sound keys are prepared when Kai Chat opens. The first mic line takes ~0.2 s, and Kai
  thinks off the main thread.

### Money (credit / debit / payments)
- Everything as a draft with Confirm / Edit / Cancel; saves go through the books' engine.
- Direction per side and per verb:
  - "X kuduthan" is money in.
  - "X ku kuduthen / kuduthutan / anuppitten / settle" is money out.
  - "naan X ku tharanum" is a debit.
  - "enakku" is never the person paid.
- Paid in full however said ("settle pannitan", "kanakku mudichitaan"). Paying more than owed is said, never lost.
- Due dates in any form ("December 5", "20th tharuvan", "adutha maasam 5"). An amount with a day and no time is an entry,
  not a reminder.
- Same names: "which Lokesh?"; a new name gets "Puthu customer / supplier-ah add aagum". Any name, a shop's name, or a
  name with its shop works, with no list of names.
- One name on both sides: a Debit for a name kept only as a customer (or a Credit for a supplier) asks "already customer-aa
  irukkaar — avare-vaa?" — the same: set off on their record (balance said); someone else: a new supplier / customer.
- Tamil names in their case: "செல்வத்துக்கு" = Selvam (or the shop's Selva / Bharath for பரத்துக்கு); typed "selvathukku" too.
- A supplier's invoice by name ("ABC Traders invoice kudu") is their statement PDF; "bill podu" stays the Billing screen's.
- A second payment the same day is asked ("innoru ₹1,000-aa?"), then allowed on Confirm.
- Questions answered from the full ledger:
  - who owes
  - who pays late or on time
  - totals
  - due by date
  - a person's statement as a PDF (WhatsApp share)

### Stock
- Stock in / out / damage by chat for every kind of shop:
  - local units (moota, kattu, seepu, muzham, tray…)
  - fractions (arai, kaal, onnara)
  - typed shorthand (bgs, bx, btl, ctn, doz)
- A product named alone gets its details. A product with a count gets "vandhucha, vithucha?". An unknown product is
  created step by step (bag size, prices), with a question card.
- Catalogues fed and tested typed and by mic:
  - 30 expanded categories (grocery … wholesale)
  - pharmacy brands
  - hardware (sizes, codes, sound rules)
  - supermarket packs
  - vegetables
- Hardware stock-in, as rules for any product (PR #138):
  - A new product never lands on the last one talked about (Tata Tiscon ≠ Ultratech).
  - Another brand before the shop's goods word is another product.
  - A size (12 mm, 6 A, 8x4, 2 inch, 2.5 sqmm) is the product's, never the quantity.
  - Packs multiply: "arai kilo 24 dabba" = 12 kg.
  - inch / mm / sqmm read the same by mic.
  - No expiry / batch / phone-model questions in a hardware shop.
  - Test: `KaiHardwareStockInTest`.
- A sale said with its amount ("Onion 5 kg 100 rupees-ku vithuchu") never adds the amount to the kilos; "innaiku total sale /
  labam evlo" sums today's stock sales (₹100 + ₹50 = ₹150) and their profit — kept with Kai's memory (no books table yet).
- Stock-in of goods that can expire asks the expiry date; a date said next goes onto the draft (hardware never asked).
  "skip" / "illa" / "theriyaadhu" keeps the draft with no expiry (never cancels it); a customer return is not asked.
- Goods arriving ("kadaiku saraku vandhuruku") → items one by one into a list, saved together on the list's Confirm.
- Expiry for goods that can expire, in any shop. Batches and a 7-day alert. Old stock becomes an "OLD-STOCK" batch on
  Confirm.
- Reports: stock on hand, low stock, fast and slow movers, profit and loss. A stock card shows the product's photo.

### Bills
- Bill photo OCR (Tesseract), hardened on 50+ real bills:
  - GST rows and Tally formats
  - batch / expiry columns
  - photo-column specks
  - qty × rate checked against the row
- The bill decides Credit / Debit from who issued it; a "PURCHASE INVOICE" title is a debit. The supplier under
  "SUPPLIER DETAILS" is the party. "Payment Terms: Credit (15 Days)" is a credit purchase, never ₹15 paid.
- Bill items move stock: a purchase bill is stock in, a sales bill stock out. Units are converted (10 bags × 25 kg =
  250 kg). Items not in stock can be added.
- "Intha bill la irukura stock ah add / kammi pannu" opens the scanner that way.
- **Upload a bill as a photo, PDF, Excel (.xlsx) or CSV** (not yet checked on a phone):
  - PDF pages are drawn and read like photos.
  - Sheets are read cell by cell.
  - An old .xls asks to be saved as .xlsx.

### Daily Sales / Cash Note by chat
- Service shops log sales by chat: "oru cutting, oru shaving", "2 tea".
  - Each price is asked once, then remembered.
  - Opening cash is required first.
  - "innaikku avlothan" closes the day with each service's total.
  - Sales are answered for any day, week, month or range, with a PDF.
- The shop's category decides its services:
  - Beauty salon → salon / parlour
  - Restaurant → tea / hotel
  - Hardware / electrical → rentals
  - Other → all
  - Every other category → none
  - Tested over all 13 categories.
- Salon master list of 272 services. Hand / leg wax is asked full or half. The owner's Menu (their own items and prices)
  comes first, typed or by mic, even by part of the name.

### Reminders
- "naalaiku 10 manikku Kumar ku call panna remind pannu", typed or spoken, relative or absolute. "naalu naal kazhichu
  kaalai 10" is that day at 10.
- Smart persistent reminder: Urgent Action Mode, a full-screen Kai, a natural Tamil voice at alarm volume, retries.
  Reminders only remind; Kai never says it called.

### Morning work and memory
- Morning brief / work list (`docs/KAI_MORNING_WORK.md`).
- Private memory: the owner teaches a word ("podu" = stock in), saved only after yes, for that owner only. A learned
  word never replaces a name it is said with (`docs/KAI_PRIVATE_MEMORY.md`, `KAI_PERSONAL_LEARNING.md`).

### Screens and look
- Kai's note scene: the notebook writes the draft, a digital card follows, and a tick appears only after the save.
  iPhone-style cards; a compact question card with step bars and chips.
- Inventory screen with animated category pictures.
- The voice / bill screen no longer has the "Your sentence" box (its "Kumar-ku 2000-ku tharanum" example) or the Parse button.
- Customers: a small Import beside the title. More → "Pazhaiya kanakku import" reads another app's Customer / Supplier
  List PDF and adds people with opening balances on Confirm. No other app's name is shown anywhere.

## 4. Fixes from the owner's phone (examples worth knowing)
- "sri balaji ku 786 kuduthutan" was drafted as a new debit (786 → 1,572). "-ku" now means the owner paid
  (`KaiSupplierPaidTest`).
- "pulungal arisi 10bgs" → menu → "stock" → "vandhuchu" asked "Evlo amount?". The line is now kept and becomes a stock
  draft (`KaiGuidedLineTest`).
- "குமார் எனக்கு 5000 குடுத்துட்டான்" drafted as a Credit, and "11 manikku" drafted as ₹11, are fixed and covered by
  owner-lines (`KaiPaidAndSecondEntryTest`, `KaiIntentWordsTest`).
- The tests named in each commit message are where to look before changing a rule.

## 5. Still open / to check on a real phone
- Android UI that was written without compiling here needs an Android Studio build and a phone check:
  - bill upload (PDF / Excel / CSV, `DocumentCaptureSection`)
  - CashNoteShare
  - the note scene's images
- Real-device acceptance list: `docs/KAI_PRODUCTION_FIXES.md`.
- Kai 2.0 roadmap (owner, Oct 2026): understand everything → know the business → never make silent mistakes → learn
  automatically → predict before problems happen (cash-flow risk, stock running out). Step 5 is not started; the owner
  said to wait until after launch.
- The backend (`OwnerNote-Backend`, Prisma) has no `category` enum change; the app maps categories on its side.
