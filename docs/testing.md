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
| `app/src/test/.../PureLogicTest.java` | runnable JVM | budget maths, JSON quoting, failure classification |
| `native-tests/view_adjust_test.cpp` | compile + device/Linux run | neutral pass, monotone curves, levels |
| `native-tests/export_metadata_test.cpp` | compile + device/Linux run | EXIF round trip |

## 3. Device smoke test (opt-in)

```powershell
./scripts/device-smoke.ps1 -Apk app/build/SpektraLab-prebuilt-native-debug.apk
```

Requires an ARM64 device with USB debugging. Installs the APK, launches the
activity, captures a screenshot, and fails on `FATAL EXCEPTION` / native crash
signals in logcat. Add `-Uninstall` to clean up.

## 4. Known gaps

- No golden render-hash regression run yet (needs a device and a fixture RAW).
- No automated banded-vs-whole-plane comparison yet.
- The engine patch check is expected to fail until the complete engine source
  is published; see `source-build.md`.
