# Opt-in device smoke test. Requires an ARM64 device with USB debugging.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Apk,
    [string]$BuildHome = "$env:LOCALAPPDATA\SpektraLabBuild",
    [string]$Package = 'app.spektralab',
    [string]$Activity,
    [string]$Serial,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'
$adb = "$BuildHome\sdk\platform-tools\adb.exe"
if (!(Test-Path $adb)) { throw "adb not found: $adb (run scripts/setup-windows.ps1)" }
if (!(Test-Path $Apk)) { throw "APK not found: $Apk" }

& $adb start-server
$devices = & $adb devices | Select-String 'device$'
if (!$devices) { throw 'No Android device connected.' }

$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial) }
function Adb([string[]]$arguments) {
    & $adb @adbArgs @arguments
}

function Fail($message) { Write-Error $message; exit 1 }

Write-Host "Installing $Apk"
Adb @('install', '-r', $Apk) | Out-Host
if ($LASTEXITCODE -ne 0) { Fail 'adb install failed' }

Adb @('logcat', '-c')
if (!$Activity) { $Activity = "$Package/.MainActivity" }
Adb @('shell', 'am', 'force-stop', $Package)
Adb @('shell', 'am', 'start', '-n', $Activity) | Out-Host
if ($LASTEXITCODE -ne 0) { Fail 'activity launch failed' }
Start-Sleep -Seconds 8

$log = Adb @('logcat', '-d', '-t', '500')
$crash = $log | Select-String 'FATAL EXCEPTION|native crash|SIGSEGV|SIGABRT|AndroidRuntime'
if ($crash) {
    $crash | ForEach-Object { Write-Host $_.Line }
    Fail 'crash detected in logcat'
}
$shots = Join-Path ([IO.Path]::GetTempPath()) 'spektralab-smoke.png'
# PowerShell's `>` redirection mangles binary output; pull the file instead.
Adb @('shell', 'screencap', '-p', '/sdcard/spektralab-smoke.png')
Adb @('pull', '/sdcard/spektralab-smoke.png', $shots) | Out-Host
Adb @('shell', 'rm', '/sdcard/spektralab-smoke.png')
Write-Host "Screenshot: $shots"
Write-Host 'Device smoke test passed.'

if ($Uninstall) { Adb @('uninstall', $Package) | Out-Host }
