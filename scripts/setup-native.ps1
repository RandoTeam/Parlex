# Restores the optional native engines for Parlex.
#
# A clean clone always builds a CPU-only debug APK. Run this script to:
#   * initialise the pinned OpenCL headers submodule;
#   * generate the ARM64 link-time libOpenCL.so stub used by the Adreno path;
#   * clone/checkout the exact llama.cpp revision from app/src/main/cpp/llama.cpp.version;
#   * optionally clone MNN for the hybrid PP-OCR bridge (MNN.version).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/setup-native.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/setup-native.ps1 -WithMnn
#   powershell -ExecutionPolicy Bypass -File scripts/setup-native.ps1 -SkipLlama

param(
    [switch]$SkipLlama,
    [switch]$WithMnn,
    [string]$NdkPath = $env:ANDROID_NDK_HOME
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$cpp = Join-Path $root "app/src/main/cpp"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA "Android/Sdk" }

if (-not $NdkPath) {
    $candidate = Join-Path $sdk "ndk/27.3.13750724"
    if (Test-Path $candidate) { $NdkPath = $candidate }
}

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

Assert-Command git

# 1. Pinned OpenCL headers (link-time only; the device supplies libOpenCL.so).
$openClHeaders = Join-Path $cpp "third_party/OpenCL-Headers"
if (-not (Test-Path (Join-Path $openClHeaders "CL/cl.h"))) {
    Write-Host "Initialising OpenCL-Headers submodule..."
    git -C $root submodule update --init --recursive
}
if (-not (Test-Path (Join-Path $openClHeaders "CL/cl.h"))) {
    throw "OpenCL headers are still missing at $openClHeaders after submodule init."
}

# 2. Generate the link-time stub. It is never packaged; packaging excludes it
#    and the manifest requests the real system libOpenCL.so.
if (-not $NdkPath -or -not (Test-Path $NdkPath)) {
    throw "Android NDK 27.3.13750724 not found. Set ANDROID_NDK_HOME or install it with sdkmanager 'ndk;27.3.13750724'."
}

$hostTag = if ($IsWindows -or $env:OS -eq "Windows_NT") { "windows-x86_64" } elseif ($IsMacOS) { "darwin-x86_64" } else { "linux-x86_64" }
$clangName = if ($hostTag -eq "windows-x86_64") { "aarch64-linux-android26-clang.cmd" } else { "aarch64-linux-android26-clang" }
$clang = Join-Path $NdkPath "toolchains/llvm/prebuilt/$hostTag/bin/$clangName"
if (-not (Test-Path $clang)) {
    throw "NDK clang not found at $clang"
}

$stubDir = Join-Path $cpp "opencl-linker/arm64-v8a"
New-Item -ItemType Directory -Force -Path $stubDir | Out-Null
$stubSource = Join-Path $stubDir "opencl_stub.c"
$clHeader = Join-Path $openClHeaders "CL/cl.h"
$openClSymbols = @(
    Select-String -Path $clHeader -Pattern '^\s*(cl[A-Z][A-Za-z0-9_]*)\s*\(' -AllMatches |
        ForEach-Object { $_.Matches } |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique
)
if ($openClSymbols.Count -eq 0) {
    throw "No OpenCL entry points found in $clHeader"
}
$stubLines = @(
    "/* Link-time OpenCL stubs. The device supplies the real libOpenCL.so. */"
) + ($openClSymbols | ForEach-Object { "void $_ (void) {}" })
Set-Content -LiteralPath $stubSource -Value $stubLines -Encoding ASCII

& $clang -shared -fPIC -o (Join-Path $stubDir "libOpenCL.so") $stubSource
if ($LASTEXITCODE -ne 0) { throw "Failed to compile the OpenCL link stub." }
Write-Host "OpenCL link stub: $stubDir/libOpenCL.so ($($openClSymbols.Count) symbols)"

# 3. llama.cpp at the exact tracked revision.
if (-not $SkipLlama) {
    $versionFile = Join-Path $cpp "llama.cpp.version"
    $versionText = Get-Content -LiteralPath $versionFile -Raw
    $commit = ([regex]::Match($versionText, "llama\.cpp commit:\s*([0-9a-f]{40})")).Groups[1].Value
    if (-not $commit) { throw "Could not parse llama.cpp commit from $versionFile" }

    $llamaDir = Join-Path $cpp "llama.cpp"
    if (-not (Test-Path (Join-Path $llamaDir "CMakeLists.txt"))) {
        if (Test-Path $llamaDir) { Remove-Item -Recurse -Force $llamaDir }
        Write-Host "Cloning llama.cpp..."
        git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git $llamaDir
    }
    git -C $llamaDir fetch --all --tags --prune
    git -C $llamaDir checkout $commit
    if ($LASTEXITCODE -ne 0) { throw "Failed to checkout llama.cpp $commit" }
    Write-Host "llama.cpp: $commit"
}

# 4. MNN is optional and only needed for the hybrid PP-OCR bridge.
if ($WithMnn) {
    $mnnCommit = (Get-Content -LiteralPath (Join-Path $cpp "MNN.version") -Raw).Trim()
    $mnnDir = Join-Path $cpp "MNN"
    if (-not (Test-Path (Join-Path $mnnDir "CMakeLists.txt"))) {
        if (Test-Path $mnnDir) { Remove-Item -Recurse -Force $mnnDir }
        Write-Host "Cloning MNN..."
        git clone --filter=blob:none https://github.com/alibaba/MNN.git $mnnDir
    }
    if ($mnnCommit) {
        git -C $mnnDir fetch --all --tags --prune
        git -C $mnnDir checkout $mnnCommit
        Write-Host "MNN: $mnnCommit"
    }
}

# A stale external-native cache can keep using the JNI stub after llama.cpp is
# restored. Drop it so the next Gradle build links the real native engine.
$cxxCache = Join-Path $root "app/.cxx"
if (Test-Path $cxxCache) {
    Remove-Item -Recurse -Force $cxxCache
    Write-Host "Removed stale native cache: $cxxCache"
}

Write-Host "Native prerequisites are ready. Rebuild with: .\gradlew.bat :app:assembleDebug"
