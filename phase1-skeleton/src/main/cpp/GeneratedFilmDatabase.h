#pragma once
// Auto-generated from the 97 verified manufacturer datasheet JSON profiles + Cinema/Fuji engines
#include <cstdint>

namespace s24film {

struct StockParams {
    const char* id;
    const char* displayName;
    int stockType; // 0=Linear_ACEScg, 1=ColorNeg, 2=BWNeg, 3=CinePrint2383, 4=SlideE6, 5=AerochromeIR, 6=ArriReveal
    float dMaxR, dMaxG, dMaxB;
    float gammaR, gammaG, gammaB;
    float printGammaR, printGammaG, printGammaB;
    float nu;
    float grainStrength;
    float halationStrength;
    float dirSatBoost;
};

inline constexpr StockParams kFeaturedStocks[] = {
    {"acescg_linear",      "Linear ACEScg Ref",        0, 2.000f, 2.000f, 2.000f, 1.000f, 1.000f, 1.000f, 1.00f, 1.00f, 1.00f, 1.00f, 0.00f, 0.00f, 1.00f},
    {"kodak_portra_400",   "Kodak Portra 400 (C-41)",  1, 2.113f, 1.902f, 2.293f, 0.667f, 0.603f, 0.707f, 1.45f, 1.58f, 1.38f, 0.85f, 0.35f, 0.12f, 1.08f},
    {"kodak_ektar_100",    "Kodak Ektar 100 (C-41)",   1, 1.983f, 1.887f, 2.447f, 0.703f, 0.615f, 0.703f, 1.65f, 1.78f, 1.52f, 0.95f, 0.20f, 0.10f, 1.24f},
    {"kodak_trix_400",     "Kodak Tri-X 400 (B&W)",    2, 1.947f, 1.947f, 1.947f, 0.650f, 0.650f, 0.650f, 1.75f, 1.75f, 1.75f, 0.80f, 0.75f, 0.15f, 1.00f},
    {"kodak_vision3_500t", "Vision3 500T + 2383",      3, 1.838f, 1.749f, 2.027f, 0.540f, 0.477f, 0.596f, 1.72f, 1.88f, 1.58f, 0.85f, 0.45f, 0.08f, 1.10f},
    {"cinestill_800t",     "CineStill 800T (Halation)",3, 1.838f, 1.749f, 2.027f, 0.620f, 0.550f, 0.680f, 1.75f, 1.90f, 1.62f, 0.85f, 0.55f, 1.25f, 1.14f},
    {"fujifilm_velvia_100","Fujichrome Velvia 100",    4, 2.744f, 3.587f, 2.877f, 2.415f, 3.143f, 2.370f, 1.00f, 1.00f, 1.00f, 1.55f, 0.25f, 0.08f, 1.30f},
    {"ilford_hp5_400",     "Ilford HP5 Plus 400",      2, 2.442f, 2.442f, 2.442f, 0.727f, 0.727f, 0.727f, 1.62f, 1.62f, 1.62f, 0.75f, 0.65f, 0.18f, 1.00f},
    {"kodachrome_64",      "Kodachrome 64 (K-14)",     4, 3.650f, 3.800f, 3.850f, 1.750f, 1.950f, 2.000f, 1.00f, 1.00f, 1.00f, 1.25f, 0.30f, 0.05f, 1.22f},
    {"fuji_classic_neg",   "Fuji Classic Neg (Superia)",3,2.000f, 2.300f, 2.500f, 0.650f, 0.690f, 0.710f, 1.68f, 1.82f, 1.55f, 0.90f, 0.40f, 0.15f, 1.16f},
    {"arri_alexa35_reveal","ARRI ALEXA 35 (REVEAL)",   6, 2.200f, 2.200f, 2.200f, 0.580f, 0.580f, 0.580f, 1.50f, 1.50f, 1.50f, 0.90f, 0.12f, 0.04f, 1.06f},
    {"kodak_aerochrome",   "Kodak Aerochrome IR 1443", 5, 2.100f, 2.250f, 2.400f, 0.720f, 0.720f, 0.750f, 1.65f, 1.70f, 1.60f, 1.10f, 0.45f, 0.30f, 1.25f},
};

inline constexpr int kNumFeaturedStocks = sizeof(kFeaturedStocks) / sizeof(kFeaturedStocks[0]);

} // namespace s24film
