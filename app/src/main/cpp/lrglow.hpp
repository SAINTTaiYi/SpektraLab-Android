// lrglow.hpp — Lightroom Glow 的独立重实现（逆向自 libLrAndroid.so / cr_glow.cpp）
// 纯 C++17，无第三方依赖。所有常数来源见 lrglow.cpp 注释。
#pragma once
#include <cstddef>

namespace lrglow {

enum Style {
    kDiffusion = 0,   // 枚举值 0  ← UI 索引 1
    kBloom     = 1,   // 枚举值 1  ← UI 索引 0
    kHalation  = 2,
    kMotion    = 3,
    kStreak    = 4,
};

struct Params {
    int   style     = kBloom;
    float amount    = 0.50f;  // 0..1   (UI 0..100)
    float range     = 0.00f;  // 0..1
    float spread    = 0.30f;  // 0..1
    float warmth    = 0.00f;  // -1..1
    float direction = 0.0f;   // deg, 0..360   (仅 Motion/Streak)
    float softness  = 0.50f;  // 0..1
    float boostGain = 2.15f;  // extract 的 boost 系数（LR 反解 ≈2.15；1.0 = 关闭）          (仅 Motion/Streak)
};

// 该 style/range 下的高光阈值（sqrt-luma 域）。exposed for tests.
float thresholdFor(int style, float range);

// 3 通道通道增益（Warmth）。w' = warmth * (halation?1.0 : (warmth>=0?2.0:1.5))
void warmthGain(int style, float warmth, float out_rgb[3]);

// 原地处理：rgb = interleaved float, w*h*3, 线性或 gamma 空间均可（阈值按 sRGB 设计）
// mask 可为 nullptr，否则为 w*h 的 [0,1] 权重（对应 LR 的蒙版 Amount 调制）
void set_boost_gain(float g);
void set_sigma_scale(float v);
void set_amount_scale(float v);
void set_norm_floor(float v);
void set_extract_gain(float v);
void set_th(float hi, float lo);
void set_layer_base(float v);   // extract 的 boost 系数（LR 反解 ≈2.15，1.0=关闭）
void apply(float* rgb, int w, int h, const Params& p, const float* mask = nullptr);

// 诊断：输出 Range/Spread/Warmth 的黑白 feedback（Alt 预览），gray = w*h
void feedbackRange(const float* rgb, int w, int h, const Params& p, float* gray);
void feedbackSpread(const float* rgb, int w, int h, const Params& p, float* gray);
void feedbackWarmth(const float* rgb, int w, int h, const Params& p, float* gray);

// 调试：返回光晕覆盖度 w*h（值为归一化权重，0..1）
void debugCoverage(const float* rgb, int w, int h, const Params& p, float* cov);

} // namespace lrglow
