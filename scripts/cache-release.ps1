# Cache the release APK locally, with checksum verification.
[CmdletBinding()]
param(
    [string]$BuildHome = "$env:LOCALAPPDATA\SpektraLabBuild",
    [switch]$Force,
    [switch]$Offline,
    [string]$Url
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$root = (Resolve-Path "$PSScriptRoot\..").Path
$version = Get-Content -LiteralPath "$root\version.json" -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Path $BuildHome -Force | Out-Null
$target = Join-Path (Resolve-Path $BuildHome).Path $version.releaseApk

function Test-Cache($path) {
    if (!(Test-Path -LiteralPath $path)) { return $false }
    $got = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($got -ne $version.releaseSha256) {
        Write-Host "Cached APK has wrong SHA-256 ($got); ignoring it."
        return $false
    }
    return $true
}

if (!$Force -and (Test-Cache $target)) {
    Write-Host "Release APK already cached: $target"
    Write-Output $target
    exit 0
}

if ($Offline) {
    throw "Offline mode: no cached release APK at $target. Run with network access once, or provide app/src/main/assets/resources."
}

if (!$Url) {
    $Url = "https://github.com/SAINTTaiYi/SpektraLab-Android/releases/download/v$($version.versionName)/$($version.releaseApk)"
}
$part = "$target.part"
Remove-Item -LiteralPath $part -Force -ErrorAction SilentlyContinue
Write-Host "Downloading $Url"
curl.exe --fail --location --silent --show-error --retry 2 $Url -o $part
if (!(Test-Path -LiteralPath $part)) { throw "Download failed: $Url" }
if (!(Test-Cache $part)) {
    Remove-Item -LiteralPath $part -Force
    throw "Downloaded APK failed SHA-256 verification: $Url"
}
Move-Item -LiteralPath $part -Destination $target -Force
Write-Host "Cached release APK: $target"
Write-Output $target
