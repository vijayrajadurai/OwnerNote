# Failures, blocked items and pre-existing issues

## Caused by this change
**None found.**
- All 614 existing JVM tests pass.
- The 16 bill-review tests (`BillTotalOcrTest`, `BillEntryVerifyTest`), previously blocked here, pass on main and on this branch.
- Grand total is unchanged on every fixture and on all 50 benchmark invoices.

## Failures found and fixed during the work

| # | Found | Fix |
|---|---|---|
| 1 | "Cashew 0.5 kg 900.00 450.00": the quantity sat inside the description, so qty and price were not read | The quantity at the end of the name is accepted only when qty × price = amount; otherwise the name is untouched |
| 2 | "Ravi Traders   Date: 12/09/2026" under "Bill To:" was rejected as a metadata line, so the buyer was lost | The right-hand column is removed before the check |
| 3 | Tamil letters inside label words ("Taxaபble Value", "Invoிce No") became junk items. 2/50 benchmark invoices were affected; they were flagged, never READY | Mixed-script words are repaired to the nearest label word (≤ 1 letter away) |
| 4 | Multi-pass OCR would also have applied to product-label scans (StockCaptureSheet), where bill scoring means nothing | Multi-pass applies only to the bill scanner (`printedBills = true`) |
| 5 | My own TEST-025 test wrongly expected `total == null`. The existing design offers a stray number only as a *suggestion* (never filled in) | The test now asserts `totalFromLabel == false` and that Save is disabled. No production change |

## Pre-existing (on main, before this branch)

| Issue | Status |
|---|---|
| Only the first CGST/SGST pair was read on multi-rate bills | Fixed |
| "CGST 90.00 @ 9%" and "SGST @ 9%" alone were read as ₹9 | Fixed |
| "GSTIN: 33…" could be read as a "GST" tax amount when no CGST/SGST line existed | Fixed (identity lines excluded) |
| Impossible tax (TEST-024) was displayed | Fixed (withheld + INVALID) |
| "Taxable Value" became an item; HSN, qty and rate leaked into names | Fixed |
| Buyer name taken from the right-hand column | Fixed |
| Native `TessBaseAPI` was not locked; bitmaps were not recycled; `clear()` was skipped on error; `release()` on screen close could recycle the engine during a running scan | Fixed in code. Device confirmation BLOCKED |

## BLOCKED (not run; never claimed as passing)

| Item | Why |
|---|---|
| 34 real-image re-run, and the 50-image target | Images and harness are on the unpushed PC branch `feature/ocr-gst-stress-test`; there is no device here |
| OnePlus 7 runs (scans, review, edit, cancel, confirm, no accidental save) | No device access from the cloud session |
| Pixel 8 smoke test | No emulator (it also failed on the PC with INSTALL_FAILED_DUPLICATE_PACKAGE) |
| 50 consecutive scans: crash, ANR, memory | No device |
| Crash stack trace and root-cause confirmation | Not available here |
| `./gradlew :app:testDebugUnitTest` (Robolectric / Android suites) | No Android SDK |

## NOT TESTED

- English-first vs eng+tam, measured on real photos.
- Deskew / adaptive-threshold benefit on real dark, shadowed or rotated photos. The logic is unit-tested on synthetic pixel arrays only.
- Seller-name accuracy.
- Fully Tamil-script invoices.
