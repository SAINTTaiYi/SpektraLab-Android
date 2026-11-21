# Source-build inputs (known blocker)

The APK published for 1.6.2m was built from an engine tree that is **not fully
contained in this repository**. The public patch applies to upstream
`445e314e28b9b455d2457471fadaffd28c07270b`, but the patched tree does not
compile:

1. Five patched translation units include `engine/src/pipeline/tiling.hpp`
   (`src/pipeline/{blur,engine,pipeline,scene_latitude,view_process}.cpp`).
   Neither upstream nor `patches/engine-android.patch` contains that header.
2. The two JNI units call public C ABI functions that the patched
   `spk_engine.h` never declares: `spk_clear_pin_auto_exposure_ev`,
   `spk_debug_print_exposure`, `spk_debug_print_gain`, `spk_device_profile`,
   `spk_error_code`, `spk_export_source`, `spk_max_plane_bytes`,
   `spk_pin_auto_exposure_ev`, `spk_probe_canary`, `spk_resume_session`,
   `spk_set_output_linear`, `spk_stage_snapshot`, `spk_suspend_session`,
   `spk_trim_pool`. Declarations alone would not be enough — the
   implementations live in the matching engine tree as well.

`docs/windows-build.md` therefore offers `-UseReleaseNative` (Java/UI changes
against the published native library and assets) as the only verified path.

## Checking it yourself

```powershell
python app/build/check-patch.py <upstream-engine-checkout>
```

The script parses the patch, resolves every added `#include`, and compares the
`spk_*` calls in `app/src/main/cpp/*.cpp` against the patched public header.
It exits non-zero with an itemised list when the source tree cannot build the
JNI. CI runs it in report-only mode so the gap stays visible.

## What to do about it

Publishing the matching engine source (or a complete patch that adds
`tiling.hpp` and the 14 declarations plus their implementations) is a
prerequisite for any native change to be reviewable outside the maintainer's
machine. Until then, treat the C++ sources here as documentation of the JNI
surface, not as a reproducible build.
