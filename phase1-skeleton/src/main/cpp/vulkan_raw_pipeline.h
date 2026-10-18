#pragma once

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <vector>
#include <chrono>
#include <memory>
#include <mutex>
#include <atomic>

#define LOG_TAG "S24FilmVulkan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

struct PushConstants {
    uint32_t width;              // 0
    uint32_t height;             // 4
    uint32_t stride;             // 8
    uint32_t cfa;                // 12
    float whiteLevel;            // 16
    float gainR;                 // 20
    float gainG;                 // 24
    float gainB;                 // 28
    uint32_t sensorOrientation;  // 32
    uint32_t stockType;          // 36
    uint32_t lensType;           // 40
    uint32_t frameSeed;          // 44
    float dMaxR;                 // 48
    float dMaxG;                 // 52
    float dMaxB;                 // 56
    float nu;                    // 60
    float gammaR;                // 64
    float gammaG;                // 68
    float gammaB;                // 72
    float dirSatBoost;           // 76
    float printGammaR;           // 80
    float printGammaG;           // 84
    float printGammaB;           // 88
    float grainStrength;         // 92
    float halationStrength;      // 96
    float exposureScale;         // 100
    uint32_t camMatrixIdx;       // 104
    uint32_t packedRingInfo;     // 108
    uint32_t level;              // 112 pyramid destination level
    uint32_t flags;              // 116 kFlag*
    uint32_t reserved0;          // 120
    uint32_t reserved1;          // 124
};
static_assert(sizeof(PushConstants) == 128, "PushConstants must stay within the 128-byte guaranteed limit");

// std140 mirror of FrameParams in demosaic.comp (binding 6).
struct FrameParamsUbo {
    float noise[4];
    float lsc[4];
    float look[4];
    float look2[4];
    float optics[4];
    float halW[4];
    float halW2[4];
    float diffW[4];
    float diffW2[4];
    float bokeh[4];
    float bokeh2[4];
    float grain[4];
    float hdr[4];
    float lens[4];
    float fm0[4];   // CCT-interpolated ForwardMatrix rows (camera WB RGB -> XYZ D50), fm0[3] = valid
    float fm1[4];
    float fm2[4];
    float lfx0[4];  // lens character, see demosaic.comp f_lfx0..4
    float lfx1[4];
    float lfx2[4];
    float lfx3[4];
    float lfx4[4];
};
static_assert(sizeof(FrameParamsUbo) == 352, "FrameParamsUbo layout mismatch");

// std430 mirror of LookGpu in demosaic.comp (binding 5).
struct LookGpu {
    float dMax[4];
    float gamma[4];
    float dMid[4];
    float m0[4];
    float m1[4];
    float m2[4];
    float misc[4];   // category, mode, DIR boost, LUT row
    float misc2[4];  // normalized flag, halation, grain, unused
    float print0[4]; // printer-light logE offset per layer, w = print LUT row (< 0: no print stage)
    float print1[4]; // print paper D-min per layer, w = print exposure scale
    float print2[4]; // rendered black per layer (subtracted), w = mid-grey normalisation
};
static_assert(sizeof(LookGpu) == 176, "LookGpu layout mismatch");

struct GpuImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView sampledView = VK_NULL_HANDLE;       // all mips
    std::vector<VkImageView> levelViews;            // one storage view per mip
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent2D extent = {0, 0};
    uint32_t mipLevels = 1;
};

// One full set of intermediate images for either the live viewfinder or the
// offline master still. Each target owns its descriptor set and FrameParams UBO.
struct FrameTargets {
    bool master = false;
    VkExtent2D extent = {0, 0};
    VkExtent2D bokehExtent = {0, 0};
    GpuImage linearAces;        // mLinearAcesImage: RGBA16F, 6 mips (base + 5-level pyramid)
    GpuImage bokeh;             // RGBA16F, half-res live / full-res master
    GpuImage storageRgba8;      // mMasterStorageImage (master) / 1x1 dummy (live)
    GpuImage storageRgba16;     // live viewfinder output (live) / 1x1 dummy (master)
    VkBuffer ubo = VK_NULL_HANDLE;
    VkDeviceMemory uboMemory = VK_NULL_HANDLE;
    void* uboMapped = nullptr;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
};

struct LastFrameState {
    bool valid = false;
    int width = 0;
    int height = 0;
    int stride = 0;
    int cfa = 0;
    float whiteLevel = 1023.0f;
    float gainR = 1.0f;
    float gainG = 1.0f;
    float gainB = 1.0f;
    int sensorOrientation = 0;
    int camMatrixIdx = 0;
    uint32_t slot = 0;
    uint32_t burstCount = 1;
};

class VulkanRawPipeline {
public:
    static constexpr uint32_t kStageCount = 5;
    static constexpr uint32_t kPyramidLevels = 5;      // mips 1..5 on top of the base
    static constexpr uint32_t kFlagMaster = 1u;
    static constexpr uint32_t kFlagSpatial = 2u;
    static constexpr uint32_t kFlagGat = 4u;
    static constexpr uint32_t kFlagLsc = 8u;
    static constexpr int kSuper70LensIndex = 5;

    VulkanRawPipeline();
    ~VulkanRawPipeline();

    bool init(ANativeWindow* window, AAssetManager* assetManager);
    void destroy();
    void setFilmLook(int stockIndex, int lensIndex, bool burstEnabled);
    void setMasterLook(int lookIndex);
    void setDarkroomSliders(float evStops, float grainMult, float halationMult);
    void setCinemaParams(int anamorphicMode, int cinemaGate, bool gateWeave, bool stepPrint8fps);
    void setCaptureState(int iso, int64_t exposureNs, float focusDiopters,
                         float noiseS, float noiseO, float blackLevel);
    void setForwardMatrix(const float* fm9);
    void setFilmFormat(int formatIdx) { mFilmFormat.store(std::clamp(formatIdx, 0, 3)); }
    // Film exposure: digital gain from sensor ISO to the stock's exposure index, and push (+)
    // or pull (-) development in stops.
    void setFilmExposure(float digitalGain, float pushStops) {
        mFilmDigitalGain.store(std::clamp(digitalGain, 0.25f, 16.0f));
        mPushStops.store(std::clamp(pushStops, -2.0f, 3.0f));
    }
    int activeLookIndex() const { return mActiveLookIdx.load(); }
    void setLensFxEnabled(bool on) { mLensFxEnabled.store(on); }
    void setVirtualAperture(float fNumber) { mVirtualFNumber.store(std::clamp(fNumber, 0.95f, 22.0f)); }
    float lastRawMean() const { return mLastRawMean.load(); }
    bool setEncoderWindow(ANativeWindow* encoderWindow);
    float renderRawFrame(AHardwareBuffer* ahb, int width, int height, int stride,
                         int cfa, float whiteLevel, float gainR, float gainG, float gainB,
                         int sensorOrientation, int camMatrixIdx);
    bool copyLastRenderedRgba(std::vector<uint8_t>& outRgba, int& outW, int& outH);
    bool renderMasterStill(std::vector<uint8_t>& outRgba, int& outW, int& outH);
    bool copyMasterGainmap(std::vector<uint8_t>& outGain, int& outW, int& outH);

    // Multi-frame stacking on the frames already held in the RAW ring (no extra camera streams).
    // Begin sizes the accumulators for the current RAW size; Add folds the most recent ring frame in
    // (relExposure = this frame's ISO x shutter over the reference frame's, 1 for equal frames);
    // Finish writes the merged mosaic back over the newest ring slot so the master render uses it
    // alone; End clears the flag. Modes: 0 burst average with ghost rejection, 1 HDR bracket,
    // 2 long exposure average, 3 long exposure lighten.
    bool stackBegin(int mode);
    bool stackAdd(float relExposure);
    bool stackFinish();
    void stackEnd();

private:
    struct StackState {
        int mode = -1;
        int width = 0, height = 0, stride = 0;
        float black = 0.0f, white = 1023.0f;
        int frames = 0;
        std::vector<float> num;
        std::vector<float> den;
        std::vector<uint16_t> ref;
        std::vector<uint16_t> peak;
    };
    StackState mStack;
    std::atomic<bool> mStackActive{false};
    std::atomic<bool> mForceSingleFrame{false};

    bool createInstance();
    bool selectPhysicalDevice();
    bool createDevice();
    bool createSwapchain();
    void destroySwapchain();
    bool createEncoderSwapchainLocked(ANativeWindow* encoderWindow);
    void destroyEncoderSwapchainLocked();
    bool createComputeResources();
    void destroyComputeResources();
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);

    // Resource helpers
    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags props,
                      VkBuffer& buffer, VkDeviceMemory& memory, void** mapped);
    void destroyBuffer(VkBuffer& buffer, VkDeviceMemory& memory, void** mapped);
    bool createImage(GpuImage& img, VkFormat format, uint32_t w, uint32_t h, uint32_t mips,
                     VkImageUsageFlags usage);
    void destroyImage(GpuImage& img);
    bool uploadImage(GpuImage& img, const void* data, VkDeviceSize bytes);
    bool transitionToGeneral(const std::vector<VkImage>& images);
    bool formatSupports(VkFormat format, VkFormatFeatureFlags features);

    bool createDescriptorLayoutAndPipelines();
    bool createSharedResources();
    bool loadLookLut();
    bool createGrainTile();
    bool createTargets(FrameTargets& t, VkExtent2D extent, bool master);
    void destroyTargets(FrameTargets& t);
    void writeTargetDescriptors(FrameTargets& t);
    void fillFrameParams(FrameTargets& t, int camMatrixIdx);
    void recordEngine(VkCommandBuffer cmd, FrameTargets& t, PushConstants pc,
                      uint32_t rawW, uint32_t rawH, uint32_t queryBase);
    void computeBarrier(VkCommandBuffer cmd);
    void fillPushConstants(PushConstants& pc, const LastFrameState& st, uint32_t slot, uint32_t burstCnt);
    void logTimestamps(const char* label, uint32_t queryBase, uint32_t count);
    VkExtent2D masterExtentFor(int rawW, int rawH, int orientation) const;

    std::mutex mRenderMutex;
    ANativeWindow* mWindow = nullptr;
    AAssetManager* mAssetManager = nullptr;
    uint32_t mWindowWidth = 0;
    uint32_t mWindowHeight = 0;

    VkInstance mInstance = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;
    VkQueue mQueue = VK_NULL_HANDLE;
    uint32_t mQueueFamilyIndex = 0;
    uint32_t mTimestampValidBits = 0;
    float mTimestampPeriodNs = 1.0f;

    // Viewfinder Swapchain (prefers 10-bit A2B10G10R10)
    VkSurfaceKHR mSurface = VK_NULL_HANDLE;
    VkSwapchainKHR mSwapchain = VK_NULL_HANDLE;
    VkFormat mSwapchainFormat = VK_FORMAT_R8G8B8A8_UNORM;
    VkColorSpaceKHR mSwapchainColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    VkExtent2D mSwapchainExtent = {0, 0};
    std::vector<VkImage> mSwapchainImages;
    std::vector<VkImageView> mSwapchainImageViews;

    // Phase 5 Hardware Video Encoder Swapchain (MediaCodec Surface)
    ANativeWindow* mEncoderWindow = nullptr;
    VkSurfaceKHR mEncoderSurface = VK_NULL_HANDLE;
    VkSwapchainKHR mEncoderSwapchain = VK_NULL_HANDLE;
    VkFormat mEncoderFormat = VK_FORMAT_R8G8B8A8_UNORM;
    VkExtent2D mEncoderExtent = {0, 0};
    std::vector<VkImage> mEncoderImages;
    VkSemaphore mEncoderImageAvailableSemaphore = VK_NULL_HANDLE;
    VkSemaphore mEncoderRenderFinishedSemaphore = VK_NULL_HANDLE;

    // Shared engine resources
    VkBuffer mRawStorageBuffer = VK_NULL_HANDLE;
    VkDeviceMemory mRawStorageBufferMemory = VK_NULL_HANDLE;
    void* mMappedRawBuffer = nullptr;
    VkDeviceSize mRawStorageBufferSize = 0;

    VkBuffer mCleanBayerBuffer = VK_NULL_HANDLE;
    VkDeviceMemory mCleanBayerMemory = VK_NULL_HANDLE;
    VkDeviceSize mCleanBayerSize = 0;

    VkBuffer mLscBuffer = VK_NULL_HANDLE;
    VkDeviceMemory mLscMemory = VK_NULL_HANDLE;
    void* mLscMapped = nullptr;

    VkBuffer mLookBuffer = VK_NULL_HANDLE;
    VkDeviceMemory mLookMemory = VK_NULL_HANDLE;
    void* mLookMapped = nullptr;

    GpuImage mLookLutImage;        // 256 x 120 RGBA32F (RGBA16F if 32F linear filtering is unsupported)
    GpuImage mGrainTileImage;      // 512 x 512 RGBA16F tileable Gabor noise
    VkSampler mLutSampler = VK_NULL_HANDLE;
    VkSampler mLinearClampSampler = VK_NULL_HANDLE;
    VkSampler mRepeatSampler = VK_NULL_HANDLE;
    bool mLutReady = false;
    std::vector<LookGpu> mLookParams;

    FrameTargets mLiveTargets;
    FrameTargets mMasterTargets;

    VkBuffer mReadbackBuffer = VK_NULL_HANDLE;           // live RGBA16F readback (fallback capture)
    VkDeviceMemory mReadbackBufferMemory = VK_NULL_HANDLE;
    void* mMappedReadbackBuffer = nullptr;
    VkDeviceSize mReadbackBufferSize = 0;

    VkBuffer mMasterReadbackBuffer = VK_NULL_HANDLE;     // master RGBA8 + gain-in-alpha readback
    VkDeviceMemory mMasterReadbackMemory = VK_NULL_HANDLE;
    void* mMasterReadbackMapped = nullptr;
    VkDeviceSize mMasterReadbackSize = 0;
    std::vector<uint8_t> mMasterGain;
    int mMasterGainW = 0;
    int mMasterGainH = 0;

    VkDescriptorSetLayout mDescriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool mDescriptorPool = VK_NULL_HANDLE;
    VkPipelineLayout mPipelineLayout = VK_NULL_HANDLE;
    VkPipeline mPipelines[kStageCount] = {};

    VkQueryPool mQueryPool = VK_NULL_HANDLE;
    static constexpr uint32_t kQueriesPerPass = 8;

    VkCommandPool mCommandPool = VK_NULL_HANDLE;
    VkCommandBuffer mCommandBuffer = VK_NULL_HANDLE;
    VkCommandBuffer mReadbackCommandBuffer = VK_NULL_HANDLE;
    VkSemaphore mImageAvailableSemaphore = VK_NULL_HANDLE;
    VkSemaphore mRenderFinishedSemaphore = VK_NULL_HANDLE;
    VkFence mInFlightFence = VK_NULL_HANDLE;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID pfnGetAndroidHardwareBufferPropertiesANDROID = nullptr;

    std::atomic<int> mActiveStockIdx{1};
    std::atomic<int> mActiveLensIdx{0};
    std::atomic<int> mActiveLookIdx{-1};
    std::atomic<bool> mBurstModeEnabled{true};
    std::atomic<float> mUserEvStops{0.0f};
    std::atomic<float> mUserGrainMult{1.0f};
    std::atomic<float> mUserHalationMult{1.0f};

    // Phase 5 Cinema Parameters
    std::atomic<int> mAnamorphicMode{0}; // 0=1.0x, 1=1.33x, 2=2.0x
    std::atomic<int> mCinemaGate{0};     // 0=3:4, 1=16:9, 2=2.39:1
    std::atomic<bool> mGateWeave{false};
    std::atomic<bool> mStepPrint8fps{false};
    std::atomic<int> mFilmFormat{0};      // 0=35mm, 1=16mm, 2=Super 8, 3=6x7
    std::atomic<float> mFilmDigitalGain{1.0f};
    std::atomic<float> mPushStops{0.0f};
    std::atomic<float> mExposureSeconds{0.01f};
    std::atomic<float> mVirtualFNumber{2.0f};
    std::atomic<bool> mLensFxEnabled{false};    // flare/ghost/streak/CA/distortion/swirl (testing)   // aperture ring: lens character / depth of field only

    // Per-frame capture metadata from CaptureResult (Java)
    std::atomic<int> mIso{100};
    std::atomic<float> mFocusDiopters{0.0f};
    std::atomic<float> mNoiseS{0.0f};
    std::atomic<float> mNoiseO{0.0f};
    std::atomic<float> mBlackLevel{0.0f};
    std::atomic<float> mLastRawMean{0.0f};

    // CCT-interpolated ForwardMatrix from CameraCharacteristics (Java), row-major.
    std::mutex mFmMutex;
    float mForwardMatrix[9] = {};
    bool mForwardMatrixValid = false;

    LastFrameState mLastFrame;
    uint32_t mFrameCounter = 0;
    uint32_t mRingSlot = 0;
    int mLastWidth = 0;
    int mLastHeight = 0;
    uint32_t mValidRingFrames = 0;
    bool mLiveQueriesPending = false;
};
