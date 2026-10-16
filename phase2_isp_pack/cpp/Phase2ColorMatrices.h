#pragma once
#include <array>
#include <cmath>
#include <cstring>

namespace s24film {

// Column-major 4x4 matrix padded for std140 UBO upload (Fixes Bug B6)
struct alignas(16) Stage6ColorUBO {
    float camToACEScg[16]; // Column-major 3x3 stored in mat4 layout
    float wbGains[4];      // [R_gain, G_gain, B_gain, exposureScale]
};

// Multiplies two row-major 3x3 matrices: C = A * B
inline std::array<float, 9> mul3x3(const std::array<float, 9>& A, const std::array<float, 9>& B) {
    std::array<float, 9> C{};
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            C[r * 3 + c] = A[r * 3 + 0] * B[0 * 3 + c] +
                           A[r * 3 + 1] * B[1 * 3 + c] +
                           A[r * 3 + 2] * B[2 * 3 + c];
        }
    }
    return C;
}

// Builds the exact Stage 6 Color UBO from Camera2 ForwardMatrix (or ColorMatrix inverse)
// and packs it into column-major std140 mat4 format (Fixes Bug B6).
inline Stage6ColorUBO buildStage6ColorUBO(
    const std::array<float, 9>& forwardMatrixD50, // Camera white-balanced RGB -> CIE XYZ (D50)
    float rGain, float gGain, float bGain, float exposureScale = 1.0f)
{
    // Official SMPTE ST 2065-1 / ACEScg: CIE XYZ (D50) -> Bradford D60 -> Linear ACEScg (AP1)
    // Combined M_XYZ_D50_to_ACEScg (Row-major mathematical representation):
    const std::array<float, 9> M_XYZ_D50_to_ACEScg = {
         1.7048586f, -0.3217799f, -0.2367812f,
        -0.6723159f,  1.6129003f,  0.0177516f,
         0.0110566f, -0.0082800f,  0.9886214f
    };

    auto M_Cam_to_ACEScg = mul3x3(M_XYZ_D50_to_ACEScg, forwardMatrixD50);

    Stage6ColorUBO ubo{};
    // Pack row-major 3x3 into GLSL column-major mat4 (4 floats per column)
    for (int col = 0; col < 3; ++col) {
        for (int row = 0; row < 3; ++row) {
            ubo.camToACEScg[col * 4 + row] = M_Cam_to_ACEScg[row * 3 + col];
        }
        ubo.camToACEScg[col * 4 + 3] = 0.0f;
    }
    ubo.camToACEScg[12] = 0.0f;
    ubo.camToACEScg[13] = 0.0f;
    ubo.camToACEScg[14] = 0.0f;
    ubo.camToACEScg[15] = 1.0f;

    float minG = std::max(gGain, 1e-4f);
    ubo.wbGains[0] = rGain / minG;
    ubo.wbGains[1] = 1.0f;
    ubo.wbGains[2] = bGain / minG;
    ubo.wbGains[3] = exposureScale;
    return ubo;
}

} // namespace s24film
