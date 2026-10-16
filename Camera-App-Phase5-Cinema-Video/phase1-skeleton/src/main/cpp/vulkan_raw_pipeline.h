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
};

class VulkanRawPipeline {
public:
    VulkanRawPipeline();
    ~VulkanRawPipeline();

    bool init(ANativeWindow* window);
    void destroy();
    void setFilmLook(int stockIndex, int lensIndex, bool burstEnabled);
    void setDarkroomSliders(float evStops, float grainMult, float halationMult);
    void setCinemaParams(int anamorphicMode, int cinemaGate, bool gateWeave, bool stepPrint8fps);
    bool setEncoderWindow(ANativeWindow* encoderWindow);
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
    bool createEncoderSwapchainLocked(ANativeWindow* encoderWindow);
    void destroyEncoderSwapchainLocked();
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

    // Viewfinder Swapchain
    VkSurfaceKHR mSurface = VK_NULL_HANDLE;
    VkSwapchainKHR mSwapchain = VK_NULL_HANDLE;
    VkFormat mSwapchainFormat = VK_FORMAT_R8G8B8A8_UNORM;
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

    VkImage mStorageImage = VK_NULL_HANDLE;
    VkDeviceMemory mStorageImageMemory = VK_NULL_HANDLE;
    VkImageView mStorageImageView = VK_NULL_HANDLE;
    VkExtent2D mStorageImageExtent = {0, 0};

    VkBuffer mRawStorageBuffer = VK_NULL_HANDLE;
    VkDeviceMemory mRawStorageBufferMemory = VK_NULL_HANDLE;
    void* mMappedRawBuffer = nullptr;
    VkDeviceSize mRawStorageBufferSize = 0;

    VkBuffer mReadbackBuffer = VK_NULL_HANDLE;
    VkDeviceMemory mReadbackBufferMemory = VK_NULL_HANDLE;
    void* mMappedReadbackBuffer = nullptr;
    VkDeviceSize mReadbackBufferSize = 0;

    VkDescriptorSetLayout mDescriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool mDescriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet mDescriptorSet = VK_NULL_HANDLE;
    VkPipelineLayout mPipelineLayout = VK_NULL_HANDLE;
    VkPipeline mComputePipeline = VK_NULL_HANDLE;

    VkCommandPool mCommandPool = VK_NULL_HANDLE;
    VkCommandBuffer mCommandBuffer = VK_NULL_HANDLE;
    VkCommandBuffer mReadbackCommandBuffer = VK_NULL_HANDLE;
    VkSemaphore mImageAvailableSemaphore = VK_NULL_HANDLE;
    VkSemaphore mRenderFinishedSemaphore = VK_NULL_HANDLE;
    VkFence mInFlightFence = VK_NULL_HANDLE;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID pfnGetAndroidHardwareBufferPropertiesANDROID = nullptr;

    std::atomic<int> mActiveStockIdx{1};
    std::atomic<int> mActiveLensIdx{0};
    std::atomic<bool> mBurstModeEnabled{true};
    std::atomic<float> mUserEvStops{0.0f};
    std::atomic<float> mUserGrainMult{1.0f};
    std::atomic<float> mUserHalationMult{1.0f};

    // Phase 5 Cinema Parameters
    std::atomic<int> mAnamorphicMode{0}; // 0=1.0x, 1=1.33x, 2=2.0x
    std::atomic<int> mCinemaGate{0};     // 0=3:4, 1=16:9, 2=2.39:1
    std::atomic<bool> mGateWeave{false};
    std::atomic<bool> mStepPrint8fps{false};

    uint32_t mFrameCounter = 0;
    uint32_t mRingSlot = 0;
    int mLastWidth = 0;
    int mLastHeight = 0;
    uint32_t mValidRingFrames = 0;
};
