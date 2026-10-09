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
