# Install the Windows x64 Android build tools and sources without admin access.
[CmdletBinding()]
param(
    [string]$BuildHome = "$env:LOCALAPPDATA\SpektraLabBuild",
    [switch]$AcceptAndroidLicenses
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

function Get-Archive($Name, $Url, $Marker, $Destination = $BuildHome) {
    if (Test-Path (Join-Path $Destination $Marker)) { return }
    $archive = Join-Path $BuildHome $Name
    if (!(Test-Path $archive)) {
        curl.exe --fail --location --silent --show-error --retry 2 $Url -o "$archive.part"
        Move-Item "$archive.part" $archive -Force
    }
    New-Item -ItemType Directory -Force $Destination | Out-Null
    tar.exe -xf $archive -C $Destination
    if (!(Test-Path (Join-Path $Destination $Marker))) { throw "Missing $Marker after extracting $Name" }
}

if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw 'This setup uses Windows x64 tools.' }
New-Item -ItemType Directory -Force $BuildHome | Out-Null
$BuildHome = (Resolve-Path $BuildHome).Path
Get-Archive jdk.zip 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' 'jdk-17*\bin\javac.exe'
Get-Archive cmake.zip 'https://github.com/Kitware/CMake/releases/download/v3.31.8/cmake-3.31.8-windows-x86_64.zip' 'cmake-3.31.8-windows-x86_64\bin\cmake.exe'
Get-Archive ninja.zip 'https://github.com/ninja-build/ninja/releases/download/v1.12.1/ninja-win.zip' 'ninja.exe'
Get-Archive python.zip 'https://www.python.org/ftp/python/3.13.12/python-3.13.12-embed-amd64.zip' 'python.exe'
Get-Archive glslang.zip 'https://github.com/KhronosGroup/glslang/releases/download/main-tot/glslang-main-windows-x86_64-release.zip' 'bin\glslang.exe' "$BuildHome\glslang"
Get-Archive sdk-tools.zip 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' 'cmdline-tools\bin\sdkmanager.bat' "$BuildHome\android-tools"
Get-Archive LibRaw-0.22.2.tar.gz 'https://www.libraw.org/data/LibRaw-0.22.2.tar.gz' 'LibRaw-0.22.2\libraw\libraw.h'
Get-Archive jpeg-turbo-3.1.2.tar.gz 'https://codeload.github.com/libjpeg-turbo/libjpeg-turbo/tar.gz/refs/tags/3.1.2' 'libjpeg-turbo-3.1.2\CMakeLists.txt'
Get-Archive lcms2-2.19.1.tar.gz 'https://codeload.github.com/mm2/Little-CMS/tar.gz/refs/tags/lcms2.19.1' 'Little-CMS-lcms2.19.1\include\lcms2.h'

$engine = Join-Path $BuildHome upstream
$revision = '445e314e28b9b455d2457471fadaffd28c07270b'
if (!(Test-Path "$engine\.git")) {
    if (Test-Path $engine) { throw "$engine exists but is not a Git checkout." }
    New-Item -ItemType Directory $engine | Out-Null
    git -C $engine init -q
    git -C $engine config core.autocrlf false
    git -C $engine remote add origin https://github.com/JamesQiu2005/SpektraLab.git
}
# Check the ref before using it, without throwing if this is a fresh repo.
$PSNativeCommandUseErrorActionPreference = $false
$head = git -C $engine rev-parse --verify --quiet HEAD
$PSNativeCommandUseErrorActionPreference = $true
if (!$head) {
    git -C $engine fetch --depth 1 origin $revision
    git -C $engine switch --detach FETCH_HEAD
} elseif ($head -ne $revision) {
    throw "Unexpected commit in $engine ($head); expected $revision."
}
$env:JAVA_HOME = (Get-ChildItem "$BuildHome\jdk-17*" -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$sdk = Join-Path $BuildHome sdk
New-Item -ItemType Directory -Force $sdk | Out-Null
$manager = Join-Path $BuildHome 'android-tools\cmdline-tools\bin\sdkmanager.bat'
$packages = @('platforms;android-36', 'build-tools;36.0.0', 'ndk;28.2.13676358', 'platform-tools')
if ($AcceptAndroidLicenses) {
    (1..20 | ForEach-Object { 'y' }) | & $manager "--sdk_root=$sdk" @packages
} else {
    & $manager "--sdk_root=$sdk" @packages
}
Write-Host "Android build tools and dependencies are in $BuildHome"
