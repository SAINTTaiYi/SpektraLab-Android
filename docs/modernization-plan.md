# Modernization plan — Kotlin, Compose, Hilt, Room, Gradle

**Status: plan only.** No Gradle files, Kotlin sources, or build scripts were changed by
this document. It is a risk-weighted proposal for a sequence of small, independently
verifiable PRs.

Architecture guidance comes from
[`dpconde/claude-android-skill`](https://github.com/dpconde/claude-android-skill)
(`SKILL.md` plus `references/architecture.md`, `modularization.md`,
`compose-patterns.md`, `gradle-setup.md`, `testing.md`). The existing local analysis in
[`docs/android-skill-adoption.md`](android-skill-adoption.md) deliberately adopted the
skill's *principles* while rejecting its *stack* ("No Kotlin, Compose, Hilt, Room …
or Gradle in this repo"). **This plan supersedes that non-goal** by request, but keeps
its guardrails: deterministic pixels, offline checks, a size budget (re-baselined
per mode in §5), no C ABI changes.
Update that document's gap analysis and §4 when Phase 0 is approved; do not leave two
contradictory policies in the repo.

## 1. Cost/benefit and the decisions it drives

Modernization is not a single switch. The four technologies have very different
payoffs against this app's constraints, so the plan splits into a **core track**
worth doing and an **optional track** that needs its own go/no-go.

| Technology | Upside here | Cost/risk here | Decision |
|---|---|---|---|
| **Gradle** | Standard IDE/CI tooling, version catalog, variants, dependency management; replaces a hand-maintained `aapt2`/`d8`/`zipalign` pipeline. | Must reproduce asset staging, native packaging, 16 KB alignment, versioning, side-by-side IDs and signing. No local Gradle cache; offline CI must be proven. | **Do first, additively** (Phase 0). Shell build stays canonical until parity is measured. |
| **Kotlin (domain/data)** | Null safety, sealed types, real unidirectional state, much better JVM testability of the extracted seams. | Must preserve package names/JVM signatures for remaining Java callers; the 12.7k-line Activity still dominates. | **Do second** (Phase 1). Pure classes and fakes only. |
| **Hilt** | Replaces manual `onCreate` wiring of four repositories + engine; makes fakes injectable and removes Context plumbing. | Build-time KSP dependency; must not disturb native load/crash-logger ordering. | **Do third** (Phase 2). Keep the JNI facade untouched. |
| **Compose** | Declarative state for dialogs and stateless panels; easier iteration with previews. | Poor fit for the pixel-sensitive canvas, banded preview, pinch/geometry and `renderMutex` loop; adds large dependencies and APK weight; rewriting them risks golden-hash regressions. | **Optional, edges only** (Phase 3). `ComposeView` host with `AndroidView` islands; no core-UI rewrite. |
| **Room** | Typed, queryable presets with managed migrations instead of one JSON blob. | User-visible data migration risk; extra dependencies for a small dataset; `ui`/debug/session preferences gain nothing. | **Optional and conditional** (Phase 4). Proceed only with a concrete query need and a migration test on real 1.6.2m presets; otherwise keep `SharedPreferences` behind the repository interface. |

Two consequences follow:

- **This is not a performance project.** The Vulkan/JNI engine dominates latency; no
  modernization step is justified by speed claims. The emulator is a correctness and
  smoke tool only — never a Vulkan latency or GPU-memory baseline. README timings are
  historical. Performance work remains in
  [`docs/performance-plan.md`](performance-plan.md) at the `dd71ced` handoff baseline.
- **Keep the JNI-facing class as Java by default.**
  `app.spektralab.engine.SpektraLabEngine` owns 48 name-bound native methods; porting it
  to Kotlin gains little and risks symbol mismatches. Revisit only after cutover, with
  a JNI symbol-check test.

Single `:app` module through the cutover: the skill's `feature/*-api` + `feature/*-impl`
graph pays off only with independent features or teams. Revisit after Phase 5
(candidates: `:core:domain`, `:core:data`, `:core:engine`, `:feature:editor`,
`:feature:settings`).

## 2. Ground truth (inventoried at `c3c90fd`, `feat/windows-debug-builds`)

| Area | Current fact that constrains the plan |
|---|---|
| Sources | 64 Java files, ~18.2k lines; `MainActivity.java` is **12,727 lines** and owns lifecycle, UI construction, threading, import, render and export. `SpektraLabEngine.java` is 557 lines. |
| Build | No `settings.gradle*`, `build.gradle*`, `gradle.properties`, `gradlew`, or `gradle/`. No Gradle or Maven cache on this host. `scripts/build-windows.ps1` runs `aapt2 compile/link` → `javac --release 17` → `jar` → `d8 --min-api 24` → `zipalign -P 16 -f 4` → `apksigner` → `zipalign -c -P 16 4`. `version.json` is the single source for versionCode/versionName. CI (`windows-latest`) runs `scripts/verify.ps1`. |
| JNI | **48 native methods**, all bound by name to the fully qualified class `app.spektralab.engine.SpektraLabEngine` (`#define JNI ... Java_app_spektralab_engine_SpektraLabEngine_##name` in both `spektralab_jni.cpp` and `spektralab_raw_jni.cpp`). Renaming or repackaging that class breaks the shipped native payload. |
| Native payload | ARM64 only: seven `.so` files in `app/prebuilt/arm64-v8a/` with `sha256.json`; `-UseReleaseNative` extracts libraries and assets from the 1.6.2m APK; `-UsePrebuiltNative` is the offline path. Full native rebuild is blocked (incomplete public engine patch). |
| Assets | `app/src/main/assets/` is gitignored (~30 MB of engine resources). Gradle must stage assets from the cached release APK or a local resources directory exactly as the shell script does; it cannot assume `src/main/assets` exists in a fresh clone. |
| Localization | Custom JSON catalogs in `app/src/main/locales/{en,zh-CN}.json`, copied to `assets/locales/`, validated by `app/build/check-locales.py`. **Not** Android `res/values-*` resources. Keep this system; localization is a separate upstream PR. |
| Persistence | `SharedPreferences` only: `presets` (one JSON blob via `PresetStore`/`SharedPreferencesPresetRepository`), `ui` (language, raw auto-bright, rotate grid, …), plus debug/session keys. `SessionState` serializes frames to JSON; `CrashTrail`, `DebugSink` and `ThumbCache` write files. No database today. |
| DI | Manual construction in `MainActivity.onCreate` (repositories at lines 419–424, engine at 9377/9739). No DI framework. |
| UI | Hand-built Views: `CompareView` (556), `CurveView` (288), `FilmCoverView` (240), `HistogramView` (218), `CropOverlay` (197), `ColorWheel` (172), `RotateScale` (119), plus small helpers; 7 dialog constructions; 13 `new Thread(...)` sites and a `renderMutex`-guarded live loop. Theme is `android:Theme.Material.Light.NoActionBar` with hardcoded colours; tokens partly in `ui.UiPalette`. |
| Tests | `scripts/verify.ps1`: locale/version/prebuilt checks, Python test suites, package-rule checker, `javac` of all `.java`, pure JVM `PureLogicTest`, `DomainTest`, two C++ compile checks. Device scripts: `device-golden.ps1`, `device-smoke.ps1`. |

## 3. Related frontends — how they package and use the engine

Researched 2026-11-22 from the public repositories. Sources:
[Negs README](https://github.com/noahhaasis/negs),
[Negs development](https://github.com/noahhaasis/negs/blob/main/docs/development.md),
[Negs WebAssembly](https://github.com/noahhaasis/negs/blob/main/docs/web-wasm.md),
[SpektraLab README](https://github.com/JamesQiu2005/SpektraLab),
[SpektraLab architecture](https://github.com/JamesQiu2005/SpektraLab/blob/main/ARCHITECTURE.md),
[SpektraLab Windows](https://github.com/JamesQiu2005/SpektraLab/blob/main/engine/WINDOWS.md),
[Silverphase README](https://github.com/akinal5765-byte/Silverphase),
[Silverphase architecture](https://github.com/akinal5765-byte/Silverphase/blob/main/ARCHITECTURE.md).

| Frontend | Engine | Packaging | UI → engine boundary |
|---|---|---|---|
| **Negs** | Rust `spektrafilm-rs` crates, not the C++ engine | Native app + persistent helper + CLI binaries; `data/profiles`, `data/luts`, `data/filters` beside the app. Browser: `spektrafilm-wasm`, ~4 MB brotli of data fetched on first render through `data_fs`; WGSL shaders shared with desktop. | f32 GPU resident preview; export shells out to a CLI — f32 GPU "Fast" or f64 CPU "Reference" — with one JSON `RuntimeParams` object. WebGPU submit/readback is split because browsers cannot block on GPU map. |
| **SpektraLab** | C++20/Metal engine with hand-written C ABI `spk_engine.h` | `libspektralab_engine.a` linked into the Mac Catalyst app; `EngineResources/` (constants, 28 profiles, print LUTs, `.metallib`, licences) copied into the bundle; helpers `spklib-host` and `spkraw-host` in `Contents/Helpers`; app ~68 MB. Windows: Vulkan `spektralab_engine.dll`, CLI, viewer and Qt Quick frontend with `resources/` beside the binaries. | JSON params via `spk_set_params`; pixels borrowed into the device, zero-copy `MTLTexture` out; each render is a priced `RenderQueue` job; engine returns pixels/transforms, host writes files. |
| **Silverphase** | Same C++20/Metal lineage, forked from SpektraLab | Engine linked into both macOS and iOS hosts; no subprocess renderer, CPU fallback or runtime Python. Desktop `Resources/engine`, iOS `AppResources/engine`; product profiles overlaid at build time; `SILVERPHASE_LOCAL_MATERIALS=0` strips local-only material from public builds. | C ABI + JSON requests, typed native buffers/textures with explicit release; host owns decode, scheduling, file writes and a versioned library manifest that never overwrites good state with an empty one. |

What this means for this plan (these are precedents, not instructions to copy):

- **A stable boundary is the valuable part.** Their C ABI and JSON wire are the equivalent of our JNI class FQN plus `paramsJson`. Treat the JNI keep rules as a wire contract (§6 Phase 0), not as an implementation detail.
- **Engine data is versioned separately from app code.** Their profile/LUT directories and hash-verified helpers correspond to our `check-prebuilt.py`, `version.json` and release-APK asset staging. Keep engine data in `assets/`, never in Android resources.
- **The host owns decode, scheduling and file writes.** Our `MediaStoreExport`/`ExportVerifier` and `renderMutex` loop already follow the pattern; do not move those responsibilities into Compose or Room.
- **Preview/export tiering and negative caching are universal.** Negs saves the preview when it is already at export size; SpektraLab reuses a kept full render. This is why the plan guards engine/session creation counts (§6 Phase 2).
- **A defensive, versioned edit store is the precedent for Room.** Silverphase preserves an invalid manifest instead of replacing it with an empty session. Make that rule a Room gate (§7 Phase 4).
- **Do not copy their packaging shape.** A 68 MB Mac bundle, helper-executable folders and App Store layouts reflect desktop/Apple constraints. Our 15.21 MB side-by-side APK is already far smaller; the per-mode size baseline and packaging A/B (§5) remain the right measures.
- **Third-party licence isolation is a trade-off, not a free win.** SpektraLab runs LibRaw in a separate helper to keep LGPL code out of the app. Our prebuilt payload links it in-process; changing that would violate the "preserve native payload" constraint and is out of scope.
- **SpektraLab is also the best reference for our own patch repair.** Its public `Windows_Linux` refs match 45 of the 55 patch pre-image blobs, but contain neither `tiling.hpp` nor nine of the ten missing JNI APIs; see [`docs/source-build-repair-plan.md`](source-build-repair-plan.md). That work is a separate stream from this plan.

## 4. Target architecture (skill principles, adapted)

```
UI (Compose host at the edges + existing Views)
   Route (ViewModel via hiltViewModel) -> Screen (state + events)
   AndroidView bridges the pixel-sensitive native canvas and custom Views
        |  UiState (StateFlow) down / events up
Domain (pure Kotlin; no android.* except Uri-as-string)
   Look, Frame, EditorState/EditorAction, use cases, EngineApi/SessionApi
        |  interfaces only
Data (repositories; source of truth)
   SharedPreferences/JSON today, Room only if Phase 4 is justified,
   PhotoRepository, StockCatalog, MediaStoreExport, ExportVerifier
        |
Engine (SpektraLabEngine JNI facade — Java shim, unchanged C ABI and class FQN)
```

Adopt from the skill: three layers with unidirectional data flow, repository
interfaces, test doubles instead of mocking libraries, route/screen separation,
`StateFlow` + `collectAsStateWithLifecycle`, version catalog, convention plugins,
and JVM/device test tiers.

Technology placement:

| Technology | Where it goes | Where it does **not** go |
|---|---|---|
| Kotlin | Domain and data classes first (pure JVM-testable); then controllers, then ViewModels. | The JNI-facing class stays Java unless a symbol-check test proves a Kotlin conversion safe. |
| Compose | New leaf screens/dialogs and stateless panels via a `ComposeView` host. Existing custom Views are wrapped with `AndroidView`, then migrated one at a time. | The native preview canvas, banded render loop, `renderMutex`, pinch/geometry gestures, film-cover drawing, and shader/resource paths. No rewrite of the render pipeline. |
| Hilt | `@HiltAndroidApp` application, `@AndroidEntryPoint` activity, providers for `EngineApi`, repositories, dispatchers; replaces manual `onCreate` wiring. | Must not move `SpektraLabEngine.prepare(nativeDir)` after the first log/report or engine use. |
| Room | Only if Phase 4's entry gate is met: a preset entity/DAO with a one-time import of the JSON blob. | `ui`/debug/session preferences and `SessionState` JSON stay in `SharedPreferences` behind `AppPreferences`. |
| Gradle | Parallel build path in Phase 0, canonical at Phase 5. | Must reproduce (or deliberately replace, with evidence) the shell pipeline's asset staging, native packaging, alignment, versioning, side-by-side IDs, and signing. Android Studio defaults are not assumed. |

## 5. Performance and size budget

Correctness gates alone will not stop modernization from making the app slower or
larger. Add the following budget before any source migration.

### Baseline and per-mode size table

| Build mode | Cached APK | Note |
|---|---|---|
| `-UsePrebuiltNative -SideBySide` | 15.21 MB | Seven libraries (~15 MB raw) deflated by `apk-contents.py`. |
| `-UseReleaseNative -SideBySide` | 25.72 MB | 20 stored ZIP entries (15.84 MB raw) from the staged asset tree; already above `build-windows.ps1`'s 20 MB guard. |

A single ≤20 MB threshold is therefore not a trustworthy baseline. Record entry
sizes and compression per mode before Phase 0, then report a delta after every
phase. Re-baseline the guard per mode rather than inheriting a threshold that the
current release-native artifact already violates.

### Performance budgets

Capture a physical-device baseline with the opt-in probe in
[`docs/performance-plan.md`](performance-plan.md) (`dd71ced`, same native payload,
side-by-side package). Require each phase to show no regression in:

- cold start to "Preview ready" and first settled preview;
- export tap-to-output (including session reopen cost);
- main-thread jank / FrameTimeline, thread count and UI queue delay;
- Java/native PSS and engine/session creation counts;
- install time and on-disk footprint for the packaging A/B below.

Use the same fixture, stock, paper, tier and format; alternate baseline and
candidate builds; report median/p95 and failures/drops. The emulator stays a
correctness/smoke tool — no Vulkan latency or GPU-memory claims from it. The
measurable win from coroutines is fewer threads/stack reservations and less
main-thread work, not a proven render-speed gain. Negs and SpektraLab both reuse
a preview that is already at export size (§3), which is why Phase 2 guards
engine/session creation counts.

### APK size actions

- Enable R8 (`isMinifyEnabled`) and resource shrinking in release-type Gradle
  variants. Keep `app.spektralab.engine.SpektraLabEngine` (all native methods),
  `MainActivity` and any reflection targets. Keep obfuscation off or publish the
  mapping file: `DebugSink`/`CrashTrail` report stack traces that must stay
  readable. Measure dex size before/after — R8 matters most once Compose, Hilt
  and Room are added.
- Include only the Compose artifacts actually used (`ui`, `material3`,
  `activity-compose`, `lifecycle-runtime-compose`, and `hilt-navigation-compose`
  only if navigation is added). Do **not** add `material-icons-extended`. If the
  size delta breaks the per-mode budget, skip the optional Compose track rather
  than raising the budget.
- A/B the native packaging: today's deflated libraries versus AGP's default
  uncompressed, page-aligned libraries. The latter grows the APK by several MB
  but removes install-time extraction and on-disk duplication — an install and
  first-launch performance gain. Measure APK size, install time, first launch and
  disk usage, then choose per mode. Do not strip prebuilt libraries (`check-prebuilt.py`
  verifies their hashes and the native payload must be preserved).
- Do not chase ineffective wins: ABI splits are moot (arm64 only; the x86_64
  emulator uses `libndk_translation.so`), and `resConfigs` does nothing because
  localization is JSON in `assets/locales`, not `res/values-*`.
- Make CI report per-entry size/compression against the per-mode baseline instead
  of a single pass/fail cliff.

## 6. Core track

### Phase 0 — Gradle foundation (no source migration)

Add `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`,
`gradle/libs.versions.toml`, the wrapper, a minimal `build-logic/convention`, and
`app/build.gradle.kts`. Pin exact AGP/Kotlin/Compose/Hilt/Room versions in the
catalog during implementation; select versions that still build on **JDK 17** and
`compileSdk 36` — do not silently raise the JDK requirement.

Required `app/build.gradle.kts` behaviour:

- Read `versionCode`/`versionName` from `version.json` so `check-version.py` and
  the manifest stay consistent. `minSdk 24`, `targetSdk 36`.
- Enable R8 (`isMinifyEnabled`) and resource shrinking in release-type variants,
  with keep rules for `SpektraLabEngine`, `MainActivity` and reflection targets;
  keep obfuscation off or publish the mapping file so crash reports stay readable.
  Treat these keep rules as the JNI wire contract (§3). Record the dex size
  before/after.
- Side-by-side build via a flavor or `applicationIdSuffix = ".test"` producing
  `app.spektralab.test`, label "SpektraLab Test", and a test manifest source set
  that removes `INTERNET` (same as the PowerShell `-SideBySide` path). The release
  app id, label and permission set must be unchanged.
- Stage `jniLibs/arm64-v8a/*.so` from `app/prebuilt/arm64-v8a/` (or from the cached
  release APK in release-native mode) and locales from `app/src/main/locales` into
  `assets/locales`. Do not depend on gitignored `app/src/main/assets` existing.
- Verify native packaging explicitly: the shell build relies on deflated libs and
  `zipalign -P 16 -f 4` (16 KB page alignment). Check whether AGP's
  `useLegacyPackaging`/manifest `extractNativeLibs` defaults preserve both; if not,
  set them explicitly or document the deliberate change. A/B deflated versus
  uncompressed page-aligned libs (APK size, install time, first launch, disk
  usage) and choose per mode before cutover. Compare APK entry compression,
  alignment and size against the shell build and record the delta.
- Preserve debug and opt-in release signing (`EXPECTED_SIGNER_SHA256` check
  equivalent). Keep `apksigner verify` in the gate.
- Offline story: seed the Gradle distribution and dependency cache into
  `%LOCALAPPDATA%\SpektraLabBuild` (or a CI cache) and prove `gradlew --offline`
  builds a frozen version set. Until then, `scripts/verify.ps1` and
  `scripts/build-windows.ps1` remain the canonical paths; Gradle is additive.

**Gate 0:** both APKs install side by side; same deterministic band 512/128 hashes
as the shell build; same locale catalogs and asset tree; per-mode APK size budget
met with an entry-level size/compression delta recorded against the shell build
(the inherited ≤20 MB guard is already violated by the 25.72 MB release-native
artifact); `verify.ps1` green; physical-device baseline captured. No performance
claims from the emulator.

### Phase 1 — Kotlin domain and data

Convert pure classes first: `Look`, `LookCodec`, `Frame`, `Knob`,
`domain/*` (use cases, `EngineApi`, `SessionApi`, `JsonFields`, `PixelHash`,
`EdgeVerdict`, `ExportPlan`, controllers) and the test fakes. Preserve package
names and JVM-visible signatures so remaining Java callers still compile. Keep
`SpektraLabEngine.java` as Java.

Model `previewState` (throttled, high-frequency) separately from low-frequency
`uiState`, with immutable data classes, so later Compose panels do not recompose
on every render frame. Convert the 13 `new Thread(...)` sites to lifecycle-scoped
coroutines with bounded `Dispatchers.IO` concurrency for prefetch and thumbnails,
preserving `renderMutex` ordering under tests. Track thread count and main-thread
work against the baseline: the win is fewer threads/stack reservations, not a
claimed render-speed gain.

Use the skill's test-double pattern: `FakeEngine`, `FakeSession`,
`FakePhotoRepository`, `FakeAppPreferences` implement the same interfaces as
production; no Mockito/MockK. Move unit tests to Gradle `test` tasks and
**extend `verify.ps1` to run the Gradle compile/test tasks** — the current
`javac`-only step would silently stop validating Kotlin sources. Keep the pure JVM
test source set compilable without Android.

**Gate 1:** `verify.ps1` (including checkers, JVM tests and the new Gradle compile
step) and `gradlew test` both pass; golden hash unchanged; no Android imports in
`domain`; thread count and main-thread work do not increase against the baseline.

### Phase 2 — Hilt

Introduce an `@HiltAndroidApp` application and convert `MainActivity` to
`@AndroidEntryPoint`. Provide `EngineApi`/`SessionApi` (single engine instance),
the four repositories, `CoroutineDispatcher`s and the UI dispatcher through Hilt
modules; delete the manual `onCreate` construction sites. Keep native load
ordering and crash logger/crash-trail init ahead of all engine use. Do not touch
the JNI facade's class FQN or method signatures. Use KSP (not kapt) for Hilt/Room,
and enable incremental Kotlin compilation, Gradle configuration cache and a build
cache in `BuildHome` — developer iteration speed, not app runtime.

Scope a single engine instance and verify that engine/session creation counts do
not increase; the export path currently reopens a fresh 4000×3000 source session
even for the Live tier, and DI must not multiply that cost.

**Gate 2:** cold launch and ACTION_VIEW import work on a side-by-side build;
`ensureEngine` failure reporting still shows the real cause; engine/session
creation counts, golden hash and offline checks unchanged.

**Core-track review:** after Phase 2, decide explicitly whether the optional track
is worth its dependency and regression cost. The core track can ship and stop
there; the app remains Views-based and SharedPreferences-based, which is a valid
end state.

## 7. Optional track — only after a separate go/no-go

### Phase 3 — Compose at the edges

*Entry gate:* the APK size budget still holds with Compose dependencies, and a
screenshot baseline exists for the panel(s) to be migrated. If either fails,
skip this phase.

Host Compose in `MainActivity` with a `ComposeView`; keep the existing View tree
as an `AndroidView` island. Adopt the skill's route/screen split per migrated
area: `XRoute` obtains a `hiltViewModel()`, `XScreen` receives immutable state and
callbacks. Start with dialogs and one self-contained leaf panel, then migrate
more leaf panels (`ToolGlyph`, `RatioMark`, `GuideView`, `EdgeBackdrop`,
`UiPalette` tokens) behind the same public delegates. Use `UiState`/`EditorAction`
from Phase 1 rather than new mutable Compose state. Consume `previewState` only
where a preview is actually drawn, keep it throttled to the existing cadence, use
immutable/stable classes and keyed lists, and never recreate the `AndroidView`
canvas on a state change. Do not port the canvas, geometry gestures, or the render
loop.

Include only the Compose artifacts used (`ui`, `material3`, `activity-compose`,
`lifecycle-runtime-compose`, and `hilt-navigation-compose` only if navigation is
added); do not add `material-icons-extended`. If the dependency delta breaks the
per-mode size budget, skip this phase rather than raising the budget.

**Gate 3:** screenshot comparison of migrated panels on device; band 512/128
hashes unchanged; native call sequence and `renderMutex` behaviour unchanged; no
increase in main-thread work or engine/session creation; per-mode size budget met;
no new Internet permission or locale catalog changes.

### Phase 4 — Room, conditional

*Entry gate:* a concrete query/row-level need for presets (not just "use Room")
**and** a migration test using a real 1.6.2m presets JSON fixture. If either is
missing, skip Room and record that decision; `PresetRepository` already hides the
storage choice.

Room is a data-correctness/query choice, **not** a performance improvement: the
current preset blob is one small JSON parse. If performance is the priority, skip
this phase.

If it proceeds: add a Room database with a preset entity and DAO; on first launch
read the existing `presets` JSON blob through `PresetRepository`, insert rows, and
mark the migration done. Use suspend DAO calls and `@Transaction` so no database
work reaches the main thread. Keep `PresetStore.compact` as pure Kotlin so share
payloads are byte-for-byte compatible. Leave `ui`, debug and session
`SharedPreferences` untouched. Follow Silverphase's versioned-manifest precedent
(§3): never let an empty or invalid migration result overwrite good state.
Provide a fake repository for JVM tests.

**Gate 4:** presets from a 1.6.2m data directory survive the upgrade; exported
share JSON is identical; no duplicate presets on relaunch; `verify.ps1` green.

## 8. Phase 5 — Cutover and cleanup

Make Gradle the canonical build (`build-windows.ps1` delegates or is retired by a
**separate Windows-tooling PR**), update `.github/workflows/verify.yml`,
`docs/windows-build.md`, `docs/testing.md`, `docs/improvement-plan.md`, and
`docs/android-skill-adoption.md`. Make CI report per-entry size/compression and
dex/native/asset totals against the per-mode baseline, and re-baseline the size
guard for the canonical build instead of carrying the stale 20 MB threshold.
Remove duplicated Java test sources only after Gradle tests cover the same cases.
Keep the shell build as an offline fallback until the dependency cache is proven
on CI; maintain both paths only until then.

## 9. Cross-cutting acceptance gates

| Gate | Command / check | Expected |
|---|---|---|
| Offline | `.\scripts\verify.ps1` | All PASS, including locale, version, prebuilt hashes, checkers, JVM tests, C++ compile checks and (from Phase 1) Gradle compile/test. |
| Gradle | `.\gradlew --offline test assembleDebug` (and side-by-side variant) | Builds without network; unit tests pass. |
| Deterministic render | `scripts/device-golden.ps1 -Bands 512,128` with the same fixture | Same hash as the shell build (`900357762661eee2` for the 640×480 PNG fixture on the emulator); full pixel count copied. |
| Smoke | `scripts/device-smoke.ps1` on side-by-side package | Launch, import, preview, export; no fatal logs. |
| Packaging | APK entry/compression/alignment inspection + size | Per-mode baseline met with a recorded entry-level delta vs. the shell build; native libs and locales present; manifest version and permissions match. |
| Size CI diff | Per-mode entry/compression, dex, native and asset totals in CI | Reported against baseline; no unexplained growth; R8 keep rules verified after minification. |
| Performance | Physical-device A/B with the `dd71ced` probe, same fixture/payload/payload mode | No regression in cold start, first preview, export, jank, thread count, PSS or engine/session creation counts. Emulator results never used as latency data. |
| Boundaries | `check-package-rules.py` + Gradle lint/detekt if added | `domain` has no `android.*` or data/ui imports; no JNI FQN change. |
| Data | 1.6.2m preset fixture (Phase 4 only) | JSON blob imports once, share output identical, no data loss. |

## 10. Risks and mitigations

| Risk | Mitigation |
|---|---|
| JNI symbol names are generated from the Java class FQN | Keep `app.spektralab.engine.SpektraLabEngine` as Java with all 48 native signatures; add a symbol-check test before any Kotlin conversion. Treat it as the wire contract (§3). |
| Engine data packaged as Android resources | Other frontends ship profiles/LUTs as versioned data beside the engine (§3). Keep engine data in `assets/` with hash-checked native payloads; do not move it into `res/` or let localization tooling rewrite it. |
| AGP packaging differs from the custom pipeline | Phase 0 compares APK entries, compression, alignment, permissions and size before any source migration; do not assume defaults. |
| R8 shrinks or renames JNI entry points / crash stack traces | Keep rules for `SpektraLabEngine` and `MainActivity`; keep obfuscation off or publish the mapping file; verify a signed APK still launches and reports readable crashes. |
| Uncompressed native libs grow the APK past the budget | A/B deflated vs page-aligned packaging in Phase 0 and choose per mode; never strip prebuilt libraries (hash-verified payload). |
| Single 20 MB guard hides mode-specific regressions | Per-mode baselines and CI size diffs; the existing 25.72 MB release-native artifact is already above the inherited guard. |
| Gradle dependencies break offline CI | Seed/cache distribution and artifacts in `BuildHome`; `--offline` is a gate; keep the shell build canonical until proven. |
| Gitignored ~30 MB asset tree | Stage assets from the cached release APK or an explicit local resources directory in Gradle, as the shell script does. Never download during a verification run. |
| 12.7k-line `MainActivity` with thread/flag coordination | Migrate by seams (`EditorState`, controllers, repository interfaces) already extracted; replace flags one area at a time; golden hash after each step. |
| Compose/Room add weight and regression risk for little functional gain | Optional track with entry gates, size budget, screenshot/migration tests and a documented skip option. |
| Dual-build maintenance during migration | One canonical path at a time; `verify.ps1` extended early; retire the shell path only in Phase 5 / separate tooling PR. |
| Documentation conflict | Land a doc update with Phase 0 approval; `docs/android-skill-adoption.md` §4 non-goals must no longer say "no Kotlin/Compose/Hilt/Room/Gradle". |
| Windows tooling and localization drift | Keep Windows build-tooling changes in a separate upstream PR and localization in a separate upstream PR; no catalog or `res/values-*` edits here. |

## 11. PR decomposition

1. `build: add parallel Gradle build with parity gates` (Phase 0, no Kotlin; R8/keep rules, per-mode size baselines, packaging A/B).
2. `refactor: Kotlin domain/data with JVM tests` (Phase 1; `verify.ps1` Gradle step, coroutines/threading and preview/ui state split).
3. `feat: Hilt wiring for engine and repositories` (Phase 2; KSP, single engine, session-count guard).
4. `ui: incremental Compose host and first stateless panel` (Phase 3, only if its entry gate passes).
5. `feat: Room-backed presets with JSON migration` (Phase 4, only if its entry gate passes).
6. `build: make Gradle canonical` + doc updates and CI size diff (Phase 5).
7. Separate PRs for Windows tooling and localization, upstream, as agreed.

Phases 1–2 can ship and stop; 3–4 are independently droppable.

## 12. Open choices to confirm before implementation

1. Optional track: fund Compose/Room now, or stop after the core track (Phases 0–2)?
2. If Room proceeds: preset rows only (recommended) vs. also session/undo history —
   the latter is a larger behavioural change and should wait.
3. Native packaging: keep deflated libraries (smallest APK) vs. uncompressed
   page-aligned libraries (faster install/first launch, larger APK)? Decide per
   mode from the Phase 0 A/B.
4. R8: shrink only with `-dontobfuscate` (readable crash reports) vs. obfuscate and
   publish a mapping file?
5. Module split: stay single-module through cutover (recommended) vs. adopt
   `:core:*`/`:feature:*` from Phase 0.
6. Offline Gradle: vendored/cache-in-BuildHome (recommended) vs. network-enabled
   CI with a lockfile only.
