# Applying the `claude-android-skill` to this codebase

Source: [dpconde/claude-android-skill](https://github.com/dpconde/claude-android-skill)
(`SKILL.md`, `references/architecture.md`, `references/modularization.md`,
`references/testing.md`, `references/compose-patterns.md`, `references/gradle-setup.md`).

The skill targets a *new* NowInAndroid-style project: Kotlin, Jetpack Compose,
Hilt, Room, Retrofit, Gradle convention plugins, multi-module Gradle graph.
This app is the opposite: Java, hand-built Views, no AppCompat/Gradle, a
deterministic Vulkan/JNI engine, and a 15.9 MB release APK. Adopting the stack
verbatim would be a rewrite with a high risk of changing rendered pixels.

**Therefore: adopt the principles, not the stack.**

## 1. Gap analysis

| Skill principle | Current state | Apply? | Notes |
|---|---|---|---|
| Clean architecture (UI / Domain / Data) | `MainActivity` owns all layers; partial extractions exist (`Look`, `Frame`, `LookCodec`, `SessionState`, `PresetStore`, `ThumbCache`, `UiDispatcher`) | **Yes** | Highest value: explicit boundaries and interfaces |
| Unidirectional data flow | ~40 mutable fields read by 20 threads via flags and locks | **Yes** | Biggest correctness win; immutable state snapshot + actions |
| Reactive streams (`Flow`) | No reactive types; direct field reads | **Adapt** | Java observer/state holder, no Kotlin/AAC dependency |
| Repository pattern / offline-first | App is already offline; state in `SharedPreferences` + JSON | **Yes** | Interface + implementation so it can be faked in JVM tests |
| Test doubles instead of mocking libs | Pure JVM tests + Python scripts; no fakes for engine/session | **Yes** | Needed to test controllers without native library |
| Modular by feature (`api`/`impl`) | Single `app.spektralab` package, custom shell/CMake build | **Adapt** | Package-level modules + a dependency-rule checker; no Gradle modules |
| Jetpack Compose | All UI is hand-built `View` subclasses | **No** | Rewriting risks visual/pixel changes; keep Views, borrow *state hoisting* |
| Hilt | Manual construction, no DI framework | **Adapt** | Small `AppContainer` + constructor injection |
| Room / Proto DataStore | JSON in `SharedPreferences` | **No** | Dataset is small; Room adds weight and no benefit |
| Retrofit | Only HTTPS GET/POST for update/telemetry | **No** | Keep `HttpURLConnection`; wrap in repository for testability |
| Gradle convention plugins / version catalog | Shell + PowerShell + CMake, `version.json` single source | **No** | Converge scripts separately (P3.2); do not add Gradle for this app |
| Roborazzi screenshot tests | adb screenshot smoke test | **Adapt** | Device screenshot + golden render hash already exists |

## 2. Target package layout (no new build system)

```
app.spektralab
├── app/        MainActivity, AppContainer, lifecycle wiring
├── ui/         panels, custom Views, UiDispatcher, palette, dialogs
├── domain/     Look, Frame, EditorState, EditorAction, use cases,
│               EngineApi / SessionApi interfaces
├── data/       PhotoRepository, PresetRepository, StockCatalog,
│               SessionState, ThumbCache, PreferencesStore
├── engine/     SpektraLabEngine facade + JNI (unchanged C ABI)
└── testing/    fakes and fixtures (compiled only by tests)
```

Dependency rule, enforced by a new `app/build/check-package-rules.py`:
`app → ui → domain → data → engine`, never reverse; `domain` must not import
`android.*` except `Uri` (or wrap `Uri` as a String id).

## 3. Roadmap

### Phase A — interfaces and fakes (1–2 weeks) — IN PROGRESS

Done so far: `EngineApi`/`SessionApi` are implemented by the real facade;
`ImportPlanner` is used by the import path; `PhotoRepository` is implemented by
`AndroidPhotoRepository` and used for copy/read checks; `RenderPreviewUseCase`
and `FakeEngine`/`FakeSession`/`FakePhotoRepository` have JVM tests in
`DomainTest`. Both banded and whole-plane device hashes remain identical.

Phase B started: `EditorState` is captured on the UI thread and both the full
render and live-preview workers consume it through `RenderLookUseCase`; JVM
tests cover the params → render order. Golden hashes remain identical on the
physical device and the Orca-attached emulator (`emulator-5554`, API 37);
tapping a film card in Orca switched stock/paper without a crash.

Phase A repositories now cover photos, presets, preferences and the stock
catalog (translation stays in the UI via `decorateStockLabel`), with fakes for
the pure interfaces.

Phase C started: theme tokens moved to `app.spektralab.ui.UiPalette`; the app
renders identically on the Orca emulator after the extraction.

1. Define `EngineApi` (create/open session/render/export/trim) and `SessionApi`
   (setParams, renderBegin, copyArgbRange, solve, close) around the existing
   `SpektraLabEngine` facade.
2. Define `PhotoRepository`, `PresetRepository`, `StockCatalog`,
   `PreferencesStore`; move the existing implementations behind them.
3. Add `app.spektralab.testing` fakes (`FakeEngine`, `FakeSession`,
   `InMemoryPhotoRepository`) and JVM tests for the pure use cases.
4. **Verify:** `scripts/verify.ps1` + golden hash unchanged.

### Phase B — state hoisting / UDF (2–3 weeks)

1. Introduce immutable `EditorState` (look, frame id, stage, rendering flag,
   meter EV) and `EditorAction` (PickFilm, MoveKnob, ApplyGeometry, Import,
   Export…).
2. A single reducer produces a new state; render/export workers receive a
   captured `EditorState` instead of reading Activity fields.
3. Replace the remaining volatile coordination flags with state transitions;
   `UiDispatcher` already centralises UI posting.
4. **Verify:** golden hash + device smoke + locale checks after each step.

### Phase C — UI split and stateless rendering (2 weeks)

1. Move custom Views and panel builders into `ui/`; each panel becomes a
   function of `EditorState` plus an action callback (Compose-style state
   hoisting without Compose).
2. `MainActivity` shrinks to lifecycle + `AppContainer` + intent handling.
   Target: < 5,000 lines.
3. **Verify:** screenshot comparison on device, all offline checks.

### Phase D — enforce boundaries (1 week)

1. Add `check-package-rules.py`; fail CI on forbidden imports.
2. Wire into `verify.ps1` and `.github/workflows/verify.yml`.
3. Document the package map in `docs/improvement-plan.md`.

### Phase E — optional, only for greenfield screens

Evaluate Compose for *new* screens if a dependency-on-Gradle path is ever
chosen. Do not migrate the existing camera/editor UI: the native preview,
canvas pinch, and film-cover drawing are hand-tuned and pixel-sensitive.

## 4. Non-goals

- No Kotlin, Compose, Hilt, Room, Retrofit, WorkManager, or Gradle in this repo.
- No change to the C ABI, shaders, banding, or the JNI threading model.
- No change to the build outputs: APK must remain ≤20 MB and signed/verified.

## 5. Guardrails (must pass after every phase)

- `scripts/verify.ps1` — locales, version, prebuilt hashes, 7 Python tests,
  JVM `PureLogicTest`, javac, C++ compile checks, no silent catches.
- `scripts/device-golden.ps1` — band 0/128/512 render hashes identical.
- `scripts/device-smoke.ps1` — install, launch, no crash, screenshot.
- APK size guard ≤20 MB.
