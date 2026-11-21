# Improvement plan

Living checklist for the next development cycle. Tick an item only when its
**Verification** step passes; add a line to the done log with the commit hash.

Baseline measured at `1.6.2m (385)` on branch `feat/windows-debug-builds`:

| Metric | Value |
|---|---:|
| `MainActivity.java` | 14,9xx lines |
| `catch (Throwable …)` / empty bodies | 279 / 185 |
| `runOnUiThread` / `new Thread` | 44 / 20 |
| Dialog constructions / `dismiss()` calls | 7 / 19 |
| Hardcoded Chinese UI literals | 0 user-facing (3 are native-message matchers) |
| Automated tests | 7 Python tests + 1 JVM test + 2 C++ compile checks + 4 checkers |
| Built APK size (prebuilt mode) | 15.9 MB (after deflating native libs) |
| Native source build | blocked — `check-patch.py` WARN |

---

## Phase 0 — blockers

### [ ] P0.1 — Publish the complete engine source

- **Problem:** the public patch cannot build the shipped native library.
- **Evidence:** `check-patch.py` reports missing `engine/src/pipeline/tiling.hpp`
  (included by five patched translation units), 14 undeclared `spk_*` APIs, and
  missing raw-I/O types (`spk::io::write_dng`, `RawDecodeCache`,
  `read_dng_display_profile`, five `RawMetadata` fields). See `source-build.md`.
- **Change:** publish the matching engine tree, or extend the patch with the
  header, declarations, and implementations. External to this repository.
- **Verification:** `python app/build/check-patch.py <upstream>` exits 0 and a
  clean checkout builds `libspektrafilm_engine.so` in CI.
- **Effort / risk:** external dependency; no app-code risk.

### [ ] P0.2 — Real regression tests (partially done)

- **Done:** three runnable `unittest` cases for packaging helpers
  (`app/build/test_build_tools.py`), a compile-level EXIF round-trip test
  (`native-tests/export_metadata_test.cpp`), and a **JVM-runnable** pure-logic
  test (`app/src/test/java/.../PureLogicTest.java`) covering the memory budget
  and JSON quoting. CI runs all three.
- **Still open:** host golden tests for render maths and a device smoke suite
  (render-hash, banded vs whole-plane, ACTION_VIEW, locale switch, export
  self-check).
- **Problem:** CI still does not execute render/export behaviour.
- **Evidence:** only `app/build/native-tests/view_adjust_test.cpp` exists; the
  workflow `.github/workflows/verify.yml` runs compile/check steps.
- **Change:** host golden tests for pure helpers (`view_adjust`,
  `export_metadata`, JSON codecs) and a device smoke suite: render-hash
  stability, banded vs whole-plane equality, ACTION_VIEW import, locale
  switching, export self-check.
- **Verification:** a PR changing C++ or render state cannot pass CI without an
  explicit golden update.
- **Effort / risk:** 3–5 days; medium.

---

## Phase 1 — correctness and user-visible quality

### [x] P1.1 — Finish localization (research correction)

- **Finding:** the earlier "~70 literals" were almost all inside explanatory
  comments. A comment-stripped scan finds zero user-facing Chinese literals.
  The three remaining (`SpkError.java:53,91`, `DeviceReport.java:74`) are
  matchers for strings emitted by the engine or a stored status code, not UI.
- **No code change needed.** `check-locales.py` already covers every `tr()` key
  used by the Java sources; `AppLanguage` ships two complete catalogs.

### [x] P1.2 — Fix APK packaging size

- **Problem:** native libraries were stored uncompressed by our packer.
- **Evidence:** `app/build/apk-contents.py:34-43` uses default ZIP_STORED;
  built APK 27.0 MB, libs `compress_type = 0`, while the official APK uses
  deflate (`compress_type = 8`) and is 15.9 MB.
- **Change:** write package entries with `ZIP_DEFLATED`, then re-run
  zipalign/apksigner.
- **Verification:** APK is now 15.9 MB with `compress_type = 8` for libs;
  `zipalign -c`, `apksigner verify`, and the packaging unit test pass.
- **Effort / risk:** hours; low.

### [ ] P1.3 — Error taxonomy (partially done)

- **Done:** `AppFailure` classifies IMPORT/RENDER/EXPORT; the four main catch
  blocks log a `failure` row and the diagnostic header carries `stage=`.
- **Still open:** replace the remaining 279 `catch (Throwable …)` / 185 empty
  bodies with typed failures and one central `reportAndShow()`.
- **Verification:** javac passes; a classified error report is observable.

### [ ] P1.4 — UI concurrency ownership (partially done)

- **Done:** all 7 dialogs are created through `newDialog()` and dismissed in
  `onDestroy`; `uiWait()` runs inline when already on the UI thread and logs
  instead of silently swallowing exceptions.
- **Still open:** a single `UiDispatcher` for all 44 `runOnUiThread` posts and
  handler callbacks; replace the timed latch with a command queue.
- **Verification:** javac passes; lifecycle leak test still needed.

### [x] P1.5 — Privacy completeness

- **Problem:** automatic uploads are off by default, but the 关于 switch is
  labelled "driver report" while also gating error reports.
- **Change:** renamed the switch to `settings.diagnostics_on/off` in both
  catalogs, added `settings.diagnostics_consent_note`, gated `reportFailure`
  and the pending-report retry on that consent.
- **Verification:** locale checker passes; code audit shows all automatic
  outbound calls are behind `DeviceReport.enabled()`. A device network-trace
  test is still desirable.
- **Effort / risk:** 1 day; low.

---

## Phase 2 — structure and performance

### [ ] P2.1 — Finish the MainActivity split (partially done)

- **Done:** extracted `Look` / `Frame` / `Knob` / `LookCodec` (~250 lines of
  pure JSON mapping), `SessionState` (strip serialization), `ThumbCache`
  (disk cache), and `PixelBudget` / `JsonQuote` (pure, JVM-testable).
- **Still open:** `ImportController`, `RenderController`, `ExportController`,
  `PresetStore`, `DialogFactory`. Target: Activity < 5,000 lines.
- **Verification:** javac + `PureLogicTest` pass after every extraction.

### [x] P2.2 — Hot-path allocations

- **Problem:** `nativeCopyArgbRange` allocates `std::vector<jint>` per call;
  `exportSourceBitmap` allocates a full-bitmap `int[]` for sampling; two
  explicit `System.gc()` calls remain.
- **Change:** `nativeCopyArgbRange` now reuses a `thread_local` scratch vector;
  `exportSourceBitmap` samples one row at a time instead of allocating a whole
  bitmap `int[]`. The two `System.gc()` calls were **kept**: their comments
  document OOMs they prevent on batch exports.
- **Verification:** JNI compiles; javac passes. A heap-growth trace is still
  desirable.

### [x] P2.3 — Crash trail I/O

- **Problem:** `CrashTrail.java:77-79` uses synchronous `commit()` on the
  calling thread, including the render thread.
- **Change:** `CrashTrail.flush(app, force)` now uses `apply()` for routine
  marks and `commit()` only for `markNow`.
- **Verification:** javac passes; a forced-crash trail test is still desirable.
- **Effort / risk:** hours; low.

### [x] P2.4 — Storage bounds

- **Problem:** private copies capped by count (40) but not bytes; thumbnails
  pruned only at startup; `.prog`/Vulkan logs have no retention policy.
- **Change:** private copies now evict by count (40) **and** bytes (2 GiB);
  `pruneDiagnosticFiles()` removes week-old `.prog`/export/Vulkan scratch at
  startup.
- **Verification:** javac passes; a long-import storage trace is still
  desirable.
- **Effort / risk:** 1 day; low.

---

## Phase 3 — release engineering and polish

### [ ] P3.1 — Release signing

- **Problem:** `build-apk.sh:40-41` and `announce.sh` publish debug-signed APKs.
- **Change:** release keystore in CI secrets; verify signer identity.
- **Verification:** `apksigner verify --print-certs` shows the release signer.
- **Effort / risk:** 1 day; medium (key management).

### [ ] P3.2 — Build convergence (partially done)

- **Done:** deleted the tracked generated `app/build/sources.txt` and added it
  to `.gitignore`; `version.json` is the single version source.
- **Still open:** one canonical CMake/Gradle definition with thin platform
  wrappers for Termux, `build-apk.sh` and PowerShell+CMake.
- **Verification:** all three entry points produce equivalent APKs.

### [ ] P3.3 — Accessibility and theming (partially done)

- **Finding:** menu/export/undo ImageViews already have translated
  `contentDescription`s from the localization merge.
- **Still open:** live palette swap (currently restart-required) and a
  tablet/wide-screen layout pass.
- **Verification:** TalkBack sweep; palette changes without restart; tablet
  screenshot.

### [x] P3.4 — CI hardening

- **Done:** CI runs `check-prebuilt.py` and `test_build_tools.py`; the Windows
  build throws if the APK exceeds 20 MB (which caught the old uncompressed-lib
  packaging). `check-locales.py` already gates missing keys.
- **Still open:** an install-on-device smoke job.

---

## Already completed (done log)

| Item | Commit | Verification |
|---|---|---|
| Combine all branches | `091ca58` | merge clean, javac |
| Verification harness + CI | `a81ae43` | `scripts/verify.ps1` |
| Remove no-op diagnostic writes | `a81ae43` | javac, no `DebugSink.bytes` call sites |
| Native global snapshots under locks | `a81ae43` | JNI compile with shim |
| ACTION_VIEW import | `a81ae43` | javac |
| Diagnostics consent off by default | `a81ae43` | javac, locale check |
| Extract `Look`/`Frame`/`Knob` | `a81ae43` | javac |
| `version.json` single source | `a81ae43` | `check-version.py` |
| Batch stop mid-export, storage cleanup, dead code | `a81ae43` | javac |
| Track prebuilt arm64 payload | `f152b76` | `check-prebuilt.py`, APK build |
| Cache release APK with checksum | `2941932` | both Windows build modes |
| Build-tool unit tests + EXIF compile test | `501448a` | `test_build_tools.py`, verify.ps1 |
| Deflate native libs in APK | `501448a` | 15.9 MB, compress_type 8, unit test |
| Diagnostics consent labels | `501448a` | check-locales |
| Reusable native scratch / row sampling | `501448a` | JNI + javac |
| CrashTrail apply() for routine marks | `501448a` | javac |
| Storage quotas + scratch pruning | `501448a` | javac |
| APK size guard in build script | `501448a` | 20 MB threshold |
| Remove tracked generated sources.txt | `501448a` | git rm + .gitignore |
| JVM-runnable pure-logic tests | `9c3ec0d` | `PureLogicTest` in verify.ps1 + CI |
| LookCodec extraction (~250 lines) | `9c3ec0d` | javac |
| AppFailure classification + stage tag | `9c3ec0d` | javac |
| Dialog registry + uiWait inline path | `9c3ec0d` | javac |

## Updating this plan

1. Tick the box and add a done-log row with the commit hash.
2. Re-measure the baseline table when a phase completes.
3. Never tick an item whose **Verification** step was not run.
