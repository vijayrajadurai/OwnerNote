# Kai LIVE test on the running Pixel 8 emulator (or a USB phone).
# Run from the project folder in Android Studio's Terminal:
#   powershell -ExecutionPolicy Bypass -File scripts\kai-live-test.ps1
# Prints every check as OK or BUG (with what Kai should have done).

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) {
    Write-Host "Emulator odala. Android Studio -> Device Manager -> Pixel 8 -> Play (start) pannunga, appuram marupadiyum run pannunga." -ForegroundColor Red
    exit 1
}
Write-Host "Device: $($devices -join ', ')" -ForegroundColor Cyan

$results = Join-Path $root "app\build\outputs\androidTest-results\connected"
if (Test-Path $results) { Remove-Item -Recurse -Force $results }

Write-Host "Kai live test odudhu (first time 5-10 nimisham aagalaam)..." -ForegroundColor Cyan
& .\gradlew.bat ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=com.shopai.app.live.KaiLiveDeviceTest" --console=plain

$xmls = Get-ChildItem -Path $results -Recurse -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
if (-not $xmls) {
    Write-Host ""
    Write-Host "Test result file illa: build or install fail aagirukku. Mela irukura red 'e:' error lines-ah screenshot anuppunga." -ForegroundColor Red
    exit 1
}

$ok = 0; $bug = 0
Write-Host ""
Write-Host "================ KAI LIVE TEST RESULT ================" -ForegroundColor Cyan
foreach ($x in $xmls) {
    [xml]$doc = Get-Content $x.FullName -Raw -Encoding UTF8
    foreach ($tc in $doc.testsuite.testcase) {
        if ($tc.failure) {
            $bug++
            $msg = $tc.failure.message
            if (-not $msg) { $msg = $tc.failure.'#text' }
            $lines = ($msg -split "`n") | Where-Object { $_.Trim() } | Select-Object -First 3
            Write-Host "BUG  $($tc.name)" -ForegroundColor Red
            foreach ($l in $lines) { Write-Host "       $l" -ForegroundColor Yellow }
        } elseif ($tc.skipped) {
            Write-Host "SKIP $($tc.name)" -ForegroundColor DarkGray
        } else {
            $ok++
            Write-Host "OK   $($tc.name)" -ForegroundColor Green
        }
    }
}
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "Mothama: $ok OK, $bug BUG"
$html = Join-Path $root "app\build\reports\androidTests\connected\debug\index.html"
if (Test-Path $html) { Write-Host "Full report (browser-la open pannunga): $html" }
