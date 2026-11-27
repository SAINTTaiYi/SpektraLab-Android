<#
.SYNOPSIS
    SpektraLab-Android Windows UI-only APK build & repack script.

.DESCRIPTION
    Compiles Java sources to classes.dex (javac + d8), packages AndroidManifest.xml + res + assets
    with aapt, and repacks native .so libraries & non-code assets extracted from an official
    1.7.4t APK into a zipaligned and signed debug APK ready for installation.

.PARAMETER BaseApk
    Path to the official SpektraLab 1.7.4t APK (used to extract lib/arm64-v8a/*.so and assets/).
    If omitted, auto-searches base-official.apk or *SpektraLab*.apk in the repo root and parent folder.

.PARAMETER DexOnly
    Only compile Java -> classes.dex and verify AndroidManifest.xml + res/ packaging with aapt.
    Does not require BaseApk.

.PARAMETER RefreshAssets
    Force re-extracting assets/ and lib/arm64-v8a/*.so from BaseApk even if already present.
#>
[CmdletBinding()]
param(
    [string]$BaseApk = "",
    [switch]$DexOnly,
    [switch]$RefreshAssets
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Root = $PSScriptRoot
$BuildDir = Join-Path $Root "app\build"
$TempDir = Join-Path $BuildDir "apk"
$ClassesDir = Join-Path $BuildDir "classes"
$ClassesJar = Join-Path $TempDir "classes.jar"
$DexDir = Join-Path $BuildDir "dex"
$SourcesTxt = Join-Path $TempDir "sources-win.txt"
$StageLibDir = Join-Path $TempDir "lib\arm64-v8a"
$JavaSrcRoot = Join-Path $Root "app\src\main\java"
$ManifestPath = Join-Path $Root "app\src\main\AndroidManifest.xml"
$ResDir = Join-Path $Root "app\src\main\res"
$AssetsDir = Join-Path $Root "app\src\main\assets"
$I18nSrcDir = Join-Path $Root "tools\i18n"
$KeystorePath = Join-Path $Root "debug.keystore"

function Assert-LastExitCode([string]$StepName) {
    if ($LASTEXITCODE -ne 0) {
        throw "$StepName failed with exit code $LASTEXITCODE"
    }
}

function Resolve-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(
        "D:\应用\新建文件夹\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "D:\Program Files\Android\Android Studio\jbr"
    )
    $javacCmd = Get-Command "javac.exe" -ErrorAction SilentlyContinue
    if ($javacCmd) {
        $binDir = Split-Path $javacCmd.Source -Parent
        $candidates += (Split-Path $binDir -Parent)
    }

    foreach ($cand in $candidates) {
        if (-not $cand) { continue }
        $javac = Join-Path $cand "bin\javac.exe"
        $java = Join-Path $cand "bin\java.exe"
        $keytool = Join-Path $cand "bin\keytool.exe"
        if ((Test-Path $javac) -and (Test-Path $java) -and (Test-Path $keytool)) {
            return $cand
        }
    }
    throw "未找到可用的 JDK（需要 javac.exe / java.exe / keytool.exe）。请设置 `$env:JAVA_HOME 后重试。"
}

function Resolve-AndroidSdk {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk") }

    foreach ($cand in $candidates) {
        if ($cand -and (Test-Path (Join-Path $cand "platforms")) -and (Test-Path (Join-Path $cand "build-tools"))) {
            return $cand
        }
    }
    throw "未找到 Android SDK（需要 platforms 与 build-tools）。请设置 `$env:ANDROID_HOME 后重试。"
}

function Get-LatestAndroidJar([string]$SdkRoot) {
    $platformsDir = Join-Path $SdkRoot "platforms"
    $dirs = Get-ChildItem -Path $platformsDir -Directory | Where-Object {
        Test-Path (Join-Path $_.FullName "android.jar")
    } | Sort-Object {
        if ($_.Name -match 'android-(\d+)(?:\.(\d+))?') {
            [int]$Matches[1] * 1000 + ($(if ($Matches[2]) { [int]$Matches[2] } else { 0 }))
        } else {
            0
        }
    } -Descending

    $selected = $dirs | Select-Object -First 1
    if (-not $selected) {
        throw "在 $platformsDir 下未找到包含 android.jar 的平台目录。"
    }
    return (Join-Path $selected.FullName "android.jar")
}

function Get-LatestBuildTools([string]$SdkRoot) {
    $btRoot = Join-Path $SdkRoot "build-tools"
    $dirs = Get-ChildItem -Path $btRoot -Directory | Where-Object {
        (Test-Path (Join-Path $_.FullName "aapt.exe")) -and
        (Test-Path (Join-Path $_.FullName "d8.bat")) -and
        (Test-Path (Join-Path $_.FullName "zipalign.exe")) -and
        (Test-Path (Join-Path $_.FullName "apksigner.bat"))
    } | Sort-Object {
        $v = $_.Name -replace '[^0-9.]', ''
        try { [version]$v } catch { [version]"0.0.0" }
    } -Descending

    $selected = $dirs | Select-Object -First 1
    if (-not $selected) {
        throw "在 $btRoot 下未找到完整的 Android build-tools（需要 aapt.exe / d8.bat / zipalign.exe / apksigner.bat）。"
    }
    return $selected.FullName
}

function Sync-I18nAssets {
    $enTsv = Join-Path $I18nSrcDir "en.tsv"
    $phrasesTsv = Join-Path $I18nSrcDir "phrases.tsv"
    if ((Test-Path $enTsv) -or (Test-Path $phrasesTsv)) {
        $i18nDstDir = Join-Path $AssetsDir "i18n"
        New-Item -ItemType Directory -Force -Path $i18nDstDir | Out-Null
        if (Test-Path $enTsv) { Copy-Item -Force $enTsv $i18nDstDir }
        if (Test-Path $phrasesTsv) { Copy-Item -Force $phrasesTsv $i18nDstDir }
    }
}

function Find-BaseApk([string]$ExplicitPath) {
    if ($ExplicitPath) {
        if (-not (Test-Path $ExplicitPath)) {
            throw "指定的 BaseApk 不存在: $ExplicitPath"
        }
        return (Resolve-Path $ExplicitPath).Path
    }

    $searchRoots = @($Root, (Split-Path $Root -Parent))
    foreach ($dir in $searchRoots) {
        if (-not $dir -or -not (Test-Path $dir)) { continue }
        $named = Join-Path $dir "base-official.apk"
        if (Test-Path $named) { return (Resolve-Path $named).Path }

        $matched = Get-ChildItem -Path $dir -Filter "*SpektraLab*.apk" -File -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notlike "$BuildDir*" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($matched) { return $matched.FullName }
    }
    return $null
}

function Extract-FromBaseApk([string]$ApkPath) {
    $needLibs = $RefreshAssets -or -not (Test-Path (Join-Path $StageLibDir "libspektrafilm_engine.so"))
    $needAssets = $RefreshAssets -or -not (Test-Path (Join-Path $AssetsDir "resources"))
    if (-not $needLibs -and -not $needAssets) {
        Write-Host "[assets] 已存在解压后的 .so 与 assets，跳过解压（如需重解请加 -RefreshAssets）。"
        return
    }

    Write-Host "[assets] 正在从官方 APK 提取 .so 与 assets: $ApkPath"
    if ($needLibs) {
        if (Test-Path $StageLibDir) { Remove-Item -Recurse -Force $StageLibDir }
        New-Item -ItemType Directory -Force -Path $StageLibDir | Out-Null
    }
    if ($needAssets) {
        New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null
    }

    $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $libCount = 0
        $assetCount = 0
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.EndsWith("/")) { continue }
            if ($needLibs -and $entry.FullName.StartsWith("lib/arm64-v8a/")) {
                $rel = $entry.FullName.Substring("lib/arm64-v8a/".Length)
                $dest = Join-Path $StageLibDir $rel
                New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
                $libCount++
            } elseif ($needAssets -and $entry.FullName.StartsWith("assets/")) {
                $rel = $entry.FullName.Substring("assets/".Length) -replace '/', '\'
                $dest = Join-Path $AssetsDir $rel
                New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
                $assetCount++
            }
        }
        Write-Host "[assets] 提取完成: $libCount 个 native .so， $assetCount 个 asset 文件。"
        if ($needLibs -and $libCount -eq 0) {
            throw "在 $ApkPath 中未找到 lib/arm64-v8a/*.so，请确认是 arm64 版本的 SpektraLab 官方 APK。"
        }
    } finally {
        $zip.Dispose()
    }
}

# ── 1. 工具链解析 ────────────────────────────────────────────────────────────
$JavaHome = Resolve-JavaHome
$env:JAVA_HOME = $JavaHome
$env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"

$SdkRoot = Resolve-AndroidSdk
$AndroidJar = Get-LatestAndroidJar $SdkRoot
$BuildToolsDir = Get-LatestBuildTools $SdkRoot

$JavacExe = Join-Path $JavaHome "bin\javac.exe"
$JavaExe = Join-Path $JavaHome "bin\java.exe"
$KeytoolExe = Join-Path $JavaHome "bin\keytool.exe"
$AaptExe = Join-Path $BuildToolsDir "aapt.exe"
$D8Bat = Join-Path $BuildToolsDir "d8.bat"
$ZipAlignExe = Join-Path $BuildToolsDir "zipalign.exe"
$ApkSignerBat = Join-Path $BuildToolsDir "apksigner.bat"

Write-Host "[env] JAVA_HOME   = $JavaHome"
Write-Host "[env] ANDROID_JAR = $AndroidJar"
Write-Host "[env] BUILD_TOOLS = $BuildToolsDir"

# ── 2. 编译 Java -> classes.dex ─────────────────────────────────────────────
New-Item -ItemType Directory -Force -Path $BuildDir, $TempDir | Out-Null
if (Test-Path $ClassesDir) { Remove-Item -Recurse -Force $ClassesDir }
if (Test-Path $DexDir) { Remove-Item -Recurse -Force $DexDir }
if (Test-Path $ClassesJar) { Remove-Item -Force $ClassesJar }
New-Item -ItemType Directory -Force -Path $ClassesDir, $DexDir | Out-Null

$javaFiles = Get-ChildItem -Path $JavaSrcRoot -Filter "*.java" -Recurse -File |
    Sort-Object FullName |
    ForEach-Object { $_.FullName }
if (-not $javaFiles -or $javaFiles.Count -eq 0) {
    throw "在 $JavaSrcRoot 下未找到 Java 源文件。"
}

[System.IO.File]::WriteAllLines($SourcesTxt, [string[]]$javaFiles, (New-Object System.Text.UTF8Encoding $false))
Write-Host "[javac] 正在编译 $($javaFiles.Count) 个 Java 文件..."
& $JavacExe -source 17 -target 17 -encoding UTF-8 -Xlint:-options -nowarn `
    -classpath $AndroidJar -d $ClassesDir "@$SourcesTxt"
Assert-LastExitCode "javac"

# 打包为 classes.jar 传给 d8，避免 Windows 32KB 命令行长度限制
[System.IO.Compression.ZipFile]::CreateFromDirectory($ClassesDir, $ClassesJar)
Write-Host "[d8] 正在生成 classes.dex..."
& $D8Bat --lib $AndroidJar --min-api 24 --output $DexDir $ClassesJar
Assert-LastExitCode "d8"

$ClassesDex = Join-Path $DexDir "classes.dex"
if (-not (Test-Path $ClassesDex)) {
    throw "d8 未生成 $ClassesDex"
}
$dexSize = (Get-Item $ClassesDex).Length
Write-Host "[d8] 生成成功: $ClassesDex ($dexSize bytes)"

# ── 3. 同步 i18n 资源 ───────────────────────────────────────────────────────
Sync-I18nAssets

if ($DexOnly) {
    $resCheckApk = Join-Path $BuildDir "SpektraLab-res-check.apk"
    if (Test-Path $resCheckApk) { Remove-Item -Force $resCheckApk }
    Write-Host "[aapt] DexOnly 模式：正在校验 AndroidManifest.xml 与 res/ ..."
    & $AaptExe package -f -m -M $ManifestPath -S $ResDir -I $AndroidJar -F $resCheckApk
    Assert-LastExitCode "aapt (res-check)"
    if (Test-Path $resCheckApk) { Remove-Item -Force $resCheckApk }
    Write-Host "[done] DexOnly 校验通过！Java -> classes.dex 与资源编译均无错误。"
    exit 0
}

# ── 4. 定位并解压官方 BaseApk 的 .so 与 assets ──────────────────────────────
$ResolvedBaseApk = Find-BaseApk $BaseApk
if (-not $ResolvedBaseApk) {
    Write-Host ""
    Write-Host "未找到官方 1.7.4t 基础 APK，无法提取 native .so 与光谱 assets。" -ForegroundColor Yellow
    Write-Host "请任选一种方式提供基础 APK："
    Write-Host "  1) 把官方 SpektraLab 1.7.4t APK 重命名为 base-official.apk 放到:"
    Write-Host "     $Root\base-official.apk"
    Write-Host "  2) 或运行时指定路径:"
    Write-Host "     .\build-ui-apk.ps1 -BaseApk `"C:\path\to\SpektraLab-1.7.4t.apk`""
    exit 1
}

Extract-FromBaseApk $ResolvedBaseApk
Sync-I18nAssets

# ── 5. 使用 aapt 打包资源与 assets ──────────────────────────────────────────
$ResApk = Join-Path $BuildDir "SpektraLab-res.apk"
if (Test-Path $ResApk) { Remove-Item -Force $ResApk }
Write-Host "[aapt] 正在打包 AndroidManifest.xml / res / assets..."
& $AaptExe package -f -m -M $ManifestPath -S $ResDir -A $AssetsDir -I $AndroidJar -F $ResApk
Assert-LastExitCode "aapt"

# ── 6. 组装 APK（保留 resources.arsc 无压缩，注入 classes.dex 与 .so）──────
$UnalignedApk = Join-Path $BuildDir "SpektraLab-ui-unaligned.apk"
$SignedApk = Join-Path $BuildDir "SpektraLab-ui-signed.apk"
if (Test-Path $UnalignedApk) { Remove-Item -Force $UnalignedApk }
if (Test-Path $SignedApk) { Remove-Item -Force $SignedApk }

Write-Host "[pack] 正在组装 APK（保留 resources.arsc 等条目为 STORED 无压缩）..."
$PackerJava = Join-Path $TempDir "ApkPacker.java"
$PackerSrc = @'
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class ApkPacker {
    public static void main(String[] args) throws Exception {
        String resApk = args[0];
        String classesDex = args[1];
        String libDir = args[2];
        String outApk = args[3];

        int skippedDi = 0;
        int storedCount = 0;
        int soCount = 0;

        try (ZipFile src = new ZipFile(resApk);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outApk)))) {
            Enumeration<? extends ZipEntry> entries = src.entries();
            byte[] buf = new byte[65536];
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || "classes.dex".equals(name)) continue;
                if (name.startsWith("assets/resources/DI/")) {
                    skippedDi++;
                    continue;
                }
                boolean store = (e.getMethod() == ZipEntry.STORED) || "resources.arsc".equals(name);
                if (store) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream is = src.getInputStream(e)) {
                        int n;
                        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    }
                    byte[] data = baos.toByteArray();
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    ZipEntry dst = new ZipEntry(name);
                    dst.setMethod(ZipEntry.STORED);
                    dst.setSize(data.length);
                    dst.setCompressedSize(data.length);
                    dst.setCrc(crc.getValue());
                    zos.putNextEntry(dst);
                    zos.write(data);
                    zos.closeEntry();
                    storedCount++;
                } else {
                    ZipEntry dst = new ZipEntry(name);
                    dst.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(dst);
                    try (InputStream is = src.getInputStream(e)) {
                        int n;
                        while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                }
            }

            ZipEntry dexEntry = new ZipEntry("classes.dex");
            dexEntry.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(dexEntry);
            Files.copy(Paths.get(classesDex), zos);
            zos.closeEntry();

            File[] soFiles = new File(libDir).listFiles((d, n) -> n.endsWith(".so"));
            if (soFiles == null || soFiles.length == 0) {
                throw new IllegalStateException("No .so files found in " + libDir);
            }
            Arrays.sort(soFiles, Comparator.comparing(File::getName));
            for (File so : soFiles) {
                ZipEntry soEntry = new ZipEntry("lib/arm64-v8a/" + so.getName());
                soEntry.setMethod(ZipEntry.DEFLATED);
                zos.putNextEntry(soEntry);
                Files.copy(so.toPath(), zos);
                zos.closeEntry();
                soCount++;
            }
        }
        if (skippedDi > 0) {
            System.out.println("[pack] 已跳过未使用的 DI 查表文件: " + skippedDi + " 个");
        }
        System.out.println("[pack] 已打包 " + soCount + " 个 .so 库与新编译的 classes.dex (STORED 条目: " + storedCount + ")");
    }
}
'@
[System.IO.File]::WriteAllText($PackerJava, $PackerSrc, (New-Object System.Text.UTF8Encoding $false))
& $JavaExe $PackerJava $ResApk $ClassesDex $StageLibDir $UnalignedApk
Assert-LastExitCode "java (pack-apk)"

# ── 7. zipalign 与签名 ──────────────────────────────────────────────────────
Write-Host "[zipalign] 正在执行 4 字节对齐 (-p)..."
& $ZipAlignExe -p -f 4 $UnalignedApk $SignedApk
Assert-LastExitCode "zipalign"

if (-not (Test-Path $KeystorePath)) {
    Write-Host "[sign] 正在生成本地调试签名证书 debug.keystore..."
    & $KeytoolExe -genkeypair -v -keystore $KeystorePath -storepass android `
        -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=SpektraLab UI Dev,O=Local,C=CN"
    Assert-LastExitCode "keytool"
}

Write-Host "[sign] 正在使用 apksigner 签名 (v1 + v2 + v3)..."
& $ApkSignerBat sign --ks $KeystorePath --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --v1-signing-enabled true --v2-signing-enabled true `
    --v3-signing-enabled true $SignedApk
Assert-LastExitCode "apksigner sign"

& $ApkSignerBat verify --print-certs $SignedApk
Assert-LastExitCode "apksigner verify"

Write-Host "[verify] 正在校验对齐与 resources.arsc 未压缩状态..."
$alignOut = & $ZipAlignExe -c -p -v 4 $SignedApk
Assert-LastExitCode "zipalign verify"
$arscLines = $alignOut | Where-Object { $_ -match "resources\.arsc" }
if (-not ($arscLines -match "resources\.arsc \(OK\)") -or ($arscLines -match "resources\.arsc \(OK - compressed\)")) {
    throw "APK 校验失败: resources.arsc 必须保持 STORED 未压缩且 4 字节对齐！实际输出: $($arscLines -join '; ')"
}
Write-Host "[verify] resources.arsc 校验通过: $($arscLines -join '; ')"

$apkSizeMb = [Math]::Round((Get-Item $SignedApk).Length / 1MB, 2)
Write-Host ""
Write-Host "[done] 打包成功！" -ForegroundColor Green
Write-Host "       输出 APK: $SignedApk ($apkSizeMb MB)"
