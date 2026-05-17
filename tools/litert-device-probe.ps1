param(
    [string]$Serial = "",
    [string]$PackageName = "com.translive.app",
    [string]$OutDir = "diagnostics"
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $output = & adb @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ($output -join "`n")
    }
    return $output
}

function Invoke-DeviceShell {
    param([string]$Command)
    $args = @()
    if ($Serial) {
        $args += @("-s", $Serial)
    }
    $args += @("shell", $Command)
    return (Invoke-Adb -AdbArgs $args) -join "`n"
}

function Get-Prop {
    param([string]$Name)
    try {
        return (Invoke-DeviceShell "getprop $Name").Trim()
    } catch {
        return ""
    }
}

function Select-DefaultSerial {
    $raw = Invoke-Adb -AdbArgs @("devices", "-l")
    $devices = $raw |
        Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "^List of devices" } |
        ForEach-Object {
            $parts = $_ -split "\s+"
            [pscustomobject]@{
                Serial = $parts[0]
                Line = $_
                IsEmulator = $parts[0] -like "emulator-*"
            }
        }

    $physical = $devices | Where-Object { -not $_.IsEmulator } | Select-Object -First 1
    if ($physical) {
        return $physical.Serial
    }

    $first = $devices | Select-Object -First 1
    if ($first) {
        return $first.Serial
    }

    throw "No adb device found."
}

if (-not $Serial) {
    $Serial = Select-DefaultSerial
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outPath = Join-Path $OutDir "litert-device-probe-$timestamp.md"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$deviceList = (Invoke-Adb -AdbArgs @("devices", "-l")) -join "`n"
$model = Get-Prop "ro.product.model"
$manufacturer = Get-Prop "ro.product.manufacturer"
$device = Get-Prop "ro.product.device"
$socModel = Get-Prop "ro.soc.model"
$hardware = Get-Prop "ro.hardware"
$android = Get-Prop "ro.build.version.release"
$sdk = Get-Prop "ro.build.version.sdk"
$abi = Get-Prop "ro.vendor.product.cpu.abilist"
$meminfo = Invoke-DeviceShell "cat /proc/meminfo | head -n 8"

$packageInfo = ""
try {
    $packageInfo = Invoke-DeviceShell "dumpsys package $PackageName | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime' | head -n 12"
} catch {
    $packageInfo = "Package $PackageName not found or dumpsys unavailable."
}

$appMemInfo = ""
try {
    $appMemInfo = Invoke-DeviceShell "dumpsys meminfo $PackageName | head -n 35"
} catch {
    $appMemInfo = "App process is not running or meminfo unavailable."
}

$thermal = ""
try {
    $thermal = Invoke-DeviceShell "cmd thermalservice status"
} catch {
    $thermal = "Thermal service status unavailable."
}

$gpu = ""
try {
    $gpu = Invoke-DeviceShell "cat /sys/class/kgsl/kgsl-3d0/gpu_model"
} catch {
    $gpu = "GPU model unavailable."
}

$report = @"
# LiteRT Device Probe

- Timestamp: $timestamp
- Selected serial: $Serial
- Package: $PackageName

## ADB Devices

``````
$deviceList
``````

## Device

- Manufacturer: $manufacturer
- Model: $model
- Device: $device
- SoC model: $socModel
- Hardware: $hardware
- Android: $android
- SDK: $sdk
- ABI: $abi
- GPU: $gpu

## Memory

``````
$meminfo
``````

## Package

``````
$packageInfo
``````

## App Memory

``````
$appMemInfo
``````

## Thermal

``````
$thermal
``````

## Benchmark Notes

- Use this report to confirm that measurements are from the intended Snapdragon 8 Elite phone.
- Do not compare LiteRT numbers from emulator or Snapdragon 845 against production GGUF numbers.
"@

Set-Content -Path $outPath -Value $report -Encoding UTF8
Write-Output $outPath
