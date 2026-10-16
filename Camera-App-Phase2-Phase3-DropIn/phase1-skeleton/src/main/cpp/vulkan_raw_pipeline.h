#pragma once

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
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

// 112-Byte PushConstant block (strictly <= 128-byte Vulkan min limit, 4-byte aligned fields)
struct PushConstants {
    uint32_t width;              // 0
    uint32_t height;             // 4
    uint32_t stride;             // 8  (in uint16 elements)
    uint32_t cfa;                // 12 (0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR)
    float whiteLevel;            // 16 (1023.0)
    float gainR;                 // 20
    float gainG;                 // 24
    float gainB;                 // 28
    uint32_t sensorOrientation;  // 32 (90 for Back, 270 for Front)
    uint32_t stockType;          // 36 (0=Linear ACEScg, 1=ColorNeg, 2=BWNeg, 3=CinePrint2383, 4=SlideE6)
    uint32_t lensType;           // 40 (0=Clean, 1=Helios Swirl, 2=Leica Glow, 3=Thorium, 4=Anamorphic)
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
    uint32_t camMatrixIdx;       // 104 (0=Wide GN3, 1=Front S5K3LU, 2=UW IMX564, 3=Tele S5K3K1)
    uint32_t burstCount;         // 108 (1=Single, 4=4-Frame Temporal Burst Merge)
};

class VulkanRawPipeline {
public:
    VulkanRawPipeline();
    ~VulkanRawPipeline();

    bool init(ANativeWindow* window);
    void destroy();
    void setFilmLook(int stockIndex, int lensIndex, bool burstEnabled);
    float renderRawFrame(AHardwareBuffer* ahb, int width, int height, int stride,
                         int cfa, float whiteLevel, float gainR, float gainG, float gainB,
                         int sensorOrientation, int camMatrixIdx);
    bool copyLastRenderedRgba(std::vector<uint8_t>& outRgba, int& outW, int& outH);

private:
    bool createInstance();
    bool selectPhysicalDevice();
    bool createDevice();
    bool createSwapchain();
    void destroySwapchain();
    bool createComputeResources();
    void destroyComputeResources();
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);

    std::mutex mRenderMutex;
    ANativeWindow* mWindow = nullptr;
    uint32_t mWindowWidth = 0;
    uint32_t mWindowHeight = 0;

    VkInstance mInstance = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;
    VkQueue mQueue = VK_NULL_HANDLE;
    uint32_t mQueueFamilyIndex = 0;

    VkSurfaceKHR mSurface = VK_NULL_HANDLE;
    VkSwapchainKHR mSwapchain = VK_NULL_HANDLE;
    VkFormat mSwapchainFormat = VK_FORMAT_R8G8B8A8_UNORM;
    VkExtent2D mSwapchainExtent = {0, 0};
    std::vector<VkImage> mSwapchainImages;
    std::vector<VkImageView> mSwapchainImageViews;

    // Intermediate Storage Image
    VkImage mStorageImage = VK_NULL_HANDLE;
    VkDeviceMemory mStorageImageMemory = VK_NULL_HANDLE;
    VkImageView mStorageImageView = VK_NULL_HANDLE;
    VkExtent2D mStorageImageExtent = {0, 0};

    // 4-Slot Temporal Ring Buffer in Host-Visible Coherent Memory (104 MB)
    VkBuffer mRawStorageBuffer = VK_NULL_HANDLE;
    VkDeviceMemory mRawStorageBufferMemory = VK_NULL_HANDLE;
    void* mMappedRawBuffer = nullptr;
    VkDeviceSize mRawStorageBufferSize = 0;

    // Master Frame Readback Buffer (for saving Graded Film JPEG alongside DNG)
    VkBuffer mReadbackBuffer = VK_NULL_HANDLE;
    VkDeviceMemory mReadbackBufferMemory = VK_NULL_HANDLE;
    void* mMappedReadbackBuffer = nullptr;
    VkDeviceSize mReadbackBufferSize = 0;

    // Descriptor & Pipeline
    VkDescriptorSetLayout mDescriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool mDescriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet mDescriptorSet = VK_NULL_HANDLE;
    VkPipelineLayout mPipelineLayout = VK_NULL_HANDLE;
    VkPipeline mComputePipeline = VK_NULL_HANDLE;

    // Command & Sync
    VkCommandPool mCommandPool = VK_NULL_HANDLE;
    VkCommandBuffer mCommandBuffer = VK_NULL_HANDLE;
    VkSemaphore mImageAvailableSemaphore = VK_NULL_HANDLE;
    VkSemaphore mRenderFinishedSemaphore = VK_NULL_HANDLE;
    VkFence mInFlightFence = VK_NULL_HANDLE;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID pfnGetAndroidHardwareBufferPropertiesANDROID = nullptr;
    bool mDirectAhbImportTested = false;

    // Active Look & Temporal Ring Buffer State
    std::atomic<int> mActiveStockIdx{1}; // Default to Kodak Portra 400!
    std::atomic<int> mActiveLensIdx{0};  // Default to Clean Lens
    std::atomic<bool> mBurstModeEnabled{true};
    uint32_t mFrameCounter = 0;
    uint32_t mRingSlot = 0;
    int mLastWidth = 0;
    int mLastHeight = 0;
    uint32_t mValidRingFrames = 0;
};
