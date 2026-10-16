#pragma once
// Auto-generated from the 97 verified manufacturer datasheet JSON profiles
#include <cstdint>
namespace s24film {
struct StockParams {
    const char* id;
    const char* displayName;
    int stockType; // 0=Linear_ACEScg, 1=ColorNeg, 2=BWNeg, 3=CinePrint2383, 4=SlideE6
    float dMaxR, dMaxG, dMaxB;
    float gammaR, gammaG, gammaB;
    float printGammaR, printGammaG, printGammaB;
    float nu;
    float grainStrength;
    float halationStrength;
    float dirSatBoost;
};
inline constexpr StockParams kFeaturedStocks[] = {
    {"acescg_linear",     "Phase 2: Linear ACEScg",    0, 2.00f, 2.00f, 2.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f, 1.00f},
    {"kodak_portra_400",  "Kodak Portra 400 (C-41)",   1, 2.113f, 1.902f, 2.293f, 0.667f, 0.603f, 0.707f, 1.45f, 1.58f, 1.38f, 0.85f, 0.35f, 0.12f, 1.06f},
    {"kodak_ektar_100",   "Kodak Ektar 100 (C-41)",    1, 1.983f, 1.887f, 2.447f, 0.703f, 0.615f, 0.703f, 1.65f, 1.78f, 1.52f, 0.95f, 0.20f, 0.10f, 1.22f},
    {"kodak_trix_400",    "Kodak Tri-X 400 (B&W)",     2, 1.947f, 1.947f, 1.947f, 0.650f, 0.650f, 0.650f, 1.75f, 1.75f, 1.75f, 0.80f, 0.75f, 0.15f, 1.00f},
    {"kodak_vision3_500t","Vision3 500T + 2383 Print", 3, 1.838f, 1.749f, 2.027f, 0.540f, 0.477f, 0.596f, 1.72f, 1.88f, 1.58f, 0.85f, 0.45f, 0.05f, 1.08f},
    {"cinestill_800t",    "CineStill 800T (Halation)", 3, 1.838f, 1.749f, 2.027f, 0.620f, 0.550f, 0.680f, 1.75f, 1.90f, 1.62f, 0.85f, 0.55f, 1.15f, 1.12f},
    {"fujifilm_velvia_100","Fujichrome Velvia 100",    4, 2.744f, 3.587f, 2.877f, 2.415f, 3.143f, 2.370f, 1.00f, 1.00f, 1.00f, 1.55f, 0.25f, 0.08f, 1.28f},
    {"ilford_hp5_400",    "Ilford HP5 Plus 400 (B&W)", 2, 2.442f, 2.442f, 2.442f, 0.727f, 0.727f, 0.727f, 1.62f, 1.62f, 1.62f, 0.75f, 0.65f, 0.18f, 1.00f},
};
inline constexpr int kNumFeaturedStocks = sizeof(kFeaturedStocks) / sizeof(kFeaturedStocks[0]);
} // namespace s24film
