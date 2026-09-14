param(
    [string]$Serial = "a36d1946",
    [string]$PackageName = "com.translive.app",
    [string]$OutputDir = "diagnostics/tqi_device_run",
    [int]$WaitTimeoutSec = 15
)

$ErrorActionPreference = "Stop"

# Strict safety rule: OnePlus 13 only
if ($Serial -ne "a36d1946") {
    throw "SAFETY ERROR: Only OnePlus 13 (a36d1946) is permitted. Target '$Serial' is forbidden."
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Parlex Tier 2 On-Device TQI Smoke Runner (OnePlus 13)     " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Verify ADB connection and authorization
$devices = & adb devices -l
$targetLine = $devices | Where-Object { $_ -match "^$Serial\s+" }
if (-not $targetLine) {
    throw "Target device $Serial not found in 'adb devices'."
}
if ($targetLine -match "unauthorized") {
    throw "Device $Serial is unauthorized. Check screen for USB debugging prompt."
}
Write-Host "[✓] Device verified: $targetLine" -ForegroundColor Green

# Ensure output directory exists
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
}

# 2. Check accessibility service status
$a11yServices = & adb -s $Serial shell settings get secure enabled_accessibility_services
$serviceName = "$PackageName/$PackageName.service.accessibility.ScreenAccessibilityService"
if ($a11yServices -notmatch [regex]::Escape($serviceName)) {
    Write-Warning "ScreenAccessibilityService is not enabled in settings. Attempting to enable..."
    & adb -s $Serial shell settings put secure enabled_accessibility_services "$a11yServices:$serviceName"
} else {
    Write-Host "[✓] ScreenAccessibilityService is enabled in settings" -ForegroundColor Green
}

# 3. Bring Firefox test page into foreground
Write-Host "[>] Opening DuckDuckGo search page in Firefox..." -ForegroundColor Yellow
& adb -s $Serial shell am start -a android.intent.action.VIEW -d "https://duckduckgo.com/?q=android+police" org.mozilla.firefox
Start-Sleep -Seconds 3

# 4. Capture baseline pre-translation screenshot
$preScreenshot = Join-Path $OutputDir "pre_translate.png"
Write-Host "[>] Capturing baseline pre-translation screenshot: $preScreenshot" -ForegroundColor Gray
& cmd.exe /c "adb -s $Serial exec-out screencap -p > `"$preScreenshot`""
if (-not (Test-Path $preScreenshot) -or (Get-Item $preScreenshot).Length -lt 1000) {
    throw "Failed to capture baseline screenshot: $preScreenshot is missing or invalid."
}
Write-Host "[✓] Baseline screenshot captured ($((Get-Item $preScreenshot).Length) bytes)" -ForegroundColor Green

# 5. Clear recent logcat to isolate translation logs
& adb -s $Serial logcat -c

# 6. Trigger translation
Write-Host "[>] Triggering Screen Translation..." -ForegroundColor Yellow
# Ensure service is running via AssistTriggerActivity if not already started
& adb -s $Serial shell am start -n "$PackageName/.service.assist.AssistTriggerActivity" -a "android.intent.action.ASSIST"
Start-Sleep -Milliseconds 800

# Tap the floating bubble located on the right edge
# On OnePlus 13 (1440x3168 or 1080x2376 depending on display mode), bubble docks to right edge at Y=screenHeight/2
$wmSize = (& adb -s $Serial shell wm size) -replace ".*Physical size:\s*", ""
Write-Host "[i] Screen physical size: $wmSize" -ForegroundColor Gray
$parts = $wmSize.Trim().Split("x")
$screenWidth = [int]$parts[0]
$screenHeight = [int]$parts[1]
$bubbleX = [int]($screenWidth - 40)
$bubbleY = [int]($screenHeight / 2)

Write-Host "[>] Simulating tap on floating bubble at ($bubbleX, $bubbleY)..." -ForegroundColor Yellow
& adb -s $Serial shell input tap $bubbleX $bubbleY

# 7. Wait for translation pipeline to complete
Write-Host "[>] Waiting for AR translation pipeline to finish..." -ForegroundColor Yellow
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$translated = $false

while ($sw.Elapsed.TotalSeconds -lt $WaitTimeoutSec) {
    Start-Sleep -Milliseconds 600
    $logs = & adb -s $Serial logcat -d -s "ScreenOverlayService" "ArTranslateOverlayView" "NativeScreenExtractor"
    $match = $logs | Where-Object { $_ -match "Rendered\s+(\d+)\s+AR\s+boxes" -or $_ -match "Direct nodes translate" }
    if ($match) {
        $translated = $true
        Write-Host "[✓] Pipeline active: $($match[-1])" -ForegroundColor Green
        # Allow 1 more second for UI overlay animation to complete
        Start-Sleep -Seconds 1
        break
    }
}

# 8. Capture post-translation screenshot
$postScreenshot = Join-Path $OutputDir "post_translate.png"
Write-Host "[>] Capturing post-translation screenshot: $postScreenshot" -ForegroundColor Yellow
& cmd.exe /c "adb -s $Serial exec-out screencap -p > `"$postScreenshot`""

if (-not (Test-Path $postScreenshot) -or (Get-Item $postScreenshot).Length -lt 1000) {
    throw "Failed to capture post-translation screenshot."
}
Write-Host "[✓] Post-translation screenshot captured: $postScreenshot ($((Get-Item $postScreenshot).Length) bytes)" -ForegroundColor Green

# 9. Dump and save relevant logcat
$logcatDump = Join-Path $OutputDir "translation_logcat.txt"
& adb -s $Serial logcat -d -s "ScreenOverlayService" "ArTranslateOverlayView" "NativeScreenExtractor" "ScreenAccessibilityService" "FastBatchTranslator" "ScreenTextNoiseFilter" > $logcatDump
Write-Host "[✓] Translation logs dumped to $logcatDump" -ForegroundColor Green

# 10. Summary
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Tier 2 On-Device Smoke Verification Completed!            " -ForegroundColor Cyan
Write-Host "  Pre-Translation:  $preScreenshot" -ForegroundColor White
Write-Host "  Post-Translation: $postScreenshot" -ForegroundColor White
Write-Host "  Log Dump:         $logcatDump" -ForegroundColor White
Write-Host "============================================================" -ForegroundColor Cyan
