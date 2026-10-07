# Test matrix: the spec's 30 areas

- **Where:** JVM = this cloud harness (real repo code). DEVICE = OnePlus 7 / Pixel 8.
- **Where the tests live:**
  - New tests: `app/src/test/java/com/shopai/app/util/BillOcrHardeningTest.kt` (32 tests).
  - Benchmark: `dataset/text_invoices.json` (50 invoices), results in `dataset/text_benchmark_results.json`.

| # | Area | Test(s) | JVM | DEVICE |
|---|---|---|---|---|
| 1 | Clean invoice | `cleanInvoiceIsReadyAndFullyRead` + 10 benchmark "single" | PASS | BLOCKED |
| 2 | Multi-item | 12-item benchmark ×5, `gstTableRowsGiveHsnQtyUnitAndPrice` | PASS | BLOCKED |
| 3 | Multi-rate GST | `multiRateGstAddsEveryRateLine`, `footerTotalOfRateLinesIsNotCountedTwice`, benchmark "multi" ×5 | PASS | BLOCKED |
| 4 | CGST + SGST | `cleanInvoiceIsReadyAndFullyRead` | PASS | BLOCKED |
| 5 | IGST | `igstInvoice`, `igstTogetherWithCgstSgstNeedsReview`, benchmark "igst" ×5 | PASS | BLOCKED |
| 6 | Decimal price | `decimalPriceAndQuantity` | PASS | BLOCKED |
| 7 | Decimal quantity | `decimalPriceAndQuantity`, benchmark "decimalqty" ×5 | PASS | BLOCKED |
| 8 | Discount | `discountIsReadAndItemsStillReconcile`, benchmark "discount" ×5 | PASS | BLOCKED |
| 9 | Round-off | `roundOffReconciles`, benchmark "roundoff" ×5 | PASS | BLOCKED |
| 10 | Grand total | `grandTotalOfExistingGstFixturesUnchanged` + all existing total tests + benchmark 50/50 | PASS | BLOCKED |
| 11 | GSTIN | `gstinCheckDigitAndPositionAwareOcrFixes`, `anInvoiceNumberIsNotAGstin`, benchmark 50/50 | PASS | BLOCKED |
| 12 | Invoice number | `cleanInvoiceIsReadyAndFullyRead`, `buyerNameIsNeverTheRightHandColumn`, `tamilLettersInsideEnglishLabelsAreRepaired` | PASS | BLOCKED |
| 13 | Invoice date | `cleanInvoiceIsReadyAndFullyRead` | PASS | BLOCKED |
| 14 | Buyer name | `buyerNameIsNeverTheRightHandColumn`, `unreadableBuyerIsLeftEmptyAndMarked`, benchmark 50/50 | PASS | BLOCKED |
| 15 | Seller name | `cleanInvoiceIsReadyAndFullyRead` (one fixture) | PASS (limited) | BLOCKED |
| 16 | "Taxable Value" header | `summaryAndFooterRowsAreNeverItems`, benchmark 0 junk rows | PASS | BLOCKED |
| 17 | CGST header | `summaryAndFooterRowsAreNeverItems` | PASS | BLOCKED |
| 18 | SGST header | `summaryAndFooterRowsAreNeverItems` | PASS | BLOCKED |
| 19 | Footer rows | `summaryAndFooterRowsAreNeverItems`, clean fixture (Terms / Signatory) | PASS | BLOCKED |
| 20 | HSN extraction | `gstTableRowsGiveHsnQtyUnitAndPrice`, `indoBurmaStyleRowDropsSerialAndHsnFromTheName` | PASS | BLOCKED |
| 21 | TEST-024 | `test024DroppedDecimalIsNeverAccepted`, `test024ReviewFormHoldsTheTotalAndHidesTheTax`, benchmark ×2 | PASS (text pattern) | BLOCKED (real image) |
| 22 | TEST-025 | `test025TextLossWithHighConfidenceIsNeverReady`, benchmark ×1 | PASS (text pattern) | BLOCKED (real image) |
| 23 | Impossible tax | `impossibleTaxIsRejected` | PASS | BLOCKED |
| 24 | Grand-total mismatch | `grandTotalMismatchNeedsReview` | PASS | BLOCKED |
| 25 | Invalid GSTIN | `invalidGstinIsFlaggedNotAccepted` | PASS | BLOCKED |
| 26 | Missing quantity | `missingQuantityOrPriceIsLeftEmptyNeverGuessed` | PASS | BLOCKED |
| 27 | Missing unit price | `missingQuantityOrPriceIsLeftEmptyNeverGuessed` | PASS | BLOCKED |
| 28 | Corrupted OCR | `corruptedOcrNeverCrashesOrClaimsReady`, `tamilLettersInsideEnglishLabelsAreRepaired` | PASS | BLOCKED |
| 29 | 50 consecutive scans | `fiftyConsecutiveReadsKeepNoStaleData` (parser only) | PASS (parser) | **BLOCKED** (device scans, crash, memory) |
| 30 | Review-required safety gate | `test024ReviewFormHoldsTheTotalAndHidesTheTax`, `grandTotalMismatchNeedsReview`, `readyBillStillWaitsForTheOwnersSave` + existing `BillEntryVerifyTest` | PASS | BLOCKED |
| + | OCR pass choice (not by confidence) | `ocrChooserPrefersWhatAddsUpNotConfidence` | PASS | NOT TESTED |
| + | Adaptive threshold / deskew math | `adaptiveThresholdKeepsTextUnderAShadow`, `skewIsFoundOnATiltedPage` | PASS | NOT TESTED |
| + | Rate never read as money | `aRateIsNeverReadAsAnAmount` | PASS | BLOCKED |

## Counts

| | PASS | FAIL | BLOCKED | NOT TESTED |
|---|---|---|---|---|
| JVM unit tests (all suites) | 662 | 0 | 17 Android/Robolectric suites need the PC | — |
| Spec areas 1–30, JVM | 30 | 0 | 0 | 0 |
| Spec areas 1–30, DEVICE | 0 | 0 | 30 | 0 |
| Spec §24 real-device checks (scans, clean/difficult, multi-rate, blur, shadow, rotation, Tamil/English, repeat, review, edit, cancel, confirm, no accidental save) | 0 | 0 | 13 | 0 |
| English-first A/B, deskew/threshold on real photos | 0 | 0 | 0 | 2 |
