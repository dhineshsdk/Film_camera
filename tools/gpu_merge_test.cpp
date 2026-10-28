// Desktop check of the A2 GPU burst merge (phase1-skeleton/src/main/cpp/gpu_merge.*) on any Vulkan
// driver, e.g. Mesa lavapipe on a PC without a GPU. Builds a synthetic Bayer burst with known hand
// shake, sensor noise and a square that moves between frames, merges it, and checks:
//   1. the median tile offset of every frame matches the shake it was given,
//   2. noise in static areas drops by close to sqrt(frames),
//   3. the moving square leaves no ghost (the merge keeps the reference there).
// Build and run: tools/gpu_merge_test.sh
#include "gpu_merge.h"
#include "merge_spv.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <random>
#include <vector>

static int fail(const char* what) { std::fprintf(stderr, "FAIL: %s\n", what); return 1; }

int main() {
    const int W = 1536, H = 1152, S = W, N = 8;
    const float black = 64.0f, white = 1023.0f, range = white - black;
    // Hand shake per frame in RAW pixels (even: whole Bayer cells).
    const int shift[N][2] = {{0, 0}, {6, -4}, {-10, 2}, {14, 8}, {-2, -12}, {20, -6}, {-16, -16}, {4, 18}};

    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "gpu_merge_test", 1, nullptr, 0, VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    VkInstance inst;
    if (vkCreateInstance(&ici, nullptr, &inst) != VK_SUCCESS) return fail("no Vulkan instance");
    uint32_t nd = 1;
    VkPhysicalDevice phys;
    vkEnumeratePhysicalDevices(inst, &nd, &phys);
    if (nd == 0) return fail("no Vulkan device");
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(phys, &props);
    std::printf("device: %s\n", props.deviceName);
    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &nq, nullptr);
    std::vector<VkQueueFamilyProperties> qf(nq);
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &nq, qf.data());
    uint32_t fam = 0;
    while (fam < nq && !(qf[fam].queueFlags & VK_QUEUE_COMPUTE_BIT)) fam++;
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, nullptr, 0, fam, 1, &prio};
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    VkDevice dev;
    if (vkCreateDevice(phys, &dci, nullptr, &dev) != VK_SUCCESS) return fail("no device");
    VkQueue queue;
    vkGetDeviceQueue(dev, fam, 0, &queue);

    // RAW ring: N slots, host visible, like the app's.
    const VkDeviceSize frameBytes = VkDeviceSize(S) * H * 2;
    VkBufferCreateInfo bci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = frameBytes * N;
    bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    VkBuffer ring;
    vkCreateBuffer(dev, &bci, nullptr, &ring);
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(dev, ring, &req);
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(phys, &mp);
    uint32_t type = 0;
    const VkMemoryPropertyFlags hv = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    while (type < mp.memoryTypeCount && !((req.memoryTypeBits & (1u << type)) && (mp.memoryTypes[type].propertyFlags & hv) == hv)) type++;
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, req.size, type};
    VkDeviceMemory mem;
    if (vkAllocateMemory(dev, &mai, nullptr, &mem) != VK_SUCCESS) return fail("ring alloc");
    vkBindBufferMemory(dev, ring, mem, 0);
    uint16_t* ringMap = nullptr;
    vkMapMemory(dev, mem, 0, bci.size, 0, reinterpret_cast<void**>(&ringMap));

    // Clean scene, a bit larger than the frame so shifted frames have real content at the edges.
    const int M = 64, SW = W + 2 * M, SH = H + 2 * M;
    std::mt19937 rng(7);
    std::vector<float> scene(size_t(SW) * SH);
    {
        // Multi-scale, non-repeating texture: value noise at 256, 64, 16 and 4 px plus a few hard
        // edges, mid grey on average, so every pyramid level has something to align on.
        std::uniform_real_distribution<float> u(-1.f, 1.f);
        auto octave = [&](int cell, float amp) {
            int gw = SW / cell + 2, gh = SH / cell + 2;
            std::vector<float> grid(size_t(gw) * gh);
            for (auto& v : grid) v = u(rng);
            for (int y = 0; y < SH; ++y)
                for (int x = 0; x < SW; ++x) {
                    float fx = float(x) / cell, fy = float(y) / cell;
                    int ix = int(fx), iy = int(fy);
                    float tx = fx - ix, ty = fy - iy;
                    tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty);
                    float a = grid[size_t(iy) * gw + ix], b = grid[size_t(iy) * gw + ix + 1];
                    float c = grid[size_t(iy + 1) * gw + ix], d = grid[size_t(iy + 1) * gw + ix + 1];
                    scene[size_t(y) * SW + x] += amp * ((a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty);
                }
        };
        for (auto& v : scene) v = 0.4f;
        octave(256, 0.2f); octave(64, 0.1f); octave(16, 0.06f); octave(4, 0.04f);
        for (int y = 0; y < SH; ++y)
            for (int x = 0; x < SW; ++x) {
                float& v = scene[size_t(y) * SW + x];
                if ((x - 3 * y / 2) % 397 < 9) v += 0.25f;   // a few slanted lines
                v = std::clamp(v, 0.02f, 0.9f);
            }
    }
    auto clean = [&](int f, int x, int y) {   // normalized 0..1, square moves in frames 1..N-1
        float v = scene[size_t(y - shift[f][1] + M) * SW + (x - shift[f][0] + M)];
        if (f > 0) {
            int cx = 300 + 60 * f, cy = 500;
            if (x >= cx && x < cx + 120 && y >= cy && y < cy + 120) v = 0.95f;
        }
        return v;
    };
    // Noise: var = S x + O in normalized units (roughly ISO 1600 on this sensor).
    const float noiseS = 4e-4f, noiseO = 2e-6f;
    std::normal_distribution<float> g(0.f, 1.f);
    for (int f = 0; f < N; ++f)
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                float c = clean(f, x, y);
                float v = c + std::sqrt(noiseS * c + noiseO) * g(rng);
                ringMap[size_t(f) * S * H + size_t(y) * S + x] = uint16_t(std::clamp(black + v * range + 0.5f, 0.f, white));
            }
    std::vector<uint16_t> ref(ringMap, ringMap + size_t(S) * H);

    GpuBurstMerge m;
    if (!m.init(phys, dev, queue, fam, merge_spv, merge_spv_size)) return fail("init");
    if (!m.begin(ring, W, H, S, black, white, noiseS, noiseO)) return fail("begin");
    int bad = 0;
    for (int f = 0; f < N; ++f) {
        GpuBurstMerge::AddStats st;
        if (!m.add(uint32_t(f), &st)) return fail("add");
        int ex = shift[f][0] / 2, ey = shift[f][1] / 2;
        std::printf("frame %d: %.1f ms, median offset %d,%d (expected %d,%d)\n", f, st.ms, st.medianDx, st.medianDy, ex, ey);
        if (f > 0 && (st.medianDx != ex || st.medianDy != ey)) bad++;
        if (f > 0) {
            std::vector<int32_t> t; uint32_t tw, th;
            m.readTiles(t, tw, th);
            size_t ok = 0;
            for (size_t i = 0; i < t.size() / 2; ++i) ok += (t[2 * i] == ex && t[2 * i + 1] == ey);
            std::printf("  tiles exactly right: %zu / %zu\n", ok, t.size() / 2);
        }
    }
    if (!m.resolve(0)) return fail("resolve");

    // Errors against the clean reference, away from the borders the shake uncovers.
    double eSingle = 0, eMerged = 0, eGhost = 0, eGhostSingle = 0;
    size_t n = 0, ng = 0;
    for (int y = 40; y < H - 40; ++y)
        for (int x = 40; x < W - 40; ++x) {
            float c = clean(0, x, y);
            float s = (ref[size_t(y) * S + x] - black) / range;
            float mg = (ringMap[size_t(y) * S + x] - black) / range;
            bool inPath = (y >= 480 && y < 640 && x >= 340 && x < 900);   // where the square passed
            if (inPath) { eGhost += std::fabs(mg - c); eGhostSingle += std::fabs(s - c); ng++; }
            else { eSingle += double(s - c) * (s - c); eMerged += double(mg - c) * (mg - c); n++; }
        }
    double gain = std::sqrt(eSingle / eMerged);
    std::printf("noise: single %.4f, merged %.4f, reduction %.2fx (ideal %.2fx)\n",
                std::sqrt(eSingle / n), std::sqrt(eMerged / n), gain, std::sqrt(double(N)));
    std::printf("ghost path: mean abs error merged %.4f vs single frame %.4f\n", eGhost / ng, eGhostSingle / ng);

    m.destroy();
    int rc = 0;
    if (bad) rc = fail("tile offsets do not match the shake");
    if (gain < 2.0) rc = fail("noise reduction under 2x");
    if (eGhost / ng > 2.0 * eGhostSingle / ng) rc = fail("moving square leaves a ghost");
    if (!rc) std::printf("PASS\n");
    return rc;
}
