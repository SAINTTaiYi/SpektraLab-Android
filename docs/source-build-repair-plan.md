# Source-build repair plan — make the engine patch self-contained

**Status: plan only.** No patch, C++, build-tooling or CI files are changed by
this document.

## 1. Goal and boundaries

Make `patches/engine-android.patch` apply to a clean checkout of upstream
`445e314` and produce a native payload that builds the current JNI and renders
identically to the shipped 1.6.2m payload. Today `check-patch.py` fails, so
native changes are not reproducible outside the maintainer's machine.

- **In scope:** `patches/engine-android.patch`, C++ sources under
  `app/src/main/cpp`, the offline checker, and docs/CI that describe the check.
- **Out of scope:** changing the C ABI or the 48 JNI method names, swapping in
  another frontend's engine, localization, Windows build tooling (separate
  upstream PR), and the Kotlin/Compose modernization in
  [`docs/modernization-plan.md`](modernization-plan.md).
- **Success criterion is pixel equivalence, not binary identity.** A rebuild
  with NDK/CMake will not reproduce the shipped `.so` hash byte-for-byte. It
  must reproduce the deterministic render hashes, export metadata and JNI
  surface.

## 2. Research findings (2026-11-22)

Sources: [SpektraLab repository](https://github.com/JamesQiu2005/SpektraLab),
[445e314](https://github.com/JamesQiu2005/SpektraLab/commit/445e314e28b9b455d2457471fadaffd28c07270b),
[`Windows_Linux` branch](https://github.com/JamesQiu2005/SpektraLab/tree/Windows_Linux),
[`spektralab-windows-v1.3.4-beta.3` tag](https://github.com/JamesQiu2005/SpektraLab/tree/spektralab-windows-v1.3.4-beta.3),
[current `main` header](https://github.com/JamesQiu2005/SpektraLab/blob/main/engine/include/spektralab/spk_engine.h).

| Question | Finding |
|---|---|
| Is the base tree public? | **Yes.** `445e314` exists in the public repository. **45 of the 55 pre-image blob hashes** in `patches/engine-android.patch` match files in `Windows_Linux`, `Windows_Linux_v2` and the `spektralab-windows-v1.3.4-beta.3` tag byte-for-byte. Those refs are the right source for unmodified files. |
| Does public SpektraLab contain `engine/src/pipeline/tiling.hpp`? | **No** — not at `445e314` and not in any public branch/tag checked. But the patch already adds `TilePlan`, `TileRule`, `Arg::window(...)` and `dispatch_banded(...)` to `gpu.hpp`/`vulkan_gpu.cpp`. `tiling.hpp` is therefore likely a small `dispatch_tiled(...)` helper; its signature is inferable from the five call sites in `blur.cpp`, `engine.cpp`, `pipeline.cpp`, `scene_latitude.cpp` and `view_process.cpp`. |
| Are the 14 APIs in `docs/source-build.md` all missing? | **No; the doc is stale.** Four — `spk_export_source`, `spk_max_plane_bytes`, `spk_device_profile`, `spk_error_code` — are already declared *and* implemented by the patch. The current checker reports **10** undeclared APIs and **5** unresolved `tiling.hpp` includes. |
| Does public SpektraLab declare the missing APIs? | Only `spk_trim_pool`, and only in current `main` (2.4.x), where it is a short wrapper over `engine->gpu->trim_pool(keep_bytes)`. The other nine appear in no public header or implementation. `main` has no common ancestor with `445e314`, so use it as a pattern, not a copy source. |
| Header path | Old refs use `engine/include/spektrafilm/spk_engine.h` (matches our patch); current `main` uses `engine/include/spektralab/spk_engine.h`. |
| Licensing | GPL-3.0-or-later for code, CC BY-SA 4.0 for profiles; compatible with this app, with NOTICE/attribution updates if source is copied. |

### Upstream `spektrafilm` as a reference

[`andreavolpato/spektrafilm`](https://github.com/andreavolpato/spektrafilm) is
the Python reference implementation, not a C++ source tree (472 files, no
`assist/`, Vulkan or engine C++). It cannot supply `tiling.hpp`, the JNI APIs, or
device-specific code. It is useful in two narrower roles:

- **Semantics for four missing APIs.**
  `src/spektrafilm/runtime/params_schema.py` defines `camera.auto_exposure`,
  `auto_exposure_method`, `exposure_compensation_ev`,
  `io.input_cctf_decoding` and `output_cctf_encoding`;
  `runtime/stages/printing.py` defines `enlarger.print_exposure`,
  `print_exposure_compensation` and `normalize_print_exposure`;
  `runtime/process.py` exposes stage timings. These are the reference meaning of
  `spk_pin_auto_exposure_ev`/`clear`, `spk_set_output_linear`,
  `spk_debug_print_exposure`/`gain` and `spk_stage_snapshot`.
- **Optional numerical oracle.** `tests/baselines/*.npz`,
  `scripts/regenerate_test_baselines.py` and `runtime/api.py`
  (`create_params`, `simulate`) can spot-check a rebuilt payload — but only
  against a **pinned upstream commit** that matches the model data in the
  shipped APK. Current `main` has moved on and must not be used as a gate.

Not useful for `spk_probe_canary`, `spk_trim_pool` or
`spk_suspend_session`/`resume` (device/Vulkan concerns), and not as a runtime
dependency: the app stays Python-free.

Licensing caveat: upstream states that profiles/LUTs are CC BY-SA 4.0 but also
mentions a separate custom licence for LUTs; our `LICENSE-SPEKTRAFILM.txt` came
via SpektraLab. No new data is copied by this plan, so this is a provenance
note, not a blocker.

Current checker output to preserve as the baseline artifact:

```
check-patch: the published source tree cannot build the JNI:
  - engine/src/pipeline/{blur,engine,pipeline,scene_latitude,view_process}.cpp:
    patched include cannot be resolved: "tiling.hpp"                       (5)
  - JNI calls spk_clear_pin_auto_exposure_ev, spk_debug_print_exposure,
    spk_debug_print_gain, spk_pin_auto_exposure_ev, spk_probe_canary,
    spk_resume_session, spk_set_output_linear, spk_stage_snapshot,
    spk_suspend_session, spk_trim_pool without a declaration              (10)
```

## 3. Triage of the ten missing APIs

| API | JNI use | Class | Plan |
|---|---|---|---|
| `spk_pin_auto_exposure_ev`, `spk_clear_pin_auto_exposure_ev` | Pin the canvas meter EV so export reproduces that frame | Behaviour | Needs a real implementation; public repo has none |
| `spk_suspend_session`, `spk_resume_session` | Drop/rebuild session scratch around export | Behaviour/memory | Needs a real implementation |
| `spk_set_output_linear` | Linear export, display-encoded previews afterwards | Behaviour | Needs a real implementation |
| `spk_trim_pool` | Memory trim | Memory | Backport the public `main` pattern + add `gpu->trim_pool` |
| `spk_probe_canary` | Driver binding-limit probe before import | Capability/diagnostics | Candidate stub, but changes the pixel budget decision — verify |
| `spk_stage_snapshot` | Progress/diagnostic snapshot | Diagnostics | Candidate stub after checking the Java consumer |
| `spk_debug_print_exposure`, `spk_debug_print_gain` | Progress log and export result values | Diagnostics | Candidate stub after checking the Java consumer |

Preferred route for the behaviour-critical APIs: ask the upstream maintainer
for the private engine tree or a complete patch. If that is not available,
reimplement them in our patch from the JNI contracts, document each deviation,
and gate on render/export equivalence.

## 4. Work phases

### Phase 0 — Correct the record and capture the baseline

- Fix `docs/source-build.md`: 10 undeclarations, 5 includes, and point to this
  plan.
- Run `python app/build/check-patch.py <engine checkout>` on a clean `445e314`
  checkout; save the output with the commit hash and toolchain versions.
- Record the equivalence fixture: the 640×480 PNG at bands 512/128
  (hash `900357762661eee2`), the 12 MP JPEG import/export metadata, the
  `app/prebuilt/sha256.json` manifest, and the 48 JNI symbols.
- Identify and record the upstream `andreavolpato/spektrafilm` commit that
  matches the model data in the shipped payload. It is used only for the
  optional oracle in Phase 3, never current `main`.

**Gate 0:** docs accurate; baseline artifact saved; upstream pin recorded; no
source changes.

### Phase 1 — Reconstruct `tiling.hpp`

- Derive the `dispatch_tiled(...)` signature from the five call sites and the
  `TilePlan`/`dispatch_banded` declarations already added by the patch.
- Add `engine/src/pipeline/tiling.hpp` to the patch as a new file.
- Ensure all five includes resolve in the patched tree.

**Gate 1:** `check-patch.py` no longer reports `tiling.hpp` errors; a compile
attempt reaches only the API-link errors; no rendered pixel changes.

### Phase 2 — Complete the C ABI surface

- Verify the four APIs already declared/implemented by the patch still match
  the JNI signatures.
- Add `spk_trim_pool` following the public `main` implementation, including
  `trim_pool` on the GPU interface used by this patch.
- Resolve the remaining nine APIs: real implementations for the three
  behaviour-critical pairs, documented stubs only where the Java consumer is
  verified to tolerate them. Use the pinned upstream schema/stage definitions
  above as the *semantic* reference for the four parameter-related APIs; they
  are not implementations.

**Gate 2:** `check-patch.py` passes; all 48 `Java_app_spektralab_engine_SpektraLabEngine_*`
symbols link; no C ABI or JNI name changes.

### Phase 3 — Build and verify native equivalence

- Build the seven ARM64 libraries with the existing NDK/CMake toolchain.
- Check exported symbols with `app/build/check-libs.py`.
- Build `-UsePrebuiltNative -SideBySide` against the rebuilt payload; run
  `scripts/device-golden.ps1 -Bands 512,128` and the 12 MP JPEG export check.
- Compare renders and export metadata with the shipped payload; run
  `scripts/verify.ps1` in full.
- Optional: spot-check the rebuilt payload against the pinned upstream Python
  reference on a small fixture. Report it alongside, not instead of, the
  shipped-payload hash gate.

**Gate 3:** deterministic hashes and export metadata unchanged; `verify.ps1`
green; APK within the per-mode size baseline from
[`docs/modernization-plan.md`](modernization-plan.md) §5. Do not require the
rebuilt `.so` hashes to equal the shipped ones.

### Phase 4 — Integrate and enforce

- Make `check-patch.py` a required CI check instead of report-only once Phase 2
  passes.
- Update `docs/windows-build.md`, `docs/testing.md` and `docs/source-build.md`
  to describe the now-supported `-EngineCheckout` path.
- Any change to `scripts/build-windows.ps1` or CI workflow is a **separate
  Windows-tooling PR**, per the agreed split.

## 5. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Reconstructed `tiling.hpp` has the wrong signature or tiling semantics | Compile gate plus byte-equality of banded vs whole renders before touching any API work |
| Stubbing a diagnostics API changes a user-visible value or budget decision | Check every Java consumer first; classify stubs explicitly; re-run the import/export smoke |
| Copying from public `main` (2.4.x) pulls in a diverged ABI | Use only `Windows_Linux`/v1.3.4-beta.3 refs as a base; `main` is a pattern for `spk_trim_pool` only |
| Rebuilt libraries differ from the shipped payload | Accept pixel/metadata equivalence as the gate; binary identity was never achievable across toolchains |
| Native work leaks into Windows tooling or localization scope | Keep toolchain/CI changes in separate upstream PRs; no locale or `res/values-*` edits |
| Private engine source stays unavailable | Escalate to the maintainer; without it, Phase 2 reimplementations are the fallback and must be reviewed as behavioural changes |
| Upstream oracle pinned to the wrong commit | Record the hash in Phase 0; treat the Python cross-check as informational, never as a gate |

## 6. PR decomposition

1. `docs: correct the source-build blocker inventory` (Phase 0).
2. `native: add the missing tiling helper header to the patch` (Phase 1).
3. `native: add spk_trim_pool and GPU pool trimming` (Phase 2).
4. `native: complete the private JNI C ABI surface` (Phase 2, behaviour-critical APIs; diagnostics stubs split if they need separate review).
5. `ci: require check-patch and document the source path` (Phase 4).
6. Separate upstream PR for Windows build tooling integration, if desired.

## 7. Open questions

1. Can the maintainer publish the private engine tree or a complete patch? This
   is strongly preferred to reimplementation.
2. Do any Java screens display `spk_debug_print_exposure`/`gain`,
   `spk_stage_snapshot` or `spk_probe_canary` output? That decides which stubs
   are safe.
3. Which public ref should be pinned as the documented base: `Windows_Linux`
   tip or tag `spektralab-windows-v1.3.4-beta.3`? The tag is more stable.
4. Is pixel equivalence sufficient for closing the blocker, or is a
   source-built APK required to ship before the change is accepted?
5. Should the pinned Python oracle be automated in CI, or used only manually
   during Phase 3?
