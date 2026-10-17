#pragma once
// =============================================================================
// S24+ Master 120-Look Database (97 Real Datasheets + 9 Cinema + 14 Fujifilm)
// + All 8 Legendary Wavefront Lens Archetypes (Master120LooksDatabase.h)
// Companion Binary 2D LUT Texture Asset: assets/s24_master_120_looks.bin (256 x 120 RGBA32F)
// =============================================================================
#include <cstdint>

namespace s24film {

enum class LookCategory : uint8_t {
    COLOR_NEGATIVE_C41 = 0,    // 28 C-41 Color Negative Film Stocks
    MOTION_PICTURE_ECN2 = 1,   // 16 ECN-2 / CineStill Motion Picture Stocks
    COLOR_REVERSAL_SLIDE = 2,  // 21 E-6 / K-14 Slide Film Stocks
    BLACK_AND_WHITE = 3,       // 26 Silver Halide B&W Film Stocks
    PRINT_STOCK_AND_PAPER = 4, // 6 RA-4 / ECP-2E Print Papers & 2383/2393
    DIGITAL_CINEMA_CAMERA = 5, // 9 ARRI / RED / Sony Venice / Canon / BMD Pipelines
    FUJIFILM_SIMULATION = 6    // 14 Parametric Fujifilm Film Simulations
};

struct LookProfile120 {
    uint16_t lutRowIndex;      // Row y in the 256x120 RGBA32F GPU LUT texture (0..119)
    LookCategory category;
    const char* lookId;
    const char* displayName;
    float dMin[3];
    float dMax[3];
    float gamma[3];
    float spectralMatrix[9];
    float halationStrength;
    float grainStrength;
};

inline constexpr uint32_t kTotalMasterLooks = 120;

// ISO box speed of each look (exposure index the film is rated at). 0 = digital profile,
// print stock or Fujifilm simulation: those keep the camera's own auto ISO. Exception: 110
// (Classic Neg) is offered as the Superia film stock, so it is rated at Superia X-TRA's 400.
inline constexpr int kMasterLookBoxIso[kTotalMasterLooks] = {
     200,  400,  100,  200,  400,  160,  100,  100,  200,  400,  // 0-9
     800, 1600,  160,  160, 1600,  100,  200,  400,  400,  100,  // 10-19
     500,   50,  200,  160,  400,  800, 1600, 3200,  400,  160,  // 20-29
     200,  100,  200,  250,  250,  400,  500,  160,  200,  250,  // 30-39
     500,   50,  100,  200,  100,  200,   50,  100,  400,  100,  // 40-49
      50,  100,  100,  100,  100,  100,  100,  100,  100,  100,  // 50-59
     200,   25,   64,  100,  400,  200,  100,  200,  400,  100,  // 60-69
    1600,  400,  100, 3200,  400,  125,  400,   80,   50,  200,  // 70-79
     400,  250,  125,  100,  400, 3200,  320,  400,   50,    0,  // 80-89
       0,    0,    0,    0,    0,    0,    0,    0,    0,    0,  // 90-99
       0,    0,    0,    0,    0,    0,    0,    0,    0,    0,  // 100-109
     400,    0,    0,    0,    0,    0,    0,    0,    0,    0,  // 110-119
};

inline constexpr LookProfile120 kMasterLooks120[] = {
    {0, LookCategory::COLOR_NEGATIVE_C41, "agfa_aps_star_200", "Agfa APS star 200", {0.000f, 0.000f, 0.000f}, {2.133f, 2.059f, 2.276f}, {0.696f, 0.666f, 0.719f}, {0.8759f, 0.1229f, 0.0012f, 0.2281f, 0.7107f, 0.0612f, 0.0009f, 0.0809f, 0.9182f}, 0.14f, 0.40f},
    {1, LookCategory::COLOR_NEGATIVE_C41, "agfa_aps_star_400", "Agfa APS star 400", {0.000f, 0.000f, 0.000f}, {2.116f, 2.029f, 2.291f}, {0.672f, 0.657f, 0.747f}, {0.8917f, 0.1082f, 0.0000f, 0.2295f, 0.7173f, 0.0532f, 0.0010f, 0.0712f, 0.9278f}, 0.14f, 0.40f},
    {2, LookCategory::COLOR_NEGATIVE_C41, "agfa_optima_100", "Agfacolor Optima 100", {0.000f, 0.000f, 0.000f}, {1.897f, 1.929f, 2.094f}, {0.596f, 0.602f, 0.658f}, {0.8881f, 0.1117f, 0.0002f, 0.2242f, 0.7212f, 0.0545f, 0.0009f, 0.0751f, 0.9240f}, 0.14f, 0.40f},
    {3, LookCategory::COLOR_NEGATIVE_C41, "agfa_optima_200", "Agfacolor Optima 200", {0.000f, 0.000f, 0.000f}, {1.762f, 1.789f, 1.975f}, {0.531f, 0.544f, 0.582f}, {0.8924f, 0.1070f, 0.0006f, 0.2372f, 0.7150f, 0.0477f, 0.0009f, 0.0705f, 0.9286f}, 0.14f, 0.40f},
    {4, LookCategory::COLOR_NEGATIVE_C41, "agfa_optima_400", "Agfacolor Optima 400", {0.000f, 0.000f, 0.000f}, {2.023f, 2.061f, 2.266f}, {0.603f, 0.613f, 0.656f}, {0.8844f, 0.1109f, 0.0047f, 0.2648f, 0.6761f, 0.0591f, 0.0009f, 0.0809f, 0.9182f}, 0.14f, 0.40f},
    {5, LookCategory::COLOR_NEGATIVE_C41, "agfa_portrait_160", "Agfacolor Portrait 160", {0.000f, 0.000f, 0.000f}, {1.602f, 1.741f, 1.918f}, {0.512f, 0.564f, 0.625f}, {0.9498f, 0.0502f, 0.0000f, 0.2865f, 0.6882f, 0.0253f, 0.0008f, 0.0626f, 0.9366f}, 0.14f, 0.40f},
    {6, LookCategory::COLOR_NEGATIVE_C41, "agfa_ultra_100", "Agfa Ultra 100", {0.000f, 0.000f, 0.000f}, {1.927f, 1.949f, 2.125f}, {0.618f, 0.625f, 0.681f}, {0.9032f, 0.0968f, 0.0000f, 0.2273f, 0.7419f, 0.0308f, 0.0009f, 0.0761f, 0.9230f}, 0.14f, 0.40f},
    {7, LookCategory::COLOR_NEGATIVE_C41, "agfa_vista_100", "Agfa Vista 100", {0.000f, 0.000f, 0.000f}, {1.838f, 1.848f, 2.182f}, {0.618f, 0.614f, 0.739f}, {0.8894f, 0.1104f, 0.0002f, 0.2261f, 0.7201f, 0.0538f, 0.0010f, 0.0757f, 0.9233f}, 0.14f, 0.40f},
    {8, LookCategory::COLOR_NEGATIVE_C41, "agfa_vista_200", "Agfa Vista 200", {0.000f, 0.000f, 0.000f}, {2.133f, 2.059f, 2.276f}, {0.696f, 0.666f, 0.719f}, {0.8759f, 0.1229f, 0.0012f, 0.2281f, 0.7107f, 0.0612f, 0.0009f, 0.0809f, 0.9182f}, 0.14f, 0.40f},
    {9, LookCategory::COLOR_NEGATIVE_C41, "agfa_vista_400", "Agfa Vista 400", {0.000f, 0.000f, 0.000f}, {2.116f, 2.029f, 2.291f}, {0.672f, 0.657f, 0.747f}, {0.8917f, 0.1082f, 0.0000f, 0.2295f, 0.7173f, 0.0532f, 0.0010f, 0.0712f, 0.9278f}, 0.14f, 0.40f},
    {10, LookCategory::COLOR_NEGATIVE_C41, "agfa_vista_800", "Agfa Vista 800", {0.000f, 0.000f, 0.000f}, {2.423f, 2.393f, 2.664f}, {0.749f, 0.756f, 0.820f}, {0.8847f, 0.1088f, 0.0064f, 0.2409f, 0.6909f, 0.0682f, 0.0007f, 0.0675f, 0.9317f}, 0.14f, 0.40f},
    {11, LookCategory::COLOR_NEGATIVE_C41, "fuji_natura_1600", "Fujicolor Natura 1600", {0.001f, 0.001f, 0.001f}, {2.233f, 2.490f, 2.473f}, {0.636f, 0.713f, 0.707f}, {0.8847f, 0.1153f, 0.0000f, 0.3006f, 0.6590f, 0.0404f, 0.0010f, 0.0865f, 0.9125f}, 0.14f, 0.40f},
    {12, LookCategory::COLOR_NEGATIVE_C41, "fuji_pro_160nh", "Fujicolor Pro 160NH", {0.000f, 0.000f, 0.000f}, {1.841f, 1.637f, 1.642f}, {0.652f, 0.582f, 0.565f}, {0.8769f, 0.1231f, 0.0000f, 0.2428f, 0.7324f, 0.0248f, 0.0011f, 0.0867f, 0.9123f}, 0.14f, 0.40f},
    {13, LookCategory::COLOR_NEGATIVE_C41, "fuji_pro_160ns", "Fujicolor Pro 160NS", {0.000f, 0.000f, 0.000f}, {1.723f, 1.498f, 1.575f}, {0.583f, 0.525f, 0.540f}, {0.8680f, 0.1320f, 0.0000f, 0.2528f, 0.7226f, 0.0245f, 0.0011f, 0.0925f, 0.9064f}, 0.14f, 0.40f},
    {14, LookCategory::COLOR_NEGATIVE_C41, "fuji_superia_1600", "Fujicolor Superia 1600", {0.001f, 0.001f, 0.001f}, {2.233f, 2.490f, 2.473f}, {0.636f, 0.713f, 0.707f}, {0.8847f, 0.1153f, 0.0000f, 0.3008f, 0.6586f, 0.0405f, 0.0010f, 0.0865f, 0.9124f}, 0.14f, 0.40f},
    {15, LookCategory::COLOR_NEGATIVE_C41, "fuji_superia_reala", "Fujicolor Superia Reala 100", {0.000f, 0.000f, 0.000f}, {1.740f, 1.804f, 1.724f}, {0.559f, 0.596f, 0.592f}, {0.8767f, 0.1233f, 0.0000f, 0.1700f, 0.7528f, 0.0772f, 0.0015f, 0.0926f, 0.9059f}, 0.14f, 0.40f},
    {16, LookCategory::COLOR_NEGATIVE_C41, "fujifilm_c200", "Fujifilm C200", {-0.000f, -0.001f, -0.001f}, {1.704f, 2.040f, 2.642f}, {0.525f, 0.546f, 0.635f}, {0.8719f, 0.1276f, 0.0005f, 0.2595f, 0.6853f, 0.0552f, 0.0016f, 0.1051f, 0.8932f}, 0.14f, 0.40f},
    {17, LookCategory::COLOR_NEGATIVE_C41, "fujifilm_pro_400h", "Fujifilm Pro 400H", {-0.001f, -0.001f, -0.001f}, {1.581f, 1.605f, 2.126f}, {0.483f, 0.471f, 0.590f}, {0.8748f, 0.1249f, 0.0003f, 0.2206f, 0.7350f, 0.0444f, 0.0012f, 0.0787f, 0.9201f}, 0.04f, 0.40f},
    {18, LookCategory::COLOR_NEGATIVE_C41, "fujifilm_xtra_400", "Fujifilm X-Tra 400", {-0.001f, -0.001f, -0.001f}, {2.132f, 2.249f, 2.862f}, {0.580f, 0.566f, 0.679f}, {0.8606f, 0.1389f, 0.0005f, 0.2479f, 0.7120f, 0.0401f, 0.0012f, 0.0939f, 0.9050f}, 0.14f, 0.40f},
    {19, LookCategory::COLOR_NEGATIVE_C41, "kodak_ektar_100", "Kodak Ektar 100", {-0.001f, -0.001f, -0.001f}, {1.890f, 1.780f, 2.270f}, {0.577f, 0.524f, 0.591f}, {0.9006f, 0.0981f, 0.0013f, 0.2218f, 0.6699f, 0.1084f, 0.0007f, 0.0645f, 0.9348f}, 0.04f, 0.40f},
    {20, LookCategory::COLOR_NEGATIVE_C41, "kodak_exr_500t", "Eastman EXR 500T 5298", {0.000f, 0.000f, 0.000f}, {1.296f, 1.466f, 1.481f}, {0.502f, 0.568f, 0.575f}, {0.9048f, 0.0952f, 0.0000f, 0.2010f, 0.7504f, 0.0486f, 0.0015f, 0.0853f, 0.9132f}, 0.14f, 0.40f},
    {21, LookCategory::COLOR_NEGATIVE_C41, "kodak_exr_50d", "Eastman EXR 50D 5245/7245", {0.000f, 0.000f, 0.001f}, {1.160f, 1.330f, 1.368f}, {0.504f, 0.601f, 0.606f}, {0.8608f, 0.1291f, 0.0101f, 0.2266f, 0.7164f, 0.0570f, 0.0012f, 0.0925f, 0.9064f}, 0.14f, 0.40f},
    {22, LookCategory::COLOR_NEGATIVE_C41, "kodak_gold_200", "Kodak Gold 200", {-0.001f, -0.001f, -0.001f}, {1.627f, 1.514f, 1.687f}, {0.541f, 0.499f, 0.567f}, {0.8704f, 0.1112f, 0.0184f, 0.2112f, 0.6897f, 0.0991f, 0.0009f, 0.0748f, 0.9243f}, 0.14f, 0.40f},
    {23, LookCategory::COLOR_NEGATIVE_C41, "kodak_portra_160", "Kodak Portra 160", {-0.001f, -0.001f, -0.001f}, {1.670f, 1.744f, 2.025f}, {0.536f, 0.498f, 0.547f}, {0.8478f, 0.1456f, 0.0066f, 0.2200f, 0.6432f, 0.1368f, 0.0010f, 0.0710f, 0.9280f}, 0.04f, 0.40f},
    {24, LookCategory::COLOR_NEGATIVE_C41, "kodak_portra_400", "Kodak Portra 400", {-0.001f, -0.001f, -0.001f}, {1.991f, 1.798f, 2.163f}, {0.542f, 0.500f, 0.581f}, {0.8682f, 0.1273f, 0.0045f, 0.2051f, 0.6749f, 0.1201f, 0.0009f, 0.0583f, 0.9408f}, 0.04f, 0.40f},
    {25, LookCategory::COLOR_NEGATIVE_C41, "kodak_portra_800", "Kodak Portra 800", {-0.001f, -0.001f, -0.001f}, {1.898f, 1.731f, 2.025f}, {0.526f, 0.495f, 0.537f}, {0.9053f, 0.0930f, 0.0017f, 0.2418f, 0.6867f, 0.0715f, 0.0012f, 0.0791f, 0.9198f}, 0.04f, 0.40f},
    {26, LookCategory::COLOR_NEGATIVE_C41, "kodak_portra_800_push1", "Kodak Portra 800 (Push 1)", {-0.001f, -0.001f, -0.001f}, {2.297f, 1.959f, 2.256f}, {0.624f, 0.574f, 0.642f}, {0.9053f, 0.0930f, 0.0017f, 0.2418f, 0.6867f, 0.0715f, 0.0012f, 0.0791f, 0.9198f}, 0.04f, 0.40f},
    {27, LookCategory::COLOR_NEGATIVE_C41, "kodak_portra_800_push2", "Kodak Portra 800 (Push 2)", {-0.001f, -0.001f, -0.001f}, {2.518f, 2.010f, 2.523f}, {0.669f, 0.618f, 0.684f}, {0.9053f, 0.0930f, 0.0017f, 0.2418f, 0.6867f, 0.0715f, 0.0012f, 0.0791f, 0.9198f}, 0.04f, 0.40f},
    {28, LookCategory::COLOR_NEGATIVE_C41, "kodak_ultramax_400", "Kodak Ultramax 400", {-0.001f, -0.001f, -0.001f}, {1.803f, 1.703f, 2.056f}, {0.518f, 0.489f, 0.545f}, {0.8951f, 0.1018f, 0.0031f, 0.2430f, 0.6986f, 0.0584f, 0.0010f, 0.0724f, 0.9266f}, 0.14f, 0.40f},
    {29, LookCategory::COLOR_NEGATIVE_C41, "kodak_vericolor_iii", "Kodak Vericolor III Professional (VPS)", {0.001f, 0.002f, 0.002f}, {1.780f, 1.925f, 2.049f}, {0.578f, 0.639f, 0.678f}, {0.8977f, 0.1021f, 0.0002f, 0.2946f, 0.6765f, 0.0290f, 0.0013f, 0.0863f, 0.9124f}, 0.14f, 0.40f},
    {30, LookCategory::COLOR_NEGATIVE_C41, "kodak_verita_200d", "Kodak Verita 200D", {-0.001f, -0.001f, -0.001f}, {1.803f, 1.681f, 1.979f}, {0.489f, 0.485f, 0.540f}, {0.8946f, 0.1033f, 0.0021f, 0.2384f, 0.6824f, 0.0793f, 0.0009f, 0.0703f, 0.9288f}, 0.04f, 0.40f},
    {31, LookCategory::COLOR_NEGATIVE_C41, "konica_centuria_super_100", "Konica Centuria Super 100", {0.001f, 0.000f, 0.000f}, {1.792f, 1.864f, 2.088f}, {0.604f, 0.631f, 0.757f}, {0.8994f, 0.1006f, 0.0000f, 0.2596f, 0.7122f, 0.0281f, 0.0008f, 0.0735f, 0.9256f}, 0.14f, 0.40f},
    {32, LookCategory::COLOR_NEGATIVE_C41, "konica_centuria_super_200", "Konica Centuria Super 200", {0.000f, 0.000f, 0.000f}, {1.777f, 1.841f, 2.068f}, {0.612f, 0.639f, 0.747f}, {0.8994f, 0.1006f, 0.0000f, 0.2597f, 0.7122f, 0.0281f, 0.0008f, 0.0737f, 0.9254f}, 0.14f, 0.40f},
    {33, LookCategory::MOTION_PICTURE_ECN2, "fuji_eterna_250d", "Fujifilm Eterna 250D (8563/8663)", {0.000f, 0.000f, 0.000f}, {1.362f, 1.636f, 1.562f}, {0.498f, 0.551f, 0.582f}, {0.8911f, 0.1089f, 0.0000f, 0.2509f, 0.7176f, 0.0315f, 0.0008f, 0.0746f, 0.9246f}, 0.14f, 0.40f},
    {34, LookCategory::MOTION_PICTURE_ECN2, "fuji_eterna_250t", "Fujifilm Eterna 250T (8553/8653)", {0.000f, 0.000f, 0.000f}, {1.350f, 1.624f, 1.641f}, {0.512f, 0.534f, 0.581f}, {0.8999f, 0.1001f, 0.0000f, 0.2609f, 0.7130f, 0.0260f, 0.0010f, 0.0822f, 0.9169f}, 0.14f, 0.40f},
    {35, LookCategory::MOTION_PICTURE_ECN2, "fuji_eterna_400t", "Fujifilm Eterna 400T (8583/8683)", {0.000f, 0.000f, 0.000f}, {1.189f, 1.444f, 1.522f}, {0.385f, 0.444f, 0.489f}, {0.8825f, 0.1175f, 0.0000f, 0.2692f, 0.7017f, 0.0290f, 0.0013f, 0.0979f, 0.9008f}, 0.14f, 0.40f},
    {36, LookCategory::MOTION_PICTURE_ECN2, "fuji_eterna_500t", "Fujifilm Eterna 500T (8573/8673)", {0.000f, 0.000f, 0.001f}, {1.330f, 1.625f, 1.621f}, {0.443f, 0.523f, 0.548f}, {0.8991f, 0.1009f, 0.0000f, 0.2774f, 0.6987f, 0.0239f, 0.0012f, 0.0965f, 0.9023f}, 0.14f, 0.40f},
    {37, LookCategory::MOTION_PICTURE_ECN2, "fuji_eterna_vivid_160t", "Fujifilm Eterna Vivid 160T (8543/8643)", {0.000f, 0.000f, 0.000f}, {1.333f, 1.607f, 1.659f}, {0.467f, 0.550f, 0.562f}, {0.8712f, 0.1288f, 0.0000f, 0.2773f, 0.7005f, 0.0223f, 0.0018f, 0.1156f, 0.8827f}, 0.14f, 0.40f},
    {38, LookCategory::MOTION_PICTURE_ECN2, "kodak_vision3_200t", "Kodak Vision3 200T", {-0.001f, -0.001f, -0.001f}, {1.667f, 1.635f, 1.845f}, {0.470f, 0.434f, 0.511f}, {0.9159f, 0.0840f, 0.0001f, 0.2527f, 0.7048f, 0.0425f, 0.0010f, 0.0671f, 0.9319f}, 0.04f, 0.40f},
    {39, LookCategory::MOTION_PICTURE_ECN2, "kodak_vision3_250d", "Kodak Vision3 250D", {-0.000f, -0.000f, -0.000f}, {1.695f, 1.777f, 1.907f}, {0.455f, 0.449f, 0.515f}, {0.9240f, 0.0753f, 0.0007f, 0.2482f, 0.7013f, 0.0506f, 0.0010f, 0.0702f, 0.9288f}, 0.04f, 0.40f},
    {40, LookCategory::MOTION_PICTURE_ECN2, "kodak_vision3_500t", "Kodak Vision3 500T", {-0.000f, -0.000f, -0.000f}, {1.745f, 1.639f, 1.921f}, {0.467f, 0.411f, 0.515f}, {0.8873f, 0.1072f, 0.0054f, 0.2517f, 0.6904f, 0.0580f, 0.0018f, 0.0901f, 0.9081f}, 0.04f, 0.40f},
    {41, LookCategory::MOTION_PICTURE_ECN2, "kodak_vision3_50d", "Kodak Vision3 50D", {-0.001f, -0.001f, -0.001f}, {1.624f, 1.695f, 1.912f}, {0.463f, 0.468f, 0.535f}, {0.9196f, 0.0804f, 0.0001f, 0.2426f, 0.7149f, 0.0425f, 0.0007f, 0.0700f, 0.9292f}, 0.04f, 0.40f},
    {42, LookCategory::COLOR_REVERSAL_SLIDE, "agfa_ct_precisa_100", "Agfa CT precisa 100", {0.000f, 0.000f, 0.000f}, {3.266f, 3.771f, 3.210f}, {1.261f, 1.302f, 1.236f}, {0.8980f, 0.1017f, 0.0004f, 0.2802f, 0.6901f, 0.0297f, 0.0013f, 0.0734f, 0.9253f}, 0.14f, 0.25f},
    {43, LookCategory::COLOR_REVERSAL_SLIDE, "agfa_ct_precisa_200", "Agfa CT precisa 200", {0.000f, 0.000f, 0.000f}, {3.105f, 3.399f, 3.050f}, {1.118f, 1.166f, 1.119f}, {0.8793f, 0.1087f, 0.0120f, 0.3158f, 0.6413f, 0.0428f, 0.0009f, 0.0594f, 0.9397f}, 0.14f, 0.25f},
    {44, LookCategory::COLOR_REVERSAL_SLIDE, "agfa_rsx2_100", "Agfachrome RSX II 100", {0.000f, 0.000f, 0.000f}, {3.293f, 3.787f, 3.236f}, {1.283f, 1.318f, 1.259f}, {0.8980f, 0.1017f, 0.0004f, 0.2803f, 0.6899f, 0.0298f, 0.0013f, 0.0734f, 0.9253f}, 0.14f, 0.25f},
    {45, LookCategory::COLOR_REVERSAL_SLIDE, "agfa_rsx2_200", "Agfachrome RSX II 200", {0.000f, 0.000f, 0.000f}, {3.144f, 3.023f, 3.195f}, {1.127f, 1.112f, 1.183f}, {0.8692f, 0.1172f, 0.0135f, 0.2872f, 0.6621f, 0.0507f, 0.0008f, 0.0531f, 0.9461f}, 0.14f, 0.25f},
    {46, LookCategory::COLOR_REVERSAL_SLIDE, "agfa_rsx2_50", "Agfachrome RSX II 50", {0.000f, 0.000f, 0.000f}, {3.307f, 3.793f, 3.272f}, {1.265f, 1.308f, 1.239f}, {0.8978f, 0.1019f, 0.0004f, 0.2797f, 0.6904f, 0.0299f, 0.0013f, 0.0733f, 0.9253f}, 0.14f, 0.25f},
    {47, LookCategory::COLOR_REVERSAL_SLIDE, "fuji_astia_100f", "Fujichrome Astia 100F (RAP100F)", {0.000f, 0.000f, 0.000f}, {3.180f, 3.322f, 3.385f}, {1.325f, 1.285f, 1.286f}, {0.9113f, 0.0887f, 0.0000f, 0.2916f, 0.6851f, 0.0233f, 0.0019f, 0.0976f, 0.9005f}, 0.14f, 0.25f},
    {48, LookCategory::COLOR_REVERSAL_SLIDE, "fuji_provia_400x", "Fujichrome Provia 400X (RXP)", {0.000f, 0.000f, 0.000f}, {3.069f, 3.347f, 3.198f}, {1.305f, 1.367f, 1.352f}, {0.8774f, 0.1225f, 0.0000f, 0.2532f, 0.7165f, 0.0303f, 0.0004f, 0.0447f, 0.9549f}, 0.14f, 0.25f},
    {49, LookCategory::COLOR_REVERSAL_SLIDE, "fuji_velvia_100f", "Fujichrome Velvia 100F (RVP100F)", {0.000f, 0.000f, 0.000f}, {3.324f, 3.707f, 3.615f}, {1.458f, 1.533f, 1.473f}, {0.9147f, 0.0852f, 0.0000f, 0.3104f, 0.6711f, 0.0185f, 0.0018f, 0.0916f, 0.9065f}, 0.14f, 0.25f},
    {50, LookCategory::COLOR_REVERSAL_SLIDE, "fuji_velvia_50", "Fujichrome Velvia 50 (RVP50)", {0.002f, 0.002f, 0.002f}, {3.206f, 3.666f, 3.565f}, {1.370f, 1.384f, 1.316f}, {0.9375f, 0.0625f, 0.0000f, 0.2970f, 0.6806f, 0.0224f, 0.0004f, 0.0463f, 0.9533f}, 0.14f, 0.25f},
    {51, LookCategory::COLOR_REVERSAL_SLIDE, "fujifilm_provia_100f", "Fujifilm Provia 100F", {-0.002f, -0.001f, -0.001f}, {2.556f, 2.545f, 2.076f}, {0.957f, 0.953f, 0.765f}, {0.9204f, 0.0761f, 0.0034f, 0.3285f, 0.6380f, 0.0335f, 0.0009f, 0.0713f, 0.9278f}, 0.04f, 0.25f},
    {52, LookCategory::COLOR_REVERSAL_SLIDE, "fujifilm_velvia_100", "Fujifilm Velvia 100", {-0.002f, -0.001f, -0.001f}, {2.731f, 3.573f, 2.859f}, {1.169f, 1.367f, 1.219f}, {0.9080f, 0.0889f, 0.0031f, 0.3097f, 0.6690f, 0.0213f, 0.0027f, 0.0945f, 0.9028f}, 0.04f, 0.25f},
    {53, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_ektachrome_100", "Kodak Ektachrome 100", {-0.002f, -0.001f, -0.001f}, {2.511f, 2.805f, 2.812f}, {0.916f, 0.992f, 0.969f}, {0.9364f, 0.0634f, 0.0002f, 0.2935f, 0.6762f, 0.0303f, 0.0014f, 0.0516f, 0.9470f}, 0.04f, 0.25f},
    {54, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_ektachrome_100d", "Kodak Ektachrome 100D 5294/7294", {0.005f, 0.005f, 0.000f}, {2.958f, 3.376f, 3.600f}, {1.229f, 1.349f, 1.306f}, {0.9345f, 0.0655f, 0.0000f, 0.2814f, 0.6851f, 0.0334f, 0.0007f, 0.0497f, 0.9496f}, 0.14f, 0.25f},
    {55, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_ektachrome_e100g", "Kodak Ektachrome E100G", {0.009f, 0.006f, 0.005f}, {3.082f, 3.442f, 3.605f}, {1.283f, 1.355f, 1.311f}, {0.9381f, 0.0619f, 0.0000f, 0.2942f, 0.6776f, 0.0282f, 0.0006f, 0.0500f, 0.9494f}, 0.14f, 0.25f},
    {56, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_ektachrome_e100s", "Kodak Ektachrome E100S", {0.003f, 0.003f, 0.002f}, {3.057f, 3.414f, 3.598f}, {1.311f, 1.388f, 1.343f}, {0.9305f, 0.0695f, 0.0000f, 0.2817f, 0.6824f, 0.0360f, 0.0004f, 0.0433f, 0.9563f}, 0.14f, 0.25f},
    {57, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_ektachrome_e100sw", "Kodak Ektachrome E100SW", {0.003f, 0.003f, 0.002f}, {3.063f, 3.414f, 3.590f}, {1.298f, 1.388f, 1.378f}, {0.9305f, 0.0695f, 0.0000f, 0.2817f, 0.6824f, 0.0360f, 0.0004f, 0.0433f, 0.9563f}, 0.14f, 0.25f},
    {58, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_ektachrome_e100vs", "Kodak Ektachrome E100VS", {0.004f, 0.003f, 0.002f}, {2.963f, 3.301f, 3.458f}, {1.331f, 1.433f, 1.342f}, {0.9230f, 0.0769f, 0.0001f, 0.2908f, 0.6748f, 0.0344f, 0.0004f, 0.0407f, 0.9589f}, 0.14f, 0.25f},
    {59, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_elite_chrome_100", "Kodak Elite Chrome 100", {0.007f, 0.005f, 0.005f}, {3.081f, 3.432f, 3.607f}, {1.267f, 1.358f, 1.317f}, {0.9407f, 0.0593f, 0.0000f, 0.3033f, 0.6704f, 0.0264f, 0.0007f, 0.0523f, 0.9471f}, 0.14f, 0.25f},
    {60, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_kodachrome_200", "Kodak Kodachrome 200 Professional (K-14)", {0.004f, 0.003f, 0.002f}, {3.294f, 3.016f, 2.933f}, {1.462f, 1.318f, 1.235f}, {0.8888f, 0.1052f, 0.0060f, 0.2470f, 0.6784f, 0.0746f, 0.0008f, 0.0590f, 0.9402f}, 0.14f, 0.25f},
    {61, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_kodachrome_25", "Kodak Kodachrome 25 Professional (K-14)", {0.002f, 0.002f, 0.003f}, {3.556f, 3.256f, 3.155f}, {1.530f, 1.362f, 1.286f}, {0.8993f, 0.1003f, 0.0004f, 0.3318f, 0.6549f, 0.0132f, 0.0015f, 0.0763f, 0.9222f}, 0.14f, 0.25f},
    {62, LookCategory::COLOR_REVERSAL_SLIDE, "kodak_kodachrome_64", "Kodak Kodachrome 64", {-0.002f, -0.001f, -0.001f}, {2.910f, 2.803f, 2.259f}, {1.091f, 1.088f, 0.758f}, {0.9202f, 0.0780f, 0.0017f, 0.2523f, 0.7039f, 0.0438f, 0.0012f, 0.0631f, 0.9357f}, 0.14f, 0.25f},
    {63, LookCategory::BLACK_AND_WHITE, "agfa_apx_100", "Agfapan APX 100", {0.000f, 0.000f, 0.000f}, {2.287f, 2.287f, 2.287f}, {0.673f, 0.673f, 0.673f}, {0.2919f, 0.3856f, 0.3226f, 0.2919f, 0.3856f, 0.3226f, 0.2919f, 0.3856f, 0.3226f}, 0.14f, 0.65f},
    {64, LookCategory::BLACK_AND_WHITE, "agfa_apx_400", "Agfapan APX 400", {0.000f, 0.000f, 0.000f}, {2.690f, 2.690f, 2.690f}, {0.628f, 0.628f, 0.628f}, {0.2969f, 0.3949f, 0.3082f, 0.2969f, 0.3949f, 0.3082f, 0.2969f, 0.3949f, 0.3082f}, 0.14f, 0.65f},
    {65, LookCategory::BLACK_AND_WHITE, "agfa_scala_200x", "Agfa Scala 200x Professional", {0.000f, 0.000f, 0.000f}, {2.984f, 2.984f, 2.984f}, {1.416f, 1.416f, 1.416f}, {0.2879f, 0.3882f, 0.3239f, 0.2879f, 0.3882f, 0.3239f, 0.2879f, 0.3882f, 0.3239f}, 0.14f, 0.65f},
    {66, LookCategory::BLACK_AND_WHITE, "foma_fomapan_100", "Fomapan 100 Classic", {0.004f, 0.004f, 0.004f}, {2.207f, 2.207f, 2.207f}, {0.941f, 0.941f, 0.941f}, {0.4480f, 0.3565f, 0.1955f, 0.4480f, 0.3565f, 0.1955f, 0.4480f, 0.3565f, 0.1955f}, 0.14f, 0.65f},
    {67, LookCategory::BLACK_AND_WHITE, "foma_fomapan_200", "Fomapan 200 Creative", {0.008f, 0.008f, 0.008f}, {2.295f, 2.295f, 2.295f}, {0.946f, 0.946f, 0.946f}, {0.4807f, 0.3313f, 0.1879f, 0.4807f, 0.3313f, 0.1879f, 0.4807f, 0.3313f, 0.1879f}, 0.14f, 0.65f},
    {68, LookCategory::BLACK_AND_WHITE, "foma_fomapan_400", "Fomapan 400 Action", {0.004f, 0.004f, 0.004f}, {2.210f, 2.210f, 2.210f}, {0.944f, 0.944f, 0.944f}, {0.4790f, 0.3326f, 0.1884f, 0.4790f, 0.3326f, 0.1884f, 0.4790f, 0.3326f, 0.1884f}, 0.14f, 0.65f},
    {69, LookCategory::BLACK_AND_WHITE, "fuji_acros_ii", "Fujifilm Neopan 100 Acros II", {0.000f, 0.000f, 0.000f}, {1.752f, 1.752f, 1.752f}, {0.632f, 0.632f, 0.632f}, {0.4150f, 0.3727f, 0.2123f, 0.4150f, 0.3727f, 0.2123f, 0.4150f, 0.3727f, 0.2123f}, 0.14f, 0.65f},
    {70, LookCategory::BLACK_AND_WHITE, "fuji_neopan_1600", "Fujifilm Neopan 1600 Professional", {0.000f, 0.000f, 0.000f}, {1.452f, 1.452f, 1.452f}, {0.539f, 0.539f, 0.539f}, {0.3381f, 0.3076f, 0.3543f, 0.3381f, 0.3076f, 0.3543f, 0.3381f, 0.3076f, 0.3543f}, 0.14f, 0.65f},
    {71, LookCategory::BLACK_AND_WHITE, "fuji_neopan_400", "Fujifilm Neopan 400 Professional", {0.001f, 0.001f, 0.001f}, {1.934f, 1.934f, 1.934f}, {0.669f, 0.669f, 0.669f}, {0.3171f, 0.3381f, 0.3448f, 0.3171f, 0.3381f, 0.3448f, 0.3171f, 0.3381f, 0.3448f}, 0.14f, 0.65f},
    {72, LookCategory::BLACK_AND_WHITE, "ilford_delta100", "Ilford Delta 100 Professional", {0.000f, 0.000f, 0.000f}, {1.713f, 1.713f, 1.713f}, {0.718f, 0.718f, 0.718f}, {0.3581f, 0.3824f, 0.2595f, 0.3581f, 0.3824f, 0.2595f, 0.3581f, 0.3824f, 0.2595f}, 0.14f, 0.65f},
    {73, LookCategory::BLACK_AND_WHITE, "ilford_delta3200", "Ilford Delta 3200 Professional", {0.000f, 0.000f, 0.000f}, {1.738f, 1.738f, 1.738f}, {0.585f, 0.585f, 0.585f}, {0.4116f, 0.3373f, 0.2511f, 0.4116f, 0.3373f, 0.2511f, 0.4116f, 0.3373f, 0.2511f}, 0.14f, 0.65f},
    {74, LookCategory::BLACK_AND_WHITE, "ilford_delta400", "Ilford Delta 400 Professional", {0.000f, 0.000f, 0.000f}, {2.002f, 2.002f, 2.002f}, {0.667f, 0.667f, 0.667f}, {0.4113f, 0.3374f, 0.2513f, 0.4113f, 0.3374f, 0.2513f, 0.4113f, 0.3374f, 0.2513f}, 0.14f, 0.65f},
    {75, LookCategory::BLACK_AND_WHITE, "ilford_fp4", "Ilford FP4 Plus", {0.000f, 0.000f, 0.000f}, {1.761f, 1.761f, 1.761f}, {0.714f, 0.714f, 0.714f}, {0.4519f, 0.3482f, 0.1999f, 0.4519f, 0.3482f, 0.1999f, 0.4519f, 0.3482f, 0.1999f}, 0.14f, 0.65f},
    {76, LookCategory::BLACK_AND_WHITE, "ilford_hp5", "Ilford HP5 Plus", {0.000f, 0.000f, 0.000f}, {1.943f, 1.943f, 1.943f}, {0.630f, 0.630f, 0.630f}, {0.3684f, 0.3787f, 0.2529f, 0.3684f, 0.3787f, 0.2529f, 0.3684f, 0.3787f, 0.2529f}, 0.14f, 0.65f},
    {77, LookCategory::BLACK_AND_WHITE, "ilford_ortho_plus", "Ilford Ortho Plus", {0.000f, 0.000f, 0.000f}, {1.408f, 1.408f, 1.408f}, {0.596f, 0.596f, 0.596f}, {0.0724f, 0.4217f, 0.5059f, 0.0724f, 0.4217f, 0.5059f, 0.0724f, 0.4217f, 0.5059f}, 0.14f, 0.65f},
    {78, LookCategory::BLACK_AND_WHITE, "ilford_panf", "Ilford Pan F Plus", {0.000f, 0.000f, 0.000f}, {1.849f, 1.849f, 1.849f}, {0.775f, 0.775f, 0.775f}, {0.3588f, 0.3788f, 0.2624f, 0.3588f, 0.3788f, 0.2624f, 0.3588f, 0.3788f, 0.2624f}, 0.14f, 0.65f},
    {79, LookCategory::BLACK_AND_WHITE, "ilford_sfx200", "Ilford SFX 200", {0.000f, 0.000f, 0.000f}, {1.997f, 1.997f, 1.997f}, {0.662f, 0.662f, 0.662f}, {0.3827f, 0.3152f, 0.3020f, 0.3827f, 0.3152f, 0.3020f, 0.3827f, 0.3152f, 0.3020f}, 0.14f, 0.65f},
    {80, LookCategory::BLACK_AND_WHITE, "ilford_xp2", "Ilford XP2 Super", {0.000f, 0.000f, 0.000f}, {1.619f, 1.619f, 1.619f}, {0.612f, 0.612f, 0.612f}, {0.3685f, 0.3786f, 0.2528f, 0.3685f, 0.3786f, 0.2528f, 0.3685f, 0.3786f, 0.2528f}, 0.14f, 0.65f},
    {81, LookCategory::BLACK_AND_WHITE, "kodak_double_x_5222", "Eastman Double-X 5222", {0.002f, 0.002f, 0.002f}, {1.389f, 1.389f, 1.389f}, {0.629f, 0.629f, 0.629f}, {0.2463f, 0.2800f, 0.4737f, 0.2463f, 0.2800f, 0.4737f, 0.2463f, 0.2800f, 0.4737f}, 0.14f, 0.65f},
    {82, LookCategory::BLACK_AND_WHITE, "kodak_plus_x_125", "Kodak Plus-X 125 (PXP)", {0.001f, 0.001f, 0.001f}, {1.990f, 1.990f, 1.990f}, {0.706f, 0.706f, 0.706f}, {0.2367f, 0.2572f, 0.5061f, 0.2367f, 0.2572f, 0.5061f, 0.2367f, 0.2572f, 0.5061f}, 0.14f, 0.65f},
    {83, LookCategory::BLACK_AND_WHITE, "kodak_tmax_100", "Kodak T-Max 100", {0.000f, 0.000f, 0.000f}, {1.933f, 1.933f, 1.933f}, {0.698f, 0.698f, 0.698f}, {0.3007f, 0.3396f, 0.3597f, 0.3007f, 0.3396f, 0.3597f, 0.3007f, 0.3396f, 0.3597f}, 0.14f, 0.65f},
    {84, LookCategory::BLACK_AND_WHITE, "kodak_tmax_400", "Kodak T-Max 400", {0.001f, 0.001f, 0.001f}, {1.939f, 1.939f, 1.939f}, {0.621f, 0.621f, 0.621f}, {0.3298f, 0.4053f, 0.2650f, 0.3298f, 0.4053f, 0.2650f, 0.3298f, 0.4053f, 0.2650f}, 0.14f, 0.65f},
    {85, LookCategory::BLACK_AND_WHITE, "kodak_tmax_p3200", "Kodak T-Max P3200", {0.000f, 0.000f, 0.000f}, {2.282f, 2.282f, 2.282f}, {0.745f, 0.745f, 0.745f}, {0.3168f, 0.3630f, 0.3202f, 0.3168f, 0.3630f, 0.3202f, 0.3168f, 0.3630f, 0.3202f}, 0.14f, 0.65f},
    {86, LookCategory::BLACK_AND_WHITE, "kodak_trix_320", "Kodak Tri-X 320 (320TXP)", {0.001f, 0.001f, 0.001f}, {1.994f, 1.994f, 1.994f}, {0.670f, 0.670f, 0.670f}, {0.2979f, 0.3153f, 0.3868f, 0.2979f, 0.3153f, 0.3868f, 0.2979f, 0.3153f, 0.3868f}, 0.14f, 0.65f},
    {87, LookCategory::BLACK_AND_WHITE, "kodak_trix_400", "Kodak Tri-X 400 (400TX)", {0.000f, 0.000f, 0.000f}, {1.451f, 1.451f, 1.451f}, {0.508f, 0.508f, 0.508f}, {0.2979f, 0.3153f, 0.3868f, 0.2979f, 0.3153f, 0.3868f, 0.2979f, 0.3153f, 0.3868f}, 0.14f, 0.65f},
    {88, LookCategory::BLACK_AND_WHITE, "orwo_un54", "ORWO UN 54", {0.001f, 0.001f, 0.001f}, {1.505f, 1.505f, 1.505f}, {0.580f, 0.580f, 0.580f}, {0.3318f, 0.3468f, 0.3214f, 0.3318f, 0.3468f, 0.3214f, 0.3318f, 0.3468f, 0.3214f}, 0.14f, 0.65f},
    {89, LookCategory::PRINT_STOCK_AND_PAPER, "fujifilm_crystal_archive_typeii", "Fujifilm Crystal Archive Type II", {-0.001f, -0.001f, -0.001f}, {2.446f, 2.185f, 1.959f}, {1.272f, 1.132f, 1.018f}, {0.6904f, 0.2158f, 0.0938f, 0.1629f, 0.7457f, 0.0914f, 0.0030f, 0.1770f, 0.8200f}, 0.04f, 0.40f},
    {90, LookCategory::PRINT_STOCK_AND_PAPER, "kodak_2383", "Kodak Vision 2383", {-0.001f, -0.001f, -0.001f}, {3.802f, 3.294f, 3.024f}, {1.888f, 1.580f, 1.489f}, {0.9084f, 0.0392f, 0.0523f, 0.1850f, 0.7167f, 0.0984f, 0.0007f, 0.0770f, 0.9223f}, 0.04f, 0.40f},
    {91, LookCategory::PRINT_STOCK_AND_PAPER, "kodak_2393", "Kodak Vision Premier 2393", {-0.001f, -0.001f, -0.001f}, {4.627f, 4.480f, 4.231f}, {2.160f, 2.005f, 2.067f}, {0.9496f, 0.0504f, 0.0001f, 0.1719f, 0.7196f, 0.1085f, 0.0006f, 0.0673f, 0.9321f}, 0.04f, 0.40f},
    {92, LookCategory::PRINT_STOCK_AND_PAPER, "kodak_ektacolor_edge", "Kodak Ektacolor Edge", {-0.001f, -0.001f, -0.001f}, {2.252f, 2.019f, 1.845f}, {1.155f, 1.042f, 0.957f}, {0.7311f, 0.2135f, 0.0554f, 0.1494f, 0.7222f, 0.1284f, 0.0011f, 0.1028f, 0.8960f}, 0.04f, 0.40f},
    {93, LookCategory::PRINT_STOCK_AND_PAPER, "kodak_endura_premier", "Kodak Professional Endura Premier", {-0.001f, -0.001f, -0.001f}, {2.426f, 1.891f, 1.920f}, {1.261f, 0.983f, 0.998f}, {0.8397f, 0.1376f, 0.0228f, 0.1582f, 0.7351f, 0.1067f, 0.0013f, 0.1103f, 0.8885f}, 0.04f, 0.40f},
    {94, LookCategory::PRINT_STOCK_AND_PAPER, "kodak_portra_endura", "Kodak Professional Portra Endura", {-0.001f, -0.001f, -0.001f}, {2.463f, 2.069f, 1.822f}, {1.256f, 1.038f, 0.945f}, {0.5886f, 0.3271f, 0.0843f, 0.1520f, 0.7112f, 0.1367f, 0.0014f, 0.1163f, 0.8823f}, 0.04f, 0.40f},
    {95, LookCategory::PRINT_STOCK_AND_PAPER, "kodak_supra_endura", "Kodak Professional Supra Endura", {-0.001f, -0.001f, -0.001f}, {2.478f, 2.009f, 1.924f}, {1.288f, 1.041f, 1.000f}, {0.5886f, 0.3271f, 0.0843f, 0.1520f, 0.7112f, 0.1367f, 0.0014f, 0.1163f, 0.8823f}, 0.04f, 0.40f},
    {96, LookCategory::PRINT_STOCK_AND_PAPER, "kodak_ultra_endura", "Kodak Professional Ultra Endura", {-0.001f, -0.001f, -0.001f}, {2.388f, 1.520f, 2.131f}, {1.238f, 0.790f, 1.108f}, {0.8400f, 0.1374f, 0.0226f, 0.1571f, 0.7346f, 0.1083f, 0.0012f, 0.1099f, 0.8888f}, 0.04f, 0.40f},
    {97, LookCategory::DIGITAL_CINEMA_CAMERA, "cinema_arri_alexa35_logc4", "ARRI ALEXA 35 (AWG4 / LogC4 / REVEAL)", {0.000f, 0.000f, 0.000f}, {2.200f, 2.200f, 2.200f}, {0.580f, 0.580f, 0.580f}, {0.9600f, 0.0300f, 0.0100f, 0.0200f, 0.9600f, 0.0200f, 0.0100f, 0.0400f, 0.9500f}, 0.04f, 0.10f},
    {98, LookCategory::DIGITAL_CINEMA_CAMERA, "cinema_arri_minilf_logc3", "ARRI ALEXA Mini LF (AWG3 / LogC3)", {0.000f, 0.000f, 0.000f}, {2.150f, 2.150f, 2.150f}, {0.600f, 0.600f, 0.600f}, {0.9500f, 0.0400f, 0.0100f, 0.0200f, 0.9500f, 0.0300f, 0.0100f, 0.0500f, 0.9400f}, 0.05f, 0.12f},
    {99, LookCategory::DIGITAL_CINEMA_CAMERA, "cinema_red_vraptor_ipp2", "RED V-Raptor XL (RWG / Log3G10 / IPP2)", {0.000f, 0.000f, 0.000f}, {2.250f, 2.250f, 2.250f}, {0.640f, 0.640f, 0.640f}, {0.9800f, 0.0200f, 0.0000f, 0.0100f, 0.9700f, 0.0200f, 0.0000f, 0.0300f, 0.9700f}, 0.04f, 0.10f},
    {100, LookCategory::DIGITAL_CINEMA_CAMERA, "cinema_sony_venice2_slog3", "Sony VENICE 2 (S-Gamut3.Cine / S-Log3)", {0.000f, 0.000f, 0.000f}, {2.180f, 2.180f, 2.180f}, {0.590f, 0.600f, 0.610f}, {0.9600f, 0.0300f, 0.0100f, 0.0200f, 0.9600f, 0.0200f, 0.0100f, 0.0300f, 0.9600f}, 0.04f, 0.10f},
    {101, LookCategory::DIGITAL_CINEMA_CAMERA, "cinema_canon_clog2", "Canon Cinema EOS (Cinema Gamut / C-Log2)", {0.000f, 0.000f, 0.000f}, {2.120f, 2.120f, 2.150f}, {0.570f, 0.580f, 0.590f}, {0.9700f, 0.0300f, 0.0000f, 0.0200f, 0.9600f, 0.0200f, 0.0100f, 0.0400f, 0.9500f}, 0.05f, 0.12f},
    {102, LookCategory::DIGITAL_CINEMA_CAMERA, "cinema_bmd_gen5_film", "Blackmagic URSA Gen 5 Film Color", {0.000f, 0.000f, 0.000f}, {2.100f, 2.100f, 2.140f}, {0.610f, 0.610f, 0.620f}, {0.9500f, 0.0400f, 0.0100f, 0.0300f, 0.9500f, 0.0200f, 0.0100f, 0.0400f, 0.9500f}, 0.06f, 0.15f},
    {103, LookCategory::DIGITAL_CINEMA_CAMERA, "cinema_fujifilm_flog2", "Fujifilm F-Gamut / F-Log2 Cinema", {0.000f, 0.000f, 0.000f}, {2.150f, 2.150f, 2.180f}, {0.560f, 0.570f, 0.580f}, {0.9600f, 0.0300f, 0.0100f, 0.0200f, 0.9600f, 0.0200f, 0.0100f, 0.0400f, 0.9500f}, 0.04f, 0.12f},
    {104, LookCategory::DIGITAL_CINEMA_CAMERA, "cinema_apple_log", "Apple Log (Rec.2020 Cinema Profile)", {0.000f, 0.000f, 0.000f}, {2.100f, 2.100f, 2.100f}, {0.600f, 0.600f, 0.600f}, {0.9700f, 0.0200f, 0.0100f, 0.0100f, 0.9700f, 0.0200f, 0.0100f, 0.0200f, 0.9700f}, 0.03f, 0.08f},
    {105, LookCategory::DIGITAL_CINEMA_CAMERA, "cinema_dji_dlog", "DJI Ronin 4D / Inspire 3 (D-Gamut/D-Log)", {0.000f, 0.000f, 0.000f}, {2.120f, 2.120f, 2.120f}, {0.620f, 0.620f, 0.620f}, {0.9600f, 0.0300f, 0.0100f, 0.0200f, 0.9600f, 0.0200f, 0.0100f, 0.0300f, 0.9600f}, 0.04f, 0.10f},
    {106, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_provia", "Fujifilm PROVIA / Standard", {0.000f, 0.000f, 0.000f}, {2.600f, 2.600f, 2.600f}, {1.850f, 1.850f, 1.850f}, {1.0200f, -0.0100f, -0.0100f, -0.0100f, 1.0200f, -0.0100f, -0.0100f, -0.0100f, 1.0200f}, 0.06f, 0.22f},
    {107, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_velvia", "Fujifilm VELVIA / Vivid", {0.000f, 0.000f, 0.000f}, {3.100f, 3.250f, 3.050f}, {2.350f, 2.450f, 2.300f}, {1.1800f, -0.0900f, -0.0900f, -0.0800f, 1.1600f, -0.0800f, -0.0800f, -0.0900f, 1.1700f}, 0.08f, 0.20f},
    {108, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_astia", "Fujifilm ASTIA / Soft", {0.000f, 0.000f, 0.000f}, {2.450f, 2.480f, 2.450f}, {1.650f, 1.680f, 1.650f}, {1.0600f, -0.0300f, -0.0300f, -0.0200f, 1.0500f, -0.0300f, -0.0200f, -0.0300f, 1.0500f}, 0.08f, 0.20f},
    {109, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_classic_chrome", "Fujifilm CLASSIC CHROME", {0.000f, 0.000f, 0.000f}, {2.550f, 2.480f, 2.320f}, {1.950f, 1.880f, 1.720f}, {0.9200f, 0.0600f, 0.0200f, 0.0400f, 0.9200f, 0.0400f, 0.0200f, 0.0800f, 0.9000f}, 0.06f, 0.30f},
    {110, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_classic_neg", "Fujifilm CLASSIC NEG (Superia)", {0.000f, 0.000f, 0.000f}, {2.100f, 2.300f, 2.450f}, {0.680f, 0.720f, 0.750f}, {1.0800f, -0.0400f, -0.0400f, -0.0300f, 1.0600f, -0.0300f, -0.0400f, -0.0400f, 1.0800f}, 0.14f, 0.38f},
    {111, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_nostalgic_neg", "Fujifilm NOSTALGIC NEG (Amber)", {0.000f, 0.000f, 0.000f}, {2.150f, 2.050f, 1.850f}, {0.620f, 0.580f, 0.520f}, {1.0600f, 0.0200f, -0.0800f, 0.0200f, 1.0000f, -0.0200f, -0.0400f, 0.0200f, 0.9200f}, 0.16f, 0.35f},
    {112, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_reala_ace", "Fujifilm REALA ACE (4th Layer)", {0.000f, 0.000f, 0.000f}, {2.120f, 2.150f, 2.250f}, {0.640f, 0.640f, 0.650f}, {1.0500f, -0.0300f, -0.0200f, -0.0200f, 1.0400f, -0.0200f, -0.0200f, -0.0300f, 1.0500f}, 0.08f, 0.25f},
    {113, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_pro_neg_hi", "Fujifilm PRO Neg. Hi", {0.000f, 0.000f, 0.000f}, {2.180f, 2.150f, 2.200f}, {0.660f, 0.650f, 0.660f}, {1.0400f, -0.0200f, -0.0200f, -0.0200f, 1.0400f, -0.0200f, -0.0200f, -0.0200f, 1.0400f}, 0.08f, 0.24f},
    {114, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_pro_neg_std", "Fujifilm PRO Neg. Std", {0.000f, 0.000f, 0.000f}, {1.950f, 1.950f, 2.000f}, {0.560f, 0.560f, 0.560f}, {0.9800f, 0.0100f, 0.0100f, 0.0100f, 0.9800f, 0.0100f, 0.0100f, 0.0100f, 0.9800f}, 0.08f, 0.22f},
    {115, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_eterna", "Fujifilm ETERNA / Cinema", {0.000f, 0.000f, 0.000f}, {1.820f, 1.850f, 1.900f}, {0.500f, 0.510f, 0.520f}, {0.9200f, 0.0500f, 0.0300f, 0.0300f, 0.9400f, 0.0300f, 0.0200f, 0.0600f, 0.9200f}, 0.06f, 0.26f},
    {116, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_bleach_bypass", "Fujifilm ETERNA BLEACH BYPASS", {0.000f, 0.000f, 0.000f}, {2.450f, 2.450f, 2.450f}, {0.850f, 0.850f, 0.850f}, {0.6500f, 0.2500f, 0.1000f, 0.2000f, 0.7000f, 0.1000f, 0.2000f, 0.2500f, 0.5500f}, 0.05f, 0.45f},
    {117, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_acros", "Fujifilm ACROS 100 (Fine Silver)", {0.000f, 0.000f, 0.000f}, {2.250f, 2.250f, 2.250f}, {0.680f, 0.680f, 0.680f}, {0.2800f, 0.5800f, 0.1400f, 0.2800f, 0.5800f, 0.1400f, 0.2800f, 0.5800f, 0.1400f}, 0.08f, 0.28f},
    {118, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_monochrome", "Fujifilm MONOCHROME", {0.000f, 0.000f, 0.000f}, {2.050f, 2.050f, 2.050f}, {0.620f, 0.620f, 0.620f}, {0.3000f, 0.5900f, 0.1100f, 0.3000f, 0.5900f, 0.1100f, 0.3000f, 0.5900f, 0.1100f}, 0.08f, 0.35f},
    {119, LookCategory::FUJIFILM_SIMULATION, "fuji_sim_sepia", "Fujifilm SEPIA (Warm Sulfide)", {0.000f, 0.000f, 0.000f}, {2.100f, 1.950f, 1.720f}, {0.600f, 0.560f, 0.480f}, {0.3200f, 0.5800f, 0.1000f, 0.2800f, 0.5800f, 0.1400f, 0.2400f, 0.5600f, 0.2000f}, 0.10f, 0.35f},
};

// All 8 Legendary Wavefront Lens Archetypes (MASTER-PLAN.md Section 6)
struct LensArchetypeSpec {
    uint8_t lensId;
    const char* name;
    const char* eraAndFormula;
    uint8_t irisBlades;
    float swirlPetzval;
    float sphericalGlowW040;
    float soapBubbleRing;
    float thoriumWarmth;
    float anamorphicSqueeze;
    float vignetteCos4;
};

inline constexpr LensArchetypeSpec kLegendaryLenses8[] = {
    {0, "Helios 44-2 58mm f/2",         "1958 KMZ Biotar (6E/4G)",          8,  0.028f, 0.05f, 0.00f, 0.00f, 1.0f, 0.42f},
    {1, "Leica Summilux 35mm f/1.4 v1", "1961 Steel Rim Double-Gauss",     10,  0.004f, 0.34f, 0.00f, 0.00f, 1.0f, 0.35f},
    {2, "Canon 50mm f/0.95 Dream Lens", "1961 Rangefinder 7E/5G",          10,  0.008f, 0.42f, 0.00f, 0.15f, 1.0f, 0.48f},
    {3, "Meyer-Optik Trioplan 100mm",   "1916 Cooke Triplet (Soap Bubble)",15,  0.002f, 0.10f, 0.85f, 0.00f, 1.0f, 0.28f},
    {4, "Asahi Super-Takumar 50mm f/1.4","1964 Radioactive Thorium 7-Elem", 8,  0.003f, 0.12f, 0.00f, 0.85f, 1.0f, 0.32f},
    {5, "Carl Zeiss Planar 50mm f/1.4 T*","1975 C/Y Ninja-Star Iris",       6,  0.001f, 0.02f, 0.00f, 0.00f, 1.0f, 0.25f},
    {6, "Joseph Petzval 85mm f/2.2",    "1840 Brass Portrait (4E/3G)",     12,  0.048f, 0.18f, 0.00f, 0.20f, 1.0f, 0.65f},
    {7, "Panavision C-Series 2x Anamorphic","1968 Cylindrical MgF2 Scope",  8,  0.005f, 0.15f, 0.00f, 0.00f, 2.0f, 0.38f},
};

} // namespace s24film
