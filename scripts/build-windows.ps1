# Build and verify an Android debug APK from a Windows x64 host.
[CmdletBinding()]
param(
    [string]$BuildHome = "$env:LOCALAPPDATA\SpektraLabBuild",
    [string]$OutputDirectory,
    [switch]$UseReleaseNative,
    [switch]$SideBySide,
    [string]$EngineCheckout,
    [ValidateRange(1, 64)][int]$Jobs = 8
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$root = (Resolve-Path "$PSScriptRoot\..").Path
if (!$OutputDirectory) { $OutputDirectory = "$root\app\build" }
if (!(Test-Path "$BuildHome\sdk\build-tools\36.0.0\aapt2.exe")) {
    throw 'Run scripts/setup-windows.ps1 first.'
}
$BuildHome = (Resolve-Path $BuildHome).Path
$ndk = "$BuildHome\sdk\ndk\28.2.13676358"
$tools = "$BuildHome\sdk\build-tools\36.0.0"
$jar = "$BuildHome\sdk\platforms\android-36\android.jar"
$cmake = "$BuildHome\cmake-3.31.8-windows-x86_64\bin\cmake.exe"
$python = "$BuildHome\python.exe"
$localeCheck = "$root\app\build\check-locales.py"
if (Test-Path $localeCheck) { & $python $localeCheck }
if ($UseReleaseNative -and $EngineCheckout) { throw 'Choose either -UseReleaseNative or -EngineCheckout.' }

function Build-Native {
$enginePatch = "$root\patches\engine-android.patch"
if ($EngineCheckout) {
    $engine = (Resolve-Path $EngineCheckout).Path
} else {
    $engineHash = (Get-FileHash $enginePatch -Algorithm SHA256).Hash.Substring(0, 12)
    $engine = "$BuildHome\engine-$engineHash"
    if (!(Test-Path "$engine\.spk-patched")) {
        if (!(Test-Path "$engine\.git")) {
            git -c core.autocrlf=false clone --quiet --no-checkout "$BuildHome\upstream" $engine
            git -C $engine config core.autocrlf false
            git -C $engine switch --detach 445e314e28b9b455d2457471fadaffd28c07270b
        }
        git -C $engine -c core.whitespace=cr-at-eol apply --ignore-space-change $enginePatch
        New-Item -ItemType File "$engine\.spk-patched" | Out-Null
    }
}
$header = "$engine\engine\include\spektrafilm\spk_engine.h"
if (!(Test-Path "$engine\engine\src\pipeline\tiling.hpp") -or
    !(Test-Path $header) -or !(Select-String -Quiet -SimpleMatch 'spk_trim_pool(' $header)) {
    throw 'Incomplete Android engine source (missing tiling.hpp or current JNI APIs). Supply a full patched checkout via -EngineCheckout, or use -UseReleaseNative for Java/UI work.'
}

$raw = "$BuildHome\LibRaw-0.22.2"
if (!(Select-String -Quiet -SimpleMatch 'The stream says what it is' "$raw\src\metadata\tiff.cpp")) {
    # git apply also works outside a repository; don't depend on GNU patch.
    Push-Location $raw
    try { git apply -p2 --ignore-space-change "$root\app\build\libraw-he-sniff.patch" }
    finally { Pop-Location }
}
& $python "$root\app\build\libraw-new-cameras.py" "$raw\src\tables\colordata.cpp" | Out-Host
$toolchain = @('-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$BuildHome/ninja.exe",
    "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-24',
    '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Release')
$jpegSource = "$BuildHome\libjpeg-turbo-3.1.2"
$jpegBuild = "$BuildHome\jpeg-android"
& $cmake -S $jpegSource -B $jpegBuild @toolchain -DENABLE_SHARED=OFF -DENABLE_STATIC=ON `
    -DWITH_JPEG8=ON -DCMAKE_POSITION_INDEPENDENT_CODE=ON | Out-Host
& $cmake --build $jpegBuild --target jpeg-static --parallel $Jobs | Out-Host

# Ninja caches the project and engine source paths. Keep one build per pair.
$buildKey = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
    [Text.Encoding]::UTF8.GetBytes("$root|$engine"))).Substring(0, 12)
$native = "$BuildHome\native-$buildKey"
$nativeArgs = @(
    "-DENGINE_ROOT=$engine/engine"
    "-DSPEKTRALAB_GLSLANG=$BuildHome/glslang/bin/glslang.exe"
    "-DSPEKTRALAB_LIBRAW_SOURCE=$raw"
    "-DLCMS_SOURCE=$BuildHome/Little-CMS-lcms2.19.1"
    "-DJPEG_BUILD=$jpegBuild"
    "-DJPEG_SOURCE=$jpegSource"
)
$nativeArgs = $nativeArgs -replace '\\', '/'
& $cmake -S "$root\app\build\windows-native" -B $native @toolchain @nativeArgs | Out-Host
& $cmake --build $native --target spektrafilm_engine --parallel $Jobs | Out-Host
return $native
}

$env:JAVA_HOME = (Get-ChildItem "$BuildHome\jdk-17*" -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
if ($UseReleaseNative) {
    $release = "$BuildHome\SpektraLab-1.6.2m-arm64-v8a.apk"
    if (!(Test-Path $release)) {
        $url = 'https://github.com/SAINTTaiYi/SpektraLab-Android/releases/download/v1.6.2m/SpektraLab-1.6.2m-arm64-v8a.apk'
        curl.exe --fail --location --silent --show-error --retry 2 $url -o "$release.part"
        Move-Item "$release.part" $release -Force
    }
    if ((Get-FileHash $release -Algorithm SHA256).Hash -ne 'ae999a770893f1d0f7e5d43ea9b89bddfd73915e66361341a41e1d7e71e78561') {
        throw "Release APK checksum mismatch: $release"
    }
    Write-Host 'Using v1.6.2m release native libraries and assets; C++ changes are not included.'
} else {
    $native = Build-Native
}

$stage = Join-Path $BuildHome ("apk-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$abiDir = "$stage\lib\arm64-v8a"
New-Item -ItemType Directory -Path "$stage\classes", "$stage\dex", "$stage\assets\resources", $abiDir -Force | Out-Null
try {
    $main = "$root\app\src\main"
    $apkContents = "$root\app\build\apk-contents.py"
    if ($UseReleaseNative) {
        & $python $apkContents extract $stage $release
    } else {
        $resources = "$stage\assets\resources"
        Get-ChildItem "$native\engine\resources" -Exclude DI | Copy-Item -Destination $resources -Recurse
        if (Test-Path "$main\assets") {
            Get-ChildItem "$main\assets" -Exclude locales | Copy-Item -Destination "$stage\assets" -Recurse -Force
            # Native shader layouts must match the rebuilt engine, not local .spv files.
            Copy-Item "$native\engine\resources\vulkan\*.spv" "$resources\vulkan" -Force
        }
        & $python "$root\app\build\gen-stock-catalog.py" $resources
        Copy-Item "$native\libspektrafilm_engine.so" $abiDir
    }
    if (Test-Path "$main\locales" -PathType Container) {
        New-Item -ItemType Directory "$stage\assets\locales" | Out-Null
        Copy-Item "$main\locales\*.json" "$stage\assets\locales"
    }
    $env:READELF = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
    & $python "$root\app\build\check-libs.py" $abiDir

    $sources = Get-ChildItem "$main\java" -Recurse -Filter '*.java' |
        ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
    Set-Content -Encoding utf8NoBOM "$stage\sources.txt" $sources
    & "$env:JAVA_HOME\bin\javac.exe" '-J-Dfile.encoding=UTF-8' --release 17 -encoding UTF-8 `
        -classpath $jar -d "$stage\classes" "@$stage\sources.txt"
    & "$env:JAVA_HOME\bin\jar.exe" --create --file "$stage\classes.jar" -C "$stage\classes" .
    & "$tools\d8.bat" --lib $jar --min-api 24 --output "$stage\dex" "$stage\classes.jar"

    $manifest = "$main\AndroidManifest.xml"
    if ($SideBySide) {
        [xml]$testManifest = Get-Content -LiteralPath $manifest -Raw
        $android = 'http://schemas.android.com/apk/res/android'
        $testManifest.DocumentElement.SetAttribute('package', 'app.spektralab.test')
        [void]$testManifest.manifest.application.SetAttribute('label', $android, 'SpektraLab Test')
        [void]$testManifest.manifest.application.activity.SetAttribute('name', $android, 'app.spektralab.MainActivity')
        # Local smoke test: don't send telemetry or diagnostics from the device.
        foreach ($permission in $testManifest.SelectNodes('/manifest/uses-permission')) {
            if ($permission.GetAttribute('name', $android) -eq 'android.permission.INTERNET') {
                [void]$permission.ParentNode.RemoveChild($permission)
            }
        }
        $manifest = "$stage\AndroidManifest.xml"
        $testManifest.Save($manifest)
    }
    & "$tools\aapt2.exe" compile --dir "$main\res" -o "$stage\res.zip"
    & "$tools\aapt2.exe" link -I $jar --manifest $manifest --debug-mode `
        -A "$stage\assets" -o "$stage\resources.apk" "$stage\res.zip"
    & $python $apkContents pack $stage "$stage\resources.apk"
    & "$tools\zipalign.exe" -P 16 -f 4 "$stage\unsigned.apk" "$stage\aligned.apk"

    $key = "$BuildHome\debug.keystore"
    if (!(Test-Path $key)) {
        & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $key -alias androiddebugkey `
            -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US' `
            -keyalg RSA -keysize 2048 -validity 10000
    }
    & "$tools\apksigner.bat" sign --ks $key --ks-pass pass:android --key-pass pass:android `
        --ks-key-alias androiddebugkey --v1-signing-enabled true "$stage\aligned.apk"
    & "$tools\apksigner.bat" verify --verbose "$stage\aligned.apk"
    & "$tools\zipalign.exe" -c -P 16 4 "$stage\aligned.apk"
    $suffix = if ($UseReleaseNative) { 'release-native-debug' } else { 'windows-debug' }
    if ($SideBySide) { $suffix += '-side-by-side' }
    $outputDir = New-Item -ItemType Directory -Path $OutputDirectory -Force
    $output = Join-Path $outputDir.FullName "SpektraLab-$suffix.apk"
    Copy-Item "$stage\aligned.apk" $output -Force
    Write-Host "APK: $output"
} finally {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
