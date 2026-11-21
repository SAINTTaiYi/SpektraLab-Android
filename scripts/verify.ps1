# Fast, offline verification of everything that does not need a device.
[CmdletBinding()]
param(
    [string]$BuildHome = "$env:LOCALAPPDATA\SpektraLabBuild",
    [string]$EngineRoot
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..").Path
$failed = @()

function Report($name, [bool]$ok) {
    if ($ok) { Write-Host "  PASS  $name" } else { Write-Host "  FAIL  $name"; $script:failed += $name }
}

# Python: the embedded runtime in BuildHome, or whatever is on PATH.
$py = Join-Path $BuildHome 'python.exe'
if (!(Test-Path $py)) { $py = (Get-Command python -ErrorAction SilentlyContinue).Source }
if (!$py) { throw 'No Python interpreter found (run scripts/setup-windows.ps1).' }

Write-Host '== locale catalogs =='
& $py "$root\app\build\check-locales.py"
Report 'check-locales' ($LASTEXITCODE -eq 0)

Write-Host '== version metadata =='
& $py "$root\app\build\check-version.py"
Report 'check-version' ($LASTEXITCODE -eq 0)

Write-Host '== prebuilt native payload =='
& $py "$root\app\build\check-prebuilt.py"
Report 'check-prebuilt' ($LASTEXITCODE -eq 0)

Write-Host '== build-tool tests =='
& $py "$root\app\build\test_build_tools.py"
Report 'test_build_tools' ($LASTEXITCODE -eq 0)
& $py "$root\app\build\test_checkers.py"
Report 'test_checkers' ($LASTEXITCODE -eq 0)
& $py "$root\app\build\check-no-silent-catch.py"
Report 'check-no-silent-catch' ($LASTEXITCODE -eq 0)
& $py "$root\app\build\check-package-rules.py"
Report 'check-package-rules' ($LASTEXITCODE -eq 0)

Write-Host '== Java compilation =='
$jdk = (Get-ChildItem "$BuildHome\jdk-17*" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1).FullName
if (!$jdk) { $jdk = $env:JAVA_HOME }
if (!$jdk) { throw 'No JDK found (run scripts/setup-windows.ps1 or set JAVA_HOME).' }
$jar = "$BuildHome\sdk\platforms\android-36\android.jar"
if (!(Test-Path $jar)) { throw "Android platform jar missing: $jar" }
$classes = Join-Path ([IO.Path]::GetTempPath()) 'spektralab-verify-classes'
Remove-Item -LiteralPath $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classes | Out-Null
$sources = Get-ChildItem "$root\app\src\main\java" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$javacArgs = @('--release', '17', '-encoding', 'UTF-8', '-nowarn', '-classpath', $jar, '-d', $classes) + $sources
& "$jdk\bin\javac.exe" @javacArgs
Report 'javac' ($LASTEXITCODE -eq 0)

Write-Host '== runnable JVM logic tests =='
$logicOut = Join-Path ([IO.Path]::GetTempPath()) 'spektralab-purelogic'
Remove-Item -LiteralPath $logicOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $logicOut | Out-Null
$logicSources = @(
    "$root\app\src\main\java\app\spektralab\PixelBudget.java",
    "$root\app\src\main\java\app\spektralab\JsonQuote.java",
    "$root\app\src\main\java\app\spektralab\AppFailure.java",
    "$root\app\src\test\java\app\spektralab\PureLogicTest.java")
& "$jdk\bin\javac.exe" @logicSources -d $logicOut
if ($LASTEXITCODE -eq 0) {
    & "$jdk\bin\java.exe" -cp $logicOut app.spektralab.PureLogicTest
}
Report 'PureLogicTest' ($LASTEXITCODE -eq 0)

Write-Host '== domain use-case tests =='
$domainOut = Join-Path ([IO.Path]::GetTempPath()) 'spektralab-domain'
Remove-Item -LiteralPath $domainOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $domainOut | Out-Null
$domainSources = @(
    "$root\app\src\main\java\app\spektralab\domain\EngineApi.java",
    "$root\app\src\main\java\app\spektralab\domain\SessionApi.java",
    "$root\app\src\main\java\app\spektralab\domain\ImportPlanner.java",
    "$root\app\src\main\java\app\spektralab\domain\EditorState.java",
    "$root\app\src\main\java\app\spektralab\domain\RenderLookUseCase.java",
    "$root\app\src\main\java\app\spektralab\domain\RenderPreviewUseCase.java",
    "$root\app\src\main\java\app\spektralab\domain\PhotoRepository.java",
    "$root\app\src\main\java\app\spektralab\domain\AppPreferences.java",
    "$root\app\src\test\java\app\spektralab\testing\FakeSession.java",
    "$root\app\src\test\java\app\spektralab\testing\FakeAppPreferences.java",
    "$root\app\src\test\java\app\spektralab\testing\FakePhotoRepository.java",
    "$root\app\src\test\java\app\spektralab\testing\FakeEngine.java",
    "$root\app\src\test\java\app\spektralab\testing\DomainTest.java")
& "$jdk\bin\javac.exe" @domainSources -d $domainOut
if ($LASTEXITCODE -eq 0) {
    & "$jdk\bin\java.exe" -cp $domainOut app.spektralab.testing.DomainTest
}
Report 'DomainTest' ($LASTEXITCODE -eq 0)

Write-Host '== native header checks =='
$ndk = "$BuildHome\sdk\ndk\28.2.13676358"
$clang = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
if (Test-Path $clang) {
    $test = "$root\app\build\native-tests\view_adjust_test.cpp"
    $obj = Join-Path ([IO.Path]::GetTempPath()) 'spektralab-view_adjust_test.o'
    & $clang --target=aarch64-linux-android24 -std=c++20 -O0 -I"$root\app\src\main\cpp" -c $test -o $obj
    Report 'view_adjust_test compile' ($LASTEXITCODE -eq 0)
    $jpegSrc = Join-Path $BuildHome 'libjpeg-turbo-3.1.2\src'
    $jpegBuild = Join-Path $BuildHome 'jpeg-android'
    $meta = "$root\app\build\native-tests\export_metadata_test.cpp"
    & $clang --target=aarch64-linux-android24 -std=c++20 -O0 -I"$root\app\src\main\cpp" `
        "-I$jpegBuild" "-I$jpegSrc" -c $meta -o (Join-Path ([IO.Path]::GetTempPath()) 'spk_export_metadata_test.o')
    Report 'export_metadata_test compile' ($LASTEXITCODE -eq 0)
} else {
    Write-Host '  SKIP  clang++ not found'
}

if ($EngineRoot) {
    Write-Host '== engine patch =='
    & $py "$root\app\build\check-patch.py" $EngineRoot
    if ($LASTEXITCODE -eq 0) { Write-Host '  PASS  check-patch' }
    else { Write-Host '  WARN  engine patch is incomplete (known blocker; see docs/source-build.md)' }

    Write-Host '== JNI compile against upstream (shimmed missing APIs) =='
    $eng = Join-Path $EngineRoot 'engine'
    $sysroot = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot"
    $shim = "$root\app\build\native-tests\jni-shim.hpp"
    $jpeg = Join-Path $BuildHome 'jpeg-android'
    $jpegSrc = Join-Path $BuildHome 'libjpeg-turbo-3.1.2\src'
    $incs = @("--target=aarch64-none-linux-android24", "--sysroot=$sysroot", '-std=gnu++20',
              '-fPIC', "-I$eng\include", "-I$eng\src", "-I$eng\src\core",
              "-I$eng\src\pipeline", "-I$eng\src\io", "-I$eng\src\gpu",
              "-I$root\app\src\main\cpp", "-I$jpeg", "-I$jpegSrc", "-include$shim")
    & $clang @incs -c "$root\app\src\main\cpp\spektralab_jni.cpp" -o (Join-Path ([IO.Path]::GetTempPath()) 'spk_jni_check.o')
    Report 'spektralab_jni.cpp compile' ($LASTEXITCODE -eq 0)
}

if ($failed.Count) {
    throw ('verify failed: ' + ($failed -join ', '))
}
Write-Host 'All offline checks passed.'
