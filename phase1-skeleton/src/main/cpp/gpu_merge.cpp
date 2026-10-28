#include "gpu_merge.h"

#include <algorithm>
#include <chrono>
#include <cstring>

#ifdef __ANDROID__
#include <android/log.h>
#define GM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "S24FilmMerge", __VA_ARGS__)
#define GM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "S24FilmMerge", __VA_ARGS__)
#else
#include <cstdio>
#define GM_LOGI(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define GM_LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

// Mirrors the push-constant block in shaders/merge.comp.
struct MergePc {
    uint32_t pass, slot, width, height, stride;
    float black, range;
    uint32_t dstOff, srcOff, dstW, dstH, srcW, srcH, factor, tileSize;
    int32_t radius;
    uint32_t tileOff, tilesW, tilesH, prevOff, prevW, prevH, prevTile;
    float noiseS, noiseO;
    uint32_t refL0, altL0;
};
static_assert(sizeof(MergePc) <= 128, "push constants must fit the 128-byte minimum");

enum : uint32_t { kLuma = 0, kDown = 1, kAlign = 2, kInit = 3, kMerge = 4, kResolve = 5 };
constexpr uint32_t kNoPrev = 0xFFFFFFFFu;

// Per level: box factor from the finer level, alignment tile size, search radius. The coarsest
// level (1/64 of the RAW size) searches +-4 px, i.e. +-256 RAW pixels of hand shake.
constexpr uint32_t kFactor[GpuBurstMerge::kLevels] = {1, 2, 4, 4};
constexpr uint32_t kTile[GpuBurstMerge::kLevels] = {16, 16, 16, 8};
constexpr uint32_t kRadius[GpuBurstMerge::kLevels] = {1, 2, 4, 4};

uint32_t divUp(uint32_t a, uint32_t b) { return (a + b - 1) / b; }

}  // namespace

uint32_t GpuBurstMerge::memType(uint32_t bits, VkMemoryPropertyFlags flags) {
    for (uint32_t i = 0; i < mMemProps.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) && (mMemProps.memoryTypes[i].propertyFlags & flags) == flags) return i;
    }
    return UINT32_MAX;
}

bool GpuBurstMerge::createBuf(Buf& b, VkDeviceSize size, bool hostVisible) {
    VkBufferCreateInfo info = {};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = size;
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(mDevice, &info, nullptr, &b.buf) != VK_SUCCESS) return false;
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(mDevice, b.buf, &req);
    VkMemoryPropertyFlags flags = hostVisible
        ? (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    uint32_t type = memType(req.memoryTypeBits, flags);
    if (type == UINT32_MAX) { destroyBuf(b); return false; }
    VkMemoryAllocateInfo alloc = {};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = type;
    if (vkAllocateMemory(mDevice, &alloc, nullptr, &b.mem) != VK_SUCCESS) { destroyBuf(b); return false; }
    vkBindBufferMemory(mDevice, b.buf, b.mem, 0);
    if (hostVisible && vkMapMemory(mDevice, b.mem, 0, size, 0, &b.map) != VK_SUCCESS) { destroyBuf(b); return false; }
    b.size = size;
    return true;
}

void GpuBurstMerge::destroyBuf(Buf& b) {
    if (b.map) { vkUnmapMemory(mDevice, b.mem); b.map = nullptr; }
    if (b.buf) { vkDestroyBuffer(mDevice, b.buf, nullptr); b.buf = VK_NULL_HANDLE; }
    if (b.mem) { vkFreeMemory(mDevice, b.mem, nullptr); b.mem = VK_NULL_HANDLE; }
    b.size = 0;
}

bool GpuBurstMerge::init(VkPhysicalDevice phys, VkDevice device, VkQueue queue, uint32_t queueFamily,
                         const uint32_t* spv, size_t spvBytes) {
    mPhys = phys;
    mDevice = device;
    mQueue = queue;
    vkGetPhysicalDeviceMemoryProperties(phys, &mMemProps);

    VkDescriptorSetLayoutBinding binds[5] = {};
    for (uint32_t i = 0; i < 5; ++i) {
        binds[i].binding = i;
        binds[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        binds[i].descriptorCount = 1;
        binds[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo sl = {};
    sl.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    sl.bindingCount = 5;
    sl.pBindings = binds;
    if (vkCreateDescriptorSetLayout(device, &sl, nullptr, &mSetLayout) != VK_SUCCESS) { destroy(); return false; }

    VkPushConstantRange range = {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(MergePc)};
    VkPipelineLayoutCreateInfo pl = {};
    pl.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &mSetLayout;
    pl.pushConstantRangeCount = 1;
    pl.pPushConstantRanges = &range;
    if (vkCreatePipelineLayout(device, &pl, nullptr, &mLayout) != VK_SUCCESS) { destroy(); return false; }

    VkShaderModuleCreateInfo sm = {};
    sm.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    sm.codeSize = spvBytes;
    sm.pCode = spv;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(device, &sm, nullptr, &module) != VK_SUCCESS) { destroy(); return false; }
    VkComputePipelineCreateInfo cp = {};
    cp.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cp.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cp.stage.module = module;
    cp.stage.pName = "main";
    cp.layout = mLayout;
    VkResult pr = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cp, nullptr, &mPipeline);
    vkDestroyShaderModule(device, module, nullptr);
    if (pr != VK_SUCCESS) { mPipeline = VK_NULL_HANDLE; destroy(); return false; }

    VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 5};
    VkDescriptorPoolCreateInfo dp = {};
    dp.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dp.maxSets = 1;
    dp.poolSizeCount = 1;
    dp.pPoolSizes = &ps;
    if (vkCreateDescriptorPool(device, &dp, nullptr, &mPool) != VK_SUCCESS) { destroy(); return false; }
    VkDescriptorSetAllocateInfo da = {};
    da.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    da.descriptorPool = mPool;
    da.descriptorSetCount = 1;
    da.pSetLayouts = &mSetLayout;
    if (vkAllocateDescriptorSets(device, &da, &mSet) != VK_SUCCESS) { destroy(); return false; }

    VkCommandPoolCreateInfo cpi = {};
    cpi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpi.queueFamilyIndex = queueFamily;
    if (vkCreateCommandPool(device, &cpi, nullptr, &mCmdPool) != VK_SUCCESS) { destroy(); return false; }
    VkCommandBufferAllocateInfo cba = {};
    cba.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cba.commandPool = mCmdPool;
    cba.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cba.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device, &cba, &mCmd) != VK_SUCCESS) { destroy(); return false; }
    VkFenceCreateInfo fi = {};
    fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fi, nullptr, &mFence) != VK_SUCCESS) { destroy(); return false; }
    GM_LOGI("GPU merge ready");
    return true;
}

void GpuBurstMerge::destroy() {
    if (!mDevice) return;
    destroyBuf(mPyr);
    destroyBuf(mTiles);
    destroyBuf(mNum);
    destroyBuf(mDen);
    if (mFence) vkDestroyFence(mDevice, mFence, nullptr);
    if (mCmdPool) vkDestroyCommandPool(mDevice, mCmdPool, nullptr);
    if (mPool) vkDestroyDescriptorPool(mDevice, mPool, nullptr);
    if (mPipeline) vkDestroyPipeline(mDevice, mPipeline, nullptr);
    if (mLayout) vkDestroyPipelineLayout(mDevice, mLayout, nullptr);
    if (mSetLayout) vkDestroyDescriptorSetLayout(mDevice, mSetLayout, nullptr);
    mFence = VK_NULL_HANDLE; mCmdPool = VK_NULL_HANDLE; mCmd = VK_NULL_HANDLE;
    mPool = VK_NULL_HANDLE; mSet = VK_NULL_HANDLE; mPipeline = VK_NULL_HANDLE;
    mLayout = VK_NULL_HANDLE; mSetLayout = VK_NULL_HANDLE;
    mRing = VK_NULL_HANDLE;
    mWidth = mHeight = mStride = 0;
    mDevice = VK_NULL_HANDLE;
}

bool GpuBurstMerge::begin(VkBuffer rawRing, int width, int height, int stride, float black, float white,
                          float noiseS, float noiseO) {
    if (!ready() || !rawRing || width < 128 || height < 128 || (width & 1) || (height & 1) || (stride & 1)) return false;
    const bool resize = (width != mWidth || height != mHeight);
    if (resize || rawRing != mRing) {
        if (resize) {
            destroyBuf(mPyr); destroyBuf(mTiles); destroyBuf(mNum); destroyBuf(mDen);
            uint32_t w = static_cast<uint32_t>(width / 2), h = static_cast<uint32_t>(height / 2);
            uint32_t pyrFloats = 0, tileCount = 0;
            for (uint32_t l = 0; l < kLevels; ++l) {
                Level& lv = mLv[l];
                if (l > 0) { w = divUp(w, kFactor[l]); h = divUp(h, kFactor[l]); }
                lv.w = w; lv.h = h; lv.factor = kFactor[l]; lv.tile = kTile[l]; lv.radius = kRadius[l];
                lv.tilesW = divUp(w, lv.tile); lv.tilesH = divUp(h, lv.tile);
                lv.tileOff = tileCount;
                tileCount += lv.tilesW * lv.tilesH;
                pyrFloats += w * h;
            }
            uint32_t off = 0;
            for (uint32_t l = 0; l < kLevels; ++l) { mLv[l].refOff = off; off += mLv[l].w * mLv[l].h; }
            for (uint32_t l = 0; l < kLevels; ++l) { mLv[l].altOff = off; off += mLv[l].w * mLv[l].h; }
            const VkDeviceSize px = static_cast<VkDeviceSize>(width) * static_cast<VkDeviceSize>(height);
            if (!createBuf(mPyr, VkDeviceSize(pyrFloats) * 2 * sizeof(float), false)
                || !createBuf(mTiles, VkDeviceSize(tileCount) * 2 * sizeof(int32_t), true)
                || !createBuf(mNum, px * sizeof(float), false)
                || !createBuf(mDen, px / 4 * sizeof(float), false)) {
                GM_LOGE("GPU merge: buffers for %dx%d failed", width, height);
                destroyBuf(mPyr); destroyBuf(mTiles); destroyBuf(mNum); destroyBuf(mDen);
                mWidth = mHeight = 0;
                return false;
            }
            mWidth = width;
            mHeight = height;
        }
        mRing = rawRing;
        VkDescriptorBufferInfo infos[5] = {
            {mRing, 0, VK_WHOLE_SIZE}, {mPyr.buf, 0, VK_WHOLE_SIZE}, {mTiles.buf, 0, VK_WHOLE_SIZE},
            {mNum.buf, 0, VK_WHOLE_SIZE}, {mDen.buf, 0, VK_WHOLE_SIZE}};
        VkWriteDescriptorSet writes[5] = {};
        for (uint32_t i = 0; i < 5; ++i) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = mSet;
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[i].pBufferInfo = &infos[i];
        }
        vkUpdateDescriptorSets(mDevice, 5, writes, 0, nullptr);
    }
    mStride = stride;
    mBlack = black;
    mRange = std::max(white - black, 1.0f);
    // Normalized noise profile; a missing one still gets a small floor so flat areas merge.
    mNoiseS = std::max(noiseS, 2e-5f);
    mNoiseO = std::max(noiseO, 1e-7f);
    mFrames = 0;
    return true;
}

VkCommandBuffer GpuBurstMerge::beginCmd() {
    vkResetCommandBuffer(mCmd, 0);
    VkCommandBufferBeginInfo bi = {};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(mCmd, &bi);
    vkCmdBindPipeline(mCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipeline);
    vkCmdBindDescriptorSets(mCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mLayout, 0, 1, &mSet, 0, nullptr);
    return mCmd;
}

void GpuBurstMerge::barrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb = {};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_HOST_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
}

void GpuBurstMerge::dispatch(VkCommandBuffer cmd, const void* pc, uint32_t w, uint32_t h) {
    vkCmdPushConstants(cmd, mLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(MergePc), pc);
    vkCmdDispatch(cmd, divUp(w, 16), divUp(h, 16), 1);
    barrier(cmd);
}

bool GpuBurstMerge::submit(VkCommandBuffer cmd) {
    VkMemoryBarrier mb = {};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 1, &mb, 0, nullptr, 0, nullptr);
    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) return false;
    VkSubmitInfo si = {};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    vkResetFences(mDevice, 1, &mFence);
    if (vkQueueSubmit(mQueue, 1, &si, mFence) != VK_SUCCESS) return false;
    return vkWaitForFences(mDevice, 1, &mFence, VK_TRUE, 2'000'000'000ull) == VK_SUCCESS;
}

bool GpuBurstMerge::add(uint32_t slot, AddStats* stats) {
    if (!ready() || mWidth == 0) return false;
    auto t0 = std::chrono::steady_clock::now();
    const bool first = (mFrames == 0);
    MergePc base = {};
    base.slot = slot;
    base.width = static_cast<uint32_t>(mWidth);
    base.height = static_cast<uint32_t>(mHeight);
    base.stride = static_cast<uint32_t>(mStride);
    base.black = mBlack;
    base.range = mRange;
    base.noiseS = mNoiseS;
    base.noiseO = mNoiseO;
    base.prevOff = kNoPrev;

    VkCommandBuffer cmd = beginCmd();
    // Luma pyramid of this frame (into the reference levels for the first frame).
    {
        MergePc p = base;
        p.pass = kLuma;
        p.dstOff = first ? mLv[0].refOff : mLv[0].altOff;
        p.dstW = mLv[0].w; p.dstH = mLv[0].h;
        dispatch(cmd, &p, p.dstW, p.dstH);
        for (uint32_t l = 1; l < kLevels; ++l) {
            p.pass = kDown;
            p.srcOff = first ? mLv[l - 1].refOff : mLv[l - 1].altOff;
            p.srcW = mLv[l - 1].w; p.srcH = mLv[l - 1].h;
            p.dstOff = first ? mLv[l].refOff : mLv[l].altOff;
            p.dstW = mLv[l].w; p.dstH = mLv[l].h;
            p.factor = mLv[l].factor;
            dispatch(cmd, &p, p.dstW, p.dstH);
        }
    }
    if (first) {
        MergePc p = base;
        p.pass = kInit;
        dispatch(cmd, &p, mLv[0].w, mLv[0].h);
    } else {
        // Coarse to fine: each level searches around the offset of the level above.
        for (int l = static_cast<int>(kLevels) - 1; l >= 0; --l) {
            const Level& lv = mLv[l];
            MergePc p = base;
            p.pass = kAlign;
            p.srcOff = lv.refOff; p.dstOff = lv.altOff;
            p.dstW = lv.w; p.dstH = lv.h;
            p.tileSize = lv.tile;
            p.radius = static_cast<int32_t>(lv.radius);
            p.tileOff = lv.tileOff; p.tilesW = lv.tilesW; p.tilesH = lv.tilesH;
            if (l + 1 < static_cast<int>(kLevels)) {
                const Level& up = mLv[l + 1];
                p.factor = up.factor;
                p.prevOff = up.tileOff; p.prevW = up.tilesW; p.prevH = up.tilesH; p.prevTile = up.tile;
            }
            dispatch(cmd, &p, lv.tilesW, lv.tilesH);
        }
        MergePc p = base;
        p.pass = kMerge;
        p.tileSize = mLv[0].tile;
        p.tileOff = mLv[0].tileOff; p.tilesW = mLv[0].tilesW; p.tilesH = mLv[0].tilesH;
        p.refL0 = mLv[0].refOff; p.altL0 = mLv[0].altOff;
        dispatch(cmd, &p, mLv[0].w, mLv[0].h);
    }
    if (!submit(cmd)) { GM_LOGE("GPU merge: add submit failed"); return false; }
    mFrames++;
    if (stats) {
        stats->ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        stats->medianDx = stats->medianDy = 0;
        if (!first && mTiles.map) {
            const int32_t* t = static_cast<const int32_t*>(mTiles.map) + 2 * mLv[0].tileOff;
            const size_t n = static_cast<size_t>(mLv[0].tilesW) * mLv[0].tilesH;
            std::vector<int32_t> xs(n), ys(n);
            for (size_t i = 0; i < n; ++i) { xs[i] = t[2 * i]; ys[i] = t[2 * i + 1]; }
            std::nth_element(xs.begin(), xs.begin() + n / 2, xs.end());
            std::nth_element(ys.begin(), ys.begin() + n / 2, ys.end());
            stats->medianDx = xs[n / 2];
            stats->medianDy = ys[n / 2];
        }
    }
    return true;
}

bool GpuBurstMerge::resolve(uint32_t slot) {
    if (!ready() || mWidth == 0 || mFrames < 1) return false;
    VkCommandBuffer cmd = beginCmd();
    MergePc p = {};
    p.pass = kResolve;
    p.slot = slot;
    p.width = static_cast<uint32_t>(mWidth);
    p.height = static_cast<uint32_t>(mHeight);
    p.stride = static_cast<uint32_t>(mStride);
    p.black = mBlack;
    p.range = mRange;
    dispatch(cmd, &p, mLv[0].w, mLv[0].h);
    return submit(cmd);
}

bool GpuBurstMerge::readTiles(std::vector<int32_t>& out, uint32_t& tilesW, uint32_t& tilesH) {
    if (!mTiles.map) return false;
    tilesW = mLv[0].tilesW;
    tilesH = mLv[0].tilesH;
    const int32_t* t = static_cast<const int32_t*>(mTiles.map) + 2 * mLv[0].tileOff;
    out.assign(t, t + 2 * static_cast<size_t>(tilesW) * tilesH);
    return true;
}
