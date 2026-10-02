# Fetches the pinned OpenCV Android SDK used by app/src/main/cpp/CMakeLists.txt.
#
# The SDK is a prebuilt binary distribution, not source we own, so it is deliberately
# kept out of git (.gitignore) and re-fetched from a checksum-pinned upstream artifact.
# Run this once after a fresh clone, before any Gradle build that compiles native code:
#
#   powershell -ExecutionPolicy Bypass -File scripts\fetch-opencv.ps1
#
# Only sdk/native is extracted (headers, per-ABI CMake config, static libs). The sdk/java,
# sdk/apk, samples and bin trees are not used: this project links OpenCV statically into
# its own C++ library and never touches the OpenCV Java bindings.

$ErrorActionPreference = 'Stop'

$OpenCvVersion = '4.14.0'
$OpenCvSha256  = 'e8cfaf2e51f2e2127a6ede91718d1ef7587f8b6e62db922816e7c33a1f1116a7'
$ZipName       = "opencv-$OpenCvVersion-android-sdk.zip"
$ZipUrl        = "https://github.com/opencv/opencv/releases/download/$OpenCvVersion/$ZipName"

$repoRoot  = Split-Path -Parent $PSScriptRoot
$thirdParty = Join-Path $repoRoot 'third_party'
$zipPath   = Join-Path $thirdParty $ZipName
$extractTo = Join-Path $thirdParty 'opencv'

New-Item -ItemType Directory -Force -Path $thirdParty | Out-Null

if (Test-Path $zipPath) {
    Write-Host "Reusing cached $ZipName"
} else {
    Write-Host "Downloading $ZipUrl"
    Invoke-WebRequest -Uri $ZipUrl -OutFile $zipPath
}

$actual = (Get-FileHash -Path $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $OpenCvSha256) {
    throw "SHA-256 mismatch for $ZipName`n  expected $OpenCvSha256`n  actual   $actual"
}
Write-Host "SHA-256 verified: $actual"

if (Test-Path $extractTo) {
    Write-Host "Removing previous extraction at $extractTo"
    Remove-Item -Recurse -Force $extractTo
}

Write-Host "Extracting sdk/native from $ZipName (this takes a few minutes)"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -notlike 'OpenCV-android-sdk/sdk/native/*') { continue }
        $dest = Join-Path $extractTo $entry.FullName
        if ([string]::IsNullOrEmpty($entry.Name)) {
            New-Item -ItemType Directory -Force -Path $dest | Out-Null
            continue
        }
        $parent = Split-Path -Parent $dest
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
    }
} finally {
    $zip.Dispose()
}

$expected = Join-Path $extractTo 'OpenCV-android-sdk/sdk/native/jni/abi-arm64-v8a/OpenCVConfig.cmake'
if (-not (Test-Path $expected)) {
    throw "Extraction incomplete, missing: $expected"
}
Write-Host "OpenCV $OpenCvVersion native SDK ready at $extractTo"
