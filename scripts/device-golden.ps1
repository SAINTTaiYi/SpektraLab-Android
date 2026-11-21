# On-device golden/banding comparison. Requires a device via Wi-Fi or USB adb.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$Serial,
    [string]$Image = '/sdcard/Download/spk_test.png',
    [string]$Package = 'app.spektralab.test',
    [string]$Activity = 'app.spektralab.test/app.spektralab.MainActivity',
    [int[]]$Bands = @(0, 512, 128),
    [string]$Output = 'app/build/device-golden.json',
    [int]$TimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\SpektraLabBuild\sdk\platform-tools\adb.exe"
if (!(Test-Path $adb)) { throw "adb not found: $adb" }
if (!(Test-Path $Apk)) { throw "APK not found: $Apk" }

function Adb([string[]]$arguments) { & $adb -s $Serial @arguments }

Adb @('install', '-r', $Apk) | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
# The test image is pushed into shared storage; grant the test package access
# so a file:// ACTION_VIEW can actually be opened on API 33+.
Adb @('shell', 'pm', 'grant', $Package, 'android.permission.READ_MEDIA_IMAGES')

$results = @()
foreach ($band in $Bands) {
    Adb @('shell', 'am', 'force-stop', $Package)
    Start-Sleep -Seconds 2
    Adb @('logcat', '-c')
    $intent = @('shell', 'am', 'start',
        '-a', 'android.intent.action.VIEW',
        '-d', "file://$Image",
        '-t', 'image/png',
        '-n', $Activity,
        '--ei', 'spk_band_rows', "$band",
        '--ez', 'spk_deterministic', 'true',
        '--ez', 'spk_auto_film', 'true')
    Adb $intent | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "launch failed for band=$band" }

    $hash = $null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline -and !$hash) {
        Start-Sleep -Seconds 2
        $line = Adb @('logcat', '-d', '-s', 'SpektraLabHash:I') |
                Select-String 'SpektraLabHash' | Select-Object -Last 1
        if ($line -and $line.Line -match 'hash=([0-9a-f]+)') { $hash = $Matches[1] }
    }
    if (!$hash) { throw "no render hash appeared within ${TimeoutSeconds}s for band=$band" }
    Write-Host "band=$band hash=$hash"
    $results += [pscustomobject]@{ band = $band; hash = $hash }
}

$unique = @($results | Select-Object -ExpandProperty hash -Unique)
$passed = $unique.Count -eq 1
$document = [pscustomobject]@{
    device = $Serial
    image = $Image
    apk = (Split-Path $Apk -Leaf)
    passed = $passed
    hashes = $results
} | ConvertTo-Json -Depth 4
$outDir = Split-Path $Output -Parent
if ($outDir -and !(Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
Set-Content -Encoding utf8NoBOM -LiteralPath $Output -Value $document
Write-Host "Golden comparison $(if ($passed) {'PASSED'} else {'FAILED'}); wrote $Output"
if (!$passed) { exit 1 }
