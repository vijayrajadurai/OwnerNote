# GST Bill OCR: Production Hardening Report

- **Branch:** `feature/ocr-complete-hardening`, from `origin/main` @ `fdb9205`.
- Not committed, pushed or merged. `main` and `master` were not touched.
- **Environment:** cloud container. There is no Android SDK, emulator or phone here.
  - Unit tests ran on the JVM through a scratch Gradle harness. It compiles the real repo files.
  - Android files were type-checked against `android-all` with stubs for Tesseract, ExifInterface and the UI components.
- **Status labels:** PASS, FAIL, BLOCKED, NOT TESTED. Nothing marked BLOCKED or NOT TESTED is claimed to work.

---

## 1. Baseline (verified earlier on the OnePlus 7, not re-run here)

| Metric | Baseline (34 images, OnePlus 7 GM1901, Android 12) |
|---|---|
| Bills | 34 (0 PASS / 34 FAIL strict) |
| Overall OCR | 77.2 % |
| Critical financial | 84.2 % (154/183) |
| GST total | 70.6 % (24/34) |
| Grand total | 100 % (34/34) |
| Worst case | TEST-024: tax shown as ₹44,056.20 on a ₹4,552.40 bill |
| Crash | after ~23 consecutive scans |

The 34 images, their ground truth and `GstOcrStressTest.kt` are on the PC branch `feature/ocr-gst-stress-test`. That branch was **not pushed**, so they could not be used here. See §9.

## 2. Architecture audit (actual code)

```
DocumentCaptureSection (Bill Scanner: camera / gallery)
  → DeviceTextRecognizer.recognizeFromUri          (Tesseract 4 Android, eng+tam, offline)
      → OcrImagePreprocessor.prepare               (EXIF rotate, scale ≤2048, gray, contrast)
  → BillTextParser.parse → ExtractedBill            (total, items, tax, buyer, date, invoice no, paid)
  → [server fallback] voiceRepository.parseOcrText (sends OCR TEXT, not the image; only when name/total missing; result only offered as a suggestion)
  → BillDirection.decide                           (credit vs debit)
  → BillEntryState.applyScan → BillEntrySection    (review form: Save / Cancel; toVerify gate)
  → saveBill(): createDebit/createCredit(total, party, date, invoice no) + paid part
```

- **What is saved:** party, date, **grand total** and the paid amount, plus the invoice number in the description.
  - Tax, items, GSTIN and taxable value are **never saved**. Tax was only *displayed*.
  - No stock or purchase-item mutation comes from a bill scan.
- **Confirmation:** nothing is saved without the owner's **Save** tap. `canSave` already required `toVerify` to be empty.
- **No duplicates created.** The existing `ExtractedBill`, `BillTextParser`, `BillEntryState` and `DeviceTextRecognizer` were extended. `BillValidation.kt` is new: there was no reconciliation or GSTIN logic before.

## 3. What changed

| Area | Change | File |
|---|---|---|
| Multi-rate GST | **Every** CGST/SGST/IGST line is added up. Before, only the first pair was taken. A footer line that repeats the sum is not counted twice; an inconsistent footer → `suspect`. | `BillTextParser.gstBreakdown` |
| Rate ≠ money | A number followed by `%` is never an amount: in tax lines, totals, the amount-in-words check and GST-total candidates. `@9%` with the amount on the next line is supported. | `lastAmount`, `amountsIn`, `moneyWithPaise` |
| Financial fields | Taxable value, CGST/SGST/IGST, rates, round-off (with sign) and discount are now extracted. | `ExtractedBill` new fields |
| Reconciliation and sanity | Deterministic checks, no AI: taxable + tax ± round-off = total; tax ≤ 40 % of the base and < total; CGST = SGST; ~100× ratio = decimal slip; IGST with CGST/SGST; items vs taxable/total (discount-aware); missing half of the tax. | `BillValidation.check` |
| Status | READY / REVIEW_REQUIRED / INVALID / PARTIAL, with an explainable issue list. **OCR confidence never makes a bill READY.** | `BillCheck`, `BillIssue` |
| TEST-024 safety | An impossible tax is **removed** (`tax = null`), never shown as read. The total is then held for the owner's check. | `parse`, `BillEntryState.applyScan` |
| GSTIN | Extraction plus the official mod-36 check digit (verified on 4 real GSTINs). Position-aware O↔0 / I↔1 / S↔5 / B↔8 fixes are accepted **only** if the check digit then passes. Invalid → flagged, never "fixed". Invoice numbers are not GSTINs. | `GstinReader` |
| Buyer | The right-hand column ("Invoice No: CSH-…", "Date:") is never taken as a name; the name is taken from the next line. Unreadable → `null` + `BUYER_UNREADABLE`. | `cleanCustomer`, `buyerOnNextLine` |
| Items | "Taxable Value", Terms, Declaration, Signatory and IFSC rows are no longer items. HSN code and serial number are removed from names. Qty / unit / unit price are kept only when **qty × price = amount**. | `findItems`, `itemColumns` |
| Tamil letters in English words | `Taxaபble` → `Taxable`, `Invoிce` → `Invoice`, but only within 1 letter of a known label. Pure-Tamil text is untouched. | `repairMixedScript` |
| Review UI | "Some bill details need verification" banner. The tax is shown as "⚠ please check" instead of a wrong value. The total is held with a reason when the amounts don't add up. Confirm / Edit / Cancel are unchanged. | `BillEntrySection.kt`, `strings.xml` (en + ta) |
| OCR engine (crash) | All native `TessBaseAPI` use is serialised by a mutex. Before, concurrent scans could hit the same native object. `api.clear()` now runs in `finally`. Decoded and intermediate bitmaps are recycled right after use; before, up to ~50 MB decoded plus copies per scan were left for the GC. `release()` (screen closed) no longer recycles an engine while a read is still using it; the read frees it when it finishes. | `DeviceTextRecognizer`, `OcrImagePreprocessor` |
| English-first OCR | **Bill scanner only** (`printedBills = true`):<br>1. English pass.<br>2. eng+tam if pass 1 isn't clearly good.<br>3. Deskew + adaptive threshold pass.<br>4. A 90° pass if almost no text was read.<br>The best text is chosen by what adds up (`OcrResultChooser`), not by confidence. Product-label OCR keeps the old single pass. | `DeviceTextRecognizer`, `OcrResultChooser`, `OcrImageMath` |

**Not changed:**
- grand-total parsing (`findLabelledTotal`, `gstGrandTotal`);
- the save flow, accounting, stock, the database, dependencies and Tesseract itself.

No LLM was used. No cloud OCR or upload was added.

## 4. Test results (JVM, this environment)

| Suite | Result |
|---|---|
| Existing suites (incl. KaiAgent, KaiConversationHardening, KaiPersonalLearning, KaiUnitConversion, KaiTime, KaiReminder*, KaiUrgentVoice*, MorningWork*, KaiCalculator, KaiFinalFixes, KaiProductionFixes, BooksTax, BillTextParser, BillGstInvoice, DocumentParsing, StockVoice*) | **614 / 614 PASS** (same as baseline) |
| `BillTotalOcrTest` (11) + `BillEntryVerifyTest` (5) | Previously blocked here. Now run with a plain-JVM state shim: **16 / 16 PASS** on main **and** on this branch. |
| New `BillOcrHardeningTest` | **32 / 32 PASS** |
| **Total** | **662 / 662 PASS, 0 FAIL** |
| Android type-check (DeviceTextRecognizer, OcrImagePreprocessor, BillEntrySection, BillValidation) | PASS (against android-all + stubs) |
| `./gradlew :app:testDebugUnitTest` (real AGP, incl. Robolectric suites: KaiReminderEngineTest, Books*, …) | **BLOCKED**: no Android SDK. Must be run on the PC. |

## 5. Before / after: 50-invoice text benchmark

- **Dataset:** `dataset/text_invoices.json`, 50 synthetic GST invoices as **OCR text**, generated by `tools/gen_text_dataset.py` (seeded).
- **Layouts:** single-rate, multi-rate (3 rates), IGST, discount, round-off, 12-item, decimal qty, Tamil header, no buyer.
- **10 carry the stress-test failure modes:**
  - dropped decimal ×2 (TEST-024 type);
  - Tamil letters in labels ×2;
  - buyer merged with the right-hand column ×2;
  - rate after the amount ×2;
  - amount on the next line ×1;
  - text loss ×1 (TEST-025 type).
- The same file was run through main's parser and this branch's parser.

| Field (50 invoices) | BEFORE (main) | AFTER (branch) |
|---|---|---|
| Grand total | 50/50 (100 %) | **50/50 (100 %)**: no regression |
| GST tax (correct, or withheld when unreadable) | 41/50 (82 %) | **50/50 (100 %)** |
| Wrong tax shown to owner | 9 | **0** |
| Catastrophic tax (≥ bill total) shown | 1 | **0** |
| Buyer name | 48/50 | **50/50** |
| Wrong buyer name shown | 2 | **0** |
| Invoices with exactly the right item list | 1/50 | **50/50** |
| Item rows correct / junk rows | 0 / 211 junk | **160/160, 0 junk** |
| Seller GSTIN valid and correct | n/a (not extracted) | **50/50** |
| Any field wrong while status = READY | n/a | **0** |

**Honest caveat:** I wrote this dataset myself from the stress-test failure descriptions. It is **not** real Tesseract output from real photos, so the numbers show the parser and safety logic, not image OCR. Real-image accuracy on the 34 (and the target 50) must be re-measured on the OnePlus. That is **BLOCKED** here.

## 6. Specific results

| Item | Result |
|---|---|
| TEST-024 pattern (dropped decimal in CGST) | PASS (unit + benchmark).<br>• `tax = null`, status **INVALID** (TAX_IMPOSSIBLE, DECIMAL_SUSPECT, CGST_SGST_MISMATCH).<br>• The total ₹4,552.40 is kept but held for the owner's check.<br>• Save is disabled until checked. |
| TEST-024 real image | BLOCKED (image not available) |
| TEST-025 pattern (text loss, confidence 95) | PASS: `TOO_LITTLE_TEXT`, status not READY, nothing filled in, Save disabled. |
| TEST-025 real image | BLOCKED |
| Crash after ~23 scans | Code-level fixes applied (mutex, finally-clear, bitmap recycle, safe release). **Root cause NOT CONFIRMED.** The stack trace from the PC run was not available here. |
| 50 consecutive device scans | **BLOCKED** (no device). The JVM test "50 consecutive reads, no stale data" PASSES, but it only covers the parser. |

## 7. Device information

| Device | Status |
|---|---|
| OnePlus 7 GM1901, Android 12 | BLOCKED: not reachable from this cloud session |
| Pixel 8 emulator | BLOCKED: no emulator here (it also failed with INSTALL_FAILED_DUPLICATE_PACKAGE on the PC) |

## 8. Remaining risks

1. **The English-first strategy is untested on real photos.** It could change which text wins on some bills.
   - The grand total is protected by scoring: "labelled total + amounts add up" must hold before stopping early.
   - It still needs the 34-image A/B on the OnePlus **before merging**. If it regresses, set `printedBills = false` in `DocumentCaptureSection` to get the old single pass back.
2. **Up to 4 OCR passes on a bad photo:** slower, roughly 2–4× on poor images. Clean bills that read well stop after pass 1.
3. **The second engine (eng-only) adds native memory** while the bill screen is open. It is released with the screen.
4. **Item table extraction is still line-based.** Multi-line item names and tables where OCR splits columns onto separate lines are not reconstructed. Item mismatches are **flagged**, never saved.
5. **Seller name** extraction is unchanged; it was not measured here.
6. **Tamil-script invoices** (not just a Tamil header) were not covered by the generated dataset.

## 9. Production verdict

- **Safe for the existing owner-review flow:** YES, at unit level.
  - Wrong or impossible GST values are no longer shown as read.
  - Money that doesn't add up holds Save until the owner checks it.
  - Only the grand total is saved, as before.
- **Automatic financial acceptance:** **NOT READY.** This was never enabled and must not be. The real-image metrics are unmeasured.
- **Acceptance targets** (≥ 99 % total, ≥ 98 % financial and GST, ≥ 95 % items, no crash in 50 scans):
  - met on the text benchmark only;
  - **unverified on real images**;
  - the 50-scan crash test is **BLOCKED**.

**To finish:**
1. Push `feature/ocr-gst-stress-test` (images, ground truth, test harness), or run it on the PC against this branch, using `DeviceTextRecognizer(context, printedBills = true)`.
2. Run 50+ consecutive scans on the OnePlus.
3. Run `./gradlew :app:testDebugUnitTest`.

## 10. Live device test (added; not run here: no device)

`app/src/androidTest/java/com/shopai/app/live/GstBillLiveTest.kt` + `scripts/ocr-gst-pixel8.ps1`:

- **What it covers:**
  - The 50 sample GST invoices are printed into bill photos on the device: clean, tilt 3°, sideways 90°, dark, shadow, blur, low-res and JPEG q30.
  - Each photo goes through `DeviceTextRecognizer(printedBills = true)` → `BillTextParser` → `BillEntryState` (the review Save gate).
  - Then 60 consecutive scans check for crashes, stale data and native-heap growth.
- **What fails it:** only safety problems:
  - a wrong tax shown;
  - a wrong total marked READY;
  - a crash;
  - the native heap growing by more than 40 MB.

  Accuracy is reported per field and per photo kind in `summary.txt` and `results.json`.
- **Install:** uses `adb install -r` and `am instrument`, so the app's data and login are kept. Results are pulled to `artifacts/ocr-live-pixel8/`.
- **Live check in the app:** 16 sample photos go to the gallery album `OwnerNote-GST-Samples`.

Also changed: the sideways-photo pass now runs when **no pass found a labelled total**, trying 90° and then 270°. Before, it ran only when every pass scored < 20, and garbage text could pass that.
