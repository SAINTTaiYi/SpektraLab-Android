#pragma once
// Compile-only shim for the public APIs that the published engine patch does
// not declare (see docs/source-build.md). It lets CI syntax-check the JNI
// sources against upstream 445e314 until the complete engine tree is published.
// This header is NEVER shipped or linked into the APK.
#include "spektrafilm/spk_engine.h"
#include <cstddef>
#include <cstdint>
#include <string>

extern "C" {
const char* spk_device_profile(spk_engine*);
int spk_error_code(const char*);
spk_status spk_export_source(spk_session*, const char*, spk_result*);
const char* spk_probe_canary(spk_engine*, uint64_t);
uint64_t spk_trim_pool(spk_engine*);
void spk_pin_auto_exposure_ev(spk_session*, double);
void spk_clear_pin_auto_exposure_ev(spk_session*);
int spk_suspend_session(spk_session*);
int spk_resume_session(spk_session*);
int spk_stage_snapshot(spk_session*, char*, size_t);
double spk_debug_print_exposure(spk_session*);
double spk_debug_print_gain(spk_session*);
void spk_set_output_linear(spk_session*, int);
}
namespace spk { namespace io {
bool write_dng(const std::string&, const uint16_t*, uint32_t, uint32_t, uint32_t, std::string&);
}}
