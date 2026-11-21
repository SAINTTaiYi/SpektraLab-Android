# Opt-in device smoke test. Requires an ARM64 device with USB debugging.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Apk,
    [string]$BuildHome = "$env:LOCALAPPDATA\SpektraLabBuild",
    [string]$Package = 'app.spektralab',
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'
$adb = "$BuildHome\sdk\platform-tools\adb.exe"
if (!(Test-Path $adb)) { throw "adb not found: $adb (run scripts/setup-windows.ps1)" }
if (!(Test-Path $Apk)) { throw "APK not found: $Apk" }

& $adb start-server
$devices = & $adb devices | Select-String 'device$'
if (!$devices) { throw 'No Android device connected.' }

function Fail($message) { Write-Error $message; exit 1 }

Write-Host "Installing $Apk"
& $adb install -r $Apk | Out-Host
if ($LASTEXITCODE -ne 0) { Fail 'adb install failed' }

& $adb logcat -c
& $adb shell am force-stop $Package
& $adb shell am start -n "$Package/.MainActivity" | Out-Host
if ($LASTEXITCODE -ne 0) { Fail 'activity launch failed' }
Start-Sleep -Seconds 8

$log = & $adb logcat -d -t 500
$crash = $log | Select-String 'FATAL EXCEPTION|native crash|SIGSEGV|SIGABRT|AndroidRuntime'
if ($crash) {
    $crash | ForEach-Object { Write-Host $_.Line }
    Fail 'crash detected in logcat'
}
$shots = Join-Path ([IO.Path]::GetTempPath()) 'spektralab-smoke.png'
& $adb exec-out screencap -p > $shots
Write-Host "Screenshot: $shots"
Write-Host 'Device smoke test passed.'

if ($Uninstall) { & $adb uninstall $Package | Out-Host }
