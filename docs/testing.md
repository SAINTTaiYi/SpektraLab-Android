# Testing tiers

## 1. Offline checks (every commit)

```powershell
./scripts/verify.ps1
```

Runs: locale catalog validation, `version.json` consistency, prebuilt payload
hashes, packaging unit tests, checker unit tests, full `javac` compile, the
JVM-runnable `PureLogicTest`, and compile checks for the two C++ test files.
With `-EngineRoot <upstream checkout>` it also verifies the engine patch and
compiles the JNI against the published header (using a shim for the APIs the
patch does not declare).

CI runs the same checks in `.github/workflows/verify.yml`.

## 2. What each test covers

| Test | Kind | Covers |
|---|---|---|
| `app/build/test_build_tools.py` | runnable Python | APK extraction filters, path traversal, deflated entries |
| `app/build/test_checkers.py` | runnable Python | version mismatch, corrupted/foreign prebuilt libraries |
| `app/build/check-no-silent-catch.py` | runnable Python | no silent `catch (Throwable/Exception)` blocks in app code |
| `app/src/test/.../PureLogicTest.java` | runnable JVM | budget maths, JSON quoting, failure classification |
| `app/src/test/.../DomainTest.java` | runnable JVM | import planning, render use-case call order, engine/session fakes |
| `native-tests/view_adjust_test.cpp` | compile + device/Linux run | neutral pass, monotone curves, levels |
| `native-tests/export_metadata_test.cpp` | compile + device/Linux run | EXIF round trip |

## 3. Device smoke test (opt-in)

```powershell
./scripts/device-smoke.ps1 -Apk app/build/SpektraLab-prebuilt-native-debug.apk
```

Requires an ARM64 device with USB debugging. Installs the APK, launches the
activity, captures a screenshot, and fails on `FATAL EXCEPTION` / native crash
signals in logcat. Add `-Uninstall` to clean up.

## 4. On-device golden / banding comparison

```powershell
./scripts/device-golden.ps1 -Apk app/build/SpektraLab-prebuilt-native-debug-side-by-side.apk `
    -Serial 192.168.1.83:42011 -Bands 0,512,128
```

The script pushes deterministic-render intent extras into the app (grain and
paper veil off, first film auto-selected), launches the same image three times
with different `SPK_BAND_ROWS` values, and reads the render hash that the app
logs as `SpektraLabHash`. It fails if the hashes differ and writes the observed
values to `app/build/device-golden.json`.

Observed on a Motorola Razr 2025 with the 1.6.2m prebuilt engine: band 0, 128
and 512 all produced `5ddbaa4709c950bd` — the banded renderer is byte-identical
to the whole-plane path on that device.

## 5. Known gaps

- Golden hashes are device/driver specific; the artifact is intentionally not
  committed. Re-run on each device class of interest.
- No golden RAW fixture yet (the test uses a small PNG).
- The engine patch check is expected to fail until the complete engine source
  is published; see `source-build.md`.
