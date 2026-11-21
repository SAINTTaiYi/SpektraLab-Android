# Improvement plan

Living checklist for the next development cycle. Tick an item only when its
**Verification** step passes; add a line to the done log with the commit hash.

Baseline measured at `1.6.2m (385)` on branch `feat/windows-debug-builds`:

| Metric | Value |
|---|---:|
| `MainActivity.java` | 15,300 lines |
| `catch (Throwable …)` / empty bodies | 279 / 185 |
| `runOnUiThread` / `new Thread` | 44 / 20 |
| Dialog constructions / `dismiss()` calls | 7 / 19 |
| Hardcoded Chinese UI literals | ~70 |
| Automated tests | 1 C++ test + 4 checker scripts |
| Built APK size (prebuilt mode) | 27.0 MB vs 15.9 MB release |
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

### [ ] P0.2 — Real regression tests

- **Problem:** CI only compiles. Render, banding, canary, localization and
  export behaviour have no automated assertions.
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

### [ ] P1.1 — Finish localization

- **Problem:** the merged catalogs cover ~700 IDs, but user-facing Chinese
  literals remain in code.
- **Evidence:** e.g. `MainActivity.java:11712` (无法解码图片), `:11878`
  (正在渲染), `:13076` (配光中…), `:10962` (关于), `:10068` (全尺寸（原图）),
  `:11277` (picker errors); `SpkError.java:53,91`; `DeviceReport.java:74`.
- **Change:** move them into `en.json`/`zh-CN.json`; extend `check-locales.py`
  to reject Chinese inside Java string literals (excluding comments).
- **Verification:** checker passes; two locale screenshots of every affected
  screen.
- **Effort / risk:** 1–2 days; low.

### [ ] P1.2 — Fix APK packaging size

- **Problem:** native libraries are stored uncompressed by our packer.
- **Evidence:** `app/build/apk-contents.py:34-43` uses default ZIP_STORED;
  built APK 27.0 MB, libs `compress_type = 0`, while the official APK uses
  deflate (`compress_type = 8`) and is 15.9 MB.
- **Change:** write package entries with `ZIP_DEFLATED`, then re-run
  zipalign/apksigner.
- **Verification:** APK size within ~2 MB of the release; `zipalign -c`,
  `apksigner verify`, and install on a device.
- **Effort / risk:** hours; low.

### [ ] P1.3 — Error taxonomy

- **Problem:** 279 `catch (Throwable …)`, 185 empty bodies; failures are
  implicit and hard to classify.
- **Change:** introduce `ImportFailure`, `RenderFailure`, `ExportFailure` and a
  single `reportAndShow()`; keep "batch must not stop" explicit rather than
  relying on a swallowed exception.
- **Verification:** every failure path produces a classified UI message and one
  diagnostic row (respecting the consent switch).
- **Effort / risk:** 3–5 days; medium — do after P0.2.

### [ ] P1.4 — UI concurrency ownership

- **Problem:** 44 `runOnUiThread` posts and 20 threads coordinate through
  flags; `uiWait()` uses a timed latch that can apply state after timeout;
  dialogs are not tied to lifecycle.
- **Change:** a single `UiDispatcher` owning dialogs and handler callbacks;
  replace `uiWait()` with a command queue / `CompletableFuture`; dismiss
  dialogs in `onDestroy`.
- **Verification:** no Handler/dialog leak in lifecycle tests; no state applied
  after a timed-out wait.
- **Effort / risk:** 3–5 days; medium.

### [ ] P1.5 — Privacy completeness

- **Problem:** automatic uploads are off by default, but the 关于 switch is
  labelled "driver report" while also gating error reports.
- **Change:** rename to a single "diagnostics" consent, add disclosure copy,
  audit every network call site (`DebugSink`, `UpdateCheck`, `DeviceReport`).
- **Verification:** with consent off, a fresh install makes zero automatic
  outbound requests; with consent on, exactly the documented ones.
- **Effort / risk:** 1 day; low.

---

## Phase 2 — structure and performance

### [ ] P2.1 — Finish the MainActivity split

- **Problem:** the Activity is still 15,300 lines and owns import, render,
  export, presets, dialogs and diagnostics.
- **Change:** incrementally extract `ImportController`, `RenderController`,
  `ExportController`, `PresetStore`, `DialogFactory`. Target: Activity < 5,000
  lines. One controller per PR, compile after each.
- **Verification:** P0.2 suite passes after every extraction.
- **Effort / risk:** 1–2 weeks; high — do after P0.2.

### [ ] P2.2 — Hot-path allocations

- **Problem:** `nativeCopyArgbRange` allocates `std::vector<jint>` per call;
  `exportSourceBitmap` allocates a full-bitmap `int[]` for sampling; two
  explicit `System.gc()` calls remain.
- **Change:** cache the native scratch buffer, sample in place, remove explicit
  GC.
- **Verification:** no allocation growth over 100 live frames (heap dump).
- **Effort / risk:** 1–2 days; low.

### [ ] P2.3 — Crash trail I/O

- **Problem:** `CrashTrail.java:77-79` uses synchronous `commit()` on the
  calling thread, including the render thread.
- **Change:** `apply()` for normal marks; forced `commit()` only for `markNow`.
- **Verification:** no disk write on the render hot path; trail survives a
  forced crash.
- **Effort / risk:** hours; low.

### [ ] P2.4 — Storage bounds

- **Problem:** private copies capped by count (40) but not bytes; thumbnails
  pruned only at startup; `.prog`/Vulkan logs have no retention policy.
- **Change:** byte quota plus periodic prune; cap diagnostic files.
- **Verification:** repeated imports of a 40 MP file do not grow storage
  without bound.
- **Effort / risk:** 1 day; low.

---

## Phase 3 — release engineering and polish

### [ ] P3.1 — Release signing

- **Problem:** `build-apk.sh:40-41` and `announce.sh` publish debug-signed APKs.
- **Change:** release keystore in CI secrets; verify signer identity.
- **Verification:** `apksigner verify --print-certs` shows the release signer.
- **Effort / risk:** 1 day; medium (key management).

### [ ] P3.2 — Build convergence

- **Problem:** three packaging paths (Termux scripts, `build-apk.sh`,
  PowerShell+CMake).
- **Change:** one canonical CMake/Gradle definition; platform scripts become
  thin wrappers; delete the tracked generated `app/build/sources.txt`.
- **Verification:** all three entry points produce equivalent APKs.
- **Effort / risk:** 2–3 days; medium.

### [ ] P3.3 — Accessibility and theming

- **Problem:** menu/export/undo ImageViews have no `contentDescription`; dark
  theme needs restart; no tablet layout.
- **Change:** add labels, live palette swap, wide-screen layout pass.
- **Verification:** TalkBack sweep; palette changes without restart; tablet
  screenshot.
- **Effort / risk:** 2 days; low.

### [ ] P3.4 — CI hardening

- **Problem:** no APK size guard, no locale diff gate, no install smoke test.
- **Change:** add those three checks to `.github/workflows/verify.yml`.
- **Verification:** CI fails on a 10 MB APK size regression or a missing locale
  key.
- **Effort / risk:** 1 day; low.

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

## Updating this plan

1. Tick the box and add a done-log row with the commit hash.
2. Re-measure the baseline table when a phase completes.
3. Never tick an item whose **Verification** step was not run.
