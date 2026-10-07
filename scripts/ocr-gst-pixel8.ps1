# GST bill OCR - live auto test on the Pixel 8 emulator (or any phone on adb).
#
# What it does:
#   50 sample GST invoices (multi-rate, IGST, discount, round-off, 12 items, decimal qty,
#   Tamil header, no buyer, TEST-024 / TEST-025 style faults) are printed into bill photos
#   ON the device - clean, tilted, sideways, dark, shadow, blur, low-res, heavy JPEG - and
#   read through the Bill Scanner's own OCR path. Then 60 scans in a row (crash / memory).
#   Nothing is saved to the ledger or stock.
#
# Before running:
#   1. Android Studio -> Device Manager -> Pixel 8 -> Play (wait for the home screen).
#   2. Be on the branch:  git switch feature/ocr-complete-hardening
#
# Run from the project folder in Android Studio's Terminal:
#   powershell -ExecutionPolicy Bypass -File scripts\ocr-gst-pixel8.ps1
#   (add -Clean if install fails with INSTALL_FAILED_DUPLICATE_PACKAGE / UPDATE_INCOMPATIBLE)
#
# Takes ~10-25 minutes on an emulator (Tesseract is slow there). App data / login is kept
# (adb install -r, not Gradle's connected test which uninstalls the app afterwards).
# Results: artifacts\ocr-live-pixel8\  (summary.txt, results.json, the bill images, logcat.txt)
# FINAL line is exactly one of: SAFE / SAFETY_FAIL / BLOCKER.

param([switch]$Clean)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) {
    Write-Host "FINAL: BLOCKER - emulator odala. Device Manager -> Pixel 8 -> Play pannunga." -ForegroundColor Red
    exit 1
}
Write-Host "Device: $($devices -join ', ')" -ForegroundColor Cyan
Write-Host ("Model: " + (& $adb shell getprop ro.product.model) + "  Android: " + (& $adb shell getprop ro.build.version.release) + "  SDK: " + (& $adb shell getprop ro.build.version.sdk))
Write-Host ("Branch: " + (git branch --show-current) + "  Commit: " + (git log -1 --oneline))

$pkg = "com.ownernote.app"
$testPkg = "com.ownernote.app.test"

Write-Host "Building app + test APKs..." -ForegroundColor Cyan
& .\gradlew.bat ":app:assembleDebug" ":app:assembleDebugAndroidTest" --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host "FINAL: BLOCKER - build fail aachu (mela error paarunga)." -ForegroundColor Red; exit 1 }

$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $root "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

if ($Clean) {
    Write-Host "-Clean: old test APK remove panren (app data apdiye irukkum)..." -ForegroundColor Yellow
    & $adb uninstall $testPkg 2>$null | Out-Null
}
Write-Host "Installing (data kept)..." -ForegroundColor Cyan
$i1 = & $adb install -r -t $apk 2>&1
$i2 = & $adb install -r -t $testApk 2>&1
if (($i1 -join " ") -notmatch "Success" -or ($i2 -join " ") -notmatch "Success") {
    Write-Host ($i1 -join "`n"); Write-Host ($i2 -join "`n")
    Write-Host "FINAL: BLOCKER - install fail. '-Clean' serthu thirumba run pannunga; adhuvum fail aana emulator-ai Cold Boot pannunga." -ForegroundColor Red
    exit 1
}

& $adb logcat -c
Write-Host "GST live OCR test odudhu (50 bills + 60 repeat scans, ~10-25 min)..." -ForegroundColor Cyan
$run = & $adb shell am instrument -w -r -e class com.shopai.app.live.GstBillLiveTest "$testPkg/androidx.test.runner.AndroidJUnitRunner" 2>&1
$run | Out-File -Encoding UTF8 (Join-Path $env:TEMP "gst-instrument.txt")

$out = Join-Path $root "artifacts\ocr-live-pixel8"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null
& $adb pull "/sdcard/Android/data/$pkg/files/ocr-live/." $out 2>$null | Out-Null
$log = & $adb logcat -d -s "GstLive:I" "DeviceTextRecognizer:W" "AndroidRuntime:E"
$log | Out-File -Encoding UTF8 (Join-Path $out "logcat.txt")
$run | Out-File -Encoding UTF8 (Join-Path $out "instrument.txt")
$crash = & $adb logcat -d -b crash
$crash | Out-File -Encoding UTF8 (Join-Path $out "crash.txt")

Write-Host ""
Write-Host "================ GST BILL OCR - LIVE RESULT ================" -ForegroundColor Cyan
$summary = Join-Path $out "summary.txt"
if (Test-Path $summary) { Get-Content $summary | ForEach-Object { Write-Host $_ } }
else { Write-Host "summary.txt varala - test mudiyala. instrument.txt paarunga:" -ForegroundColor Red; $run | Select-Object -Last 30 | ForEach-Object { Write-Host $_ } }
if ($crash) { Write-Host "CRASH LOG:" -ForegroundColor Red; $crash | Select-Object -First 40 | ForEach-Object { Write-Host $_ } }
Write-Host ""
Write-Host "Results folder: $out"
Write-Host "Gallery-la 'OwnerNote-GST-Samples' album-la 16 bill photos irukku: app -> Bill Scanner -> Gallery-la pick panni live-a paarkalam."

$text = ($run -join "`n")
if (-not (Test-Path $summary)) { Write-Host "FINAL: BLOCKER" -ForegroundColor Red }
elseif ($text -match "FAILURES!!!" -or $text -match "Process crashed" -or (Get-Content $summary -Raw) -match "SAFETY_FAIL") { Write-Host "FINAL: SAFETY_FAIL" -ForegroundColor Red }
else { Write-Host "FINAL: SAFE" -ForegroundColor Green }
