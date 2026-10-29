// GPU burst merge (Stage A2). Aligns each frame of a burst to the reference with per-tile
// offsets found on a 4-level luma pyramid (coarse to fine), then folds its Bayer cells into
// float accumulators with a noise-aware weight, so moving or misaligned areas fall back to the
// reference instead of ghosting. Reads frames straight from the RAW ring buffer and writes the
// result back over a ring slot. Plain Vulkan; no Android dependency, so it also runs on a
// desktop driver (tools/gpu_merge_test.cpp).
#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>
#include <cstddef>
#include <vector>

class GpuBurstMerge {
public:
    static constexpr uint32_t kLevels = 4;

    // Statistics of the last add(), for logs and tests.
    struct AddStats {
        double ms = 0.0;
        int medianDx = 0, medianDy = 0;   // L0 tile offsets, half-res pixels (Bayer cells)
    };

    bool init(VkPhysicalDevice phys, VkDevice device, VkQueue queue, uint32_t queueFamily,
              const uint32_t* spv, size_t spvBytes);
    void destroy();
    bool ready() const { return mPipeline != VK_NULL_HANDLE; }

    // Sizes the buffers for this frame size (kept between bursts) and clears the burst.
    // hdr: clip-aware merge for a burst plus short highlight frames, with a shoulder on resolve.
    bool begin(VkBuffer rawRing, int width, int height, int stride, float black, float white,
               float noiseS, float noiseO, bool hdr = false);
    // Folds ring slot `slot` in; the first call sets the reference. relExp is this frame's
    // ISO x shutter over the reference's (1 for burst frames, < 1 for HDR highlight frames).
    // Waits for the GPU.
    bool add(uint32_t slot, float relExp = 1.0f, AddStats* stats = nullptr);
    // Writes the merged mosaic over ring slot `slot`. Waits for the GPU.
    bool resolve(uint32_t slot);
    int frames() const { return mFrames; }
    // Copies the L0 offsets of the last add (tilesW x tilesH, x/y pairs) for tests.
    bool readTiles(std::vector<int32_t>& out, uint32_t& tilesW, uint32_t& tilesH);
    // Merge weight per Bayer cell (desktop builds only; null on Android).
    const float* debugDen() const { return static_cast<const float*>(mDen.map); }

private:
    struct Level { uint32_t w = 0, h = 0, refOff = 0, altOff = 0, tile = 0, radius = 0,
                   tilesW = 0, tilesH = 0, tileOff = 0, factor = 1; };
    struct Buf { VkBuffer buf = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; VkDeviceSize size = 0; void* map = nullptr; };

    bool createBuf(Buf& b, VkDeviceSize size, bool hostVisible);
    void destroyBuf(Buf& b);
    uint32_t memType(uint32_t bits, VkMemoryPropertyFlags flags);
    void barrier(VkCommandBuffer cmd);
    void dispatch(VkCommandBuffer cmd, const void* pc, uint32_t w, uint32_t h);
    bool submit(VkCommandBuffer cmd);
    VkCommandBuffer beginCmd();

    VkPhysicalDevice mPhys = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;
    VkQueue mQueue = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties mMemProps = {};
    VkDescriptorSetLayout mSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout mLayout = VK_NULL_HANDLE;
    VkPipeline mPipeline = VK_NULL_HANDLE;
    VkDescriptorPool mPool = VK_NULL_HANDLE;
    VkDescriptorSet mSet = VK_NULL_HANDLE;
    VkCommandPool mCmdPool = VK_NULL_HANDLE;
    VkCommandBuffer mCmd = VK_NULL_HANDLE;
    VkFence mFence = VK_NULL_HANDLE;

    Buf mPyr, mTiles, mNum, mDen;
    VkBuffer mRing = VK_NULL_HANDLE;
    Level mLv[kLevels];
    int mWidth = 0, mHeight = 0, mStride = 0;
    float mBlack = 0.0f, mRange = 1.0f, mNoiseS = 0.0f, mNoiseO = 0.0f;
    int mFrames = 0;
    bool mHdr = false;
};
