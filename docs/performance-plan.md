# App performance plan — implementation handoff

Reviewed `feat/windows-debug-builds` at `dd71ced` (49 commits ahead of `master`;
48 ahead of its remote). The controller/export/MediaStore/TabBar extraction
commits (`93881e8..dd71ced`) were reviewed as behavior-preserving: offline
checks pass and the emulator run below exercised the extracted code. The later
`fea51ff` commit is the separate release-signing change; keep it out of
performance PRs. Use this checkout as the baseline, not the published APK or
the separate localization branch. This is a plan, not an implementation. It
extends the runtime work in [the improvement plan](improvement-plan.md) without
reopening unrelated refactors, localization, or Windows packaging.

**Start here:** add opt-in timing, capture a physical-device baseline, then land
three small changes in order: skip disabled preview diagnostics, give foreground
work priority over film thumbnails, and move JPEG/HEIC import off the main
thread. Profile render copies and export before changing them. These first three
changes can be tested against the shipped 1.6.2m native payload.

## 0. Baseline and measurement

No physical-device timings were captured. The [emulator smoke run](#emulator-smoke-run)
is a reproducible correctness check, not a Vulkan latency or GPU memory
baseline. The [README](../README.md) reports historical bordered-render times
of 10.1 s at 20.9 MP and 28.8 s at 58 MP; neither is a current-device or
end-to-end export baseline.

1. Save a baseline checkout at the current HEAD (`dd71ced`) and keep baseline
   and candidate APKs in separate output directories. Use the same
   instrumentation in both builds (compare the instrumentation-only commit to
   each change). Use the same physical ARM64 Vulkan phone and native payload;
   add a memory-constrained phone before changing memory policy. Do not replace
   the user's release app.

   ```powershell
   .\scripts\verify.ps1
   .\scripts\build-windows.ps1 -UseReleaseNative -SideBySide
   $apk = '.\app\build\SpektraLab-release-native-debug-side-by-side.apk'
   $adb = "$env:LOCALAPPDATA\SpektraLabBuild\sdk\platform-tools\adb.exe"
   & $adb devices -l
   $serial = '<physical device serial from adb>'
   .\scripts\device-smoke.ps1 -Apk $apk -Serial $serial -Package app.spektralab.test
   ```

   [Windows build instructions](windows-build.md) cover setup and APK output.
   `-UsePrebuiltNative -SideBySide` is the offline alternative with engine assets
   already available. Use the same mode for both builds.
2. Add a debug-only opt-in probe with monotonic timestamps, Perfetto trace
   sections (or structured logcat spans), and request IDs carried from input
   through worker to UI. Log phase, frame/session generation, dimensions, result
   and whether it was applied, dropped, failed or retried; don't log paths,
   pixel data or user photos. Time the operations separately:
   - **Preview / shelf:** input to first applied preview and release/tap to
     correct settled preview; enqueue to `renderMutex` request, wait time,
     holder type and lock hold; params, `renderBegin`, ARGB copy,
     `Bitmap.setPixels`, diagnostics, UI queue delay and canvas update. Track
     render starts, applied frames, drops, and thumbnail starts/completions.
     Distinguish a bitmap set on the canvas from an actual presented frame;
     use Perfetto FrameTimeline where available for jank/presentation.
   - **Import:** URI selection to original displayed and separately to session
     ready; provider/file IO, decode/rotate/scale, RGB float conversion, old
     session teardown/trim, engine init/open and main-thread stalls.
   - **Export:** tap to usable output and batch total; frame reopen/RAW decode,
     prepare/suspend/open, render/encode (combined if the prebuilt JNI provides
     no split), MediaStore save, validation/retry, and session restoration.
   - **Memory:** check Java/native PSS and peak around the measured phases
     (`adb -s <serial> shell dumpsys meminfo app.spektralab.test` and/or a separate
     memory trace). Report engine/GPU counters separately when available; PSS
     alone is not a GPU peak. Avoid running heavyweight sampling in latency runs.
3. Use fixed, non-private local fixtures with recorded SHA-256, dimensions,
   EXIF orientation and expected caps: small PNG, a 12–24 MP rotated JPEG, a
   HEIC on a decoder that supports it, and a supported camera RAW. Use the same
   stock, paper, controls, crop/rotation, output tier and format on each pair.
   Record APK/commit, device, Android build, GPU/driver, power/thermal state, and
   whether app resources, source, and shelf caches are cold or warm. Separate
   fresh install, force-stopped process (data retained), and warm reuse. Run a
   scripted gesture or fixed manual sequence; do two warm-ups, then at least 20
   trials per common interaction and five per heavy export. Alternate baseline
   and candidate blocks, cooling between them. Report median/p95 and count for
   common interactions; for five-run cases report individual times and median.
   Report failures/drops as well as successful samples.
4. Use [device correctness checks](testing.md) before and after each phase.
   For the existing golden script, put the **same** fixture on the test phone
   before running it (the script does not push the image):

   ```powershell
   & $adb -s $serial push .\path\to\fixture.png /sdcard/Download/spk_test.png
   .\scripts\device-golden.ps1 -Apk $apk -Serial $serial -Bands 512,128 `
       -Output app/build/perf-baseline-golden.json
   ```

   Save a separate candidate output and compare hashes **across APKs** on
   that device. Before using the script as a gate, make it require the
   expected fixture dimensions and the requested band from `SpektraLabHash`
   and validate that a full frame was copied. The emulator emitted a 600×600
   startup render hash before the real fixture hash; the current script can
   accept whichever line appears first in its polling window. Prefer a
   fixture-specific completion marker if the fixture could share the startup
   dimensions. `ensureEngine()` maps `spk_band_rows=0` to `512`, so `0` is not
   a verified unbanded control. Use deterministic pixel comparisons for RAW,
   geometry, and export, which this script does not cover.

### Emulator smoke run

**2026-11-22, Orca CLI:** API 37 AVD `Medium_Phone_API_37.0`
(`emulator-5554`, x86_64 with `libndk_translation.so`). Built the working tree
that became `dd71ced` (the extraction commits were still uncommitted), using
`-UseReleaseNative -SideBySide` and the 1.6.2m native payload. Source files
under `app/src/main` were unchanged during the build. Installed with
`--reinstall` without clearing existing test app data; force-stop tests below
are process-cold, not fresh installs.

| Check | Observation |
|---|---|
| Launch and deterministic PNG render | UI reached `Preview ready 640×480`. At 512 and 128 band rows, both fixture-sized renders copied all 307,200 pixels and produced the same sampled hash, `900357762661eee2`. The first matching hash appeared ~5.08 s and ~4.90 s after the pre-launch device timestamp, respectively; these are individual smoke observations, not a banding benchmark. |
| 12 MP JPEG via `ACTION_VIEW` with session restore enabled | The first run logged only the previous PNG hash within the observation window (~9 s from pre-launch). Another run reached `Preview ready 2048×1535` and produced its first JPEG-sized hash after ~9.92 s. That process rendered twice at that size; the breadcrumbs show two 4000×3000 JPEG decodes/session opens on `main`. The hash alone cannot distinguish session restore from the incoming request. |
| Print exposure drag on the JPEG | Dragging from the thumb changed the value from 1.00 to 1.57 and produced a new settled preview hash ~4.79 s after the input command returned. This did not measure live-frame presentation. |
| JPEG Live quality export | UI reported 10 s total and 2.237 s rendering. Breadcrumbs show preview suspend and a fresh 4000×3000 source session (the import was capped to 2667×2000) even for this tier. MediaStore contained the new 2048×1536 JPEG (250,213 bytes); pixel and metadata equivalence were not checked. |
| Home and relaunch after export | The same JPEG, exposure value, and export result remained visible. No fatal app log was observed in the checked process. |
| Back-to-back PNG then JPEG frame-chip taps | Only the PNG settled in the 12-second observation window. Tapping the JPEG again after settling opened it successfully. The current logs cannot show whether the second rapid tap reached the app's handler, so this is a repro to trace, not proof of a stale result. |

**What this changes in the plan:**

- Keep diagnostics first: `cache/vk.log` was present after the smoke renders
  (e.g. 82,628 bytes in the 512-row PNG run) with the consumer tag disabled.
  Measure Java read cost and native write cost separately; file size does not
  measure either latency. Shelf breadcrumbs also showed off-screen stock
  cards continuing after the settled JPEG. Measure foreground lock waits
  before claiming the shelf is the cause of any particular stall.
- Add **cold `ACTION_VIEW` with an already-saved different frame** to the import
  matrix. Distinguish restore work from the new request in the trace and
  assert that the requested photo wins. Check for an avoidable restore
  decode/render before the requested photo, but don't suppress restoration
  when the incoming request cannot be opened. Neither a single hash nor
  `am start -W` proves the requested photo has reached the canvas.
- For rapid switches, log chip taps at handler entry as well as worker enqueue
  and UI commit. That separates an input not delivered/accepted from a request
  that was accepted and then lost.
- Add **Live JPEG export from a capped import** to export profiling. The
  fresh source reopen is a demonstrated cost to measure, not necessarily
  redundant: changing it could change detail and geometry in the output.

To repeat, build the side-by-side APK above and install with
`orca emulator install <apk> --device emulator-5554 --reinstall --json`. The
synthetic fixtures, APK and raw launch logs from this run are local artifacts
in `%LOCALAPPDATA%\Temp\opencode\spektralab-emulator-perf`. Push the fixtures
to `/sdcard/Download/`, grant `android.permission.READ_MEDIA_IMAGES` with
`orca emulator permissions grant`, and run:

```powershell
orca emulator exec --device emulator-5554 --command 'am force-stop app.spektralab.test && date +%s.%N && am start -W -a android.intent.action.VIEW -d file:///sdcard/Download/spk-perf-640.png -t image/png -n app.spektralab.test/app.spektralab.MainActivity --ei spk_band_rows 512 --ez spk_deterministic true --ez spk_auto_film true' --json
orca emulator exec --device emulator-5554 --command 'p=$(pidof app.spektralab.test); logcat -d --pid=$p -v epoch -s SpektraLabHash:I AndroidRuntime:E libc:F' --json
orca emulator ax --device emulator-5554 --json
```

Repeat with `128` for PNG, then with `spk-perf-12mp.jpg` and `image/jpeg`.
Use bounded waiting for **both** fixture identity and settled UI, and log
timeouts instead of accepting an unrelated hash. For the print test, start
the swipe on the **thumb**, not elsewhere on the track. HEIC, RAW/WB,
bordered/full export, the batch matrix, and physical-device timings remain
open.

## 1. Skip disabled preview diagnostics (small, low risk)

**Evidence:** In [MainActivity.java](../app/src/main/java/app/spektralab/MainActivity.java),
`renderImage()` calls `uploadVkLog()` inside `renderMutex`. It can stat, read
and UTF-8-decode up to 220 KiB before [DebugSink.java](../app/src/main/java/app/spektralab/DebugSink.java)
discards the `vk-log` tag (`VK_LOG = false`). Engine creation also passes a path
that the Java facade sets as `SPK_VK_LOG`; disabling Java reads does not prove
there are no native writes. **Already done:** the settled preview JPEG
compression and export-byte readback for the no-op `DebugSink.bytes()` are gone.

1. Put the Vulkan tail-reader behind the sink's diagnostic gate **before**
   opening or allocating anything; cover all callers. Leave native log writing
   unchanged in the first patch. Evaluate an opt-in native log path separately
   only after checking engine setup and which failure reports consume the file.
2. Time the remaining `renderImage()` and `drawLivePreview()` diagnostics:
   hash scans, `edgeReport`, overscan geometry, `blackBands` on the UI callback,
   and forced [CrashTrail](../app/src/main/java/app/spektralab/CrashTrail.java)
   marks. `markNow()` still does a synchronous preferences commit; `mark()`
   already throttles and uses `apply()`. If material, gate purely diagnostic
   scans or move work on owned Java data outside the lock. Keep the
   `SpektraLabHash` golden hook working with deterministic opts, and keep
   critical crash and clean lifecycle markers. Do not move engine queries
   outside their valid result/session lifetime or treat `rememberAppliedEv()`
   and histogram updates as dead logging.

**Done when:** a normal settled preview does zero Vulkan-tail reads, an enabled
diagnostic run still captures the bounded tail, and golden hashes, histogram,
and crash-trail behavior are unchanged. Report the diagnostic and lock-hold
delta even if total preview latency is dominated by rendering.

## 2. Give foreground work priority over the film shelf (medium risk)

**Evidence:** `startPrefetch()` walks up to roughly 25 missing cards, using 800 px
renders on the same session and `renderMutex` as the canvas. Thread priority
does not give a queued render priority on that monitor. `stopPrefetch()` only
invalidates the generation; a card can still acquire the lock after waiting or
already be in native code. `setPrefetchEdge()` also mutates the shared tier
outside a card transaction, and the worker reads `filmThumbs` while the UI
mutates that `HashMap`. Each card can change stock/tier and invalidate useful
negative or parameter cache state.

1. In `startPrefetch()`, `stopPrefetch()` and `renderStockThumbnail()`, take
   the candidate list and cache decisions on the UI thread. Schedule one card
   at a time: visible cards first, then the rest only during idle time. Before
   starting work **and again inside `renderMutex`**, validate the captured
   session, frame identity and shelf generation, and yield to active/pending
   live or settled preview, frame import/WB reopen, export, or pause. Recheck
   identity before publishing. Resume after foreground work or when missing
   cards become visible. Native work already started may finish; don't
   interrupt it or close its session underneath it.
2. Make each card's 800 px edge part of the same parameter transaction used
   for that render; remove the queue-wide set/restore only after verifying
   the `deltaSent`/`deltaSession` ledger always sends the correct next stock,
   paper and edge. Keep the card's monochrome paper override and full look.
   Tie cache reuse to frame/source identity and the inputs that affect the card
   pixels. Reuse bounded scratch or move thumbnail scaling outside the lock
   only once the native result has been copied and released; keep trim policy
   initially and measure its cost separately.
3. Add deterministic scheduler tests with the existing JVM test/fake pattern:
   cancel while waiting for the lock, publish after frame swap, foreground
   requests between cards, and resume without starting duplicate work.

**Done when:** no new card starts after foreground priority is visible at the
lock check, stale cards never publish, and foreground waits for at most the
already-admitted shelf job (besides unrelated foreground work). With a cold
shelf, show lower p95 tap-to-preview or foreground lock wait. Under idle
conditions visible cards still fill and settled previews use their requested
edge. Check memory after filling/revisiting shelves and keep background and
batch-export protections.

## 3. Move JPEG/HEIC import off the main thread (higher risk)

**Evidence:** `loadImage()` can wait for `renderMutex` and close/trim the previous
session on the main thread. It also calls `System.gc()`, decodes and transforms
the bitmap, writes 12 RGB float bytes per pixel and opens the engine session.
`loadRawImage()` already has a decode worker, but its lifecycle and
`redecodeWithWhiteBalance()` need to agree with the new import ownership rules.

1. Split `loadImage()` into UI request capture, bounded serial worker, and UI
   commit. Capture URI, stable frame identity, look/reopen flags, one-shot
   smaller/native-size choice and generation on the UI thread. Invalidate
   live/shelf work immediately. On a worker, close/trim/free the old source in
   the existing safe order before allocating a large replacement, then decode,
   apply EXIF transforms/caps, convert and open the session. Use the existing
   `ImportPlanner`, `PhotoRepository` and `UiDispatcher` boundaries where they
   fit; the extracted `ImportController` is the natural owner of that worker,
   and `RenderController` is the seam for the render calls. No broad Activity
   rewrite is required.
2. Use an explicit result with ownership of session, direct buffer and bitmaps.
   Recheck generation before expensive stages and before commit; only the
   current request gets to publish a canvas, transfer resources or update
   chips, dialogs and persistence. Dispose of stale or failed results exactly
   once, serialized with native users. Keep UI responsive during teardown and
   avoid any main-thread wait for native work. Don't recycle a bitmap still
   referenced by `CompareView`. Preserve current RAW WB, ACTION_VIEW,
   persisted/copied URI, restore, original-view, retry and memory-cap behavior.
   Keep explicit GC initially: relocating its call does not prevent a
   process-wide pause. Revisit only with memory and GC traces.
3. Audit all callers for formerly synchronous behavior, especially
   `reopenFrameForced()`, `startBatchImport()` and `startBatchExport()`.
   Expose completion for the exact request (ready, failed or canceled);
   replace batch export's `session != null` readiness poll when switching
   frame open to the new flow. Do not let a timeout or late completion export
   the wrong session. Keep batch order/serial memory limits; UI frame list and
   look changes stay on the main thread.

**Done when:** traces show no JPEG/HEIC decode, conversion, render-lock wait
or session open/close on the UI thread during import, with fewer/shorter main
stalls on large inputs and no repeatable regression in time to original or
session ready. A → B → C and A → B → A rapid switches, delete during open,
background/resume, failure/retry, and mixed-format batches commit only the
correct request; peak memory does not rise from overlapping imports.

## 4. Tune render and export only after the traces

- **Live/settled render:** `liveLoop()` throttles starts to a 90 ms minimum gap
  at an 800 px long edge, coalesces requests and currently clears `liveWanted`
  before its `rendering` check. Reproduce drag-during-settled-render before
  changing that policy; if it loses the last useful request, keep one
  latest-wins request for the active gesture, never an unbounded queue. Keep
  release-to-settled ordering. Settled preview is normally 2048 px (~6.55× the
  pixels at the same aspect ratio). Compare print/view adjustments using
  `renderBegin("live", true)` with shoot-layer/geometry changes that require a
  full develop. Before tuning reuse or scheduling, audit `EditorState` and
  `RenderLookUseCase`: the live callback still invokes `applyParams(...)`
  against Activity fields despite capturing a partial snapshot. Where needed,
  capture immutable request parameters and use the captured session, not a
  later global session. Test undo, original/edited compare, histogram and rotate.
- **Copies/memory:** profile `renderBegin` vs. `copyArgbRange` vs. `setPixels`
  for neutral and CPU-graded looks. Java-side experiments can reuse bounded
  per-worker scratch or copy in strips if that lowers peak without worsening
  latency. Do not reuse a bitmap still on screen. The checked-in
  [JNI C++](../app/src/main/cpp/spektralab_jni.cpp) already uses a thread-local
  vector for RGBA16 → ARGB; the transfer through the Java `int[]` into a bitmap
  remains. `view_adjust.hpp` has per-pixel work for non-neutral controls. Only
  if the copy/grading cost is material, prototype a direct bitmap/strip
  transfer or a faster grade after the native prerequisite below is met.
- **Export:** start with `exportImage()` and `startBatchExport()`. Attribute
  reopen/decode, preview suspend/resume, fresh session, border canvas render,
  encode, save, validation and retry separately where visible. Prefer removing
  a demonstrated redundant reopen/preview pass or polling wait over changing
  the renderer or parallelizing the batch. Require completion for the exact
  frame **and** actual save success, not just a callback signal. Do not skip
  corruption checks (`ExportVerifier.matchesCanvas()` and
  `ExportVerifier.inspect()`), pool trims or RAW cache cleanup to win a
  benchmark. Compare decoded output, geometry, orientation, EXIF/ICC,
  MediaStore URI and share/open behavior. Exercise corruption detection,
  retry, cancellation and temp cleanup.

**Native gate:** keep `renderBegin` → copy → `renderEnd` serialized across users
of the shared native result slot (`g_result`). The facade's static `RENDER_LOCK`
covers individual calls; it is not a transaction that protects a result from
another session's begin/end. Keep cleanup on failure and serialize session
close/suspend with users of that session. Changing JNI, RAW internals, shaders
or Vulkan dispatch requires matching engine source and assets: the public patch
against `445e314` lacks `tiling.hpp` and newer C APIs (see
[source-build.md](source-build.md)). Release-native and prebuilt-native modes
do not compile those edits. The shipped engine has Vulkan batching symbols;
don't infer its dispatch behavior from the partial patch. Do not open a native
optimization change until a matching source-built APK passes smoke and the
same-device pixel/export comparisons against the shipped baseline.

## Before/after acceptance matrix

| Scenario | Compare | Must preserve |
|---|---|---|
| First film tap, rapid stock taps, busy vs. idle shelf, brand switch | Tap-to-settled p50/p95, lock holder/wait, card starts and peak memory | Latest film wins, correct paper/edge, visible cards eventually fill |
| Print/view drag vs. shoot/geometry drag, including during settled render | First applied live frame, applied/dropped counts, release-to-settled p50/p95, jank | No stale paint after release, original toggle, frame switch or pause |
| PNG, rotated JPEG, HEIC, RAW; cold `ACTION_VIEW` over a saved different frame, warm switches and RAW WB | Time to original and session ready, restore vs. requested-open work, main stalls, peak memory | Requested frame wins, color/orientation and caps, restore on failure, no leaked/stale session |
| Live JPEG from capped import; preview/full JPEG with and without border, crop/rotation and RAW WB; TIFF/DNG if touched | Per-stage and end-to-end time, output dimensions/pixels, EXIF/ICC, peak memory | Correct file saved/shared, no new seams/corruption or dropped metadata |
| Mixed six-frame batch, abort and background/resume | Total and per-frame times, memory across frames, retries/failures | Per-frame look/WB, correct success count, restore original selection, no shelf work or stuck UI |

Run `verify.ps1` per change; the offline suite passed on the reviewed baseline.
Keep each optimization in a separate reviewable commit. For each, attach the
before/after table, the trace or log that explains the change, correctness
results, and any device cases not tested. As a provisional rule, investigate a
repeatable regression beyond 5% in unaffected latency or peak memory rather than
averaging it away. Do not claim a speedup when the result is within run-to-run
noise. No regressions in pixel correctness, stale-frame handling, crashes or
OOM recovery are acceptable.
