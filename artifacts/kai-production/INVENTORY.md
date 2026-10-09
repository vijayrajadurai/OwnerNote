# Inventory by Kai Chat — `feature/kai-inventory`

Owner's request (9 Oct 2026): stock entry by voice / text was opening the camera form; make it a shop
assistant conversation, following the owner's 30-point inventory brief. Main / master were NOT touched.

## What was inspected first

| Layer | Finding |
|---|---|
| Books DB (Room) | `products` already has category, brand, SKU, barcode, primary + secondary unit with conversion, purchase / selling / MRP price, minimum + reorder level, supplier. `stock_movements` is the movement ledger (OPENING, PURCHASE, SALE, SALE_RETURN, PURCHASE_RETURN, ADJUSTMENT_IN/OUT, TRANSFER_IN/OUT) with reasons (DAMAGED, EXPIRED, MISSING, WASTAGE, PHYSICAL_COUNT). `txns.clientKey` is unique (a repeated post is not written twice). |
| Engine | `postPurchase` / `postSale` write the bill, the party balance and the stock movement in one DB transaction; `postStockAdjustment`, `postOpeningStock`; `quote*` checks everything before writing. |
| Kai | `KaiStock` / `KaiUnits` understood stock in / out and conversions; a new product **opened the camera by itself** (`direct = OpenStockCamera`). |

**So no new table, no migration and no data copy were needed:** the existing "stock" already lives in the books'
inventory model. Nothing is renamed or moved, so there is nothing to migrate twice and no quantity can double.

## What Kai does now (brain — JVM-verified)

- **New product in the chat** (`KaiInventory`, `KaiAgent.itemStep`): "Colgate 5 box add pannu" → one question at a time,
  only what is missing, chosen by the kind of goods: FMCG (pieces per box → grams → purchase → selling), grocery (kg per bag →
  purchase per bag → selling per kg), beverages (bottles per case → ml), liquids (bottles → litre), garments (sizes, never grams),
  footwear (pairs, sizes), hardware (size / spec), electronics (model / spec). Size questions can be skipped ("skip", "theriyadhu").
- **Everything in one message** ("Colgate 5 box, boxக்கு 48 pieces, piece 200 gram, purchase 28, selling 35") → no question,
  straight to the summary: 240 pieces, 48 kg, ₹6,720 purchase, ₹8,400 selling, ₹1,680 margin.
- **Confirm** → ONE write: product (units, conversion, prices per stock unit) + its opening stock (`AppKaiTools.createWithOpening`,
  one Room transaction — a rejected part saves nothing). Read back from the books after saving.
- **Existing product** → its own units / prices; never created again; "Colgate 2 box add" → 96 pieces draft.
- **Unit safety**: "Colgate 5" for a product kept in pieces and boxes → "5 pieces-aa, 5 boxes-aa?" (also for a new product). Never guessed.
- **Corrections**: "illa 3 box dhaan" updates the same pending entry (+3 only).
- **Reasons**: damage, wastage, expiry, missing, customer return (in), supplier return (out), free → shown on the draft and
  kept in the movement note (the books' `AdjustmentReasonText` maps it to the adjustment reason).
- **Physical count**: "Colgate actual stock 230 pieces, system 240" → ADJUSTMENT −10 (physical count) against the books' own figure; never overwritten.
- **Prices / minimum**: "Colgate selling price 38 aakku", "Rice purchase rate 1400 per bag" (→ ₹56/kg), "Rice minimum stock 5 bags aakku",
  "Colgate 2 box-ku keela pona remind pannu" → Confirm → product updated. Stock-out that reaches the minimum warns right in the save reply.
- **Bills with goods**: "ABC Traders kitta 5 box Colgate vaanginen" → credit-or-cash asked → one purchase bill (stock + payable together);
  "Ramesh-ku 5 Colgate credit sale" → one sale (stock − and receivable +). Rate from the product, or from a total said ("₹6,000").
- **Questions**: "Colgate evlo irukku?" → "240 PCS (5 boxes)"; "Colgate details kaattu"; "en inventory summary kaattu" (items, stock value at
  purchase price, low stock); "ella inventory items kaattu".
- **Camera**: only when the owner asks for a photo ("Colgate photo edu"). "new stock add pannu" asks which product (camera is a button).

## Tests

`KaiInventoryChatTest` — 35 JUnit tests checking the inventory's state (products, units, prices, minimum, movements, party balances,
bills), not only Kai's words: the owner's Colgate dialogue, one-message entry (Tamil and Tanglish), rice / oil / shirt / Coke / soap /
slippers / charger, unit safety, corrections, cancel, rejected save (nothing half-saved), Tamil script, English, existing product,
damage / wastage / returns / sale, physical count, prices, minimum + warning, details, summary, purchase on credit, purchase with a total,
credit sale, rejected bill, free goods, the inventory-check reminder, **the owner's final scenario (section 27)**, a 50-case matrix
(10 kinds × 5 phrasings incl. Tamil script and English) and answer spellings.

Old tests changed (owner's new rules, assertions kept, one step added):
- "5" with no unit for Colgate (pieces + boxes) is now asked first → tests answer "pieces", then check the same draft as before
  (`KaiChatChecklistTest`, `KaiHumanUnderstandingTest`, `KaiNaturalLanguageTest`, `KaiOwnerUnderstandingTest`, `KaiPersonalLearningTest`,
  `KaiProductionFixesTest`, `KaiProductionMatrixTest.m08`).
- A new product no longer opens the camera by itself (`KaiFinalFixesTest`, `KaiProductionFixesTest`, `KaiPersonalLearningTest`):
  the tests now check the chat question; "photo edu" still opens the camera.

Full JVM suite: **1231 tests, 0 failures**.

## Not done / not verified (honest)

| Item | Status |
|---|---|
| Android build | **BLOCKED here** (no Android SDK). `AppKaiTools.kt` (createWithOpening, updateProduct, stockBill, prices in products) is reviewed by hand, not compiled. |
| Pixel 8 | **BLOCKED** — not run on a device. |
| Correcting an entry already saved ("andha 5 box entry-ai 3 box-aa maathu") | Not done — only pending entries are corrected. Saved ones still go through the existing Transactions screen (void). |
| Stock transfer between warehouses | Not done (one warehouse in use; the engine supports it). |
| Low-stock **notification** | Not done as a push / alarm: Kai warns in the chat when a stock-out reaches the minimum, and the low-stock list shows it. |
| "Intha product supplier yaaru?", "innaikku enna stock vandhuchu?", "nethu evlo sale?" | Not done in this branch. |
| Customer return as a credit note against the original sale | Recorded as a stock-in with reason "Customer return" (no original bill picked). |
| "TypeScript / lint" criteria in the brief | Not applicable — this app is Kotlin / Android. |

## Inventory / Stock screen — `feature/kai-inventory-screen`

Owner's request (9 Oct 2026, with Home + Inventory screenshots): what Kai saves must show in Inventory / Stock; the old
stock-entry logic goes; "arisi moota 50 add pannu" → Kai's questions → saved → shown with a rice-sack picture; every
category with its own animated picture. Main / master NOT touched.

- **Kai entries in the screen**: Inventory reads `inventoryRepository.listProducts()`. With the books on, that is the books'
  products — exactly where Kai writes (`createWithOpening`). Without the books (local / API mode) `AppKaiTools.createProduct`
  now also passes the opening stock and prices to the inventory repository, so the product shows with its stock.
- **Old logic removed** (one stock logic = Kai): `VoiceStockEntryScreen`, `StockVoiceParser`, `StockVoiceResolver`,
  `ConfirmStockChangeDialog`, its route, and their two test files (30 tests of the removed parser / resolver). The Inventory
  mic button now opens Kai Chat. Stock In / Stock Out buttons and the product form are unchanged.
- **"moota" / "sack" / "மூட்டை" = bag**, said before or after the number ("arisi moota 50", "arisi 50 moota").
- **After saving**, Kai's reply has a "📦 Inventory-la paarunga" button that opens the Inventory screen.
- **Animated pictures** (`ui/components/ProductArt.kt`): drawn in code (no image files, no network): rice sack (grocery),
  bottle (drinks), oil can (liquids), shirt (garments), shoe (footwear), screw (hardware), charger (electronics), tube (FMCG),
  carton (other). Pops in once, then a slow bob. Kind from category / name (`KaiInventory.artKindOf`).

Tests added to `KaiInventoryChatTest`: the arisi moota dialogue (state checked: KG, 1250 kg, BAG = 25, ₹56 / ₹65, Grocery,
one OPENING movement, Inventory button, rice-sack kind), moota before / after the number + "sack", picture kind per category.
Full JVM suite: **1204 tests, 0 failures** (1231 − 30 removed + 3 new).

| Not verified | Status |
|---|---|
| `ProductArt.kt`, `InventoryScreen.kt`, `ShopAiApp.kt`, `AppKaiTools.kt` | **Not compiled** — Android build BLOCKED here (no Android SDK). Reviewed by hand. |
| How the animation looks | Not seen — no device / emulator. |
| Pixel 8 | **BLOCKED**. |

## Owner check — 100 owners + 150 combinations (`KaiInventoryOwnersTest`)

Every dialogue is played to the end: Kai's question is read (pack size / gram / size / purchase / selling / unit / summary),
the owner's answer for THAT question is given, Confirm, and then the inventory itself is checked: product name, stock unit,
quantity, pack conversion, purchase and selling price per stock unit, category, exactly one opening movement, the saved
reply, nothing saved before Confirm, and a second "yes" saving nothing more.

- **100 owners** — kirana (16), general store (18), cool drinks (11), oil / dairy (10), textile (10), footwear (5),
  hardware (8), mobile / electrical (7), stationery / bakery (5), safety (correction at the summary, cancel mid-question,
  rejected save, existing product) (4), shops already stocked (moota stock-in, damage, box sale, stock question, price
  change, purchase on credit with the supplier's balance) (6). Tanglish, Tamil script and English; spoken numbers
  ("anju box", "naarpathettu"), ₹ / rs / rupees.
- **150 combinations** — 10 products (FMCG, grocery, drinks, oil, shirt, slippers, screws, charger, sugar, soap) ×
  5 phrasings × 3 languages, each played to the save.

Result after the fixes below: **100 / 100 owners, 150 / 150 combinations.** Full JVM suite: **1206 tests, 0 failures.**

Bugs the owner check found (all fixed at the root, in the existing parsers):

| Owner said | Before | Fix |
|---|---|---|
| "Colgate 5 box சேர்த்துடு", "அரிசி 10 மூட்டை சேர்த்துடு" | "எதைப் பத்தி சொல்றீங்க?" | Tamil stock-in verbs (சேர்த்துடு, சேர்த்து, வந்தது …) |
| "Colgate 5 box arrived", "5 box Colgate came in today" | "edha pathi sollureenga?" | "arrived", "came in" |
| "Add 5 box of Colgate to stock" | product saved as "Colgate To" | "to" / "into" are not part of a name |
| "Parle G 10 box add pannu" | saved as "Parle" | a one-letter unit ("g") is a unit only right after a number |
| "Bata shoe 6 jodi", "செருப்பு 10 ஜோடி" | asked "pairs-aa, boxes-aa?" | jodi / ஜோடி = pair |
| "Moong dal 3 moota" (no verb) | "edha pathi sollureenga?" | a new product's bare "name + number + unit" starts the questions (never for a product the books have — in / out isn't said) |
| "Cement 10 bag" | "1 bag-la evlo pieces?" and kept in kg | a hardware bag is itself the stock unit |

## Second owner check — 200 more combinations

- **120 new products** (`hundredTwentyNewProductsSaidSixWays`): 20 products not used before (Dove soap, Basmati rice, Urad dal,
  Fanta, Kinley water, Gold winner oil, Milk, Kurti, Saree bundles, Sandals, Nails, Hinges, Earphone, Bulb, Biscuit, Lays chips,
  Sugar moota, Shampoo, Candle, Surf) × 6 ways of saying it (2 Tanglish, 2 Tamil script, 2 English), with answers said
  differently too ("48 irukku", "₹28", "28 ரூபாய்", "Rs 28", "28 rupees") and different yeses (aama / seri / ஆமா / சரி / yes / ok).
- **80 on stock already there** (`eightyMovementsOnStockAlreadyThere`): stock in, sale, damage, wastage, customer return,
  supplier return × box / pieces / "1 box 6 pieces" / moota / kg / "1 bag 5 kg" / bottles × 3 languages (72), plus 8 safety
  cases: cancel (Tanglish, Tamil), selling more than in stock (nothing written), "illa 3 box dhaan" correction on the draft,
  an unknown unit word (Kai asks pieces or boxes), a number with no unit, a shelf count, Confirm tapped twice (one movement).

Found and fixed (root cause, existing parsers):

| Owner said | Before | Fix |
|---|---|---|
| "Got 4 box of Dove soap today" | "what is this about?" / "Who did you receive it from?" | "got" + a stock unit after a number = goods in (without a unit it stays money) |
| "Colgate 2 box விற்றேன்" | "எதைப் பத்தி சொல்றீங்க?" | Tamil sale words (விற்றேன், வித்தேன், வித்துட்டேன் …) |
| "10 pieces Colgate wasted", "Returned 2 moota Rice to supplier" | not understood | "wasted", "… to supplier" (reason: Supplier return) |
| "illa 3 box dhaan" right after a stock draft on a product the books have | "edha pathi sollureenga?" | the same draft is corrected (same key; never a second entry) |

Result: **120 / 120 + 80 / 80**; the first check still **100 / 100 + 150 / 150** — **450 stock dialogues in all.**
Full JVM suite: **1208 tests, 0 failures.**

## Eggs by the tray — `feature/kai-egg-tray`

Owner asked: "Mutta oru tray vandhuruku add pannu" — does it add?
**Before this fix it saved the wrong thing:** "mutta" had been taken as moota (sack), so Kai asked "1 bag evlo kg?" and saved a
product called "Tray" in kg. Fixed:
- mutta / muttai / முட்டை / egg = eggs (grocery); moota / mootta / மூட்டை = bag (sack) as before. "mutta" is no longer a bag.
- "tray" / "trays" / "ட்ரே" is a pack unit (the books create the TRAY unit when it is first used).
- A tray is asked like a box: "1 tray-la evlo pieces irukku?" → bought by the tray, sold by the egg (₹150 a tray = ₹5 an egg).
- Inventory picture: an egg tray (not the rice sack).

Tests: `muttaOneTrayIsEggsNeverASack` (dialogue, PCS, TRAY = 30, ₹5 / ₹6, one opening, then 2 trays in and 10 eggs sold),
`eggWordsInEveryLanguageAndTheSackStaysASack`. Full JVM suite: **1210 tests, 0 failures.** `ProductArt.kt` (egg tray) not compiled here.

## Tamil Nadu shops — every kind of kadai (`tamilNaduShops`, 62 dialogues)

Owner asked to check every kind of Tamil Nadu shop the way "Mutta oru tray" was checked. A first probe of 60 real lines found:
11 entries saved nothing (seepu, thaar, muzham, kattu, churul, bucket, crate, dabba … were not units — Kai asked "pieces-aa,
boxes-aa?"), uram / Tamil "சிமெண்ட்" moota kept in kg with the bag rate taken as a kg rate, thengai moota asked "evlo kg?",
and chicken / fish / sweets / steel vessels / kambi filed as "Grocery" with the rice-sack picture.

Fixed (existing parsers, no new engine):
- New kinds (category + their own questions + their own animated picture): **Vegetables & Fruits** (tomato), **Meat & Fish**
  (fish), **Sweets & Bakery** (laddus), **Pooja & Flowers** (lamp), **Agri & Fertilizer** (sack with a leaf). Tanglish and Tamil
  words for each (thakkali, vengayam, vazhaipazham, thengai, kozhi, meen, mysore pak, laddu, bun, karpooram, kungumam, malli poo,
  uram, urea …). Hardware: kambi, சிமெண்ட், கம்பி. Vessels / plastic stay General even when weighed.
- Units: kattu / கட்டு = bundle, crate = case, dabba / tin / டப்பா = can, seepu / சீப்பு and thaar / தார் (bananas: bought by the
  seepu / thaar, sold by the piece), churul / coil / சுருள் (wire: "1 coil evlo meter?"), muzham / முழம் (flowers kept in muzham),
  bucket (paint kept in buckets), கிலோ, பாக்கெட்.
- A bag of fertilizer is the stock unit (like cement); coconuts in a sack are counted ("1 bag-la evlo pieces?").
- Kind is chosen by a whole word first ("Coconut oil" → oil, "Muttaikose" → cabbage), then a long word inside a name.
- "petti" is NOT made a global word: it stays the owner's own word Kai learns privately (existing personal-learning feature
  and its tests are untouched) — for a new product Kai asks pieces or boxes.
- Safety: "EB bill 500 kattu" (kattu = pay) is never a stock bundle (`payingIsNeverTakenAsAStockBundle`).

Two earlier expectations changed to the more specific category (stock, unit and prices unchanged): Agarbatti → Pooja &
Flowers, Bread → Sweets & Bakery.

Result: **Tamil Nadu shops 62 / 62**, owners 100 / 100, combinations 150 + 120 + 80 all pass. Full JVM suite: **1212 tests, 0
failures.** `ProductArt.kt` (5 new pictures) is not compiled here (Android build blocked); Pixel 8 blocked.

## The owner's 20 shop categories — `feature/kai-shop-categories`

Owner gave 20 categories with their products and units ("intha categories yellam shop owners enna solli stock pannuvanga atha
add pannidu"). Kai's product kinds are now exactly those categories (+ Meat & Fish), with the owner's names as the category label:
Grocery & Staples · FMCG & Personal Care · Snacks & Confectionery · Beverages & Dairy · Home Care & Cleaning · Garments & Textiles ·
Hardware & Plumbing · Electricals & Lighting · Mobile & Electronics · Stationery & Office Supplies · Footwear & Accessories ·
Kitchenware & Household · Pharmacy & Medical · Fruits & Vegetables · Pooja & Religious Items · Agriculture & Gardening ·
Automobile Spare Parts · Bakery & Fresh Foods · Toys, Gifts & Party · Baby Care & Hygiene.

What Kai asks now, per category (one question at a time, every extra detail skippable):
- **Size of one piece** — grams (FMCG, packed snacks, detergent, baby food), litre / ml (anything poured: oil, milk, juice,
  phenyl, syrup, engine oil, pooja oil), **size / colour** (garments, footwear: "M 10 L 10 XL 10 white" → "M 10, L 10, XL 10 · White"),
  size / spec (hardware), watt / size / spec (electricals), **model / compatibility** (mobile), **vehicle / model** (auto spares).
  Nothing for loose grocery, vegetables, stationery, kitchenware, toys, belts / wallets / bags.
- **Pharmacy: batch number and expiry**; **bakery: expiry** ("03/2027", "15/08/2027", "March 2027").
- **Units** the owner listed: tube (= piece), set, roll (fabric / wire / hose → metre), ream (paper kept in reams), basket, crate,
  tablet / maathirai, strip (→ tablets), coil, dozen, tray, pkt. Bought by the pack, sold by the piece where shops do that
  (rice bag / tomato crate / mango basket → kg; strip of tablets bought and sold by the strip, kept in tablets; box of medicine →
  strips; box of masks / bandages → pieces). Greens are kept in kattu; fertilizer and cement in bags.
- Words: Tanglish, Tamil and English for each category, and two-word names that decide together ("engine oil" = auto, "vilakku
  ennai" = pooja, "baby soap" = baby care, "hair oil" = FMCG, "tube light" = electricals, "phone cover" / "tempered glass" = mobile,
  "lunch box" = kitchenware, "return gift" = gifts, "paint brush" = hardware, "detergent powder" = home care).
- Inventory pictures for every category (spray bottle, bulb, pencil, pot, capsule, spark plug, bread, balloon, feeding bottle …;
  oil in the grocery shows an oil can).

Bugs found by the new tests and fixed: "Power bank" was read as a bank (money) → joined to "Powerbank" before anything else;
"A4 paper" lost "A4"; "Return gift" lost "Return"; "7, 8, 9 black" kept only the colour; a box of bandages was asked in strips.

**Batch / expiry — honest limit:** Kai saves the batch and expiry with the product (product notes) and shows them in the summary.
It does NOT mark the product batch-tracked in the books: Kai's stock in / out and the stock screen's buttons do not choose a batch
yet, and the books reject a movement without a batch for a batch-tracked product. Batch-wise stock (first-expiry-first-out,
expiry alerts) is the next step.

Category labels changed (stock, unit and price expectations unchanged): tests updated to the owner's names. Products already saved
under earlier labels ("Grocery", "Liquids", "Sweets & Bakery" …) keep their picture (`productsSavedUnderEarlierLabelsKeepTheirPicture`).

Tests: `twentyShopCategories` — **133 / 133** owner dialogues across all 20 categories (each checked on the inventory: unit,
quantity, pack, purchase / selling per stock unit, category, size / colour / model / vehicle, batch, expiry; some followed by a
sale). All earlier checks still pass (owners 100, combinations 150 + 120 + 80, Tamil Nadu 62). Full JVM suite: **1215 tests,
0 failures.** `ProductArt.kt`, `AppKaiTools.kt` not compiled here (Android build blocked); Pixel 8 blocked.

## 100 owners, a day each — `feature/kai-owner-sessions`

Owner asked: "all categories-la 100 real owner stock add and exit pandramari full test panni bug iruntha fix panni production
move pandramari paru". `hundredOwnersAddThenSellAcrossAllCategories`: 5 owners in each of the 20 categories add their product
by chat (all questions answered, Confirm), then do a day's work — stock in (a pack, or 5 of its unit), a sale, a damage, a
customer return, an entry started and dropped ("venam" / "வேண்டாம்" / "cancel"), and "how much is left?" — owners rotate
Tanglish / Tamil / English. Every step is checked on the inventory (and the dropped entry and the stock answer on Kai's reply).
**100 / 100 owners, 700 steps.**

Bugs it found (fixed at the root):
| Owner said | Before | Fix |
|---|---|---|
| "venam" / "வேண்டாம்" / "cancel" right after a stock draft | "edha pathi sollureenga?" (draft left open) | the draft is dropped: "Seri Owner, cancel pannitten. Edhuvum save aagala." |
| "Sugar எவ்வளவு இருக்கு?" | "இது இர் பத்தியா?" — "இருக்கு" was read as a name with "-க்கு" | இருக்கு / இருக்கா / எவ்வளவு / எத்தனை are words, never names |
| "Murukku evlo irukku?", "How much Phone Cover is left?" | "Murukku-nu customer record illa", "is this about Cover?" | a product of the shop named in full in a stock question is answered with its stock (a real person in the books still wins) |
| "1 packets kuraichiten" | plural for one | one / many said right |

Full JVM suite: **1216 tests, 0 failures.** Android build / Pixel 8 still blocked here.

## Phone bug: "Colgate save aagala" — `fix/kai-voice-draft-save`

Owner's phone: Colgate 50 boxes → summary → Confirm → "Owner, Colgate save aagala — edhuvum save pannala."
**Root cause:** the books engine accepts a VOICE entry only with its reviewed draft (`post()` → DRAFT_REQUIRED; the rule is
already tested in `BooksAccountingTest.voiceEntryNeedsAReviewedDraft`). Kai posted the opening stock (and the purchase / sale of
goods with a supplier / customer) as `TxnSource.VOICE` with no draft, so every chat product was refused and rolled back. The
JVM tests use a fake inventory, so they could not see it.

Fix (`AppKaiTools`): the owner's reviewed chat entry is kept as a voice draft (`engine.saveDraft`) and posted with its
`draftId` — the same as the stock screens' voice entries (`LegacyBridge.meta`). Opening stock: inside the same transaction
as the product. Stock bills: the draft is made right before the post and discarded if the books refuse. The books' own reason is
now logged (`Kai` tag) instead of a bare "not saved".

Test: `BooksAccountingTest.kaiChatProductAndOpeningStockNeedTheReviewedDraft` (Robolectric — runs with `./gradlew test` where the
Android SDK is present; not runnable here). JVM brain suite: 1216 tests, 0 failures. AppKaiTools still not compiled here.
