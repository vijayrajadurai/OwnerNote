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
- Kai's flow fixes (owner's phone, 21 Oct 2026; test `KaiFlowFixesTest`):
  - A new product's open question ("1 bag evlo kg?") takes only its answer: another product's line, a question, a money
    line or another new product is its own (said: "Aashirvad Atta innum serkala"). "bag-ku 25 kg" is never money.
  - "aama" / "seri confirm pannu" / "ஆமா" said to a stock draft confirms it (a number said is a correction).
  - "stock" alone asks which; "customer report" lists who owes; "weekly / monthly report" is that period; "expense
    report" is the expenses, never an expense entry. Profit with the books unreadable says so (not "Kai has no P&L").
  - A stock-out that is a sale ("vithuchu", not damage / wastage / own use) goes to the books as a walk-in cash sale
    (`KaiTools.cashSale`), so sales, profit and fast / slow movers have it; the draft shows "Cash sale: ₹…" first.
- Item register (owner, 21 Oct 2026; test `KaiItemRegisterTest`, owner-lines): every item line of the purchase / sales bills
  (returns count against), this month unless said, read-only from `txn_items` (`BooksDao.itemRegister`, `KaiTools.itemRegister`):
  - "purchase register", "sales register", "item wise report" (both sides), "purchase report", "enna enna vaanginen" → item by
    item, quantity in the stock unit and value before GST, with the total. "sales report" is the register only in a goods
    shop (a service shop keeps the Daily Sales Note's).
  - "Cinthol evlo vaanginen", "Rin Soap sales evlo" → that product's lines bill by bill (date, bill no, party). Words that name
    no product ("Kumar kitta evlo vaanginen") stay the money question.
  - "report" alone asks "Sales, purchase, stock…?"; "purchase" to it is the purchase register. Nothing recorded is said, with
    where lines come from (purchase bill / bill scan; sales bill or "vithuchu" in chat). Mic: "பர்சேஸ் ரெஜிஸ்டர்", "ஐட்டம் வைஸ்".
- Goods arriving ("kadaiku saraku vandhuruku") → items one by one into a list, saved together on the list's Confirm.
- Expiry for goods that can expire, in any shop. Batches and a 7-day alert. Old stock becomes an "OLD-STOCK" batch on
  Confirm.
- Reports: stock on hand, low stock, fast and slow movers, profit and loss. A stock card shows the product's photo.
- Kai knows the new screens and units (owner, 20 Oct 2026), typed and by mic (`brain/chat/KaiScreenLinks.kt`):
  - "purchase bill podu" / "supplier bill entry" → Purchase bill; "draft bill" → Billing; "pudhu product add pannanum"
    → Add Product; "en kadai enna vagai" → says the setup categories, button to Profile. "sales bill" says the number
    and date fill in by themselves. A link saves nothing.
  - Unit Master pack units by chat: jar, pouch, sachet, drum, bunch, bale, cone, spool, reel, ampoule, dose, yard, sq.m
    (sachet / jar are their own units now, not packet / bottle). Kai's conversions stay its own list: feet / yard for goods
    in metres are asked (owner's rule); a box / jar is never assumed.
  - Products Kai creates are "GST not specified"; a second unit told to Kai is kept beside the first (`moreUnits`).
  - A new shop (under 3 products): a new product's kind follows its setup categories. Test: `KaiFeatureLinksTest`.
- Inventory fields (owner, 20 Oct 2026; not yet checked on a phone — UI needs an Android Studio build):
  - Dashboard: search (name / code / SKU / barcode, part of a code too), Add Product, Total Products and Low Stock tiles
    (tap Low Stock to list only those).
  - Add Product: business category (the shop's by default), required product category with the kind's own list, SKU
    Auto (`books/model/ProductCategories.kt` `ProductCode`), GST "not specified" when left blank (never 0%).
  - Unit Master for every kind of business (`books/model/UnitMaster.kt`): searchable by code / English / Tamil / short
    form, the kind's units first, a new unit typed in English letters. Stock, purchase and sales unit per product, each
    with its own conversion (`ProductUnits.more`); fixed ones (1 metre = 1,000 mm, 1 quintal = 100 kg) are filled in,
    a box / strip / coil never is. Books DB v2 (`BooksDatabase.MIGRATION_1_2`). Test: `UnitMasterTest`.
  - Bills: a line starts in the purchase / sales unit; Save draft; discount, taxable, CGST + SGST / IGST shown; Credit
    as a payment choice for a named party. Stock change dialog: one-tap reasons (old stock = opening correction).
  - Sales bill: the invoice number is filled in from the series (INV-0001…) and can be changed ("use the automatic
    number" brings it back; an auto number is taken on Confirm, so two bills never share it); the date is the phone's
    today, changed by tapping it.

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
- Setup / Profile business category (owner, 20 Oct 2026; not yet checked on a phone): 40 product business categories
  plus hotel and salon (`data/model/BusinessCategories.kt`, subcategories with each). The 9 popular are cards, with a
  search (names, Tamil, what the shop sells) and "View all"; several can be picked; "Other Product Businesses" takes a
  typed name. Saved as `categories` + `otherCategoryName` (backend `claude/business-categories`); `category` stays the
  older single value Kai and the server read (a service pick wins, else the first). Test: `BusinessCategoriesTest`.
- Business types decide the features (owner, 21 Oct 2026, Phase 1; UI not yet checked on a phone): Setup / Profile pick
  Service / Retail / Wholesale-Distributor (several allowed; a hotel, tea shop or mobile shop with repairs is Service + Retail)
  before the categories, and the categories offered follow (11 service shops added, with "Other Services" and its own name).
  `data/model/BusinessTypes.kt` (`FeatureSet`) holds the table: Service → Daily Cash "Menu"; Retail → stock, bills, HSN,
  group buying, local offers, Discover tab; Wholesale → stock, bills, HSN. Read through `LocalFeatures`
  (`ui/components/FeatureGate.kt`) by Home quick links, the bottom tabs, More and the Daily Cash Note. A business set up
  before is read from its categories; nothing known shows everything. Settings → "Show all features" brings every one back.
  Saved as `businessTypes` (backend `claude/business-types`); the books' type follows (a service shop is PRODUCT_AND_SERVICE,
  so Kai's service sales work and stock still moves). Test: `BusinessTypesTest`.
  The party screens' "New bill" follows Billing too.
- Kai by business type (owner, 21 Oct 2026, Phase 2; `KaiTools.businessKinds`, read from the business each line): a service
  business's own service Kai has no word for ("blouse stitching 350", "alteration 100 panninom") is a Daily Sales draft
  (`KaiDailySales.newService`); a wholesaler's sale with no buyer asks "யாருக்கு?" (a customer → their credit bill, "cash" → the cash
  sale), a named one ("Murugan Stores-ku 10 box Colgate anuppinen") is a credit bill; "Kumar 2 box Colgate vaanginaan" is a sale to
  Kumar for every shop; "report" offers the type's reports (a salon's sales by service, a wholesaler's overdue) and "indha vaara
  report" is the Daily Sales week only for a service shop. A type not known decides nothing. Test: `KaiBusinessKindsTest`.
- The owner's 1600-line training check (21 Oct 2026, PDF "OwnerNote Kai 1600 Tamil Checked Training Examples"), every line typed
  and as the mic writes it: 68% → 99.9% (the two left ask a new product's details, as they should). Fixed: a service sale said in
  full ("… service ku 250 vaanginom", "… service sale 7500 note pannu"); "Kai, …" said to Kai; "confirm illa stop" / "save pannadhe"
  cancel (one confirmed before); "<product> stock la 2 box remove pannu" is a stock-out (was "forget a word"); a physical count is
  never posted as a cash sale; "low stock ah iruka?" answers yes / no; "<product> last month sales" is its sales, not stock; a pack
  total asked; "total 3000; 1800 kuduthachu; balance sollu"; "bill 3500; 450 mattum kuduthanga" (₹450 drafted); "last 7 days
  expenses PDF"; "August report"; "5000 entry pannu" / "Selvam ku payment pannidu" / "5 box add pannu" asked; a supplier's "250
  pending" is the owner's to pay; one person's PDF asked before it goes to another; send / reminder status said plainly.
  Test: `KaiTrainingLinesTest` and owner-lines.
- A typed name is never another person by its consonants alone (owner's phone, 21 Oct 2026: "kens 2000 tharanum" with "கணேஷ்"
  in the books became கணேஷ்): a one-syllable English word must share the Tamil name's opening vowel (`NameSound`); a books name in
  Tamil read from English letters is shown with what was typed ("கணேஷ் (Ganesh)"); "Ganesh illa kens" moves the stated payment to
  Kens and keeps that for the conversation. Test: `KaiTrainingLinesTest.aTypedNameIsNeverAnotherPerson…`, `NameSoundTest`.
- Family / relation words after a name (owner, 21 Oct 2026: "Kumar thambi evlo tharanum" answered a record called "thambi";
  "kumar akka" got Kumar's own balance): one list in `brain/tools/KaiRelations.kt` (thambi, anna, akka, thangachi, amma, appa,
  machan / macha, maama, athai, chithappa, paiyan, ponnu, wife, friend… typed, as the mic writes them, in English). A question
  about "<name> <relation>" with no record of that whole name asks once — that person (said respectfully) or their relative —
  and keeps the pick for the conversation; the relative's answer says there is no account (never another person's money). Entries
  keep their own question (`relationQuestion`, now on the same list). Test: `KaiTrainingLinesTest.aRelationAfterANameIsAskedNeverMerged`.
- Daily Cash Note, kallapetti and Close day are optional (owner, 21 Oct 2026): the morning kallapetti is asked once a day
  (Add / Skip, kept in `UserPreferencesStore.kallapettiAskedDay`) and never required — money in / out work without it; Close day
  is an outlined "optional" button (an open day is still sent by itself the next day). Kai asks the kallapetti once at the first
  sale; "skip" / "venam" / "illa" / வேணாம் goes on with the sale. Test: `KaiDailySalesTest.theKallapettiIsAskedOnceAndSkippingGoesOnWithTheSale`.
- Lakh / crore in Kai (owner, 21 Oct 2026: "credit debit la lakhs and crore la sonna add agala" — "2 lakh" was ₹2): a number and its
  scale word are one amount in `KaiUnderstanding.amountsIn` — lakh / lakhs / lak / lac / laksham, crore / cr / core (never a cable's
  "2 core wire"), and the mic's லட்சம் / லட்சத்து / கோடி (`KaiSpokenWords`, never the name Lakshmi; லாக் is a lock); "1 lakh 50 thousand"
  = ₹1,50,000. Over ₹1 crore is said plainly with Confirm off. Test: `KaiBrainTest.lakhAndCroreAreOneAmount…`, owner-lines.
- A day's list is its report (owner, 21 Oct 2026: "today report" then "today list" asked "edha pathi sollureenga?"): "today list",
  "innaiku list", டுடே லிஸ்ட், and "list kudu" right after a report give the Daily Sales Note report with its PDF; "today report" is
  the same as "innaiku report" for every shop; a day with no note entry says the books' sales / purchase / expenses. Stock / customer /
  reminder lists are untouched. Test: `KaiDailySalesTest.todayReportThenTodayListSendsTheReport`.
- One entry up to ₹99 crore (owner, 21 Oct 2026: "1 crore la irunthu 99 crore varaikkum"): `MAX_LEDGER_AMOUNT` in the app
  (`util/PartyLedger.kt`, the Add Credit / Debit / bill screens and Kai) and the backend (`src/kai/numbers.ts`); above it Kai says so
  with Confirm off. A 9-digit amount (990000000) is money; 10 digits with no commas is a phone number. Test: `PartyLedgerTest`,
  `KaiOwnerSweepTest` principle 5 (₹150 crore), owner-lines.
- "kuduthuden" / "koduthuden" / "kuduthudaan" (soft d) are "kuduthuten" / "kuduthutaan" (`KaiIntentWords`, mic: குடுத்துடேன்),
  so both spellings give the same entry; "thuttu" / துட்டு is money like kaasu / panam. Test: owner-lines.
- Kai understands it said any way (owner, 21 Oct 2026: "voice-la nariya correct solla matanga, but kai athoda intent ah
  purinjikanum"): before Kai asks what a word means, the word is matched by sound to Kai's own (`brain/tools/KaiWordSound.kt`:
  "taranum" = tharanum, "kaden" = kadan, "kudutten" = kuduthen, "vandhathu" = vandhuchu, "kensal" = cancel, "sels" = sales,
  "expairy" = expiry) — never a line Kai already reads, never the shop's names, a money / stock verb only after the amount; the
  owner's own taught words come first. The mic now gives up to five guesses and Kai takes the one it reads best (`KaiHeard`,
  `DeviceSpeechRecognizer`, Kai Chat only). A person + amount with a word Kai can't read gets three buttons — Payment Out /
  Payment In / Kadan (new `KaiMeaning.CREDIT`) — and the pick is kept. The "k" of the word after an amount is no longer cut
  ("3000 kudutten" was asked as `uduthten`). Training map: `docs/KAI_TRAINING.md`. Test: `KaiWordSoundTest`, owner-lines "Said any way".
- "X illa Y" for every feature but reminders (owner, 21 Oct 2026: "Ammu anna ku 2000 tharanum" read as Ammu — "Ammu illa Ammu
  anna" must go on with Ammu anna): an open money draft is the same entry for the person meant; a stock draft, a stock question,
  profit, a balance, a statement are asked again for the one meant (`KaiAgent.correctionReplay`); "illa Ramesh" corrects the one
  person / product of the last request. "illa naalaiku / sari / venam" stay answers. "Rice profit evlo" after Colgate's is Rice.
- Not understood → asked, never a report (owner: "puriyalana question kekkanum, theva illama report show panna kudathu"): a
  person or product with words Kai can't read ("Kumar blah", "Kumar 9876543210 credit") gets buttons — balance / statement /
  payment, stock / rate / profit — not their balance. Kai's questions carry their likely answers as buttons (`KaiAction.Say`):
  Colgate 5 came in / sold, Kumar 2000 paid / kadan / I paid, the topic menu, "stock", "report".
- Kai counts how well it understood, on the phone only (`KaiUnderstood`, `data/kai/KaiUnderstandingLog.kt`): understood / asked /
  corrected / cancelled and the last missed lines; "Kai evlo purinjuchu" answers it. The mic leans to the shop's names
  (`EXTRA_BIASING_STRINGS`, Android 13+). Test: `KaiCorrectionReplayTest`.
- A bill PDF loads fast and whole (owner, 22 Oct 2026: a 125-item purchase PDF "romba neram load aguthu"): a PDF made by a billing
  app is read from its own text (`util/BillPdf.kt` → `books/integration/PdfText.kt`, which now also undoes ReportLab's
  ASCII85 + Flate streams) — every page, in milliseconds, no OCR; a scanned PDF (or text that reads as no bill) still goes to OCR.
  A table over several pages counts every page's rows (the header printed again on each, "Page 1" footers skipped); "(VENDOR)" in
  a title is not the supplier heading; piece / packet / pack / jar / pouch / gram / tray / case are units. The parse runs off the
  screen's thread; the review draws the first 20 rows and every row that needs a check ("Show all items (105 more)"); a long
  bill's save shows "Saving stock… 37 / 125". Test: `BillPdfTest` (the owner's sample PDF in `test/resources/bills`).
- Bill PDFs from any app (owner, 22 Oct 2026: "intha format mattum read pannuma, illa all format ah"): `PdfText` now reads PDF 1.5
  object streams (Word's "minimum size", iText — before, those went to slow OCR) and a span's /ActualText (Word / LibreOffice's
  Tamil: "பருப்பு", not "பருபபு"); `BillPdf.joinWrapped` puts an item name wrapped in its cell back into its row ("Ponni Rice 25kg"
  above the numbers, "Bag" below); a counted S.No column (1, 2, 3 …) leaves the names, a lone "7 Up" keeps its number. The same
  bill printed from a browser and saved from LibreOffice reads the same. Password PDFs still can't be read. Test: `BillPdfTest`.
- Kai's question card, made for the owner's thumb (owner, 22 Oct 2026, preview approved): the question short and big
  (`KaiCard.headline` — "Kumar ₹2,000 — enna aachu?") with the books' figure first (`KaiCard.context`); each likely answer a big
  tile with an icon and what it does (`KaiButton.hint` — "Kumar tharanum ₹1,000 → ₹3,000"); a big "Vaayaala sollunga" mic and
  "Venaam". A number asked (purchase / selling rate, pack size) gets the card's own number pad (`brain/note/KaiAskPad.kt`): the
  profit while the selling rate is typed ("Oru piece-ku labam ₹10 · 100 pieces-ku ₹1,000", a loss said in red), three rates to tap
  (cost +10/20/30 %), "Adutha →" sends the number as the owner's line — the same as typing or saying it. Kai asks in the unit the
  owner said (no box question for "100 pcs"). An answered question folds to one "✓" line. Kai's rules and replies are unchanged;
  nothing is saved before Confirm. Test: `KaiAskPadTest`, `KaiNoteTest.theSellingRateQuestionKnowsTheCost`,
  `KaiCorrectionReplayTest.theMoneyQuestionSaysWhatEachAnswerDoes`.
- Goods sold to a shop, said any way (owner, 22 Oct 2026: "100 box noodles Kumaran store ku / kadaiku / Malligai ku sale pannitan /
  vithutan / vithachu / kuduthurukan"), wholesale or retail: one bill — the stock down and the shop's balance up together, after
  Confirm (credit / cash asked; a wholesaler's is credit). Sale verbs by stem (`KaiInventory.saleStem`: vith- / sale pann- / anupp-;
  "kuduth-" only said to them, so "Kumar 5 box kuduthaan" is never a sale); the shop matched with its shop word ("Kumaran kadai" =
  "Kumaran Store", `KaiAgent.shopBuyer`); a shop the books don't have is asked (add them, or cash sale) — before, the stock went out
  and the buyer was lost. "Kumaran store ku enna vithen / evlo vithen / enna kuduthen", "… enna vaanginaanga", "ABC Traders kitta enna
  vaanginen", with a period or not, answer every product, how much and the money from the item register (returns off) and what they
  owe now (`KaiAgent.goodsAsked`); "evlo kuduthen" stays a money question. Typed = mic (`KaiSpokenWords`: வித்தாச்சு,
  குடுத்துருக்கான் …). Test: `KaiSaleToShopTest`, owner-lines.
- Three screens made for the owner's thumb (owner, 22 Oct 2026, previews approved; screens only — saving unchanged):
  - Kai's money draft is the Confirm card (`ui/kai/KaiConfirmCard.kt`): what it is and the amount big, Kai's rows, the balance
    before → after, one big Confirm, then Edit / Cancel (Kai's own buttons). Stock / goods drafts keep the notebook scene.
  - Add Credit / Add Debit are three steps (`ui/screens/MoneyEntryFlow.kt`): who (they owe me / I owe them, the shop's people as
    faces with their balance, search, a new name) → how much (number pad, the amount in words — `util/AmountWords.kt` — quick
    +₹100…) → is this right (one sentence, balance before → after, due in 7 / 15 / 30 days or another date — still required, a
    WhatsApp reminder for a credit). The same entry, party creation and Kai message as before.
  - The bill review starts with a summary (`BillEntrySection.BillSummaryCard`): purchase / sale, who, the total big, items / GST /
    paid; only what needs a check, one tap each (right / change, Kai's offered name or total, how much was paid); every field under
    "Bill details", open by itself when something there must be filled. Same fields and Save rule. Test: `AmountWordsTest`.
  Not done: undo after Confirm ("Maathu" in the preview) — it needs a books void, a logic change, left for its own step.
- The voice / bill screen no longer has the "Your sentence" box (its "Kumar-ku 2000-ku tharanum" example) or the Parse button.
- Customers: a small Import beside the title. More → "Pazhaiya kanakku import" reads another app's Customer / Supplier
  List PDF and adds people with opening balances on Confirm. No other app's name is shown anywhere.
- Kai Chat first, OwnerNote's own look (owner, 22 Oct 2026). UI only; what is sent, answered and saved is unchanged:
  - Home (`HomeScreen`, `HomeOwnerNote.kt`, `ui/kai/KaiVisuals.kt`):
    - "Vanakkam Owner 👋" header — "Owner" is OwnerNote's own word, kept.
    - Vaanganum / Kodukkanum as two small chips (`HomeMoneyChips`, the same books figures as the old summary card, late count
      shown) where the "KAI-ODA DIARY" heading was; tap → Customers / Suppliers.
    - A small "Kai-kitta kelunga" bar with a turning green-gold (veshti) border and rotating examples per business type → Kai Chat.
    - Square tiles: Kai, today's cash in, reminders, Stock. Money shown short by `util/shortRupees`
      (`ShortRupeesTest`); a figure not loaded shows "—".
    - Tile stories (`HomeStories.kt`, 23 Oct 2026): a tile tap opens a full-screen Kai story with golden progress bars — Kai's
      brief, today's in / out, reminders (names, amounts, late count), Stock kammi (products at or under their minimum, read from
      Inventory; the Stock tile carries that count). Six seconds each; right tap next, left tap back, ✕ closes; the white button
      and the reply bar open the existing screen / Kai Chat. Read only — nothing is saved.
    - Kai's diary cards (23 Oct 2026, "preview-la kattuna mari"): Kai's brief ("Seri, paathen", Kelunga plays it again), today's
      sales from the Daily Cash Note with Kai beside the figure (→ Cash Note), payments due from the reminders (→ each reminder /
      all), the morning work count (→ Morning Work), then quick actions as tiles (Speak, Reports, Inventory, Group buying, Offers,
      Loans, See all — the same places, the same feature gates). Cream background; chips show whole rupees.
  - Bottom bar: dark and floating, with Kai's face (mic) raised in the middle → Kai Chat (`BottomNavBar(onKai)`).
  - Kai Chat (`KaiChatLook.kt`):
    - Night-green backdrop with a dot grid; Kai in a turning ring of light; glass bubbles; hopping typing dots.
    - The mic as a glowing orb; while it records, a full-screen voice mode (Kai standing, the words heard,
      "Mudinjudhu ✓" = the same as tapping the mic).
  - Customer detail 4b (`CustomerDetailHero.kt`):
    - The balance big with what came in so far and the days late.
    - "Panam vandhuchu" (Books payment), "Kadan kudunga" (Add Credit).
    - One green WhatsApp reminder: the Tamil / Tanglish message is shown, then WhatsApp opens with it typed (`util/WhatsAppText.kt`;
      the owner sends).
    - Statement PDF (the same `shareStatement` as Kai) and Sale bill. No call button.
  - Stock 5b: Items · Value · Kammi counts, "Kammi mudhalla / Ellaam", a bar per product. − / + open the same Stock out / in box.
  - Error / empty 7b (`ui/components/FriendlyState.kt`): plain words, "Neenga pottadhu edhuvum azhiyala", one main button,
    details on request. Used on Stock and Customer detail.
  - Respectful wording: button words end in -unga / -ங்க ("paduthunga", "Save பண்ணுங்க", "பணம் வந்துச்சு").
  - Pictures: the owner's transparent Kai (`drawable-nodpi/kai_mascot*.webp`).

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
  - Home / bottom bar / Kai Chat look / Customer 4b / Stock 5b / FriendlyState (22 Oct 2026)
  - bill upload (PDF / Excel / CSV, `DocumentCaptureSection`)
  - CashNoteShare
  - the note scene's images
- Real-device acceptance list: `docs/KAI_PRODUCTION_FIXES.md`.
- Kai 2.0 roadmap (owner, Oct 2026): understand everything → know the business → never make silent mistakes → learn
  automatically → predict before problems happen (cash-flow risk, stock running out). Step 5 is not started; the owner
  said to wait until after launch.
- The backend (`OwnerNote-Backend`, Prisma) has no `category` enum change; the app maps categories on its side. Several
  categories and the "Other" name are kept in `Business.categories` / `otherCategoryName` (migration
  `20261020180000_business_categories`); the server must be deployed with it for the picks to be kept.
