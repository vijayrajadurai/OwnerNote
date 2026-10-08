# Kai Reminder natural voice + personal reminders - LIVE auto test on the Pixel 8 emulator.
# Nothing to type or tap: just watch the emulator and listen.
#
# What happens (about 12-14 minutes):
#   D  Kai chat: "Paiyana 4 manikku ..." -> Confirm -> "Time maathu" -> "5 mani" -> the SAME reminder at 5 PM.
#   A  "Kumar-ku 2 minutes-la call panna remind pannu" -> Confirm -> REAL alarm (2 min) -> Kai speaks the first
#      turn as ONE clip -> CALL NOW is tapped while Kai is speaking -> the voice must stop at once.
#   B  "2 minutes-la paiyana school-la irundhu kootitu vara ..." -> real alarm -> DONE tapped mid-sentence.
#   C  "5 minutes-la Amma-ku call panna ..." -> real alarm (5 min) -> SNOOZE tapped mid-sentence.
#   Measured on the real natural voice: the pauses between sentences (old way vs new), one continuous
#   utterance, how fast Call / Done / Snooze stop the voice, nothing queued after.
#
# Before running:
#   1. Android Studio -> Device Manager -> Pixel 8 -> Play (wait for the home screen).
#   2. git switch feature/kai-reminder-natural-voice
#   3. PC speaker / headphone volume up.
#
# Run from the project folder in Android Studio's Terminal:
#   powershell -ExecutionPolicy Bypass -File scripts\kai-voice-pixel8.ps1
#   (add -Clean if install fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE for the TEST apk)
#
# App data / login are kept (adb install -r, not Gradle's connected test which uninstalls the app).
# A second window shows Kai's live log while it runs.
# Results: artifacts\kai-voice-live-pixel8\  (summary.txt, the joined voice turns as .wav, screenshots, logcat.txt)
# FINAL line is exactly one of: PASS / BUG / BLOCKER.

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
if ((git branch --show-current) -ne "feature/kai-reminder-natural-voice") {
    Write-Host "Note: branch feature/kai-reminder-natural-voice illa - 'git switch feature/kai-reminder-natural-voice' pannunga." -ForegroundColor Yellow
}

$pkg = "com.ownernote.app"
$testPkg = "com.ownernote.app.test"

Write-Host "Building app + test APKs..." -ForegroundColor Cyan
& .\gradlew.bat ":app:assembleDebug" ":app:assembleDebugAndroidTest" --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host "FINAL: BLOCKER - build fail aachu (mela red 'e:' lines paarunga)." -ForegroundColor Red; exit 1 }

$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $root "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

if ($Clean) {
    Write-Host "-Clean: old TEST apk mattum remove panren (app data apdiye irukkum)..." -ForegroundColor Yellow
    & $adb uninstall $testPkg 2>$null | Out-Null
}
Write-Host "Installing (data kept)..." -ForegroundColor Cyan
$i1 = & $adb install -r -t $apk 2>&1
$i2 = & $adb install -r -t $testApk 2>&1
if (($i1 -join " ") -notmatch "Success" -or ($i2 -join " ") -notmatch "Success") {
    Write-Host ($i1 -join "`n"); Write-Host ($i2 -join "`n")
    Write-Host "FINAL: BLOCKER - install fail. '-Clean' serthu thirumba run pannunga." -ForegroundColor Red
    exit 1
}

# Permissions the reminder needs (some may not apply on this Android version - that is fine).
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
& $adb shell appops set $pkg SCHEDULE_EXACT_ALARM allow 2>$null | Out-Null
& $adb shell appops set $pkg USE_FULL_SCREEN_INTENT allow 2>$null | Out-Null
& $adb shell cmd media_session volume --stream 3 --set 12 2>$null | Out-Null
& $adb shell cmd media_session volume --stream 4 --set 6 2>$null | Out-Null
& $adb shell rm -rf "/sdcard/Android/data/$pkg/files/kai-voice-live" 2>$null | Out-Null
& $adb logcat -c

# Live log in a second window (closed at the end).
$live = Start-Process powershell -PassThru -ArgumentList "-NoExit", "-Command", "`$host.UI.RawUI.WindowTitle='Kai voice - live log'; & '$adb' logcat -v time -s KaiVoiceLive:I KaiReminder:I NaturalTtsSpeaker:I"

Write-Host ""
Write-Host "Kai voice LIVE test odudhu (~12-14 min). Emulator-ai paarunga, voice-ai kelunga - edhuvum press panna vendaam." -ForegroundColor Cyan
Write-Host "  D: chat 'Time maathu'   A: Kumar call (2 min) -> CALL NOW   B: paiyan pickup (2 min) -> DONE   C: Amma call (5 min) -> SNOOZE" -ForegroundColor Cyan
$run = & $adb shell am instrument -w -r -e class com.shopai.app.live.KaiReminderVoiceLiveTest "$testPkg/androidx.test.runner.AndroidJUnitRunner" 2>&1

$out = Join-Path $root "artifacts\kai-voice-live-pixel8"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null
& $adb pull "/sdcard/Android/data/$pkg/files/kai-voice-live/." $out 2>$null | Out-Null
$log = & $adb logcat -d -v time -s KaiVoiceLive:I KaiReminder:I NaturalTtsSpeaker:I AndroidRuntime:E
$log | Out-File -Encoding UTF8 (Join-Path $out "logcat.txt")
$run | Out-File -Encoding UTF8 (Join-Path $out "instrument.txt")
$crash = & $adb logcat -d -b crash
$crash | Out-File -Encoding UTF8 (Join-Path $out "crash.txt")
if ($live) { Stop-Process -Id $live.Id -ErrorAction SilentlyContinue }

Write-Host ""
Write-Host "================ KAI REMINDER VOICE - LIVE RESULT ================" -ForegroundColor Cyan
$summary = Join-Path $out "summary.txt"
if (Test-Path $summary) {
    Get-Content $summary -Encoding UTF8 | Where-Object { $_ -match "RESULT=|JOIN_GAPS_MS|turn1 parts|TURN1|voiceStoppedAfterMs|afterAnswer|NEXT_TURN|NOT_MEASURED|log: |remindersWithThisTask|RANG|sarvamClipPadding" } | ForEach-Object { Write-Host $_ }
} else {
    Write-Host "summary.txt varala - test odala. instrument output:" -ForegroundColor Red
    $run | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
}

# Each test: OK / FAIL with the first line of its error.
$text = ($run -join "`n")
$run | Where-Object { $_ -match "^\d+\) v\d_" -or $_ -match "(AssertionError|Exception): " } | Select-Object -First 20 | ForEach-Object { Write-Host ("FAIL " + $_.Trim()) -ForegroundColor Red }
# Only OUR app's crashes count. The emulator's own services (e.g. android.hardware.uwb-service) crash on some images - not the app.
$appCrash = $crash | Select-String -Pattern "ownernote|shopai"
if ($appCrash) { Write-Host "APP CRASH LOG:" -ForegroundColor Red; $appCrash | Select-Object -First 40 | ForEach-Object { Write-Host $_ } }
elseif ($crash) { Write-Host ("Note: emulator system crash log has " + @($crash).Count + " lines (not the app - e.g. uwb-service). Ignored.") -ForegroundColor DarkGray }

Write-Host ""
Write-Host "Results folder: $out  (turn1 .wav files = Kai's first turn exactly as joined - double-click to listen)"
if (-not (Test-Path $summary)) { Write-Host "FINAL: BLOCKER" -ForegroundColor Red }
elseif ($text -match "Process crashed" -or $appCrash) { Write-Host "FINAL: BUG (app crash)" -ForegroundColor Red }
elseif ($text -match "BLOCKER") { Write-Host "FINAL: BLOCKER" -ForegroundColor Red }
elseif ($text -match "FAILURES!!!") { Write-Host "FINAL: BUG" -ForegroundColor Red }
elseif ($text -match "OK \(\d+ tests?\)") { Write-Host "FINAL: PASS" -ForegroundColor Green }
else { Write-Host "FINAL: BLOCKER - result line illa (instrument.txt paarunga)" -ForegroundColor Red }
