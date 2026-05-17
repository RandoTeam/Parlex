param(
    [ValidateSet("int4", "dynamic_int8")]
    [string]$Quant = "int4",
    [string]$OutDir = "models/litert",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$artifacts = @{
    int4 = @{
        Url = "https://huggingface.co/barakplasma/translategemma-4b-it-android-task-quantized/resolve/main/artifacts/int4-generic/translategemma-4b-it-int4-generic.litertlm"
        File = "translategemma-4b-it-int4-generic.litertlm"
        Size = 2011201536L
    }
    dynamic_int8 = @{
        Url = "https://huggingface.co/barakplasma/translategemma-4b-it-android-task-quantized/resolve/main/artifacts/dynamic_int8-generic/translategemma-4b-it-dynamic_int8-generic.litertlm"
        File = "translategemma-4b-it-dynamic_int8-generic.litertlm"
        Size = 3920576512L
    }
}

$artifact = $artifacts[$Quant]
$targetDir = Join-Path (Get-Location) $OutDir
$target = Join-Path $targetDir $artifact.File
$expectedSize = [int64]$artifact.Size

function Format-Bytes {
    param([int64]$Bytes)
    if ($Bytes -ge 1GB) {
        return "{0:N2} GB" -f ($Bytes / 1GB)
    }
    if ($Bytes -ge 1MB) {
        return "{0:N0} MB" -f ($Bytes / 1MB)
    }
    return "$Bytes B"
}

New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
$drive = Get-PSDrive -Name ([System.IO.Path]::GetPathRoot($targetDir).Substring(0, 1))
$required = [int64]($expectedSize * 1.1)

$summary = [pscustomobject]@{
    Quant = $Quant
    Url = $artifact.Url
    Target = $target
    ExpectedSize = Format-Bytes $expectedSize
    FreeSpace = Format-Bytes ([int64]$drive.Free)
    DryRun = [bool]$DryRun
}

if ($DryRun) {
    $summary | Format-List | Out-String
    exit 0
}

if ($drive.Free -lt $required) {
    throw "Not enough free space. Need at least $(Format-Bytes $required), available $(Format-Bytes ([int64]$drive.Free))."
}

if (Test-Path $target) {
    $currentSize = (Get-Item $target).Length
    if ($currentSize -eq $expectedSize) {
        Write-Output "Already downloaded: $target ($(Format-Bytes $currentSize))"
        exit 0
    }
    Write-Output "Existing partial file: $target ($(Format-Bytes $currentSize)); attempting resume."
}

$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) {
    throw "curl.exe is required for resumable downloads."
}

& $curl.Source `
    --location `
    --fail `
    --continue-at - `
    --output $target `
    $artifact.Url

if ($LASTEXITCODE -ne 0) {
    throw "curl download failed with exit code $LASTEXITCODE."
}

$actualSize = (Get-Item $target).Length
if ($actualSize -ne $expectedSize) {
    throw "Downloaded size mismatch. Expected $(Format-Bytes $expectedSize), got $(Format-Bytes $actualSize)."
}

Write-Output "Downloaded: $target ($(Format-Bytes $actualSize))"
