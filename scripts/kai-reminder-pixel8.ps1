# Kai Smart Persistent Reminder - Pixel 8 acceptance test (screen off + locked, REAL alarm).
# Before running:
#   1. Start the Pixel 8 emulator (Device Manager -> Play).
#   2. In the emulator: Settings -> Security -> Screen lock -> Swipe (or PIN).
# Run from the project folder in Android Studio's Terminal:
#   powershell -ExecutionPolicy Bypass -File scripts\kai-reminder-pixel8.ps1
# Takes ~5 minutes (t1 waits for the real 2-minute alarm). The screen will turn off and lock - that is the test.
# FINAL line is exactly one of: FULL_SCREEN_VERIFIED / FULL_SCREEN_NOT_PERMITTED_FALLBACK_VERIFIED / BUG / BLOCKER.

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

& $adb logcat -c
$results = Join-Path $root "app\build\outputs\androidTest-results\connected"
if (Test-Path $results) { Remove-Item -Recurse -Force $results }

Write-Host "Kai reminder Pixel 8 test odudhu (~5 min; screen off + lock aagum - adhu dhaan test)..." -ForegroundColor Cyan
& .\gradlew.bat ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=com.shopai.app.live.KaiReminderPixelTest" --console=plain

Write-Host ""
Write-Host "================ KAI REMINDER - PIXEL 8 EVIDENCE ================" -ForegroundColor Cyan
$log = & $adb logcat -d -s "KaiReminderLive:I" "KaiReminder:I"
$log | ForEach-Object { Write-Host $_ }

# Screenshots of the locked screen (evidence for the report).
$pkg = (& $adb shell pm list packages | Select-String "ownernote|shopai" | Select-Object -First 1).ToString().Replace("package:", "").Trim()
$shots = Join-Path $root "artifacts\kai-reminder\pixel8"
New-Item -ItemType Directory -Force -Path $shots | Out-Null
& $adb pull "/sdcard/Android/data/$pkg/files/kai-reminder/." $shots 2>$null | Out-Null
$log | Out-File -Encoding UTF8 (Join-Path $shots "logcat.txt")
Write-Host "Screenshots + log: $shots"

$xmls = Get-ChildItem -Path $results -Recurse -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
if (-not $xmls) {
    Write-Host "FINAL: BLOCKER - test result file illa (build / install fail). Mela irukura red 'e:' lines-ah screenshot anuppunga." -ForegroundColor Red
    exit 1
}
$bugs = @(); $blockers = @(); $skipped = @()
Write-Host ""
foreach ($x in $xmls) {
    [xml]$doc = Get-Content $x.FullName -Raw -Encoding UTF8
    foreach ($tc in $doc.testsuite.testcase) {
        if ($tc.failure) {
            $msg = $tc.failure.message
            if (-not $msg) { $msg = $tc.failure.'#text' }
            $first = (($msg -split "`n") | Where-Object { $_.Trim() } | Select-Object -First 1)
            if ($first -match "BLOCKER") { $blockers += "$($tc.name): $first" } else { $bugs += "$($tc.name): $first" }
            Write-Host "FAIL $($tc.name)" -ForegroundColor Red
            Write-Host "       $first" -ForegroundColor Yellow
        } elseif ($tc.skipped) {
            $skipped += $tc.name
            Write-Host "SKIP $($tc.name)" -ForegroundColor DarkGray
        } else {
            Write-Host "OK   $($tc.name)" -ForegroundColor Green
        }
    }
}
Write-Host "-----------------------------------------------------------------"
# Cinematic Kai (t7): his size on screen, the controls, body motion, the voice loop, silence after Done.
$log | Select-String "t7 (kaiHeightShare|kaiBox|tallestDp|kaiBodyMotion|voiceLines|voiceEngine|voiceStartAfterMs|kaiVisibleAfterMs|silentAfterDone)" | ForEach-Object { Write-Host ("  " + ($_.ToString() -replace '.*KaiReminderLive: ', '')) }
$result = $log | Select-String "RESULT=" | Select-Object -Last 1
if ($blockers.Count -gt 0) {
    Write-Host "FINAL: BLOCKER" -ForegroundColor Red; $blockers | ForEach-Object { Write-Host "  $_" }
} elseif ($bugs.Count -gt 0) {
    Write-Host "FINAL: BUG" -ForegroundColor Red; $bugs | ForEach-Object { Write-Host "  $_" }
} elseif ($result) {
    Write-Host ("FINAL: " + ($result.ToString() -replace '.*RESULT=', '').Trim()) -ForegroundColor Green
} else {
    Write-Host "FINAL: BLOCKER - t1 RESULT line illa (test run aagala?)" -ForegroundColor Red
}
