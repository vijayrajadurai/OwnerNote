# Kai Smart Persistent Reminder - Pixel 8 acceptance test (screen off + locked).
# Start the Pixel 8 emulator first (Device Manager -> Play). The emulator needs a screen lock
# (Settings -> Security -> Screen lock -> Swipe or PIN), otherwise "locked" can't be tested.
# Run from the project folder in Android Studio's Terminal:
#   powershell -ExecutionPolicy Bypass -File scripts\kai-reminder-pixel8.ps1
# Prints FULL_SCREEN_VERIFIED or FULL_SCREEN_NOT_PERMITTED_FALLBACK_VERIFIED - or the BUG / BLOCKER.

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) {
    Write-Host "Emulator odala. Device Manager -> Pixel 8 -> Play pannunga, appuram marupadiyum run pannunga." -ForegroundColor Red
    exit 1
}
Write-Host "Device: $($devices -join ', ')" -ForegroundColor Cyan
& $adb shell getprop ro.build.version.release | ForEach-Object { Write-Host "Android version: $_" }

& $adb logcat -c
$results = Join-Path $root "app\build\outputs\androidTest-results\connected"
if (Test-Path $results) { Remove-Item -Recurse -Force $results }

Write-Host "Kai reminder Pixel 8 test odudhu (screen off aagum, lock aagum - adhu normal)..." -ForegroundColor Cyan
& .\gradlew.bat ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=com.shopai.app.live.KaiReminderPixelTest" --console=plain

Write-Host ""
Write-Host "================ KAI REMINDER - PIXEL 8 RESULT ================" -ForegroundColor Cyan
$log = & $adb logcat -d -s "KaiReminderLive:I" "KaiReminder:I"
$log | ForEach-Object { Write-Host $_ }

$xmls = Get-ChildItem -Path $results -Recurse -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
if (-not $xmls) {
    Write-Host "Test result file illa: build or install fail aagirukku. Mela irukura red 'e:' lines-ah screenshot anuppunga." -ForegroundColor Red
    exit 1
}
$bug = 0
foreach ($x in $xmls) {
    [xml]$doc = Get-Content $x.FullName -Raw -Encoding UTF8
    foreach ($tc in $doc.testsuite.testcase) {
        if ($tc.failure) {
            $bug++
            $msg = $tc.failure.message
            if (-not $msg) { $msg = $tc.failure.'#text' }
            Write-Host "BUG  $($tc.name)" -ForegroundColor Red
            ($msg -split "`n") | Where-Object { $_.Trim() } | Select-Object -First 3 | ForEach-Object { Write-Host "       $_" -ForegroundColor Yellow }
        } else {
            Write-Host "OK   $($tc.name)" -ForegroundColor Green
        }
    }
}
Write-Host "---------------------------------------------------------------"
$result = $log | Select-String "RESULT=" | Select-Object -Last 1
if ($result -and $bug -eq 0) {
    Write-Host ("FINAL: " + ($result.ToString() -replace '.*RESULT=', '')) -ForegroundColor Green
} else {
    Write-Host "FINAL: NOT VERIFIED - mela irukura BUG / BLOCKER line-ah screenshot anuppunga." -ForegroundColor Red
}
