// =============================================================================
// Spectral film model (Phase 3, spektrafilm method): per-stock lookup tables built on the
// phone from the datasheet spectra in assets/spectral_film.bin (tools/gen_spectral_film.py
// writes it and holds the reference implementation this file mirrors).
//
//   camera   ACEScg chromaticity -> upsampled spectrum -> layer log-sensitivities -> exposure
//   negative layer log exposure -> datasheet characteristic curves -> layer density
//   print    negative: dye densities -> enlarger (halogen + Y/M dichroics) -> paper exposure
//            (Beer-Lambert) -> paper curves -> paper dyes -> reflectance under D50 -> sRGB
//            slide:    dye densities -> transmittance on a D50 light box -> sRGB, scanned at 0.8
//            B&W:      silver density -> neutral paper print (1D)
//
// The tables live in one float buffer the FILM stage reads (binding 18). No Vulkan here, so the
// builder can be compiled and checked on a PC against the Python reference.
// =============================================================================
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace s24film {
namespace spectral {

constexpr int kTypeNeg = 0, kTypeSlide = 1, kTypeBw = 2;
constexpr int kPrintN = 32;          // print LUT cells per axis (negative / slide densities)
constexpr int kNeutralN = 65;        // grey scale used to keep the neutral axis neutral
constexpr float kLogEMin = -3.0f, kLogEMax = 4.0f;
constexpr float kMidGrey = 0.18f;
constexpr float kSlideScan = 0.8f;

// Buffer layout (floats), mirrored in demosaic.comp:
//   [0] look index  [1] type  [2] log exposure offset  [3] print N  [4..6] density axis max
//   [7] camera grid N  [8] curve N  [9..15] reserved
//   kHeader                          camera table, camN * camN vec4 (rgb = exposure per r+g+b)
//   + camN^2 * 4                     characteristic curves, curveN vec4 over [-3, 4] log E
//   + curveN * 4                     print table, N^3 vec4 sRGB-encoded [c][m][y]; B&W: curveN
constexpr int kHeader = 16;

struct Paper {
    std::string name;
    std::vector<float> sens, curves, cd, base, view;   // [nw][3], [curveN][3], [nw][3], [nw], [nw]
    float mOut[9];
    float norm;
};

struct Stock {
    std::string name;
    int look = -1, type = 0, paper = -1;
    float params[3] = {0, 0, 0};
    float offset = 0;
    std::vector<float> sens, curves, cd, base, bwTable;
    float bwDmax = 0;
    float slideOut[9];
    float slideNorm = 1;
};

class Model {
public:
    bool load(const uint8_t* data, size_t len) {
        stocks_.clear();
        papers_.clear();
        if (!data || len < 32 || std::memcmp(data, "SPF1", 4) != 0) return false;
        uint32_t h[7];
        std::memcpy(h, data + 4, sizeof(h));
        if (h[0] != 1) return false;
        const uint32_t nStocks = h[1], nPapers = h[2];
        camN_ = static_cast<int>(h[3]);
        nw_ = static_cast<int>(h[4]);
        curveN_ = static_cast<int>(h[5]);
        p_ = data + 32;
        end_ = data + len;
        ok_ = true;
        spectra_ = take(static_cast<size_t>(camN_) * camN_ * nw_);
        cmf_ = take(nw_ * 3);
        d50_ = take(nw_);
        d65_ = take(nw_);
        enlarger_ = take(nw_);
        filterY_ = take(nw_);
        filterM_ = take(nw_);
        take(9);   // XYZ -> sRGB (kept for tools)
        for (uint32_t i = 0; i < nPapers && ok_; ++i) {
            Paper pp;
            pp.name = takeName();
            pp.sens = take(nw_ * 3);
            pp.curves = take(curveN_ * 3);
            pp.cd = take(nw_ * 3);
            pp.base = take(nw_);
            pp.view = take(nw_);
            takeInto(pp.mOut, 9);
            takeInto(&pp.norm, 1);
            papers_.push_back(std::move(pp));
        }
        for (uint32_t i = 0; i < nStocks && ok_; ++i) {
            Stock s;
            s.name = takeName();
            int32_t ints[3] = {0, 0, 0};
            if (p_ + sizeof(ints) <= end_) { std::memcpy(ints, p_, sizeof(ints)); p_ += sizeof(ints); } else ok_ = false;
            s.look = ints[0]; s.type = ints[1]; s.paper = ints[2];
            takeInto(s.params, 3);
            takeInto(&s.offset, 1);
            s.sens = take(nw_ * 3);
            s.curves = take(curveN_ * 3);
            s.cd = take(nw_ * 3);
            s.base = take(nw_);
            s.bwTable = take(curveN_);
            takeInto(&s.bwDmax, 1);
            takeInto(s.slideOut, 9);
            takeInto(&s.slideNorm, 1);
            stocks_.push_back(std::move(s));
        }
        if (!ok_) { stocks_.clear(); papers_.clear(); }
        return ok_;
    }

    int stockCount() const { return static_cast<int>(stocks_.size()); }
    const Stock* findLook(int look) const {
        for (const Stock& s : stocks_) if (s.look == look) return &s;
        return nullptr;
    }

    size_t bufferFloats() const {
        return kHeader + static_cast<size_t>(camN_) * camN_ * 4 + static_cast<size_t>(curveN_) * 4 +
               static_cast<size_t>(kPrintN) * kPrintN * kPrintN * 4;
    }

    // Fills 'out' (bufferFloats()) with the tables for one stock. Returns false if the look has no
    // spectral data (then the header's look index is -1 and the shader keeps the curve model).
    bool build(int look, std::vector<float>& out) const {
        out.assign(bufferFloats(), 0.0f);
        out[0] = -1.0f;
        const Stock* s = findLook(look);
        if (!s) return false;
        float* cam = out.data() + kHeader;
        float* curves = cam + camN_ * camN_ * 4;
        float* print = curves + curveN_ * 4;

        // Camera: exposure per unit r+g+b, normalised so ACES white (1/3, 1/3) gives 1/3 per layer.
        std::vector<float> tex(static_cast<size_t>(camN_) * camN_ * 3, 0.0f);
        for (int c = 0; c < camN_ * camN_; ++c) {
            const float* sp = &spectra_[static_cast<size_t>(c) * nw_];
            for (int w = 0; w < nw_; ++w)
                for (int k = 0; k < 3; ++k) tex[c * 3 + k] += sp[w] * s->sens[w * 3 + k];
        }
        float ref[3];
        bilinear(tex.data(), 1.0f / 3.0f, 1.0f / 3.0f, ref);
        for (int c = 0; c < camN_ * camN_; ++c)
            for (int k = 0; k < 3; ++k) cam[c * 4 + k] = tex[c * 3 + k] / (3.0f * std::max(ref[k], 1e-20f));

        for (int i = 0; i < curveN_; ++i)
            for (int k = 0; k < 3; ++k) curves[i * 4 + k] = s->curves[i * 3 + k];

        float dmax[3];
        for (int k = 0; k < 3; ++k) {
            float m = 0.0f;
            for (int i = 0; i < curveN_; ++i) m = std::max(m, s->curves[i * 3 + k]);
            dmax[k] = m * 1.05f;
        }

        if (s->type == kTypeBw) {
            for (int i = 0; i < curveN_; ++i) print[i * 4 + 0] = print[i * 4 + 1] = print[i * 4 + 2] = s->bwTable[i];
            dmax[0] = dmax[1] = dmax[2] = s->bwDmax;
        } else {
            const int n = kPrintN;
            std::vector<float> lin(static_cast<size_t>(n) * n * n * 3);
            float grey[kNeutralN * 3];
            if (s->type == kTypeNeg) {
                if (s->paper < 0 || s->paper >= static_cast<int>(papers_.size())) return false;
                Printer pr(*this, *s, papers_[s->paper]);
                for (int ic = 0; ic < n; ++ic)
                    for (int im = 0; im < n; ++im)
                        for (int iy = 0; iy < n; ++iy) {
                            float d[3] = {dmax[0] * ic / (n - 1), dmax[1] * im / (n - 1), dmax[2] * iy / (n - 1)};
                            pr.print(d, &lin[((static_cast<size_t>(ic) * n + im) * n + iy) * 3]);
                        }
                for (int i = 0; i < kNeutralN; ++i) {
                    float d[3];
                    const float x = -4.0f + 8.0f * i / (kNeutralN - 1);
                    for (int k = 0; k < 3; ++k) d[k] = curveAt(s->curves.data(), k, x);
                    pr.print(d, &grey[i * 3]);
                }
            } else {
                Slide sl(*this, *s);
                for (int ic = 0; ic < n; ++ic)
                    for (int im = 0; im < n; ++im)
                        for (int iy = 0; iy < n; ++iy) {
                            float d[3] = {dmax[0] * ic / (n - 1), dmax[1] * im / (n - 1), dmax[2] * iy / (n - 1)};
                            sl.view(d, &lin[((static_cast<size_t>(ic) * n + im) * n + iy) * 3]);
                        }
                for (int i = 0; i < kNeutralN; ++i) {
                    float d[3];
                    const float x = -4.0f + 8.0f * i / (kNeutralN - 1) + s->offset;
                    for (int k = 0; k < 3; ++k) d[k] = curveAt(s->curves.data(), k, x);
                    sl.view(d, &grey[i * 3]);
                }
            }
            neutralBalance(lin, grey);
            for (size_t e = 0; e < static_cast<size_t>(n) * n * n; ++e)
                for (int k = 0; k < 3; ++k) print[e * 4 + k] = srgbOetf(std::clamp(lin[e * 3 + k], 0.0f, 1.0f));
        }

        out[0] = static_cast<float>(look);
        out[1] = static_cast<float>(s->type);
        out[2] = s->offset;
        out[3] = static_cast<float>(kPrintN);
        out[4] = dmax[0]; out[5] = dmax[1]; out[6] = dmax[2];
        out[7] = static_cast<float>(camN_);
        out[8] = static_cast<float>(curveN_);
        return true;
    }

private:
    static float exp10f(float x) { return std::exp2(x * 3.32192809489f); }
    static float srgbOetf(float x) {
        return x <= 0.0031308f ? 12.92f * x : 1.055f * std::pow(std::max(x, 0.0f), 1.0f / 2.4f) - 0.055f;
    }

    float curveAt(const float* curves, int k, float logE) const {
        float x = (logE - kLogEMin) / (kLogEMax - kLogEMin) * (curveN_ - 1);
        x = std::clamp(x, 0.0f, static_cast<float>(curveN_ - 1));
        const int i0 = static_cast<int>(std::floor(x));
        const int i1 = std::min(i0 + 1, curveN_ - 1);
        const float t = x - i0;
        return curves[i0 * 3 + k] * (1.0f - t) + curves[i1 * 3 + k] * t;
    }

    void bilinear(const float* tex, float u, float v, float out[3]) const {
        const float x = u * (camN_ - 1), y = v * (camN_ - 1);
        const int i0 = static_cast<int>(std::floor(x)), j0 = static_cast<int>(std::floor(y));
        const int i1 = std::min(i0 + 1, camN_ - 1), j1 = std::min(j0 + 1, camN_ - 1);
        const float tx = x - i0, ty = y - j0;
        for (int k = 0; k < 3; ++k) {
            auto at = [&](int j, int i) { return tex[(j * camN_ + i) * 3 + k]; };
            out[k] = (at(j0, i0) * (1 - tx) + at(j0, i1) * tx) * (1 - ty) + (at(j1, i0) * (1 - tx) + at(j1, i1) * tx) * ty;
        }
    }

    // Enlarger + paper for one negative / paper pair.
    struct Printer {
        const Model& m; const Stock& s; const Paper& p;
        std::vector<float> lightSens;   // [nw][3] enlarger * filters * paper sensitivity
        std::vector<float> viewCmf;     // [nw][3] D50 * CMF / paper white
        Printer(const Model& m_, const Stock& s_, const Paper& p_) : m(m_), s(s_), p(p_) {
            const int nw = m.nw_;
            lightSens.resize(nw * 3);
            viewCmf.resize(nw * 3);
            for (int w = 0; w < nw; ++w) {
                const float light = m.enlarger_[w] * exp10f(-(s.params[1] * m.filterY_[w] + s.params[2] * m.filterM_[w]));
                for (int k = 0; k < 3; ++k) {
                    lightSens[w * 3 + k] = light * p.sens[w * 3 + k];
                    viewCmf[w * 3 + k] = p.view[w] * m.cmf_[w * 3 + k] / p.norm;
                }
            }
        }
        void print(const float d[3], float rgb[3]) const {
            const int nw = m.nw_;
            float h[3] = {0, 0, 0};
            for (int w = 0; w < nw; ++w) {
                const float dens = d[0] * s.cd[w * 3] + d[1] * s.cd[w * 3 + 1] + d[2] * s.cd[w * 3 + 2] + s.base[w];
                const float t = exp10f(-dens);
                for (int k = 0; k < 3; ++k) h[k] += t * lightSens[w * 3 + k];
            }
            float pd[3];
            for (int k = 0; k < 3; ++k)
                pd[k] = m.curveAt(p.curves.data(), k, std::log10(std::max(h[k], 1e-12f)) + s.params[0]);
            float xyz[3] = {0, 0, 0};
            for (int w = 0; w < nw; ++w) {
                const float dens = pd[0] * p.cd[w * 3] + pd[1] * p.cd[w * 3 + 1] + pd[2] * p.cd[w * 3 + 2] + p.base[w];
                const float r = exp10f(-dens);
                for (int k = 0; k < 3; ++k) xyz[k] += r * viewCmf[w * 3 + k];
            }
            for (int k = 0; k < 3; ++k)
                rgb[k] = p.mOut[k * 3] * xyz[0] + p.mOut[k * 3 + 1] * xyz[1] + p.mOut[k * 3 + 2] * xyz[2];
        }
    };

    // Slide on a D50 light box, its own clear base as white, then the scanner's contrast.
    struct Slide {
        const Model& m; const Stock& s;
        std::vector<float> viewCmf;
        Slide(const Model& m_, const Stock& s_) : m(m_), s(s_) {
            viewCmf.resize(m.nw_ * 3);
            for (int w = 0; w < m.nw_; ++w)
                for (int k = 0; k < 3; ++k) viewCmf[w * 3 + k] = m.d50_[w] * m.cmf_[w * 3 + k] / s.slideNorm;
        }
        void view(const float d[3], float rgb[3]) const {
            float xyz[3] = {0, 0, 0};
            for (int w = 0; w < m.nw_; ++w) {
                const float dens = d[0] * s.cd[w * 3] + d[1] * s.cd[w * 3 + 1] + d[2] * s.cd[w * 3 + 2] + s.base[w];
                const float t = exp10f(-dens);
                for (int k = 0; k < 3; ++k) xyz[k] += t * viewCmf[w * 3 + k];
            }
            const float ymid = std::max(s.params[0], 1e-6f);
            for (int k = 0; k < 3; ++k) {
                const float lin = s.slideOut[k * 3] * xyz[0] + s.slideOut[k * 3 + 1] * xyz[1] + s.slideOut[k * 3 + 2] * xyz[2];
                rgb[k] = kMidGrey * std::pow(std::max(lin, 0.0f) / ymid, kSlideScan);
            }
        }
    };

    // Keep the grey axis grey: the neutral scale's per-channel imbalance, indexed by output
    // luminance, is divided out of every entry. Colours keep the stock's rendering.
    static void neutralBalance(std::vector<float>& lin, const float* grey) {
        float ys[kNeutralN], corr[kNeutralN][3];
        int n = 0;
        float cummax = -1e30f, prev = 0.0f;
        for (int i = 0; i < kNeutralN; ++i) {
            const float* g = &grey[i * 3];
            const float y = 0.2126f * g[0] + 0.7152f * g[1] + 0.0722f * g[2];
            cummax = std::max(cummax, y);
            const bool keep = y > 1e-6f && y >= cummax && (i == 0 || y - prev > 1e-7f);
            prev = y;
            if (!keep) continue;
            ys[n] = y;
            for (int k = 0; k < 3; ++k) corr[n][k] = std::clamp(y / std::max(g[k], 1e-7f), 0.05f, 20.0f);
            ++n;
        }
        if (n == 0) return;
        for (size_t e = 0; e < lin.size() / 3; ++e) {
            float* c = &lin[e * 3];
            const float y = std::max(0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2], 0.0f);
            int j = 0;
            while (j < n && ys[j] < y) ++j;
            for (int k = 0; k < 3; ++k) {
                float f;
                if (j == 0) f = corr[0][k];
                else if (j == n) f = corr[n - 1][k];
                else {
                    const float t = (y - ys[j - 1]) / (ys[j] - ys[j - 1]);
                    f = corr[j - 1][k] + (corr[j][k] - corr[j - 1][k]) * t;
                }
                c[k] *= f;
            }
        }
    }

    std::vector<float> take(size_t n) {
        std::vector<float> v(n, 0.0f);
        takeInto(v.data(), n);
        return v;
    }
    void takeInto(float* dst, size_t n) {
        const size_t bytes = n * sizeof(float);
        if (!ok_ || p_ + bytes > end_) { ok_ = false; return; }
        std::memcpy(dst, p_, bytes);
        p_ += bytes;
    }
    std::string takeName() {
        if (!ok_ || p_ + 32 > end_) { ok_ = false; return std::string(); }
        std::string s(reinterpret_cast<const char*>(p_), strnlen(reinterpret_cast<const char*>(p_), 32));
        p_ += 32;
        return s;
    }

    const uint8_t* p_ = nullptr;
    const uint8_t* end_ = nullptr;
    bool ok_ = false;
    int camN_ = 0, nw_ = 0, curveN_ = 0;
    std::vector<float> spectra_, cmf_, d50_, d65_, enlarger_, filterY_, filterM_;
    std::vector<Paper> papers_;
    std::vector<Stock> stocks_;
};

}  // namespace spectral
}  // namespace s24film
