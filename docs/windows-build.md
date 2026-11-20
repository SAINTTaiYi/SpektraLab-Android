# Build SpektraLab Android on Windows x64

Use PowerShell 7.4+ (`pwsh`) and Git for Windows. No Android Studio, Gradle,
WSL, or administrator access is needed. Allow several GB of free disk space
for downloads and the Android toolchain.

## Java/UI development

From the repository root:

```powershell
.\scripts\setup-windows.ps1
.\scripts\build-windows.ps1 -UseReleaseNative
```

Review and accept the SDK package license when prompted. If you have already
reviewed the license and want unattended setup, pass `-AcceptAndroidLicenses`.
Both scripts accept `-BuildHome`; the default is
`%LOCALAPPDATA%\SpektraLabBuild`. Setup installs portable tools there without
changing the system `PATH`.

`-UseReleaseNative` verifies the SHA-256 of the published **1.6.2m** APK and
uses its ARM64 libraries and assets together as a matched set. It compiles the
Java, manifest, Android `res/` files, and [UI catalogs](localization.md) from this checkout and writes
`app/build/SpektraLab-release-native-debug.apk`. Edits to C++, JNI signatures,
shaders, and `app/src/main/assets/` are **not** included in this mode. Use it
for local development, not to publish a new release.

This is an **Android ARM64** APK (minSdk 24, targetSdk 36), not a Windows app.
To test with an ARM64 Vulkan-capable device and USB debugging enabled:

```powershell
& "$env:LOCALAPPDATA\SpektraLabBuild\sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\SpektraLabBuild\sdk\platform-tools\adb.exe" install -r .\app\build\SpektraLab-release-native-debug.apk
```

The debug key is local. Android will not update an official installation
signed with a different key. To keep an existing install and its data intact,
build with `-UseReleaseNative -SideBySide` and install
`app/build/SpektraLab-release-native-debug-side-by-side.apk` instead. The test
app is labeled **SpektraLab English**, has its own ID (`app.spektralab.english`),
and does not have Internet permission. If reinstalling local builds with the same version code,
unpacked resources can remain cached; use a fresh test install when changing
shaders or profile assets.

## Full native build: missing source

The public patch applies to upstream `445e314`, but that combination does
not compile: patched files include `engine/src/pipeline/tiling.hpp`, which
is in neither the upstream tree nor the patch. In addition, the JNI uses
newer engine APIs absent from the patched `spk_engine.h`, including
`spk_trim_pool`, `spk_suspend_session`, and `spk_set_output_linear`.
This mismatch affects the Termux build too; installing more tools will not fix it.

For native development, obtain a complete matching Android engine tree from
the maintainer. The Windows source-build path accepts a **fully patched**
checkout containing `engine/`:

```powershell
.\scripts\build-windows.ps1 -EngineCheckout C:\path\to\matching-engine
```

Native objects are cached under `-BuildHome`; a successful source build
writes `app/build/SpektraLab-windows-debug.apk`. This route has not been
verified end-to-end because the matching Android engine source is missing.

## How the repository fits together

- [`MainActivity.java`](../app/src/main/java/app/spektralab/MainActivity.java)
  is the launcher, hand-built UI, and editing workflow. It manages document
  import, frame state, preview jobs, presets, and exports. It copies packaged
  engine resources into app-private storage for native access.
- [`SpektraLabEngine.java`](../app/src/main/java/app/spektralab/engine/SpektraLabEngine.java)
  is the Java façade for the engine and image sessions. Its native methods
  cross into [`app/src/main/cpp`](../app/src/main/cpp): one JNI unit handles
  sessions, rendering and export, and a second handles RAW/LibRaw integration.
- The spectral pipeline lives in the separate upstream **SpektraLab**
  repository at `445e314`. [`engine-android.patch`](../patches/engine-android.patch)
  adapts its Vulkan execution for Android, including buffer and workgroup
  limits and GPU memory management. [Tiling notes](tiling.md) explain the design.
- In the Windows source-build path, the NDK cross-compiles the engine and both JNI units. Upstream
  CMake generates the imprint header and compiles patched GLSL to SPIR-V.
  LibRaw, libjpeg-turbo, and Little CMS are statically linked into the JNI
  library; the Termux build stages separate shared libraries instead.
- The source-build APK stages public upstream engine resources and product profiles,
  with a generated stock catalogue. It does not include the unused DI LUTs.
  Optional local assets under `app/src/main/assets/` can add fonts or profiles;
  the generated shaders take precedence over local copies. The release's
  exact assets and signing key are not reconstructed by this build.
- The scripts use `javac` and `d8` for Java, `aapt2` to package resources,
  `zipalign` for 16 KiB native-library alignment, and `apksigner` to sign and
  verify. The build also checks the native library's ELF dependencies before
  publishing the APK.
