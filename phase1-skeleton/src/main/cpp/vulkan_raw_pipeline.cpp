#include "vulkan_raw_pipeline.h"
#include "GeneratedFilmDatabase.h"
#include "Master120LooksDatabase.h"
#include "S24HardwareCalibrationMaster.h"
#include "shaders/demosaic_spv.h"
#include "shaders/encode_hlg_spv.h"

#include <cstring>
#include <algorithm>
#include <cmath>
#include <random>
#include <thread>

static VulkanRawPipeline* gPipeline = nullptr;
static bool gPortraitMode = false;   // survives pipeline re-creation

// -----------------------------------------------------------------------------
// 120-look LUT domain. The .bin header carries only magic/version/rows/cols, so the
// column domain is fixed here: column 0..255 spans log10(E / 0.18) in [min, max].
// The loader logs sample rows at startup so the domain can be checked on device.
// -----------------------------------------------------------------------------
static constexpr float kExposureScale = 2.4f;
static constexpr float kDirMultiplier = 1.8f;      // DIR coupler inter-image saturation
static constexpr uint32_t kPrintRowPaper = 94;     // Kodak Portra Endura
static constexpr uint32_t kPrintRowEcn2 = 90;      // Kodak Vision 2383
static constexpr float kPrintContrast = 0.85f;     // print exposure scale (system gamma)
static constexpr float kPrintMidDensity = 0.78f;   // mid grey print density (~16.6% reflectance)
static constexpr float kSlideMidDensity = 1.0f;    // mid grey above D-min on a reversal film
static constexpr float kReciprocityP = 0.85f;      // Schwarzschild exponent beyond 1 s
static constexpr float kLutLogEMin = -3.0f;
static constexpr float kLutLogEMax = 3.0f;
static constexpr const char* kLookLutAsset = "s24_master_120_looks.bin";
static constexpr const char* kSpectralAsset = "spectral_film.bin";

// Physical optics constants
static constexpr float kHalationRingMm = 0.2236f;   // 2 * 125 um base * tan(asin(1/1.5)) (TIR critical angle)

// Film gauges: the same emulsion on a smaller frame is enlarged more, so grain gets bigger and
// coarser relative to the picture, resolution drops and the halation ring grows.
struct FilmFormat {
    float frameLongMm;   // long side of the camera gate
    float grainAmp;      // granularity seen at the same output size, relative to 35mm
    float lpPerMm;       // system resolution (lens + emulsion)
    float gateVignette;  // extra gate / projector falloff
};
static constexpr FilmFormat kFilmFormats[4] = {
    {36.0f, 1.00f, 80.0f, 0.00f},   // 35mm (135)
    {10.26f, 1.45f, 70.0f, 0.15f},  // 16mm
    {5.79f, 1.90f, 60.0f, 0.35f},   // Super 8
    {69.5f, 0.70f, 70.0f, 0.00f},   // 6x7 medium format
};
static constexpr float kGrainClumpMm = 0.0105f;    // visible silver/dye clump scale
static constexpr float kSuper70FocalMm = 80.0f;     // Super 70mm IMAX archetype: 80 mm f/2.0
static constexpr float kSuper70FNumber = 2.0f;
static constexpr float kSuper70GateLongMm = 52.5f;  // 52.5 x 23 mm gate
static constexpr float kHdrSdrRatio = 3.0f;
// Depth bokeh (master): virtual full-frame-equivalent lens, 50 mm.
static constexpr float kDepthLensMm = 50.0f;
static constexpr float kDepthSensorLongMm = 36.0f;
static constexpr float kDepthMaxCocFrac = 0.02f;    // |CoC| cap, fraction of the long edge
static constexpr int kDepthBands = 4;               // BOKEH dispatches per master (live preview runs between them)

// UI lens index -> kLegendaryLenses8 archetype (-1 = none).
// UI: 0 Clean, 1 Helios 44-2, 2 Summilux, 3 Takumar, 4 Panavision 2x, 5 Super 70mm IMAX
static constexpr int kUiLensToArchetype[6] = {-1, 0, 1, 4, 7, -1};

// Lens character per UI lens (0 Clean, 1 Helios 44-2, 2 Summilux 35 v1, 3 Super-Takumar 50/1.4,
// 4 Panavision C anamorphic, 5 Super 70). Strengths are for wide open; the aperture ring scales them.
struct LensFx {
    float maxAperture;
    float ghosts, halo, streak, streakLen;
    float streakTint[3];
    float veil, ca, distortionK1, swirl, starburst;
    float blades, squeeze;
    float coating[3];
};
static constexpr LensFx kLensFx[6] = {
    // fmax  ghost halo streak len   streak tint          veil    CA      k1      swirl star  blades sq   coating
    {1.8f, 0.10f, 0.02f, 0.0f, 0.0f, {0.0f, 0.0f, 0.0f}, 0.002f, 0.0f,   0.0f,   0.0f, 0.6f, 9.0f, 1.0f, {0.6f, 1.0f, 0.7f}},   // Clean
    {2.0f, 0.55f, 0.15f, 0.0f, 0.0f, {0.0f, 0.0f, 0.0f}, 0.014f, 0.0012f, 0.0f,   0.9f, 0.8f, 8.0f, 1.0f, {1.0f, 0.55f, 0.9f}},  // Helios 44-2
    {1.4f, 0.25f, 0.30f, 0.0f, 0.0f, {0.0f, 0.0f, 0.0f}, 0.010f, 0.0008f, -0.02f, 0.2f, 0.7f, 10.0f, 1.0f, {1.0f, 0.85f, 0.5f}}, // Summilux 35 v1
    {1.4f, 0.35f, 0.08f, 0.0f, 0.0f, {0.0f, 0.0f, 0.0f}, 0.008f, 0.0006f, 0.0f,   0.25f, 0.8f, 8.0f, 1.0f, {0.55f, 1.0f, 0.6f}}, // Super-Takumar
    {2.8f, 0.30f, 0.05f, 0.9f, 0.45f, {0.25f, 0.55f, 1.0f}, 0.010f, 0.0015f, -0.05f, 0.0f, 0.5f, 8.0f, 2.0f, {0.4f, 0.6f, 1.0f}}, // Panavision C 2x
    {2.0f, 0.10f, 0.03f, 0.0f, 0.0f, {0.0f, 0.0f, 0.0f}, 0.003f, 0.0003f, 0.0f,   0.0f, 0.6f, 9.0f, 1.0f, {0.6f, 0.9f, 1.0f}},  // Super 70
};
static constexpr float kFlareThreshold = 1.6f;   // scene-linear (about 3 stops over mid grey)

static uint16_t floatToHalf(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = static_cast<int32_t>((x >> 23) & 0xFFu) - 127 + 15;
    uint32_t mant = x & 0x7FFFFFu;
    if (((x >> 23) & 0xFFu) == 0xFFu) return static_cast<uint16_t>(sign | 0x7C00u | (mant ? 0x200u : 0u));
    if (exp <= 0) {
        if (exp < -10) return static_cast<uint16_t>(sign);
        mant |= 0x800000u;
        uint32_t shift = static_cast<uint32_t>(14 - exp);
        uint32_t h = mant >> shift;
        if ((mant >> (shift - 1)) & 1u) h++;
        return static_cast<uint16_t>(sign | h);
    }
    if (exp >= 31) return static_cast<uint16_t>(sign | 0x7C00u);
    uint32_t h = sign | (static_cast<uint32_t>(exp) << 10) | (mant >> 13);
    if (mant & 0x1000u) h++;
    return static_cast<uint16_t>(h);
}

static float halfToFloat(uint16_t h) {
    uint32_t sign = (h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1Fu;
    uint32_t mant = h & 0x3FFu;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) {
            bits = sign;
        } else {
            exp = 127 - 15 + 1;
            while ((mant & 0x400u) == 0) { mant <<= 1; exp--; }
            mant &= 0x3FFu;
            bits = sign | (exp << 23) | (mant << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7F800000u | (mant << 13);
    } else {
        bits = sign | ((exp - 15 + 127) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}

static inline uint32_t mipDim(uint32_t base, uint32_t level) {
    return std::max(1u, base >> level);
}

VulkanRawPipeline::VulkanRawPipeline() = default;

VulkanRawPipeline::~VulkanRawPipeline() {
    destroy();
}

void VulkanRawPipeline::setFilmLook(int stockIndex, int lensIndex, bool burstEnabled) {
    mActiveStockIdx.store(std::clamp(stockIndex, 0, s24film::kNumFeaturedStocks - 1));
    mActiveLensIdx.store(std::clamp(lensIndex, 0, kSuper70LensIndex));
    mBurstModeEnabled.store(burstEnabled);
}

void VulkanRawPipeline::setMasterLook(int lookIndex) {
    if (lookIndex < 0 || lookIndex >= static_cast<int>(s24film::kTotalMasterLooks)) {
        mActiveLookIdx.store(-1);
    } else {
        mActiveLookIdx.store(lookIndex);
    }
    requestSpectralTables(mActiveLookIdx.load());
}

void VulkanRawPipeline::requestSpectralTables(int lookIndex) {
    if (mSpectral.stockCount() == 0) return;
    if (mSpectralWanted.exchange(lookIndex) == lookIndex) return;
    if (mSpectralThread.joinable()) mSpectralThread.join();
    mSpectralThread = std::thread([this, lookIndex]() {
        auto t0 = std::chrono::steady_clock::now();
        std::vector<float> tables;
        bool ok = mSpectral.build(lookIndex, tables);   // header look -1: no datasheet, curve model
        std::lock_guard<std::mutex> lock(mSpectralMutex);
        if (mSpectralWanted.load() != lookIndex) return;
        mSpectralPending.swap(tables);
        mSpectralPendingReady = true;
        LOGI("Spectral tables for look %d: %s in %.1f ms", lookIndex, ok ? "built" : "none (curve model)",
             std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    });
}

// Caller holds mRenderMutex and has waited for the in-flight fence.
void VulkanRawPipeline::uploadPendingSpectral() {
    std::lock_guard<std::mutex> lock(mSpectralMutex);
    if (!mSpectralPendingReady || !mSpectralMapped) return;
    std::memcpy(mSpectralMapped, mSpectralPending.data(),
                std::min(mSpectralBytes, mSpectralPending.size() * sizeof(float)));
    mSpectralPendingReady = false;
}

void VulkanRawPipeline::setDarkroomSliders(float evStops, float grainMult, float halationMult) {
    mUserEvStops.store(std::clamp(evStops, -2.5f, 2.5f));
    mUserGrainMult.store(std::clamp(grainMult, 0.0f, 2.5f));
    mUserHalationMult.store(std::clamp(halationMult, 0.0f, 2.5f));
}

void VulkanRawPipeline::setCinemaParams(int anamorphicMode, int cinemaGate, bool gateWeave, bool stepPrint8fps) {
    mAnamorphicMode.store(std::clamp(anamorphicMode, 0, 2));
    mCinemaGate.store(std::clamp(cinemaGate, 0, 2));
    mGateWeave.store(gateWeave);
    mStepPrint8fps.store(stepPrint8fps);
}

void VulkanRawPipeline::setCaptureState(int iso, int64_t exposureNs, float focusDiopters,
                                        float noiseS, float noiseO, float blackLevel) {
    if (iso > 0) mIso.store(iso);
    if (exposureNs > 0) mExposureSeconds.store(static_cast<float>(static_cast<double>(exposureNs) * 1e-9));
    mFocusDiopters.store(std::max(focusDiopters, 0.0f));
    mNoiseS.store(noiseS);
    mNoiseO.store(noiseO);
    if (blackLevel >= 0.0f) mBlackLevel.store(blackLevel);
}

void VulkanRawPipeline::setForwardMatrix(const float* fm9) {
    std::lock_guard<std::mutex> lock(mFmMutex);
    if (fm9 == nullptr) {
        mForwardMatrixValid = false;
        return;
    }
    std::memcpy(mForwardMatrix, fm9, sizeof(mForwardMatrix));
    mForwardMatrixValid = true;
}

uint32_t VulkanRawPipeline::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(mPhysicalDevice, &memProperties);

    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1u << i)) &&
            (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return UINT32_MAX;
}

bool VulkanRawPipeline::createInstance() {
    VkApplicationInfo appInfo = {};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "S24Film";
    appInfo.applicationVersion = VK_MAKE_VERSION(6, 0, 0);
    appInfo.pEngineName = "S24VulkanISP";
    appInfo.engineVersion = VK_MAKE_VERSION(6, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_3;

    std::vector<const char*> extensions = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME
    };

    // VK_EXT_swapchain_colorspace lets the encoder swapchain carry the BT.2020 HLG dataspace.
    uint32_t instExtCount = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &instExtCount, nullptr);
    std::vector<VkExtensionProperties> instExts(instExtCount);
    vkEnumerateInstanceExtensionProperties(nullptr, &instExtCount, instExts.data());
    mHasSwapchainColorspaceExt = false;
    for (const auto& ext : instExts) {
        if (strcmp(ext.extensionName, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME) == 0) {
            mHasSwapchainColorspaceExt = true;
        }
    }
    if (mHasSwapchainColorspaceExt) extensions.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);

    VkInstanceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();

    return vkCreateInstance(&createInfo, nullptr, &mInstance) == VK_SUCCESS;
}

bool VulkanRawPipeline::selectPhysicalDevice() {
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, nullptr);
    if (deviceCount == 0) return false;

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, devices.data());

    bool found = false;
    for (const auto& dev : devices) {
        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &queueFamilyCount, nullptr);
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &queueFamilyCount, queueFamilies.data());

        for (uint32_t i = 0; i < queueFamilyCount; i++) {
            if ((queueFamilies[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) &&
                (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                mPhysicalDevice = dev;
                mQueueFamilyIndex = i;
                mTimestampValidBits = queueFamilies[i].timestampValidBits;
                found = true;
                break;
            }
        }
        if (found) break;
    }
    if (!found) {
        mPhysicalDevice = devices[0];
        mQueueFamilyIndex = 0;
        mTimestampValidBits = 0;
    }

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(mPhysicalDevice, &props);
    mTimestampPeriodNs = props.limits.timestampPeriod;
    if (!props.limits.timestampComputeAndGraphics) mTimestampValidBits = 0;
    LOGI("GPU: %s | driver %u | API %u.%u.%u | timestamp period %.4f ns",
         props.deviceName, props.driverVersion,
         VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion),
         mTimestampPeriodNs);
    return true;
}

bool VulkanRawPipeline::createDevice() {
    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo = {};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = mQueueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    std::vector<const char*> deviceExtensions = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME
    };

    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(mPhysicalDevice, nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> availableExts(extCount);
    vkEnumerateDeviceExtensionProperties(mPhysicalDevice, nullptr, &extCount, availableExts.data());

    bool hasSubgroupControl = false;
    for (const auto& ext : availableExts) {
        if (strcmp(ext.extensionName, VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME) == 0) {
            hasSubgroupControl = true;
        }
    }
    if (hasSubgroupControl) {
        deviceExtensions.push_back(VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME);
    }

    VkPhysicalDeviceVulkan13Features vulkan13Features = {};
    vulkan13Features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
    vulkan13Features.synchronization2 = VK_TRUE;
    if (hasSubgroupControl) {
        vulkan13Features.subgroupSizeControl = VK_TRUE;
        vulkan13Features.computeFullSubgroups = VK_TRUE;
    }

    VkDeviceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.pNext = &vulkan13Features;
    createInfo.queueCreateInfoCount = 1;
    createInfo.pQueueCreateInfos = &queueCreateInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
    createInfo.ppEnabledExtensionNames = deviceExtensions.data();

    VkResult res = vkCreateDevice(mPhysicalDevice, &createInfo, nullptr, &mDevice);
    if (res != VK_SUCCESS) {
        createInfo.pNext = nullptr;
        res = vkCreateDevice(mPhysicalDevice, &createInfo, nullptr, &mDevice);
        if (res != VK_SUCCESS) return false;
    }

    vkGetDeviceQueue(mDevice, mQueueFamilyIndex, 0, &mQueue);
    pfnGetAndroidHardwareBufferPropertiesANDROID =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(
            mDevice, "vkGetAndroidHardwareBufferPropertiesANDROID");
    return true;
}

bool VulkanRawPipeline::formatSupports(VkFormat format, VkFormatFeatureFlags features) {
    VkFormatProperties props;
    vkGetPhysicalDeviceFormatProperties(mPhysicalDevice, format, &props);
    return (props.optimalTilingFeatures & features) == features;
}

bool VulkanRawPipeline::createSwapchain() {
    VkAndroidSurfaceCreateInfoKHR surfaceCreateInfo = {};
    surfaceCreateInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceCreateInfo.window = mWindow;

    if (vkCreateAndroidSurfaceKHR(mInstance, &surfaceCreateInfo, nullptr, &mSurface) != VK_SUCCESS) {
        return false;
    }

    VkSurfaceCapabilitiesKHR capabilities;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(mPhysicalDevice, mSurface, &capabilities);

    uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, mSurface, &formatCount, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, mSurface, &formatCount, formats.data());

    // Prefer the 10-bit A2B10G10R10 framebuffer (Suite 6: format_id 64, SRGB_NONLINEAR),
    // fall back to 8-bit RGBA / BGRA. Every candidate must accept a blit from the
    // RGBA16F viewfinder image.
    const bool canTransferDst = (capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0;
    bool chosen = false;
    if (canTransferDst && formatSupports(VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
        for (const auto& fmt : formats) {
            if (fmt.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 &&
                fmt.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                mSwapchainFormat = fmt.format;
                mSwapchainColorSpace = fmt.colorSpace;
                chosen = true;
                break;
            }
        }
    }
    if (!chosen) {
        mSwapchainFormat = VK_FORMAT_R8G8B8A8_UNORM;
        mSwapchainColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        for (const auto& fmt : formats) {
            if (fmt.format == VK_FORMAT_R8G8B8A8_UNORM || fmt.format == VK_FORMAT_B8G8R8A8_UNORM) {
                mSwapchainFormat = fmt.format;
                mSwapchainColorSpace = fmt.colorSpace;
                break;
            }
        }
    }
    LOGI("Viewfinder swapchain format %d (%s)", static_cast<int>(mSwapchainFormat),
         mSwapchainFormat == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ? "10-bit A2B10G10R10" : "8-bit fallback");

    mSwapchainExtent = capabilities.currentExtent;
    if (mSwapchainExtent.width == 0xFFFFFFFF) {
        mSwapchainExtent.width = std::clamp(static_cast<uint32_t>(ANativeWindow_getWidth(mWindow)),
                                            capabilities.minImageExtent.width,
                                            capabilities.maxImageExtent.width);
        mSwapchainExtent.height = std::clamp(static_cast<uint32_t>(ANativeWindow_getHeight(mWindow)),
                                             capabilities.minImageExtent.height,
                                             capabilities.maxImageExtent.height);
    }

    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0 && imageCount > capabilities.maxImageCount) {
        imageCount = capabilities.maxImageCount;
    }

    VkSwapchainCreateInfoKHR swapchainCreateInfo = {};
    swapchainCreateInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swapchainCreateInfo.surface = mSurface;
    swapchainCreateInfo.minImageCount = imageCount;
    swapchainCreateInfo.imageFormat = mSwapchainFormat;
    swapchainCreateInfo.imageColorSpace = mSwapchainColorSpace;
    swapchainCreateInfo.imageExtent = mSwapchainExtent;
    swapchainCreateInfo.imageArrayLayers = 1;
    swapchainCreateInfo.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    swapchainCreateInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swapchainCreateInfo.preTransform = capabilities.currentTransform;
    swapchainCreateInfo.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    swapchainCreateInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    swapchainCreateInfo.clipped = VK_TRUE;

    VkResult scRes = vkCreateSwapchainKHR(mDevice, &swapchainCreateInfo, nullptr, &mSwapchain);
    if (scRes != VK_SUCCESS && mSwapchainFormat == VK_FORMAT_A2B10G10R10_UNORM_PACK32) {
        LOGW("10-bit swapchain creation failed (%d), falling back to R8G8B8A8_UNORM", static_cast<int>(scRes));
        mSwapchainFormat = VK_FORMAT_R8G8B8A8_UNORM;
        mSwapchainColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        swapchainCreateInfo.imageFormat = mSwapchainFormat;
        swapchainCreateInfo.imageColorSpace = mSwapchainColorSpace;
        scRes = vkCreateSwapchainKHR(mDevice, &swapchainCreateInfo, nullptr, &mSwapchain);
    }
    if (scRes != VK_SUCCESS) {
        return false;
    }

    uint32_t actualImageCount = 0;
    vkGetSwapchainImagesKHR(mDevice, mSwapchain, &actualImageCount, nullptr);
    mSwapchainImages.resize(actualImageCount);
    vkGetSwapchainImagesKHR(mDevice, mSwapchain, &actualImageCount, mSwapchainImages.data());

    mSwapchainImageViews.resize(actualImageCount);
    for (uint32_t i = 0; i < actualImageCount; i++) {
        VkImageViewCreateInfo viewInfo = {};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = mSwapchainImages[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = mSwapchainFormat;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        vkCreateImageView(mDevice, &viewInfo, nullptr, &mSwapchainImageViews[i]);
    }
    return true;
}

void VulkanRawPipeline::destroySwapchain() {
    for (auto iv : mSwapchainImageViews) {
        if (iv != VK_NULL_HANDLE) vkDestroyImageView(mDevice, iv, nullptr);
    }
    mSwapchainImageViews.clear();
    mSwapchainImages.clear();

    if (mSwapchain != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(mDevice, mSwapchain, nullptr);
        mSwapchain = VK_NULL_HANDLE;
    }
    if (mSurface != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(mInstance, mSurface, nullptr);
        mSurface = VK_NULL_HANDLE;
    }
}

bool VulkanRawPipeline::setEncoderWindow(ANativeWindow* encoderWindow) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (!mDevice) return false;
    vkDeviceWaitIdle(mDevice);
    destroyEncoderSwapchainLocked();
    if (!encoderWindow) {
        LOGI("Phase 5 Encoder Surface detached cleanly");
        return true;
    }
    return createEncoderSwapchainLocked(encoderWindow);
}

bool VulkanRawPipeline::createEncoderSwapchainLocked(ANativeWindow* encoderWindow) {
    mEncoderWindow = encoderWindow;

    VkAndroidSurfaceCreateInfoKHR surfaceCreateInfo = {};
    surfaceCreateInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceCreateInfo.window = mEncoderWindow;

    if (vkCreateAndroidSurfaceKHR(mInstance, &surfaceCreateInfo, nullptr, &mEncoderSurface) != VK_SUCCESS) {
        LOGE("Failed to create Vulkan surface for MediaCodec encoder!");
        mEncoderWindow = nullptr;
        return false;
    }

    VkSurfaceCapabilitiesKHR capabilities;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(mPhysicalDevice, mEncoderSurface, &capabilities);

    uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, mEncoderSurface, &formatCount, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, mEncoderSurface, &formatCount, formats.data());

    // HLG: prefer a 10-bit surface tagged BT.2020 HLG, then 10-bit untagged, then 8-bit (the
    // pass still writes HLG values so the stream matches the HLG flags MediaCodec was given).
    mEncoderHlg = mEncoderHlgRequested.load();
    mEncoderFormat = VK_FORMAT_R8G8B8A8_UNORM;
    mEncoderColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    bool picked = false;
    if (mEncoderHlg && formatSupports(VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
        const VkColorSpaceKHR order[3] = {VK_COLOR_SPACE_HDR10_HLG_EXT, VK_COLOR_SPACE_PASS_THROUGH_EXT,
                                          VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
        for (int k = 0; k < 3 && !picked; ++k) {
            if (k < 2 && !mHasSwapchainColorspaceExt) continue;
            for (const auto& fmt : formats) {
                if (fmt.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 && fmt.colorSpace == order[k]) {
                    mEncoderFormat = fmt.format;
                    mEncoderColorSpace = fmt.colorSpace;
                    picked = true;
                    break;
                }
            }
        }
    }
    if (!picked) {
        for (const auto& fmt : formats) {
            if ((fmt.format == VK_FORMAT_R8G8B8A8_UNORM || fmt.format == VK_FORMAT_B8G8R8A8_UNORM) &&
                fmt.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                mEncoderFormat = fmt.format;
                break;
            }
        }
    }

    mEncoderExtent = capabilities.currentExtent;
    if (mEncoderExtent.width == 0xFFFFFFFF || mEncoderExtent.width == 0) {
        mEncoderExtent.width = static_cast<uint32_t>(ANativeWindow_getWidth(mEncoderWindow));
        mEncoderExtent.height = static_cast<uint32_t>(ANativeWindow_getHeight(mEncoderWindow));
    }

    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0 && imageCount > capabilities.maxImageCount) {
        imageCount = capabilities.maxImageCount;
    }

    VkSwapchainCreateInfoKHR swapchainCreateInfo = {};
    swapchainCreateInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swapchainCreateInfo.surface = mEncoderSurface;
    swapchainCreateInfo.minImageCount = imageCount;
    swapchainCreateInfo.imageFormat = mEncoderFormat;
    swapchainCreateInfo.imageColorSpace = mEncoderColorSpace;
    swapchainCreateInfo.imageExtent = mEncoderExtent;
    swapchainCreateInfo.imageArrayLayers = 1;
    swapchainCreateInfo.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    swapchainCreateInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swapchainCreateInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    swapchainCreateInfo.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    swapchainCreateInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    swapchainCreateInfo.clipped = VK_TRUE;

    VkResult encScRes = vkCreateSwapchainKHR(mDevice, &swapchainCreateInfo, nullptr, &mEncoderSwapchain);
    if (encScRes != VK_SUCCESS && mEncoderFormat == VK_FORMAT_A2B10G10R10_UNORM_PACK32) {
        LOGW("10-bit encoder swapchain failed (%d), retrying 8-bit", static_cast<int>(encScRes));
        mEncoderFormat = VK_FORMAT_R8G8B8A8_UNORM;
        mEncoderColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        swapchainCreateInfo.imageFormat = mEncoderFormat;
        swapchainCreateInfo.imageColorSpace = mEncoderColorSpace;
        encScRes = vkCreateSwapchainKHR(mDevice, &swapchainCreateInfo, nullptr, &mEncoderSwapchain);
    }
    if (encScRes != VK_SUCCESS) {
        LOGE("vkCreateSwapchainKHR for MediaCodec encoder failed!");
        destroyEncoderSwapchainLocked();
        return false;
    }

    if (mEncoderHlg) {
        const VkImageUsageFlags usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        if (!createEncodePipelineLocked() ||
            !createImage(mEncoderStaging, VK_FORMAT_R16G16B16A16_SFLOAT,
                         mEncoderExtent.width, mEncoderExtent.height, 1, usage) ||
            !transitionToGeneral({mEncoderStaging.image})) {
            LOGE("HLG encoder pass unavailable");
            destroyEncoderSwapchainLocked();
            return false;
        }
    }

    uint32_t actualImageCount = 0;
    vkGetSwapchainImagesKHR(mDevice, mEncoderSwapchain, &actualImageCount, nullptr);
    mEncoderImages.resize(actualImageCount);
    vkGetSwapchainImagesKHR(mDevice, mEncoderSwapchain, &actualImageCount, mEncoderImages.data());

    VkSemaphoreCreateInfo semInfo = {};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    vkCreateSemaphore(mDevice, &semInfo, nullptr, &mEncoderImageAvailableSemaphore);
    vkCreateSemaphore(mDevice, &semInfo, nullptr, &mEncoderRenderFinishedSemaphore);

    const bool tenBit = (mEncoderFormat == VK_FORMAT_A2B10G10R10_UNORM_PACK32);
    mEncoderInfo = std::string(tenBit ? "10-bit " : "8-bit ") + (mEncoderHlg ? "HLG" : "SDR");
    if (mEncoderHlg && mEncoderColorSpace != VK_COLOR_SPACE_HDR10_HLG_EXT) mEncoderInfo += " (untagged surface)";
    LOGI("Phase 5 Zero-Copy MediaCodec Vulkan Swapchain active (%ux%u, %u images, %s, colorspace %d)",
         mEncoderExtent.width, mEncoderExtent.height, actualImageCount, mEncoderInfo.c_str(),
         static_cast<int>(mEncoderColorSpace));
    return true;
}

std::string VulkanRawPipeline::encoderInfo() {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    return (mEncoderSwapchain != VK_NULL_HANDLE) ? mEncoderInfo : std::string("off");
}

bool VulkanRawPipeline::createEncodePipelineLocked() {
    if (mEncodePipeline != VK_NULL_HANDLE) return true;

    VkDescriptorSetLayoutBinding b[2] = {};
    for (uint32_t i = 0; i < 2; ++i) {
        b[i].binding = i;
        b[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        b[i].descriptorCount = 1;
        b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 2;
    layoutInfo.pBindings = b;
    if (vkCreateDescriptorSetLayout(mDevice, &layoutInfo, nullptr, &mEncodeSetLayout) != VK_SUCCESS) return false;

    VkDescriptorPoolSize poolSize = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2};
    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(mDevice, &poolInfo, nullptr, &mEncodePool) != VK_SUCCESS) return false;

    VkDescriptorSetAllocateInfo setAlloc = {};
    setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAlloc.descriptorPool = mEncodePool;
    setAlloc.descriptorSetCount = 1;
    setAlloc.pSetLayouts = &mEncodeSetLayout;
    if (vkAllocateDescriptorSets(mDevice, &setAlloc, &mEncodeSet) != VK_SUCCESS) return false;

    VkPushConstantRange pcr = {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(EncodePush)};
    VkPipelineLayoutCreateInfo pl = {};
    pl.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &mEncodeSetLayout;
    pl.pushConstantRangeCount = 1;
    pl.pPushConstantRanges = &pcr;
    if (vkCreatePipelineLayout(mDevice, &pl, nullptr, &mEncodePipelineLayout) != VK_SUCCESS) return false;

    VkShaderModuleCreateInfo smInfo = {};
    smInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smInfo.codeSize = encode_hlg_spv_size;
    smInfo.pCode = encode_hlg_spv;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(mDevice, &smInfo, nullptr, &module) != VK_SUCCESS) return false;

    VkComputePipelineCreateInfo info = {};
    info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    info.stage.module = module;
    info.stage.pName = "main";
    info.layout = mEncodePipelineLayout;
    VkResult res = vkCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &info, nullptr, &mEncodePipeline);
    vkDestroyShaderModule(mDevice, module, nullptr);
    if (res != VK_SUCCESS) {
        LOGE("HLG encode pipeline failed (%d)", static_cast<int>(res));
        mEncodePipeline = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

void VulkanRawPipeline::destroyEncodePipeline() {
    if (mEncodePipeline != VK_NULL_HANDLE) { vkDestroyPipeline(mDevice, mEncodePipeline, nullptr); mEncodePipeline = VK_NULL_HANDLE; }
    if (mEncodePipelineLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(mDevice, mEncodePipelineLayout, nullptr); mEncodePipelineLayout = VK_NULL_HANDLE; }
    if (mEncodePool != VK_NULL_HANDLE) { vkDestroyDescriptorPool(mDevice, mEncodePool, nullptr); mEncodePool = VK_NULL_HANDLE; }
    if (mEncodeSetLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(mDevice, mEncodeSetLayout, nullptr); mEncodeSetLayout = VK_NULL_HANDLE; }
    mEncodeSet = VK_NULL_HANDLE;
}

void VulkanRawPipeline::destroyEncoderSwapchainLocked() {
    destroyImage(mEncoderStaging);
    mEncoderHlg = false;
    mEncoderInfo = "off";
    mEncoderImages.clear();
    if (mEncoderRenderFinishedSemaphore != VK_NULL_HANDLE) {
        vkDestroySemaphore(mDevice, mEncoderRenderFinishedSemaphore, nullptr);
        mEncoderRenderFinishedSemaphore = VK_NULL_HANDLE;
    }
    if (mEncoderImageAvailableSemaphore != VK_NULL_HANDLE) {
        vkDestroySemaphore(mDevice, mEncoderImageAvailableSemaphore, nullptr);
        mEncoderImageAvailableSemaphore = VK_NULL_HANDLE;
    }
    if (mEncoderSwapchain != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(mDevice, mEncoderSwapchain, nullptr);
        mEncoderSwapchain = VK_NULL_HANDLE;
    }
    if (mEncoderSurface != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(mInstance, mEncoderSurface, nullptr);
        mEncoderSurface = VK_NULL_HANDLE;
    }
    if (mEncoderWindow != nullptr) {
        ANativeWindow_release(mEncoderWindow);
        mEncoderWindow = nullptr;
    }
}

// =============================================================================
// Resource helpers
// =============================================================================
bool VulkanRawPipeline::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags props,
                                     VkBuffer& buffer, VkDeviceMemory& memory, void** mapped) {
    VkBufferCreateInfo info = {};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(mDevice, &info, nullptr, &buffer) != VK_SUCCESS) return false;

    VkMemoryRequirements reqs;
    vkGetBufferMemoryRequirements(mDevice, buffer, &reqs);
    uint32_t typeIndex = findMemoryType(reqs.memoryTypeBits, props);
    if (typeIndex == UINT32_MAX) {
        vkDestroyBuffer(mDevice, buffer, nullptr);
        buffer = VK_NULL_HANDLE;
        return false;
    }

    VkMemoryAllocateInfo alloc = {};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = reqs.size;
    alloc.memoryTypeIndex = typeIndex;
    if (vkAllocateMemory(mDevice, &alloc, nullptr, &memory) != VK_SUCCESS) {
        vkDestroyBuffer(mDevice, buffer, nullptr);
        buffer = VK_NULL_HANDLE;
        return false;
    }
    vkBindBufferMemory(mDevice, buffer, memory, 0);
    if (mapped) {
        if (vkMapMemory(mDevice, memory, 0, size, 0, mapped) != VK_SUCCESS) {
            *mapped = nullptr;
            return false;
        }
    }
    return true;
}

void VulkanRawPipeline::destroyBuffer(VkBuffer& buffer, VkDeviceMemory& memory, void** mapped) {
    if (mapped && *mapped) { vkUnmapMemory(mDevice, memory); *mapped = nullptr; }
    if (buffer != VK_NULL_HANDLE) { vkDestroyBuffer(mDevice, buffer, nullptr); buffer = VK_NULL_HANDLE; }
    if (memory != VK_NULL_HANDLE) { vkFreeMemory(mDevice, memory, nullptr); memory = VK_NULL_HANDLE; }
}

bool VulkanRawPipeline::createImage(GpuImage& img, VkFormat format, uint32_t w, uint32_t h, uint32_t mips,
                                    VkImageUsageFlags usage) {
    img.format = format;
    img.extent = {w, h};
    img.mipLevels = mips;

    VkImageCreateInfo imageInfo = {};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = {w, h, 1};
    imageInfo.mipLevels = mips;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = usage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(mDevice, &imageInfo, nullptr, &img.image) != VK_SUCCESS) return false;

    VkMemoryRequirements reqs;
    vkGetImageMemoryRequirements(mDevice, img.image, &reqs);
    uint32_t typeIndex = findMemoryType(reqs.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (typeIndex == UINT32_MAX) typeIndex = findMemoryType(reqs.memoryTypeBits, 0);
    if (typeIndex == UINT32_MAX) return false;

    VkMemoryAllocateInfo alloc = {};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = reqs.size;
    alloc.memoryTypeIndex = typeIndex;
    if (vkAllocateMemory(mDevice, &alloc, nullptr, &img.memory) != VK_SUCCESS) return false;
    vkBindImageMemory(mDevice, img.image, img.memory, 0);

    VkImageViewCreateInfo viewInfo = {};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = img.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = mips;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(mDevice, &viewInfo, nullptr, &img.sampledView) != VK_SUCCESS) return false;

    if (usage & VK_IMAGE_USAGE_STORAGE_BIT) {
        img.levelViews.resize(mips, VK_NULL_HANDLE);
        for (uint32_t m = 0; m < mips; ++m) {
            viewInfo.subresourceRange.baseMipLevel = m;
            viewInfo.subresourceRange.levelCount = 1;
            if (vkCreateImageView(mDevice, &viewInfo, nullptr, &img.levelViews[m]) != VK_SUCCESS) return false;
        }
    }
    return true;
}

void VulkanRawPipeline::destroyImage(GpuImage& img) {
    for (auto v : img.levelViews) {
        if (v != VK_NULL_HANDLE) vkDestroyImageView(mDevice, v, nullptr);
    }
    img.levelViews.clear();
    if (img.sampledView != VK_NULL_HANDLE) { vkDestroyImageView(mDevice, img.sampledView, nullptr); img.sampledView = VK_NULL_HANDLE; }
    if (img.image != VK_NULL_HANDLE) { vkDestroyImage(mDevice, img.image, nullptr); img.image = VK_NULL_HANDLE; }
    if (img.memory != VK_NULL_HANDLE) { vkFreeMemory(mDevice, img.memory, nullptr); img.memory = VK_NULL_HANDLE; }
    img.extent = {0, 0};
}

// Uploads tightly packed level-0 data and leaves the image in SHADER_READ_ONLY_OPTIMAL.
bool VulkanRawPipeline::uploadImage(GpuImage& img, const void* data, VkDeviceSize bytes) {
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMem = VK_NULL_HANDLE;
    void* mapped = nullptr;
    if (!createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      staging, stagingMem, &mapped)) {
        return false;
    }
    std::memcpy(mapped, data, static_cast<size_t>(bytes));

    VkCommandBufferAllocateInfo cmdAlloc = {};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = mCommandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    vkAllocateCommandBuffers(mDevice, &cmdAlloc, &cmd);

    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);

    VkImageMemoryBarrier toDst = {};
    toDst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toDst.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toDst.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toDst.image = img.image;
    toDst.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, img.mipLevels, 0, 1};
    toDst.srcAccessMask = 0;
    toDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &toDst);

    VkBufferImageCopy region = {};
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = {img.extent.width, img.extent.height, 1};
    vkCmdCopyBufferToImage(cmd, staging, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    VkImageMemoryBarrier toRead = toDst;
    toRead.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toRead.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    toRead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toRead.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &toRead);
    vkEndCommandBuffer(cmd);

    VkSubmitInfo submit = {};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    vkQueueSubmit(mQueue, 1, &submit, VK_NULL_HANDLE);
    vkQueueWaitIdle(mQueue);

    vkFreeCommandBuffers(mDevice, mCommandPool, 1, &cmd);
    destroyBuffer(staging, stagingMem, &mapped);
    return true;
}

bool VulkanRawPipeline::transitionToGeneral(const std::vector<VkImage>& images) {
    VkCommandBufferAllocateInfo cmdAlloc = {};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = mCommandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(mDevice, &cmdAlloc, &cmd) != VK_SUCCESS) return false;

    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);

    std::vector<VkImageMemoryBarrier> barriers;
    for (VkImage image : images) {
        VkImageMemoryBarrier b = {};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, VK_REMAINING_MIP_LEVELS, 0, 1};
        b.srcAccessMask = 0;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        barriers.push_back(b);
    }
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, static_cast<uint32_t>(barriers.size()), barriers.data());
    vkEndCommandBuffer(cmd);

    VkSubmitInfo submit = {};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    vkQueueSubmit(mQueue, 1, &submit, VK_NULL_HANDLE);
    vkQueueWaitIdle(mQueue);
    vkFreeCommandBuffers(mDevice, mCommandPool, 1, &cmd);
    return true;
}

// =============================================================================
// Descriptor layout + 5 specialized compute pipelines from one SPIR-V module
// =============================================================================
bool VulkanRawPipeline::createDescriptorLayoutAndPipelines() {
    struct BindingDesc { uint32_t binding; VkDescriptorType type; };
    const BindingDesc descs[] = {
        {0,  VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},          // raw ring
        {1,  VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},          // clean bayer
        {2,  VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},          // LSC grids
        {3,  VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},  // 120-look LUT
        {4,  VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},  // Gabor grain tile
        {5,  VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},          // look params
        {6,  VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},          // frame params
        {7,  VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},           // ACES mip 0
        {8,  VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},  // ACES pyramid
        {9,  VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},           // pyramid mip 1
        {10, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {11, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {13, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},           // pyramid mip 5
        {14, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},           // bokeh out
        {15, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},  // bokeh sampled
        {16, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},           // master RGBA8
        {17, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},           // live RGBA16F
        {18, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},          // spectral film tables
        {20, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},          // depth grid + near-side blur reach
        {21, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},           // signed CoC map
        {22, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},  // signed CoC map sampled
        {23, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},          // depth bokeh params
    };
    constexpr uint32_t kBindingCount = sizeof(descs) / sizeof(descs[0]);

    VkDescriptorSetLayoutBinding bindings[kBindingCount] = {};
    uint32_t nStorageBuf = 0, nUniform = 0, nSampler = 0, nStorageImg = 0;
    for (uint32_t i = 0; i < kBindingCount; ++i) {
        bindings[i].binding = descs[i].binding;
        bindings[i].descriptorType = descs[i].type;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        switch (descs[i].type) {
            case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER: nStorageBuf++; break;
            case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER: nUniform++; break;
            case VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER: nSampler++; break;
            default: nStorageImg++; break;
        }
    }

    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = kBindingCount;
    layoutInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(mDevice, &layoutInfo, nullptr, &mDescriptorSetLayout) != VK_SUCCESS) return false;

    constexpr uint32_t kSets = 2;   // live + master
    VkDescriptorPoolSize poolSizes[4] = {
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nStorageBuf * kSets},
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, nUniform * kSets},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nSampler * kSets},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, nStorageImg * kSets},
    };
    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = kSets;
    poolInfo.poolSizeCount = 4;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(mDevice, &poolInfo, nullptr, &mDescriptorPool) != VK_SUCCESS) return false;

    VkDescriptorSetLayout layouts[kSets] = {mDescriptorSetLayout, mDescriptorSetLayout};
    VkDescriptorSet sets[kSets] = {};
    VkDescriptorSetAllocateInfo setAlloc = {};
    setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAlloc.descriptorPool = mDescriptorPool;
    setAlloc.descriptorSetCount = kSets;
    setAlloc.pSetLayouts = layouts;
    if (vkAllocateDescriptorSets(mDevice, &setAlloc, sets) != VK_SUCCESS) return false;
    mLiveTargets.descriptorSet = sets[0];
    mMasterTargets.descriptorSet = sets[1];

    VkPushConstantRange pcr = {};
    pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcr.offset = 0;
    pcr.size = sizeof(PushConstants);

    VkPipelineLayoutCreateInfo pipeLayoutInfo = {};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &mDescriptorSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &pcr;
    if (vkCreatePipelineLayout(mDevice, &pipeLayoutInfo, nullptr, &mPipelineLayout) != VK_SUCCESS) return false;

    VkShaderModuleCreateInfo smInfo = {};
    smInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smInfo.codeSize = demosaic_spv_size;
    smInfo.pCode = demosaic_spv;

    VkShaderModule shaderModule = VK_NULL_HANDLE;
    if (vkCreateShaderModule(mDevice, &smInfo, nullptr, &shaderModule) != VK_SUCCESS) return false;

    for (uint32_t stage = 0; stage < kStageCount; ++stage) {
        uint32_t stageValue = stage;
        VkSpecializationMapEntry entry = {0, 0, sizeof(uint32_t)};
        VkSpecializationInfo spec = {};
        spec.mapEntryCount = 1;
        spec.pMapEntries = &entry;
        spec.dataSize = sizeof(uint32_t);
        spec.pData = &stageValue;

        VkPipelineShaderStageRequiredSubgroupSizeCreateInfo reqSubgroup = {};
        reqSubgroup.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO;
        reqSubgroup.requiredSubgroupSize = 32;

        VkComputePipelineCreateInfo info = {};
        info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        info.stage.module = shaderModule;
        info.stage.pName = "main";
        info.stage.pNext = &reqSubgroup;
        info.stage.flags = VK_PIPELINE_SHADER_STAGE_CREATE_REQUIRE_FULL_SUBGROUPS_BIT;
        info.stage.pSpecializationInfo = &spec;
        info.layout = mPipelineLayout;

        VkResult res = vkCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &info, nullptr, &mPipelines[stage]);
        if (res != VK_SUCCESS) {
            info.stage.pNext = nullptr;
            info.stage.flags = 0;
            res = vkCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &info, nullptr, &mPipelines[stage]);
            if (res != VK_SUCCESS) {
                LOGE("Failed to create compute pipeline for stage %u (%d)", stage, static_cast<int>(res));
                vkDestroyShaderModule(mDevice, shaderModule, nullptr);
                return false;
            }
        }
    }
    vkDestroyShaderModule(mDevice, shaderModule, nullptr);
    return true;
}

// =============================================================================
// 120-look 2D LUT (assets/s24_master_120_looks.bin) + look parameter buffer
// =============================================================================
bool VulkanRawPipeline::loadLookLut() {
    const uint32_t lookCount = s24film::kTotalMasterLooks;
    mLookParams.assign(lookCount, LookGpu{});

    std::vector<float> payload;
    uint32_t rows = 0;
    uint32_t cols = 0;

    if (mAssetManager) {
        AAsset* asset = AAssetManager_open(mAssetManager, kLookLutAsset, AASSET_MODE_BUFFER);
        if (asset) {
            const uint8_t* data = static_cast<const uint8_t*>(AAsset_getBuffer(asset));
            size_t len = static_cast<size_t>(AAsset_getLength(asset));
            if (data && len >= 16 && std::memcmp(data, "S24M", 4) == 0) {
                uint32_t version = 0;
                std::memcpy(&version, data + 4, 4);
                std::memcpy(&rows, data + 8, 4);
                std::memcpy(&cols, data + 12, 4);
                if (rows == 256 && cols == 120) {
                    LOGW("LUT header has rows/cols swapped (%u x %u); treating as 120 rows x 256 cols", rows, cols);
                    std::swap(rows, cols);
                }
                size_t expected = 16 + static_cast<size_t>(rows) * cols * 4 * sizeof(float);
                if (version == 2 && rows == lookCount && cols >= 2 && len >= expected) {
                    payload.resize(static_cast<size_t>(rows) * cols * 4);
                    std::memcpy(payload.data(), data + 16, payload.size() * sizeof(float));
                    LOGI("Loaded %s: version %u, %u rows x %u cols (%zu bytes)", kLookLutAsset, version, rows, cols, len);
                } else {
                    LOGE("%s header mismatch: version %u rows %u cols %u len %zu (expected %zu)",
                         kLookLutAsset, version, rows, cols, len, expected);
                }
            } else {
                LOGE("%s missing 'S24M' magic or too short (%zu bytes)", kLookLutAsset, len);
            }
            AAsset_close(asset);
        } else {
            LOGE("Asset %s not found in APK", kLookLutAsset);
        }
    } else {
        LOGE("No AssetManager passed to nativeInit; 120-look LUT disabled");
    }

    mLutReady = !payload.empty();
    if (!mLutReady) {
        rows = 1;
        cols = 1;
        payload.assign(4, 0.0f);
    }

    const float midCol = (0.0f - kLutLogEMin) / (kLutLogEMax - kLutLogEMin) * static_cast<float>(cols - 1);
    auto sampleRow = [&](uint32_t row, float col, uint32_t ch) -> float {
        float c = std::clamp(col, 0.0f, static_cast<float>(cols - 1));
        uint32_t c0 = static_cast<uint32_t>(c);
        uint32_t c1 = std::min(c0 + 1, cols - 1);
        float f = c - static_cast<float>(c0);
        const float* rp = payload.data() + static_cast<size_t>(row) * cols * 4;
        return rp[c0 * 4 + ch] * (1.0f - f) + rp[c1 * 4 + ch] * f;
    };

    for (uint32_t i = 0; i < lookCount; ++i) {
        const auto& lp = s24film::kMasterLooks120[i];
        LookGpu& g = mLookParams[i];
        uint32_t row = std::min<uint32_t>(lp.lutRowIndex, rows - 1);
        int category = static_cast<int>(lp.category);

        float rowMax = 0.0f;
        bool increasing = true;
        if (mLutReady) {
            const float* rp = payload.data() + static_cast<size_t>(row) * cols * 4;
            for (uint32_t c = 0; c < cols; ++c) {
                rowMax = std::max(rowMax, std::max(rp[c * 4 + 0], std::max(rp[c * 4 + 1], rp[c * 4 + 2])));
            }
            uint32_t lo = cols / 8;
            uint32_t hi = cols - 1 - cols / 8;
            increasing = rp[hi * 4 + 1] > rp[lo * 4 + 1];
        }
        bool normalized = rowMax <= 1.05f;

        float mode = increasing ? 0.0f : 1.0f;
        if (category == static_cast<int>(s24film::LookCategory::DIGITAL_CINEMA_CAMERA) && normalized && increasing) {
            mode = 2.0f;   // display-encoded log/print rendition, bypasses density inversion
        }

        float dirBoost = 0.0f;
        switch (lp.category) {
            case s24film::LookCategory::COLOR_NEGATIVE_C41:    dirBoost = 0.12f; break;
            case s24film::LookCategory::MOTION_PICTURE_ECN2:   dirBoost = 0.10f; break;
            case s24film::LookCategory::COLOR_REVERSAL_SLIDE:  dirBoost = 0.06f; break;
            case s24film::LookCategory::FUJIFILM_SIMULATION:   dirBoost = 0.08f; break;
            case s24film::LookCategory::CREATIVE_INSTANT_EXPIRED: dirBoost = 0.10f; break;
            default:                                            dirBoost = 0.0f; break;
        }

        for (int c = 0; c < 3; ++c) {
            g.dMax[c] = lp.dMax[c];
            g.gamma[c] = lp.gamma[c];
            float mid = mLutReady ? sampleRow(row, midCol, static_cast<uint32_t>(c)) : 0.0f;
            g.dMid[c] = normalized ? mid * lp.dMax[c] : mid;
            g.m0[c] = lp.spectralMatrix[0 + c];
            g.m1[c] = lp.spectralMatrix[3 + c];
            g.m2[c] = lp.spectralMatrix[6 + c];
        }
        g.dMax[3] = 0.0f; g.gamma[3] = 0.0f; g.dMid[3] = 0.0f;
        g.m0[3] = 0.0f; g.m1[3] = 0.0f; g.m2[3] = 0.0f;
        g.misc[0] = static_cast<float>(category);
        g.misc[1] = mode;
        g.misc[2] = dirBoost;
        g.misc[3] = static_cast<float>(row);
        g.misc2[0] = normalized ? 1.0f : 0.0f;
        g.misc2[1] = lp.halationStrength;
        g.misc2[2] = lp.grainStrength;
        g.misc2[3] = 0.0f;

        // Slides: the LUT puts scene mid grey almost at D-min (a washed-out, over-exposed
        // transparency). Shift exposure so mid grey lands at D-min + 1.0 on the green layer,
        // on the straight line of the curve, as a correctly exposed slide does.
        if (mLutReady && mode == 1.0f) {
            const float scale = normalized ? lp.dMax[1] : 1.0f;
            const float target = sampleRow(row, static_cast<float>(cols - 1), 1) * scale + kSlideMidDensity;
            for (uint32_t k = 1; k < cols; ++k) {
                float d0 = sampleRow(row, static_cast<float>(k - 1), 1) * scale;
                float d1 = sampleRow(row, static_cast<float>(k), 1) * scale;
                if (d0 >= target && d1 <= target) {
                    float f = (d0 > d1) ? (d0 - target) / (d0 - d1) : 0.0f;
                    float col = static_cast<float>(k - 1) + f;
                    g.misc2[3] = kLutLogEMin + col / static_cast<float>(cols - 1) * (kLutLogEMax - kLutLogEMin);
                    for (int c = 0; c < 3; ++c) {
                        float d = sampleRow(row, col, static_cast<uint32_t>(c));
                        g.dMid[c] = normalized ? d * lp.dMax[c] : d;
                    }
                    break;
                }
            }
        }

        // Print stage for colour negatives: the negative is printed onto paper (C-41, Fujifilm
        // sims) or 2383 release print (ECN-2), which gives the S-shaped film tone scale with a
        // soft shoulder. Printer lights put mid grey at the same print density on every layer.
        g.print0[3] = -1.0f;
        const bool printable = mLutReady && mode == 0.0f &&
            (lp.category == s24film::LookCategory::COLOR_NEGATIVE_C41 ||
             lp.category == s24film::LookCategory::MOTION_PICTURE_ECN2 ||
             lp.category == s24film::LookCategory::FUJIFILM_SIMULATION);
        if (printable) {
            const uint32_t prow = std::min<uint32_t>(
                (lp.category == s24film::LookCategory::MOTION_PICTURE_ECN2) ? kPrintRowEcn2 : kPrintRowPaper, rows - 1);
            auto colOf = [&](float logE) {
                return (logE - kLutLogEMin) / (kLutLogEMax - kLutLogEMin) * static_cast<float>(cols - 1);
            };
            float x0[3], dminP[3], blk[3];
            for (int c = 0; c < 3; ++c) {
                dminP[c] = sampleRow(prow, 0.0f, static_cast<uint32_t>(c));
                const float target = kPrintMidDensity + dminP[c];
                float found = 0.0f;
                for (uint32_t k = 1; k < cols; ++k) {
                    float d0 = sampleRow(prow, static_cast<float>(k - 1), static_cast<uint32_t>(c));
                    float d1 = sampleRow(prow, static_cast<float>(k), static_cast<uint32_t>(c));
                    if (d0 <= target && d1 >= target) {
                        float f = (d1 > d0) ? (target - d0) / (d1 - d0) : 0.0f;
                        found = kLutLogEMin + (static_cast<float>(k - 1) + f) / static_cast<float>(cols - 1) * (kLutLogEMax - kLutLogEMin);
                        break;
                    }
                }
                x0[c] = found;
            }
            // Render an unexposed frame through the same chain to find the black to subtract.
            const float logEBlack = std::log10(1e-6f / 0.18f);
            float Dn[3];
            for (int c = 0; c < 3; ++c) {
                Dn[c] = sampleRow(row, colOf(logEBlack), static_cast<uint32_t>(c)) * (normalized ? lp.dMax[c] : 1.0f);
            }
            const float s = 1.0f + dirBoost * kDirMultiplier;
            const float off = (1.0f - s) * 0.5f;
            for (int c = 0; c < 3; ++c) {
                float dd = 0.0f;
                for (int j = 0; j < 3; ++j) dd += ((j == c) ? s : off) * (Dn[j] - g.dMid[j]);
                float logEp = -dd / std::max(g.gamma[c], 0.2f) * 0.5f * kPrintContrast + x0[c];
                float Dp = sampleRow(prow, colOf(logEp), static_cast<uint32_t>(c));
                blk[c] = std::pow(10.0f, -(Dp - dminP[c]));
            }
            const float midLin = std::pow(10.0f, -kPrintMidDensity);
            float greyMean = 0.0f;
            for (int c = 0; c < 3; ++c) greyMean += (midLin - blk[c]) / 3.0f;
            for (int c = 0; c < 3; ++c) {
                g.print0[c] = x0[c];
                g.print1[c] = dminP[c];
                g.print2[c] = blk[c];
            }
            g.print0[3] = static_cast<float>(prow);
            g.print1[3] = kPrintContrast;
            g.print2[3] = midLin / std::max(greyMean, 1e-4f);
        }
    }

    if (mLutReady) {
        const uint32_t probeLooks[] = {0, 24, 40, 50, 76, 90, 97, 110};
        for (uint32_t li : probeLooks) {
            if (li >= lookCount) continue;
            const LookGpu& g = mLookParams[li];
            uint32_t row = static_cast<uint32_t>(g.misc[3]);
            LOGI("LUT probe look %u (%s) row %u: mode %.0f norm %.0f | G@col0 %.4f G@mid %.4f G@last %.4f | dMid %.3f %.3f %.3f",
                 li, s24film::kMasterLooks120[li].lookId, row, g.misc[1], g.misc2[0],
                 sampleRow(row, 0.0f, 1), sampleRow(row, midCol, 1), sampleRow(row, static_cast<float>(cols - 1), 1),
                 g.dMid[0], g.dMid[1], g.dMid[2]);
        }
    }

    // Upload as RGBA32F with hardware LINEAR filtering when the format supports it,
    // otherwise as RGBA16F (linear filtering is mandatory there).
    VkImageUsageFlags usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    bool use32 = formatSupports(VK_FORMAT_R32G32B32A32_SFLOAT,
                                VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
    if (use32) {
        if (!createImage(mLookLutImage, VK_FORMAT_R32G32B32A32_SFLOAT, cols, rows, 1, usage)) return false;
        if (!uploadImage(mLookLutImage, payload.data(), payload.size() * sizeof(float))) return false;
    } else {
        LOGW("RGBA32F linear filtering unsupported; uploading 120-look LUT as RGBA16F");
        std::vector<uint16_t> half(payload.size());
        for (size_t i = 0; i < payload.size(); ++i) half[i] = floatToHalf(payload[i]);
        if (!createImage(mLookLutImage, VK_FORMAT_R16G16B16A16_SFLOAT, cols, rows, 1, usage)) return false;
        if (!uploadImage(mLookLutImage, half.data(), half.size() * sizeof(uint16_t))) return false;
    }

    std::memcpy(mLookMapped, mLookParams.data(), mLookParams.size() * sizeof(LookGpu));
    return true;
}

// =============================================================================
// 512x512 tileable 2D Gabor grain texture for the live viewfinder
// =============================================================================
bool VulkanRawPipeline::createGrainTile() {
    // 512x512 tileable grain: Gaussian white noise blurred by a ~0.65 px Gaussian (silver
    // clumps of 1-2 px), 4 independent unit-variance channels. The FILM stage mixes R
    // (shared, luminance grain) with G/B/A (per-dye-layer grain).
    constexpr int N = 512;
    constexpr int R = 2;
    constexpr float sigma = 0.65f;

    float kernel[2 * R + 1];
    float ksum = 0.0f;
    for (int i = -R; i <= R; ++i) {
        kernel[i + R] = std::exp(-0.5f * static_cast<float>(i * i) / (sigma * sigma));
        ksum += kernel[i + R];
    }
    for (float& k : kernel) k /= ksum;

    std::mt19937 rng(0x5324F11u);
    std::normal_distribution<float> gauss(0.0f, 1.0f);
    std::vector<uint16_t> texels(static_cast<size_t>(N) * N * 4);
    std::vector<float> a(static_cast<size_t>(N) * N);
    std::vector<float> b(static_cast<size_t>(N) * N);

    for (int ch = 0; ch < 4; ++ch) {
        for (float& v : a) v = gauss(rng);
        for (int y = 0; y < N; ++y) {          // horizontal pass (wrap)
            for (int x = 0; x < N; ++x) {
                float acc = 0.0f;
                for (int i = -R; i <= R; ++i) acc += kernel[i + R] * a[static_cast<size_t>(y) * N + ((x + i + N) % N)];
                b[static_cast<size_t>(y) * N + x] = acc;
            }
        }
        for (int y = 0; y < N; ++y) {          // vertical pass (wrap)
            for (int x = 0; x < N; ++x) {
                float acc = 0.0f;
                for (int i = -R; i <= R; ++i) acc += kernel[i + R] * b[static_cast<size_t>((y + i + N) % N) * N + x];
                a[static_cast<size_t>(y) * N + x] = acc;
            }
        }
        double sum = 0.0, sum2 = 0.0;
        for (float v : a) { sum += v; sum2 += static_cast<double>(v) * v; }
        double mean = sum / (static_cast<double>(N) * N);
        double sd = std::sqrt(std::max(sum2 / (static_cast<double>(N) * N) - mean * mean, 1e-12));
        for (size_t p = 0; p < static_cast<size_t>(N) * N; ++p) {
            texels[p * 4 + ch] = floatToHalf(static_cast<float>((a[p] - mean) / sd));
        }
    }

    if (!createImage(mGrainTileImage, VK_FORMAT_R16G16B16A16_SFLOAT, N, N, 1,
                     VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)) return false;
    return uploadImage(mGrainTileImage, texels.data(), texels.size() * sizeof(uint16_t));
}

bool VulkanRawPipeline::createSharedResources() {
    const VkMemoryPropertyFlags hostFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

    // RAW ring, 26 MB per slot (one 4080 x 3060 x 2 byte frame). Zero-lag merges want 10 slots
    // (260 MB); fall back to fewer if the driver will not give that much host-visible memory.
    mRingSlotBytes = 26ull * 1024ull * 1024ull;
    // The shader binds the whole ring as one storage buffer, so it must also fit the range limit.
    VkPhysicalDeviceProperties devProps = {};
    vkGetPhysicalDeviceProperties(mPhysicalDevice, &devProps);
    const VkDeviceSize maxRange = devProps.limits.maxStorageBufferRange;
    mRawStorageBuffer = VK_NULL_HANDLE;
    for (uint32_t slots : {kMaxRingSlots, 6u, 4u}) {
        mRawStorageBufferSize = mRingSlotBytes * slots;
        if (mRawStorageBufferSize > maxRange && slots > 4u) {
            LOGW("RAW ring of %u slots exceeds maxStorageBufferRange %llu", slots, (unsigned long long) maxRange);
            continue;
        }
        if (createBuffer(mRawStorageBufferSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags,
                         mRawStorageBuffer, mRawStorageBufferMemory, &mMappedRawBuffer)) {
            mRingSlots = slots;
            break;
        }
        destroyBuffer(mRawStorageBuffer, mRawStorageBufferMemory, &mMappedRawBuffer);
        LOGW("RAW ring of %u slots not available, trying fewer", slots);
    }
    if (mRawStorageBuffer == VK_NULL_HANDLE) return false;
    LOGI("RAW ring: %u slots (%.0f MB)", mRingSlots, mRawStorageBufferSize / 1048576.0);

    // Clean Bayer plane (float) written by PREP, read by ISP.
    mCleanBayerSize = 4096ull * 4096ull * sizeof(float);
    if (!createBuffer(mCleanBayerSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                      mCleanBayerBuffer, mCleanBayerMemory, nullptr)) {
        if (!createBuffer(mCleanBayerSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0,
                          mCleanBayerBuffer, mCleanBayerMemory, nullptr)) return false;
    }

    // 17x13x4 LSC grids for Cam 0 / 1 / 2 / 6.
    const VkDeviceSize lscBytes = sizeof(s24film::kS24LensShadingGrids);
    if (!createBuffer(lscBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags,
                      mLscBuffer, mLscMemory, &mLscMapped)) return false;
    std::memcpy(mLscMapped, s24film::kS24LensShadingGrids, static_cast<size_t>(lscBytes));
    LOGI("Uploaded %u LSC grids (%ux%ux4, %zu bytes)", 4u, s24film::kLscGridCols, s24film::kLscGridRows,
         static_cast<size_t>(lscBytes));

    const VkDeviceSize lookBytes = sizeof(LookGpu) * s24film::kTotalMasterLooks;
    if (!createBuffer(lookBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags,
                      mLookBuffer, mLookMemory, &mLookMapped)) return false;

    if (mAssetManager && mSpectral.stockCount() == 0) {
        AAsset* asset = AAssetManager_open(mAssetManager, kSpectralAsset, AASSET_MODE_BUFFER);
        if (asset) {
            const uint8_t* data = static_cast<const uint8_t*>(AAsset_getBuffer(asset));
            bool ok = mSpectral.load(data, static_cast<size_t>(AAsset_getLength(asset)));
            LOGI("%s: %s, %d stocks", kSpectralAsset, ok ? "loaded" : "invalid", mSpectral.stockCount());
            AAsset_close(asset);
        } else {
            LOGE("Asset %s not found; spectral film model disabled", kSpectralAsset);
        }
    }
    mSpectralBytes = std::max<size_t>(mSpectral.bufferFloats(), 16) * sizeof(float);
    if (!createBuffer(mSpectralBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags,
                      mSpectralBuffer, mSpectralMemory, &mSpectralMapped)) return false;
    static_cast<float*>(mSpectralMapped)[0] = -1.0f;   // no look until the first build lands
    mSpectralWanted.store(-2);
    requestSpectralTables(mActiveLookIdx.load());

    // Depth bokeh input: disparity grid + dilated near-side blur reach, written by masterFinish.
    {
        void* mapped = nullptr;
        if (!createBuffer(3ull * kDepthMaxCells * sizeof(float), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags,
                          mDepthBuffer, mDepthMemory, &mapped)) return false;
        mDepthMapped = static_cast<float*>(mapped);
        std::memset(mDepthMapped, 0, 3ull * kDepthMaxCells * sizeof(float));
    }

    VkSamplerCreateInfo sampler = {};
    sampler.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sampler.magFilter = VK_FILTER_LINEAR;
    sampler.minFilter = VK_FILTER_LINEAR;
    sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampler.minLod = 0.0f;
    sampler.maxLod = 0.0f;
    if (vkCreateSampler(mDevice, &sampler, nullptr, &mLutSampler) != VK_SUCCESS) return false;

    sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    sampler.maxLod = static_cast<float>(kPyramidLevels);
    if (vkCreateSampler(mDevice, &sampler, nullptr, &mLinearClampSampler) != VK_SUCCESS) return false;

    sampler.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    sampler.addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler.maxLod = 0.0f;
    if (vkCreateSampler(mDevice, &sampler, nullptr, &mRepeatSampler) != VK_SUCCESS) return false;

    if (!loadLookLut()) return false;
    if (!createGrainTile()) return false;

    if (mTimestampValidBits > 0) {
        VkQueryPoolCreateInfo qp = {};
        qp.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        qp.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qp.queryCount = kQueriesPerPass * 2;
        if (vkCreateQueryPool(mDevice, &qp, nullptr, &mQueryPool) != VK_SUCCESS) {
            mQueryPool = VK_NULL_HANDLE;
        }
    }
    return true;
}

// =============================================================================
// Frame targets (live viewfinder / offline master)
// =============================================================================
bool VulkanRawPipeline::createTargets(FrameTargets& t, VkExtent2D extent, bool master) {
    t.master = master;
    t.extent = extent;
    t.bokehExtent = master ? extent : VkExtent2D{std::max(1u, extent.width / 2), std::max(1u, extent.height / 2)};

    const VkImageUsageFlags rw = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    if (!createImage(t.linearAces, VK_FORMAT_R16G16B16A16_SFLOAT, extent.width, extent.height,
                     kPyramidLevels + 1, rw)) return false;
    if (!createImage(t.bokeh, VK_FORMAT_R16G16B16A16_SFLOAT, t.bokehExtent.width, t.bokehExtent.height, 1, rw)) return false;

    VkExtent2D rgba8Extent = master ? extent : VkExtent2D{1, 1};
    VkExtent2D rgba16Extent = master ? VkExtent2D{1, 1} : extent;
    if (!createImage(t.storageRgba8, VK_FORMAT_R8G8B8A8_UNORM, rgba8Extent.width, rgba8Extent.height, 1, rw)) return false;
    if (!createImage(t.storageRgba16, VK_FORMAT_R16G16B16A16_SFLOAT, rgba16Extent.width, rgba16Extent.height, 1, rw)) return false;

    VkExtent2D cocExtent = master ? extent : VkExtent2D{1, 1};
    if (!createImage(t.cocMap, VK_FORMAT_R32G32_SFLOAT, cocExtent.width, cocExtent.height, 1, rw)) return false;
    if (!createBuffer(sizeof(DepthParamsUbo), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      t.depthUbo, t.depthUboMemory, &t.depthUboMapped)) return false;
    std::memset(t.depthUboMapped, 0, sizeof(DepthParamsUbo));

    if (!createBuffer(sizeof(FrameParamsUbo), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      t.ubo, t.uboMemory, &t.uboMapped)) return false;
    std::memset(t.uboMapped, 0, sizeof(FrameParamsUbo));

    if (!transitionToGeneral({t.linearAces.image, t.bokeh.image, t.storageRgba8.image, t.storageRgba16.image, t.cocMap.image})) return false;
    writeTargetDescriptors(t);

    LOGI("%s targets: %ux%u (bokeh %ux%u, %u-level pyramid)", master ? "Master" : "Live",
         extent.width, extent.height, t.bokehExtent.width, t.bokehExtent.height, kPyramidLevels);
    return true;
}

void VulkanRawPipeline::destroyTargets(FrameTargets& t) {
    destroyImage(t.linearAces);
    destroyImage(t.bokeh);
    destroyImage(t.storageRgba8);
    destroyImage(t.storageRgba16);
    destroyImage(t.cocMap);
    destroyBuffer(t.depthUbo, t.depthUboMemory, &t.depthUboMapped);
    destroyBuffer(t.ubo, t.uboMemory, &t.uboMapped);
    t.extent = {0, 0};
    t.bokehExtent = {0, 0};
}

void VulkanRawPipeline::writeTargetDescriptors(FrameTargets& t) {
    VkDescriptorBufferInfo rawInfo = {mRawStorageBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo cleanInfo = {mCleanBayerBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo lscInfo = {mLscBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo lookInfo = {mLookBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo spectralInfo = {mSpectralBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo uboInfo = {t.ubo, 0, sizeof(FrameParamsUbo)};

    VkDescriptorImageInfo lutInfo = {mLutSampler, mLookLutImage.sampledView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo grainInfo = {mRepeatSampler, mGrainTileImage.sampledView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo acesBase = {VK_NULL_HANDLE, t.linearAces.levelViews[0], VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo acesPyr = {mLinearClampSampler, t.linearAces.sampledView, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo pyr[kPyramidLevels];
    for (uint32_t i = 0; i < kPyramidLevels; ++i) {
        pyr[i] = {VK_NULL_HANDLE, t.linearAces.levelViews[i + 1], VK_IMAGE_LAYOUT_GENERAL};
    }
    VkDescriptorImageInfo bokehOut = {VK_NULL_HANDLE, t.bokeh.levelViews[0], VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo bokehTex = {mLinearClampSampler, t.bokeh.sampledView, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo out8 = {VK_NULL_HANDLE, t.storageRgba8.levelViews[0], VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo out16 = {VK_NULL_HANDLE, t.storageRgba16.levelViews[0], VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo depthInfo = {mDepthBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorImageInfo cocOut = {VK_NULL_HANDLE, t.cocMap.levelViews[0], VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo cocTex = {mLinearClampSampler, t.cocMap.sampledView, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo depthUboInfo = {t.depthUbo, 0, sizeof(DepthParamsUbo)};

    std::vector<VkWriteDescriptorSet> writes;
    auto addBuf = [&](uint32_t binding, VkDescriptorType type, const VkDescriptorBufferInfo* info) {
        VkWriteDescriptorSet w = {};
        w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w.dstSet = t.descriptorSet;
        w.dstBinding = binding;
        w.descriptorCount = 1;
        w.descriptorType = type;
        w.pBufferInfo = info;
        writes.push_back(w);
    };
    auto addImg = [&](uint32_t binding, VkDescriptorType type, const VkDescriptorImageInfo* info) {
        VkWriteDescriptorSet w = {};
        w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w.dstSet = t.descriptorSet;
        w.dstBinding = binding;
        w.descriptorCount = 1;
        w.descriptorType = type;
        w.pImageInfo = info;
        writes.push_back(w);
    };

    addBuf(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &rawInfo);
    addBuf(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &cleanInfo);
    addBuf(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &lscInfo);
    addImg(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &lutInfo);
    addImg(4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &grainInfo);
    addBuf(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &lookInfo);
    addBuf(18, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &spectralInfo);
    addBuf(6, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &uboInfo);
    addImg(7, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &acesBase);
    addImg(8, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &acesPyr);
    for (uint32_t i = 0; i < kPyramidLevels; ++i) {
        addImg(9 + i, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &pyr[i]);
    }
    addImg(14, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &bokehOut);
    addImg(15, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &bokehTex);
    addImg(16, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &out8);
    addImg(17, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &out16);
    addBuf(20, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, &depthInfo);
    addImg(21, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &cocOut);
    addImg(22, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &cocTex);
    addBuf(23, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, &depthUboInfo);

    vkUpdateDescriptorSets(mDevice, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
}

bool VulkanRawPipeline::createComputeResources() {
    VkCommandPoolCreateInfo cmdPoolInfo = {};
    cmdPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cmdPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cmdPoolInfo.queueFamilyIndex = mQueueFamilyIndex;
    if (vkCreateCommandPool(mDevice, &cmdPoolInfo, nullptr, &mCommandPool) != VK_SUCCESS) return false;

    VkCommandBuffer bufs[2] = {};
    VkCommandBufferAllocateInfo cmdAlloc = {};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = mCommandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 2;
    vkAllocateCommandBuffers(mDevice, &cmdAlloc, bufs);
    mCommandBuffer = bufs[0];
    mReadbackCommandBuffer = bufs[1];

    if (!createSharedResources()) { LOGE("createSharedResources failed"); return false; }
    if (!createDescriptorLayoutAndPipelines()) { LOGE("createDescriptorLayoutAndPipelines failed"); return false; }
    if (!createTargets(mLiveTargets, mSwapchainExtent, false)) { LOGE("live targets failed"); return false; }

    // Live RGBA16F readback for the fallback capture path.
    mReadbackBufferSize = static_cast<VkDeviceSize>(mLiveTargets.extent.width) * mLiveTargets.extent.height * 8;
    if (!createBuffer(mReadbackBufferSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      mReadbackBuffer, mReadbackBufferMemory, &mMappedReadbackBuffer)) {
        mMappedReadbackBuffer = nullptr;
    }

    VkSemaphoreCreateInfo semInfo = {};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    vkCreateSemaphore(mDevice, &semInfo, nullptr, &mImageAvailableSemaphore);
    vkCreateSemaphore(mDevice, &semInfo, nullptr, &mRenderFinishedSemaphore);

    VkFenceCreateInfo fenceInfo = {};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    vkCreateFence(mDevice, &fenceInfo, nullptr, &mInFlightFence);

    LOGI("Sprint 1 multi-pass Wave32 engine initialized (120-look LUT %s)", mLutReady ? "ready" : "UNAVAILABLE");
    return true;
}

void VulkanRawPipeline::destroyComputeResources() {
    destroyEncoderSwapchainLocked();
    destroyEncodePipeline();
    if (mInFlightFence != VK_NULL_HANDLE) { vkDestroyFence(mDevice, mInFlightFence, nullptr); mInFlightFence = VK_NULL_HANDLE; }
    if (mRenderFinishedSemaphore != VK_NULL_HANDLE) { vkDestroySemaphore(mDevice, mRenderFinishedSemaphore, nullptr); mRenderFinishedSemaphore = VK_NULL_HANDLE; }
    if (mImageAvailableSemaphore != VK_NULL_HANDLE) { vkDestroySemaphore(mDevice, mImageAvailableSemaphore, nullptr); mImageAvailableSemaphore = VK_NULL_HANDLE; }

    destroyTargets(mLiveTargets);
    destroyTargets(mMasterTargets);
    destroyBuffer(mReadbackBuffer, mReadbackBufferMemory, &mMappedReadbackBuffer);
    destroyBuffer(mMasterReadbackBuffer, mMasterReadbackMemory, &mMasterReadbackMapped);

    if (mQueryPool != VK_NULL_HANDLE) { vkDestroyQueryPool(mDevice, mQueryPool, nullptr); mQueryPool = VK_NULL_HANDLE; }
    if (mCommandPool != VK_NULL_HANDLE) { vkDestroyCommandPool(mDevice, mCommandPool, nullptr); mCommandPool = VK_NULL_HANDLE; }
    for (auto& p : mPipelines) {
        if (p != VK_NULL_HANDLE) { vkDestroyPipeline(mDevice, p, nullptr); p = VK_NULL_HANDLE; }
    }
    if (mPipelineLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(mDevice, mPipelineLayout, nullptr); mPipelineLayout = VK_NULL_HANDLE; }
    if (mDescriptorPool != VK_NULL_HANDLE) { vkDestroyDescriptorPool(mDevice, mDescriptorPool, nullptr); mDescriptorPool = VK_NULL_HANDLE; }
    if (mDescriptorSetLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(mDevice, mDescriptorSetLayout, nullptr); mDescriptorSetLayout = VK_NULL_HANDLE; }
    mLiveTargets.descriptorSet = VK_NULL_HANDLE;
    mMasterTargets.descriptorSet = VK_NULL_HANDLE;

    if (mLutSampler != VK_NULL_HANDLE) { vkDestroySampler(mDevice, mLutSampler, nullptr); mLutSampler = VK_NULL_HANDLE; }
    if (mLinearClampSampler != VK_NULL_HANDLE) { vkDestroySampler(mDevice, mLinearClampSampler, nullptr); mLinearClampSampler = VK_NULL_HANDLE; }
    if (mRepeatSampler != VK_NULL_HANDLE) { vkDestroySampler(mDevice, mRepeatSampler, nullptr); mRepeatSampler = VK_NULL_HANDLE; }
    destroyImage(mLookLutImage);
    destroyImage(mGrainTileImage);

    {
        void* mapped = mDepthMapped;
        destroyBuffer(mDepthBuffer, mDepthMemory, &mapped);
        mDepthMapped = nullptr;
    }
    destroyBuffer(mLookBuffer, mLookMemory, &mLookMapped);
    destroyBuffer(mSpectralBuffer, mSpectralMemory, &mSpectralMapped);
    destroyBuffer(mLscBuffer, mLscMemory, &mLscMapped);
    destroyBuffer(mCleanBayerBuffer, mCleanBayerMemory, nullptr);
    destroyBuffer(mRawStorageBuffer, mRawStorageBufferMemory, &mMappedRawBuffer);
}

bool VulkanRawPipeline::init(ANativeWindow* window, AAssetManager* assetManager) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    mWindow = window;
    mAssetManager = assetManager;
    mWindowWidth = ANativeWindow_getWidth(window);
    mWindowHeight = ANativeWindow_getHeight(window);
    if (!createInstance()) return false;
    if (!selectPhysicalDevice()) return false;
    if (!createDevice()) return false;
    if (!createSwapchain()) return false;
    if (!createComputeResources()) return false;
    return true;
}

void VulkanRawPipeline::destroy() {
    if (mSpectralThread.joinable()) mSpectralThread.join();
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (mDevice != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(mDevice);
        destroyComputeResources();
        destroySwapchain();
        vkDestroyDevice(mDevice, nullptr);
        mDevice = VK_NULL_HANDLE;
    }
    if (mInstance != VK_NULL_HANDLE) {
        vkDestroyInstance(mInstance, nullptr);
        mInstance = VK_NULL_HANDLE;
    }
}

// =============================================================================
// Per-frame parameters
// =============================================================================
VkExtent2D VulkanRawPipeline::masterExtentFor(int rawW, int rawH, int orientation) const {
    // Matches the ISP geometry: 0/180 use the native 3:4 crop (e.g. 2295 x 3060),
    // 90/270 use the full sensor in portrait (e.g. 3060 x 4080).
    if (orientation == 90 || orientation == 270) {
        return {static_cast<uint32_t>(rawH), static_cast<uint32_t>(rawW)};
    }
    uint32_t cropW = static_cast<uint32_t>(std::lround(static_cast<double>(rawH) * 0.75));
    return {std::max(2u, cropW), static_cast<uint32_t>(rawH)};
}

void VulkanRawPipeline::fillFrameParams(FrameTargets& t, int camMatrixIdx) {
    FrameParamsUbo u = {};
    const int camIdx = std::clamp(camMatrixIdx, 0, 3);
    const float white = mLastFrame.whiteLevel;
    const float black = std::max(mBlackLevel.load(), 0.0f);
    const float range = std::max(white - black, 1.0f);

    // Noise model: Camera2 SENSOR_NOISE_PROFILE when present, otherwise the lab model
    // (kS24SensorNoiseModels) interpolated linearly in ISO between base and HCG switch.
    float S = mNoiseS.load();
    float O = mNoiseO.load();
    if (!(S > 0.0f)) {
        const auto& m = s24film::kS24SensorNoiseModels[camIdx];
        float iso = static_cast<float>(std::max(mIso.load(), 1));
        float span = static_cast<float>(std::max(m.hcgSwitchIso - m.minIso, 1));
        float t01 = (iso - static_cast<float>(m.minIso)) / span;
        S = std::max(m.baseScaleS + (m.hcgScaleS - m.baseScaleS) * t01, 1e-6f);
        float readVar = std::max(m.empiricalReadVarBase + (m.empiricalReadVarHcg - m.empiricalReadVarBase) * t01, 0.0f);
        O = readVar / (range * range);
    }
    u.noise[0] = S;
    u.noise[1] = std::max(O, 0.0f);
    u.noise[2] = black;
    u.noise[3] = 1.0f;
    // Normalized-RAW -> scene-linear AP1 gain, so the FILM stage can scale the noise model.
    // Includes the film looks' digital gain to the stock's EI (as in fillPushConstants): without
    // it the NR under-read the noise whenever the sensor sat below the exposure index.
    float filmGain = 1.0f;
    {
        const int li = mActiveLookIdx.load();
        if (li >= 0 && li < static_cast<int>(s24film::kTotalMasterLooks) && s24film::kMasterLookBoxIso[li] > 0) {
            filmGain = mFilmDigitalGain.load();
            const float te = mExposureSeconds.load();
            if (te > 1.0f) filmGain *= std::pow(te, kReciprocityP - 1.0f);
        }
    }
    const float evGain = std::pow(2.0f, mUserEvStops.load()) * kExposureScale * filmGain;
    u.lsc[3] = evGain * std::max((mLastFrame.gainR + mLastFrame.gainG + mLastFrame.gainB) / 3.0f, 0.5f) *
        // The tele's residual noise measured ~2.5x the wide's after NR (same scene, Portra): its
        // output is upscaled from a small crop, so the noise is coarser than the lab model says.
        // Fixed ISO 400, 1/60 s: tele 0.0181 and front 0.0175 vs wide 0.0107 (UW 0.0103) with 1.4 on tele
        // only, so tele goes to 2.0 and front gets 1.6. Scaling g by 1.4 raises the NR's expected variance 1.4x (shot) to 2x (read).
        ((camIdx == 3) ? 2.0f : (camIdx == 1) ? 1.6f : 1.0f);

    // Per-lens luma NR: the tele, UW and front sensors have smaller pixels (lab shot-noise
    // scale up to ~5x the wide's at the same ISO). Scale the strength so every lens is left
    // with about the wide camera's residual noise (0.55 of the input at strength 0.45).
    float lumaNr = 0.45f;
    {
        const float iso = static_cast<float>(std::max(mIso.load(), 1));
        auto shotS = [iso](const s24film::SensorNoiseModel& m) {
            float t01 = (iso - static_cast<float>(m.minIso)) / static_cast<float>(std::max(m.hcgSwitchIso - m.minIso, 1));
            t01 = std::clamp(t01, 0.0f, 1.0f);
            return std::max(m.baseScaleS + (m.hcgScaleS - m.baseScaleS) * t01, 1e-7f);
        };
        const float ratio = std::sqrt(shotS(s24film::kS24SensorNoiseModels[camIdx]) /
                                      shotS(s24film::kS24SensorNoiseModels[0]));
        // Exponent 1.25: strength 1/ratio left tele / UW ~30% noisier than the wide on device.
        lumaNr = std::clamp(1.0f - 0.55f / std::pow(std::max(ratio, 1.0f), 1.25f), 0.45f, 0.88f);
    }

    u.lsc[0] = 1.0f;
    u.lsc[1] = static_cast<float>(s24film::kLscGridCols);
    u.lsc[2] = static_cast<float>(s24film::kLscGridRows);

    const int lookIdx = mLutReady ? mActiveLookIdx.load() : -1;
    u.look[0] = static_cast<float>(lookIdx);
    u.look[1] = kDirMultiplier;
    u.look[2] = 1.12f;    // print / scanner contrast
    u.look[3] = kLutLogEMin;
    u.look2[0] = kLutLogEMax;
    u.look2[1] = static_cast<float>(mLookLutImage.extent.height);
    u.look2[2] = static_cast<float>(mLookLutImage.extent.width);
    u.look2[3] = 0.4f;    // strength of the look's spectral crosstalk matrix (1 = full, hues drift to orange)

    const int stockIdx = mActiveStockIdx.load();
    const auto& st = s24film::kFeaturedStocks[stockIdx];
    const float halMult = mUserHalationMult.load();
    const float baseHal = (lookIdx >= 0) ? s24film::kMasterLooks120[lookIdx].halationStrength : st.halationStrength;
    u.optics[0] = 0.9f;
    u.optics[1] = baseHal * halMult * 1.7f;

    const int uiLens = mActiveLensIdx.load();
    const int arch = kUiLensToArchetype[std::clamp(uiLens, 0, kSuper70LensIndex)];
    if (arch >= 0) {
        const auto& L = s24film::kLegendaryLenses8[arch];
        u.lens[0] = 0.0f;   // geometric twist removed; swirl is the tangential blur in lfx2.z
        u.lens[1] = L.thoriumWarmth;
        u.lens[2] = L.sphericalGlowW040;
        u.lens[3] = L.vignetteCos4;
        u.optics[2] = L.sphericalGlowW040 * 1.2f;
    }
    u.optics[3] = 0.6f;

    if (mLensFxEnabled.load()) {
        const LensFx& fx = kLensFx[std::clamp(uiLens, 0, 5)];
        const float fNum = std::max(mVirtualFNumber.load(), fx.maxAperture);
        const float open = fx.maxAperture / fNum;           // 1 wide open, smaller stopped down
        const float open2 = open * open;
        u.lfx0[0] = fx.ghosts;                               // ghosts stay (they sharpen, not fade)
        u.lfx0[1] = fx.halo * open;
        u.lfx0[2] = fx.streak;
        u.lfx0[3] = fx.streakLen;
        for (int c = 0; c < 3; ++c) {
            u.lfx1[c] = fx.streakTint[c];
            u.lfx4[c] = fx.coating[c];
        }
        u.lfx1[3] = fx.veil;
        u.lfx2[0] = fx.ca * (0.6f + 0.4f * open);
        u.lfx2[1] = fx.distortionK1;
        u.lfx2[2] = mPortraitMode.load() ? fx.swirl * open2 : 0.0f;
        u.lfx2[3] = fx.starburst;
        u.lfx3[0] = fx.blades;
        u.lfx3[1] = fNum;
        u.lfx3[2] = fx.squeeze;
        u.lfx4[3] = kFlareThreshold;
        // Glow (spherical aberration) and vignette fall quickly when stopping down.
        u.optics[2] *= open2;
        u.lens[3] *= open2;
    }

    // Halation: TIR ring radius in output pixels -> Gaussian weights over mips 1..5 (log2 space).
    const float outLong = static_cast<float>(std::max(t.extent.width, t.extent.height));
    const FilmFormat& fmt = kFilmFormats[std::clamp(mFilmFormat.load(), 0, 3)];
    const float ringPx = kHalationRingMm / fmt.frameLongMm * outLong;
    // Resolution: Gaussian with the system cut-off period, in output pixels.
    u.halW2[1] = 0.35f * outLong / (fmt.lpPerMm * fmt.frameLongMm);
    u.halW2[2] = fmt.gateVignette;
    const float ringLod = std::log2(std::max(ringPx, 1.0f));
    float hw[5];
    float dw[5];
    float hsum = 0.0f, dsum = 0.0f;
    for (int k = 0; k < 5; ++k) {
        float lod = static_cast<float>(k + 1);
        float d = (lod - ringLod) / 0.75f;
        hw[k] = std::exp(-0.5f * d * d);
        dw[k] = std::pow(2.0f, 0.5f * lod);
        hsum += hw[k];
        dsum += dw[k];
    }
    for (int k = 0; k < 4; ++k) {
        u.halW[k] = hw[k] / hsum;
        u.diffW[k] = dw[k] / dsum;
    }
    u.halW2[0] = hw[4] / hsum;
    u.diffW2[0] = dw[4] / dsum;

    // Super 70mm IMAX bokeh: background (infinity) CoC for the current focus distance.
    const bool bokeh = (uiLens == kSuper70LensIndex) && mPortraitMode.load();
    if (bokeh) {
        float diopters = mFocusDiopters.load();
        float subjectM = (diopters > 0.05f) ? 1.0f / diopters : 20.0f;
        subjectM = std::clamp(subjectM, 0.3f, 20.0f);
        float subjectMm = subjectM * 1000.0f;
        float cocMm = (kSuper70FocalMm * kSuper70FocalMm) /
                      (kSuper70FNumber * std::max(subjectMm - kSuper70FocalMm, 1.0f));
        float radiusPx = 0.5f * cocMm / kSuper70GateLongMm * outLong;
        radiusPx = std::min(radiusPx, 96.0f * outLong / 4080.0f);
        u.bokeh[0] = 1.0f;
        u.bokeh[1] = radiusPx;
        u.bokeh[2] = 0.5f;
        u.bokeh[3] = 0.5f;
        u.bokeh2[0] = 0.16f;
        u.bokeh2[1] = 0.42f;
        u.bokeh2[3] = 64.0f;
    }
    u.bokeh2[2] = (lookIdx >= 0 && s24film::kMasterLookBoxIso[lookIdx] > 0) ? mPushStops.load() : 0.0f;

    const float grainMult = mUserGrainMult.load();
    u.grain[0] = t.master ? 0.7f : 0.4f;   // luma sharpening
    // Grain tile texel size in output pixels: same grain-to-frame ratio in preview and master.
    u.grain[1] = std::max(1.0f, outLong / fmt.frameLongMm * kGrainClumpMm);
    u.grain[3] = fmt.grainAmp;
    u.grain[2] = grainMult;

    u.hdr[0] = kHdrSdrRatio;
    u.hdr[1] = t.master ? 1.0f : 0.0f;
    u.hdr[2] = 1.0f;      // chroma noise reduction strength
    u.hdr[3] = lumaNr;    // luma noise reduction strength (per lens, see above)

    {
        std::lock_guard<std::mutex> lock(mFmMutex);
        if (mForwardMatrixValid) {
            for (int c = 0; c < 3; ++c) {
                u.fm0[c] = mForwardMatrix[0 + c];
                u.fm1[c] = mForwardMatrix[3 + c];
                u.fm2[c] = mForwardMatrix[6 + c];
            }
            u.fm0[3] = 1.0f;
        }
    }

    std::memcpy(t.uboMapped, &u, sizeof(u));
}

void VulkanRawPipeline::fillPushConstants(PushConstants& pc, const LastFrameState& st, uint32_t slot, uint32_t burstCnt) {
    const int stockIdx = mActiveStockIdx.load();
    const auto& stock = s24film::kFeaturedStocks[stockIdx];
    const float evScale = std::pow(2.0f, mUserEvStops.load());

    pc = {};
    pc.width = static_cast<uint32_t>(st.width);
    pc.height = static_cast<uint32_t>(st.height);
    pc.stride = static_cast<uint32_t>(st.stride);
    pc.cfa = static_cast<uint32_t>(st.cfa);
    pc.whiteLevel = st.whiteLevel;
    pc.gainR = st.gainR;
    pc.gainG = st.gainG;
    pc.gainB = st.gainB;
    pc.sensorOrientation = static_cast<uint32_t>(st.sensorOrientation);
    pc.stockType = static_cast<uint32_t>(stock.stockType);
    pc.lensType = static_cast<uint32_t>(mActiveLensIdx.load());
    pc.frameSeed = mFrameCounter;
    pc.dMaxR = stock.dMaxR;
    pc.dMaxG = stock.dMaxG;
    pc.dMaxB = stock.dMaxB;
    pc.nu = stock.nu;
    pc.gammaR = stock.gammaR;
    pc.gammaG = stock.gammaG;
    pc.gammaB = stock.gammaB;
    pc.dirSatBoost = stock.dirSatBoost;
    pc.printGammaR = stock.printGammaR;
    pc.printGammaG = stock.printGammaG;
    pc.printGammaB = stock.printGammaB;
    pc.grainStrength = stock.grainStrength * mUserGrainMult.load();
    pc.halationStrength = stock.halationStrength * mUserHalationMult.load();
    // Film looks: digital gain to the stock's exposure index, and reciprocity failure for
    // exposures over 1 s (Schwarzschild, p = 0.85): effective exposure = I * t^p.
    float filmGain = 1.0f;
    const int lookIdx = mActiveLookIdx.load();
    if (lookIdx >= 0 && lookIdx < static_cast<int>(s24film::kTotalMasterLooks) &&
        s24film::kMasterLookBoxIso[lookIdx] > 0) {
        filmGain = mFilmDigitalGain.load();
        const float t = mExposureSeconds.load();
        if (t > 1.0f) filmGain *= std::pow(t, kReciprocityP - 1.0f);
    }
    pc.exposureScale = kExposureScale * evScale * filmGain;
    pc.camMatrixIdx = static_cast<uint32_t>(std::clamp(st.camMatrixIdx, 0, 3));

    uint32_t mirrorBit = (st.camMatrixIdx == 1) ? 1u : 0u;
    uint32_t anamorphicBits = static_cast<uint32_t>(mAnamorphicMode.load() & 3);
    uint32_t gateBits = static_cast<uint32_t>(mCinemaGate.load() & 3);
    const int format = mFilmFormat.load();
    uint32_t weaveBit = (mGateWeave.load() || format == 1 || format == 2) ? 1u : 0u;   // small-gauge gate weave
    pc.packedRingInfo = (slot & 0xFu)
                      | ((st.ringSlots & 0xFu) << 4u)
                      | ((burstCnt & 0xFFu) << 8u)
                      | (mirrorBit << 16u)
                      | (anamorphicBits << 17u)
                      | (gateBits << 19u)
                      | (weaveBit << 21u);
}

void VulkanRawPipeline::computeBarrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb = {};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 1, &mb, 0, nullptr, 0, nullptr);
}

// Records PREP -> ISP -> PYRAMID x5 -> BOKEH -> FILM (or just the front / the film pass, see kPass*). Timestamps (if supported):
// queryBase + 0 start, +1 PREP, +2 ISP, +3 PYRAMID, +4 BOKEH, +5 FILM.
void VulkanRawPipeline::recordEngine(VkCommandBuffer cmd, FrameTargets& t, PushConstants pc,
                                     uint32_t rawW, uint32_t rawH, uint32_t queryBase, uint32_t pass) {
    const bool ts = (mQueryPool != VK_NULL_HANDLE);
    if (ts) {
        vkCmdResetQueryPool(cmd, mQueryPool, queryBase, kQueriesPerPass);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, mQueryPool, queryBase + 0);
    }

    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelineLayout, 0, 1, &t.descriptorSet, 0, nullptr);
    pc.flags = kFlagGat | kFlagLsc | (t.master ? (kFlagMaster | kFlagSpatial) : 0u);
    pc.level = 0;

    auto push = [&]() {
        vkCmdPushConstants(cmd, mPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(PushConstants), &pc);
    };
    auto groups = [](uint32_t n) { return (n + 15u) / 16u; };

    if (pass != kPassFilm && pass != kPassBack) {
    // Stage 1-2: PREP (sensor resolution)
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelines[0]);
    push();
    vkCmdDispatch(cmd, groups(rawW), groups(rawH), 1);
    computeBarrier(cmd);
    if (ts) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mQueryPool, queryBase + 1);

    // Stage 3-6: ISP -> mLinearAcesImage mip 0
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelines[1]);
    push();
    vkCmdDispatch(cmd, groups(t.extent.width), groups(t.extent.height), 1);
    computeBarrier(cmd);
    if (ts) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mQueryPool, queryBase + 2);

    // Stage 7: 5-level Gaussian pyramid
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelines[2]);
    for (uint32_t L = 1; L <= kPyramidLevels; ++L) {
        pc.level = L;
        push();
        vkCmdDispatch(cmd, groups(mipDim(t.extent.width, L)), groups(mipDim(t.extent.height, L)), 1);
        computeBarrier(cmd);
    }
    pc.level = 0;
    if (ts) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mQueryPool, queryBase + 3);
    if (pass == kPassFront) return;
    }

    // Stage 8: Super 70mm bokeh (half-res live, full-res master)
    const FrameParamsUbo* u = static_cast<const FrameParamsUbo*>(t.uboMapped);
    if (pass != kPassFilm && u->bokeh[0] > 0.5f) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelines[3]);
        push();
        vkCmdDispatch(cmd, groups(t.bokehExtent.width), groups(t.bokehExtent.height), 1);
        computeBarrier(cmd);
    }
    if (ts) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mQueryPool, queryBase + 4);

    // Stage 9-12: FILM
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelines[4]);
    push();
    vkCmdDispatch(cmd, groups(t.extent.width), groups(t.extent.height), 1);
    if (ts) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mQueryPool, queryBase + 5);
}

void VulkanRawPipeline::logTimestamps(const char* label, uint32_t queryBase, uint32_t count) {
    if (mQueryPool == VK_NULL_HANDLE || count < 2) return;
    uint64_t ticks[kQueriesPerPass] = {};
    VkResult r = vkGetQueryPoolResults(mDevice, mQueryPool, queryBase, count, sizeof(ticks), ticks,
                                       sizeof(uint64_t), VK_QUERY_RESULT_64_BIT);
    if (r != VK_SUCCESS) return;
    uint64_t mask = (mTimestampValidBits >= 64) ? ~0ull : ((1ull << mTimestampValidBits) - 1ull);
    auto ms = [&](uint32_t a, uint32_t b) {
        uint64_t d = ((ticks[b] & mask) - (ticks[a] & mask)) & mask;
        return static_cast<double>(d) * mTimestampPeriodNs * 1e-6;
    };
    LOGI("[%s GPU] prep %.2f | isp %.2f | pyramid %.2f | bokeh %.2f | film %.2f | total %.2f ms",
         label, ms(0, 1), ms(1, 2), ms(2, 3), ms(3, 4), ms(4, 5), ms(0, 5));
}

// =============================================================================
// Live viewfinder
// =============================================================================
float VulkanRawPipeline::renderRawFrame(
    AHardwareBuffer* ahb, int width, int height, int stride,
    int cfa, float whiteLevel, float gainR, float gainG, float gainB,
    int sensorOrientation, int camMatrixIdx)
{
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (!mDevice || !mSwapchain || !ahb) return 0.0f;

    auto startTime = std::chrono::high_resolution_clock::now();

    if (width != mLastWidth || height != mLastHeight) {
        mLastWidth = width;
        mLastHeight = height;
        mValidRingFrames = 0;
        mRingSlot = 0;
        mRingEpoch++;
    }

    if (static_cast<VkDeviceSize>(width) * static_cast<VkDeviceSize>(height) * sizeof(float) > mCleanBayerSize) {
        LOGE("RAW %dx%d exceeds clean Bayer buffer", width, height);
        return 0.0f;
    }

    mFrameCounter++;
    bool stepPrintHold = !mStackActive.load() && mStepPrint8fps.load() && (mValidRingFrames > 0) && ((mFrameCounter % 3u) != 0u);
    uint32_t currentSlot = mLastFrame.slot;

    if (!stepPrintHold) {
        void* rawAddress = nullptr;
        int lockResult = AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &rawAddress);
        if (lockResult == 0 && rawAddress != nullptr && mMappedRawBuffer != nullptr) {
            size_t frameBytes = static_cast<size_t>(height) * static_cast<size_t>(stride) * 2;
            // Next free slot; slots held by a zero-lag merge are stepped over. A skip breaks the
            // run of consecutive frames the live temporal merge reads.
            uint32_t slot = mRingSlot % mRingSlots;
            for (uint32_t tries = 0; tries < mRingSlots && (mRingLockMask & (1u << slot)); ++tries) {
                slot = (slot + 1u) % mRingSlots;
                mValidRingFrames = 0;
            }
            if (frameBytes <= mRingSlotBytes && !(mRingLockMask & (1u << slot))) {
                // Slots are packed at the frame size (the shader's pitch is stride x height).
                uint8_t* basePtr = static_cast<uint8_t*>(mMappedRawBuffer);
                std::memcpy(basePtr + static_cast<size_t>(slot) * frameBytes, rawAddress, frameBytes);
                currentSlot = slot;
                mRingSlot = slot + 1u;
                if (mValidRingFrames < kLiveMergeFrames) mValidRingFrames++;
                RingMeta& m = mRingMeta[slot];
                m.seq = ++mRingSeq;
                m.epoch = mRingEpoch;
                m.timestampNs = mPendingTimestampNs.load();
                m.iso = mPendingIso.load();
                m.exposureNs = mPendingExposureNs.load();
                m.camMatrixIdx = camMatrixIdx;
                m.width = width;
                m.height = height;
                m.stride = stride;
                mRingNewestSeq.store(m.seq);

                // Central 50% RAW mean (every 16th pixel) for the Java DCG auto-ISO loop.
                const uint16_t* px = static_cast<const uint16_t*>(rawAddress);
                double sum = 0.0;
                uint32_t n = 0;
                for (int y = height / 4; y < (3 * height) / 4; y += 16) {
                    const uint16_t* row = px + static_cast<size_t>(y) * static_cast<size_t>(stride);
                    for (int x = width / 4; x < (3 * width) / 4; x += 16) {
                        sum += row[x];
                        n++;
                    }
                }
                if (n > 0) {
                    float black = std::max(mBlackLevel.load(), 0.0f);
                    float mean = (static_cast<float>(sum / n) - black) / std::max(whiteLevel - black, 1.0f);
                    mLastRawMean.store(std::max(mean, 0.0f));
                }
                // Whole-frame 5x5 zone means and the clipped-pixel fraction for matrix metering.
                {
                    float blk = std::max(mBlackLevel.load(), 0.0f);
                    float rng = std::max(whiteLevel - blk, 1.0f);
                    double zs[25] = {0.0}; uint32_t zn[25] = {0}; uint32_t clipped = 0, total = 0;
                    const float clipAt = blk + 0.95f * rng;
                    for (int y = 0; y < height; y += 16) {
                        const uint16_t* row = px + static_cast<size_t>(y) * static_cast<size_t>(stride);
                        const int zy = std::min(4, y * 5 / height);
                        for (int x = 0; x < width; x += 16) {
                            const int zx = std::min(4, x * 5 / width);
                            const float v = static_cast<float>(row[x]);
                            zs[zy * 5 + zx] += v; zn[zy * 5 + zx]++;
                            if (v >= clipAt) clipped++;
                            total++;
                        }
                    }
                    for (int i = 0; i < 25; ++i) {
                        float m = zn[i] ? static_cast<float>(zs[i] / zn[i]) : blk;
                        mMeterZones[i].store(std::max((m - blk) / rng, 0.0f));
                    }
                    mMeterZones[25].store(total ? static_cast<float>(clipped) / static_cast<float>(total) : 0.0f);
                }
            }
            AHardwareBuffer_unlock(ahb, nullptr);
        } else {
            return 0.0f;
        }
    }

    mLastFrame.valid = true;
    mLastFrame.width = width;
    mLastFrame.height = height;
    mLastFrame.stride = stride;
    mLastFrame.cfa = cfa;
    mLastFrame.whiteLevel = whiteLevel;
    mLastFrame.gainR = gainR;
    mLastFrame.gainG = gainG;
    mLastFrame.gainB = gainB;
    mLastFrame.sensorOrientation = sensorOrientation;
    mLastFrame.camMatrixIdx = camMatrixIdx;
    mLastFrame.slot = currentSlot;
    mLastFrame.burstCount = (mBurstModeEnabled.load() && !mForceSingleFrame.load()) ? std::max(mValidRingFrames, 1u) : 1u;
    mLastFrame.ringSlots = mRingSlots;

    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);
    vkResetFences(mDevice, 1, &mInFlightFence);
    uploadPendingSpectral();

    if (mLiveQueriesPending && (mFrameCounter % 120u) == 0u) {
        logTimestamps("Live", 0, 6);
    }

    uint32_t imageIndex = 0;
    if (vkAcquireNextImageKHR(mDevice, mSwapchain, UINT64_MAX,
                              mImageAvailableSemaphore, VK_NULL_HANDLE, &imageIndex) != VK_SUCCESS) {
        // Re-signal the fence so the next frame does not deadlock waiting on it.
        VkSubmitInfo empty = {};
        empty.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        vkQueueSubmit(mQueue, 1, &empty, mInFlightFence);
        return 0.0f;
    }

    bool hasEncoderTarget = (mEncoderSwapchain != VK_NULL_HANDLE);
    uint32_t encoderImageIndex = 0;
    if (hasEncoderTarget) {
        VkResult encRes = vkAcquireNextImageKHR(mDevice, mEncoderSwapchain, 10000000ULL,
                                                mEncoderImageAvailableSemaphore, VK_NULL_HANDLE, &encoderImageIndex);
        if (encRes != VK_SUCCESS && encRes != VK_SUBOPTIMAL_KHR) {
            hasEncoderTarget = false;
        }
    }

    fillFrameParams(mLiveTargets, camMatrixIdx);
    PushConstants pc;
    fillPushConstants(pc, mLastFrame, mLastFrame.slot, mLastFrame.burstCount);

    vkResetCommandBuffer(mCommandBuffer, 0);
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vkBeginCommandBuffer(mCommandBuffer, &beginInfo);

    recordEngine(mCommandBuffer, mLiveTargets, pc,
                 static_cast<uint32_t>(width), static_cast<uint32_t>(height), 0);

    VkMemoryBarrier toTransfer = {};
    toTransfer.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;

    VkImageMemoryBarrier barrierToDst = {};
    barrierToDst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrierToDst.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrierToDst.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrierToDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrierToDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrierToDst.image = mSwapchainImages[imageIndex];
    barrierToDst.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    barrierToDst.srcAccessMask = 0;
    barrierToDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;

    vkCmdPipelineBarrier(mCommandBuffer,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 1, &toTransfer, 0, nullptr, 1, &barrierToDst);

    const VkExtent2D outExtent = mLiveTargets.extent;
    VkImageBlit blitRegion = {};
    blitRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blitRegion.srcSubresource.layerCount = 1;
    blitRegion.srcOffsets[0] = {0, 0, 0};
    blitRegion.srcOffsets[1] = {static_cast<int32_t>(outExtent.width), static_cast<int32_t>(outExtent.height), 1};
    blitRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blitRegion.dstSubresource.layerCount = 1;
    blitRegion.dstOffsets[0] = {0, 0, 0};
    blitRegion.dstOffsets[1] = {static_cast<int32_t>(mSwapchainExtent.width), static_cast<int32_t>(mSwapchainExtent.height), 1};

    vkCmdBlitImage(mCommandBuffer,
                   mLiveTargets.storageRgba16.image, VK_IMAGE_LAYOUT_GENERAL,
                   mSwapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   1, &blitRegion, VK_FILTER_LINEAR);

    VkImageMemoryBarrier barrierToPresent = barrierToDst;
    barrierToPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrierToPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    barrierToPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrierToPresent.dstAccessMask = 0;

    vkCmdPipelineBarrier(mCommandBuffer,
                         VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &barrierToPresent);

    // Phase 5: Zero-Copy Blit of the active frame into the Hardware MediaCodec Swapchain.
    if (hasEncoderTarget) {
        VkImageMemoryBarrier encToDst = barrierToDst;
        encToDst.image = mEncoderImages[encoderImageIndex];
        vkCmdPipelineBarrier(mCommandBuffer,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &encToDst);

        // Centre crop of the live frame at the encoder's aspect (3:4 for both 1440p and 4K).
        const float srcW = static_cast<float>(outExtent.width), srcH = static_cast<float>(outExtent.height);
        const float encAspect = static_cast<float>(mEncoderExtent.width) / std::max(1.0f, static_cast<float>(mEncoderExtent.height));
        int32_t xMargin = 0, yMargin = 0;
        if (srcW / srcH < encAspect) {
            yMargin = std::max(0, static_cast<int32_t>((srcH - srcW / encAspect) * 0.5f));
        } else {
            xMargin = std::max(0, static_cast<int32_t>((srcW - srcH * encAspect) * 0.5f));
        }

        VkImageBlit encBlit = {};
        encBlit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        encBlit.srcSubresource.layerCount = 1;
        encBlit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        encBlit.dstSubresource.layerCount = 1;
        encBlit.dstOffsets[0] = {0, 0, 0};
        encBlit.dstOffsets[1] = {static_cast<int32_t>(mEncoderExtent.width),
                                 static_cast<int32_t>(mEncoderExtent.height), 1};

        if (mEncoderHlg && mEncodePipeline != VK_NULL_HANDLE && mEncoderStaging.image != VK_NULL_HANDLE) {
            // The fence wait above guarantees the set is idle, so rebinding every frame is safe
            // (and follows the live targets if they were rebuilt).
            VkDescriptorImageInfo imgs[2] = {};
            imgs[0].imageView = mLiveTargets.storageRgba16.levelViews[0];
            imgs[0].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            imgs[1].imageView = mEncoderStaging.levelViews[0];
            imgs[1].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkWriteDescriptorSet w[2] = {};
            for (uint32_t i = 0; i < 2; ++i) {
                w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                w[i].dstSet = mEncodeSet;
                w[i].dstBinding = i;
                w[i].descriptorCount = 1;
                w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                w[i].pImageInfo = &imgs[i];
            }
            vkUpdateDescriptorSets(mDevice, 2, w, 0, nullptr);

            computeBarrier(mCommandBuffer);
            EncodePush ep = {};
            ep.srcRect[0] = static_cast<float>(xMargin);
            ep.srcRect[1] = static_cast<float>(yMargin);
            ep.srcRect[2] = srcW - 2.0f * xMargin;
            ep.srcRect[3] = srcH - 2.0f * yMargin;
            ep.dstSize[0] = static_cast<int32_t>(mEncoderExtent.width);
            ep.dstSize[1] = static_cast<int32_t>(mEncoderExtent.height);
            ep.srcSize[0] = static_cast<int32_t>(outExtent.width);
            ep.srcSize[1] = static_cast<int32_t>(outExtent.height);
            vkCmdBindPipeline(mCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mEncodePipeline);
            vkCmdBindDescriptorSets(mCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mEncodePipelineLayout,
                                    0, 1, &mEncodeSet, 0, nullptr);
            vkCmdPushConstants(mCommandBuffer, mEncodePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                               0, sizeof(EncodePush), &ep);
            vkCmdDispatch(mCommandBuffer, (mEncoderExtent.width + 15u) / 16u, (mEncoderExtent.height + 15u) / 16u, 1);

            VkMemoryBarrier toBlit = {};
            toBlit.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
            toBlit.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            toBlit.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            vkCmdPipelineBarrier(mCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 0, 1, &toBlit, 0, nullptr, 0, nullptr);

            encBlit.srcOffsets[0] = {0, 0, 0};
            encBlit.srcOffsets[1] = {static_cast<int32_t>(mEncoderExtent.width),
                                     static_cast<int32_t>(mEncoderExtent.height), 1};
            vkCmdBlitImage(mCommandBuffer,
                           mEncoderStaging.image, VK_IMAGE_LAYOUT_GENERAL,
                           mEncoderImages[encoderImageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                           1, &encBlit, VK_FILTER_NEAREST);
        } else {
            encBlit.srcOffsets[0] = {xMargin, yMargin, 0};
            encBlit.srcOffsets[1] = {static_cast<int32_t>(outExtent.width) - xMargin,
                                     static_cast<int32_t>(outExtent.height) - yMargin, 1};
            vkCmdBlitImage(mCommandBuffer,
                           mLiveTargets.storageRgba16.image, VK_IMAGE_LAYOUT_GENERAL,
                           mEncoderImages[encoderImageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                           1, &encBlit, VK_FILTER_LINEAR);
        }

        VkImageMemoryBarrier encToPresent = encToDst;
        encToPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        encToPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        encToPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        encToPresent.dstAccessMask = 0;

        vkCmdPipelineBarrier(mCommandBuffer,
                             VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &encToPresent);
    }

    vkEndCommandBuffer(mCommandBuffer);

    VkSemaphore waitSems[2] = {mImageAvailableSemaphore, mEncoderImageAvailableSemaphore};
    VkPipelineStageFlags waitStages[2] = {VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT};
    VkSemaphore signalSems[2] = {mRenderFinishedSemaphore, mEncoderRenderFinishedSemaphore};

    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.waitSemaphoreCount = hasEncoderTarget ? 2 : 1;
    submitInfo.pWaitSemaphores = waitSems;
    submitInfo.pWaitDstStageMask = waitStages;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &mCommandBuffer;
    submitInfo.signalSemaphoreCount = hasEncoderTarget ? 2 : 1;
    submitInfo.pSignalSemaphores = signalSems;

    vkQueueSubmit(mQueue, 1, &submitInfo, mInFlightFence);
    mLiveQueriesPending = (mQueryPool != VK_NULL_HANDLE);

    VkPresentInfoKHR presentInfo = {};
    presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = &mRenderFinishedSemaphore;
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = &mSwapchain;
    presentInfo.pImageIndices = &imageIndex;
    vkQueuePresentKHR(mQueue, &presentInfo);

    if (hasEncoderTarget) {
        VkPresentInfoKHR encPresent = {};
        encPresent.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        encPresent.waitSemaphoreCount = 1;
        encPresent.pWaitSemaphores = &mEncoderRenderFinishedSemaphore;
        encPresent.swapchainCount = 1;
        encPresent.pSwapchains = &mEncoderSwapchain;
        encPresent.pImageIndices = &encoderImageIndex;
        vkQueuePresentKHR(mQueue, &encPresent);
    }

    auto endTime = std::chrono::high_resolution_clock::now();
    std::chrono::duration<float, std::milli> elapsed = endTime - startTime;
    return elapsed.count();
}

// Fallback capture: reads back the live RGBA16F viewfinder image as RGBA8.
bool VulkanRawPipeline::copyLastRenderedRgba(std::vector<uint8_t>& outRgba, int& outW, int& outH) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (!mDevice || !mMappedReadbackBuffer || mLiveTargets.extent.width == 0) return false;
    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);

    vkResetCommandBuffer(mReadbackCommandBuffer, 0);
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(mReadbackCommandBuffer, &beginInfo);

    VkBufferImageCopy copyRegion = {};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {mLiveTargets.extent.width, mLiveTargets.extent.height, 1};
    vkCmdCopyImageToBuffer(mReadbackCommandBuffer, mLiveTargets.storageRgba16.image,
                           VK_IMAGE_LAYOUT_GENERAL, mReadbackBuffer, 1, &copyRegion);
    vkEndCommandBuffer(mReadbackCommandBuffer);

    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &mReadbackCommandBuffer;
    vkQueueSubmit(mQueue, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(mQueue);

    outW = static_cast<int>(mLiveTargets.extent.width);
    outH = static_cast<int>(mLiveTargets.extent.height);
    const size_t pixels = static_cast<size_t>(outW) * static_cast<size_t>(outH);
    outRgba.resize(pixels * 4);
    const uint16_t* src = static_cast<const uint16_t*>(mMappedReadbackBuffer);
    for (size_t i = 0; i < pixels * 4; ++i) {
        float v = std::clamp(halfToFloat(src[i]), 0.0f, 1.0f);
        outRgba[i] = static_cast<uint8_t>(v * 255.0f + 0.5f);
    }
    return true;
}

// =============================================================================
// Offline Master Still Renderer (full native resolution)
// =============================================================================
bool VulkanRawPipeline::ensureMasterTargets(VkExtent2D ext) {
    if (mMasterTargets.extent.width != ext.width || mMasterTargets.extent.height != ext.height) {
        vkDeviceWaitIdle(mDevice);
        destroyTargets(mMasterTargets);
        destroyBuffer(mMasterReadbackBuffer, mMasterReadbackMemory, &mMasterReadbackMapped);
        if (!createTargets(mMasterTargets, ext, true)) {
            LOGE("Master target allocation failed for %ux%u", ext.width, ext.height);
            destroyTargets(mMasterTargets);
            return false;
        }
        mMasterReadbackSize = static_cast<VkDeviceSize>(ext.width) * ext.height * 4;
        // Prefer cached host memory: the CPU reads ~50 MB back.
        bool ok = createBuffer(mMasterReadbackSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                               VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                               VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                               mMasterReadbackBuffer, mMasterReadbackMemory, &mMasterReadbackMapped);
        if (!ok) {
            ok = createBuffer(mMasterReadbackSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                              VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                              mMasterReadbackBuffer, mMasterReadbackMemory, &mMasterReadbackMapped);
        }
        if (!ok) {
            LOGE("Master readback buffer allocation failed");
            destroyTargets(mMasterTargets);
            return false;
        }
    }
    return true;
}

bool VulkanRawPipeline::renderMasterStill(std::vector<uint8_t>& outRgba, int& outW, int& outH) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (!mDevice || !mLastFrame.valid) return false;
    auto cpuStart = std::chrono::high_resolution_clock::now();

    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);

    if (mMasterPending) { LOGW("Master render requested while a depth render is pending"); return false; }
    uploadPendingSpectral();
    const VkExtent2D ext = masterExtentFor(mLastFrame.width, mLastFrame.height, mLastFrame.sensorOrientation);
    if (!ensureMasterTargets(ext)) return false;

    fillFrameParams(mMasterTargets, mLastFrame.camMatrixIdx);
    PushConstants pc;
    fillPushConstants(pc, mLastFrame, mLastFrame.slot, mLastFrame.burstCount);

    vkResetCommandBuffer(mReadbackCommandBuffer, 0);
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(mReadbackCommandBuffer, &beginInfo);

    recordEngine(mReadbackCommandBuffer, mMasterTargets, pc,
                 static_cast<uint32_t>(mLastFrame.width), static_cast<uint32_t>(mLastFrame.height),
                 kQueriesPerPass);

    VkMemoryBarrier toTransfer = {};
    toTransfer.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(mReadbackCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 1, &toTransfer, 0, nullptr, 0, nullptr);

    VkBufferImageCopy copyRegion = {};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {ext.width, ext.height, 1};
    vkCmdCopyImageToBuffer(mReadbackCommandBuffer, mMasterTargets.storageRgba8.image, VK_IMAGE_LAYOUT_GENERAL,
                           mMasterReadbackBuffer, 1, &copyRegion);

    VkMemoryBarrier toHost = {};
    toHost.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(mReadbackCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 1, &toHost, 0, nullptr, 0, nullptr);
    vkEndCommandBuffer(mReadbackCommandBuffer);

    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &mReadbackCommandBuffer;
    vkQueueSubmit(mQueue, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(mQueue);

    logTimestamps("Master", kQueriesPerPass, 6);

    outW = static_cast<int>(ext.width);
    outH = static_cast<int>(ext.height);
    const size_t pixels = static_cast<size_t>(outW) * static_cast<size_t>(outH);
    outRgba.resize(pixels * 4);
    mMasterGain.resize(pixels);
    mMasterGainW = outW;
    mMasterGainH = outH;

    const uint8_t* src = static_cast<const uint8_t*>(mMasterReadbackMapped);
    for (size_t i = 0; i < pixels; ++i) {
        outRgba[i * 4 + 0] = src[i * 4 + 0];
        outRgba[i * 4 + 1] = src[i * 4 + 1];
        outRgba[i * 4 + 2] = src[i * 4 + 2];
        outRgba[i * 4 + 3] = 255;
        mMasterGain[i] = src[i * 4 + 3];
    }

    auto cpuEnd = std::chrono::high_resolution_clock::now();
    std::chrono::duration<float, std::milli> elapsed = cpuEnd - cpuStart;
    LOGI("Master still %dx%d rendered + read back in %.1f ms (look %d, lens %d, burst %u)",
         outW, outH, elapsed.count(), mActiveLookIdx.load(), mActiveLensIdx.load(), mLastFrame.burstCount);
    return true;
}

// =============================================================================
// Two-step master render (monocular depth bokeh)
// =============================================================================
bool VulkanRawPipeline::submitAndWait(VkCommandBuffer cmd) {
    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    if (vkQueueSubmit(mQueue, 1, &submitInfo, VK_NULL_HANDLE) != VK_SUCCESS) return false;
    return vkQueueWaitIdle(mQueue) == VK_SUCCESS;
}

// Infinity blur of the virtual 50 mm lens at the dial's f-number and the focus distance, in
// output pixels (thin lens: CoC = f^2 / (N (s - f)), radius = half of it).
static float depthInfinityRadiusPx(float fNumber, float diopters, float outLong) {
    float subjectM = (diopters > 0.05f) ? 1.0f / diopters : 20.0f;
    subjectM = std::clamp(subjectM, 0.3f, 20.0f);
    const float cocMm = (kDepthLensMm * kDepthLensMm) /
                        (std::max(fNumber, 0.5f) * std::max(subjectM * 1000.0f - kDepthLensMm, 1.0f));
    return 0.5f * cocMm / kDepthSensorLongMm * outLong;
}

void VulkanRawPipeline::fillDepthParams(FrameTargets& t, bool enable, float focusDisp) {
    DepthParamsUbo u = {};
    if (enable) {
        const float outLong = static_cast<float>(std::max(t.extent.width, t.extent.height));
        float fNum = mPendingFNumber;
        // Super 70mm: 52.5 mm gate, so the same f-number is a shallower full-frame equivalent.
        if (mPendingLens == kSuper70LensIndex) fNum *= kDepthSensorLongMm / kSuper70GateLongMm;
        const float rInf = depthInfinityRadiusPx(fNum, mPendingDiopters, outLong);
        const FrameParamsUbo* fp = static_cast<const FrameParamsUbo*>(t.uboMapped);
        u.a[0] = 1.0f;
        u.a[1] = rInf;
        u.a[2] = focusDisp;
        u.a[3] = std::max(0.8f * outLong / 1024.0f, 0.05f * rInf);
        u.b[0] = kDepthMaxCocFrac * outLong;
        u.b[3] = (fp->lfx3[0] > 0.5f) ? fp->lfx3[0] : 9.0f;   // iris blades
        u.c[0] = 0.6f;     // rounded blades
        u.c[1] = 0.45f;    // cat's eye at the corners
        u.c[2] = 0.25f;    // soap-bubble rim
        u.c[3] = 192.0f;   // max taps
        u.d[0] = 3.0f;     // clipped-highlight weight
    }
    std::memcpy(t.depthUboMapped, &u, sizeof(u));
}

bool VulkanRawPipeline::masterBegin(int dstW, int dstH, std::vector<uint8_t>& outRgb) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (!mDevice || !mLastFrame.valid || mMasterPending || dstW < 8 || dstH < 8) return false;
    auto cpuStart = std::chrono::high_resolution_clock::now();
    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);
    uploadPendingSpectral();

    const VkExtent2D ext = masterExtentFor(mLastFrame.width, mLastFrame.height, mLastFrame.sensorOrientation);
    if (!ensureMasterTargets(ext)) return false;
    fillFrameParams(mMasterTargets, mLastFrame.camMatrixIdx);
    PushConstants pc;
    fillPushConstants(pc, mLastFrame, mLastFrame.slot, mLastFrame.burstCount);

    // Smallest copy that still covers the network input: pyramid mip m >= 1 (the readback buffer
    // holds ext w*h*4 bytes, a mip >= 1 of RGBA16F always fits).
    uint32_t m = 1;
    while (m < kPyramidLevels && mipDim(ext.width, m + 1) >= static_cast<uint32_t>(dstW) &&
           mipDim(ext.height, m + 1) >= static_cast<uint32_t>(dstH)) ++m;
    const uint32_t mw = mipDim(ext.width, m), mh = mipDim(ext.height, m);

    vkResetCommandBuffer(mReadbackCommandBuffer, 0);
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(mReadbackCommandBuffer, &beginInfo);
    recordEngine(mReadbackCommandBuffer, mMasterTargets, pc,
                 static_cast<uint32_t>(mLastFrame.width), static_cast<uint32_t>(mLastFrame.height),
                 kQueriesPerPass, kPassFront);
    VkMemoryBarrier toTransfer = {};
    toTransfer.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(mReadbackCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 1, &toTransfer, 0, nullptr, 0, nullptr);
    VkBufferImageCopy copyRegion = {};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.mipLevel = m;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {mw, mh, 1};
    vkCmdCopyImageToBuffer(mReadbackCommandBuffer, mMasterTargets.linearAces.image, VK_IMAGE_LAYOUT_GENERAL,
                           mMasterReadbackBuffer, 1, &copyRegion);
    VkMemoryBarrier toHost = {};
    toHost.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(mReadbackCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 1, &toHost, 0, nullptr, 0, nullptr);
    vkEndCommandBuffer(mReadbackCommandBuffer);
    if (!submitAndWait(mReadbackCommandBuffer)) {
        LOGE("Master begin submit failed");
        return false;
    }

    // Scene-linear ACEScg -> display sRGB (soft shoulder), then area-average to dstW x dstH.
    const uint16_t* src = static_cast<const uint16_t*>(mMasterReadbackMapped);
    std::vector<float> disp(static_cast<size_t>(mw) * mh * 3);
    for (size_t i = 0; i < static_cast<size_t>(mw) * mh; ++i) {
        const float r = std::max(halfToFloat(src[i * 4 + 0]), 0.0f);
        const float g = std::max(halfToFloat(src[i * 4 + 1]), 0.0f);
        const float b = std::max(halfToFloat(src[i * 4 + 2]), 0.0f);
        const float lin[3] = {1.70505f * r - 0.62179f * g - 0.08326f * b,
                              -0.13026f * r + 1.14080f * g - 0.01055f * b,
                              -0.02400f * r - 0.12897f * g + 1.15297f * b};
        for (int k = 0; k < 3; ++k) {
            float x = std::max(lin[k], 0.0f);
            x = x * (1.0f + x * 0.0625f) / (1.0f + x);
            x = std::min(x, 1.0f);
            disp[i * 3 + k] = (x <= 0.0031308f) ? 12.92f * x : 1.055f * std::pow(x, 1.0f / 2.4f) - 0.055f;
        }
    }
    outRgb.assign(static_cast<size_t>(dstW) * dstH * 3, 0);
    for (int y = 0; y < dstH; ++y) {
        const float sy0 = static_cast<float>(y) * mh / dstH, sy1 = static_cast<float>(y + 1) * mh / dstH;
        for (int x = 0; x < dstW; ++x) {
            const float sx0 = static_cast<float>(x) * mw / dstW, sx1 = static_cast<float>(x + 1) * mw / dstW;
            float acc[3] = {0, 0, 0}, wsum = 0.0f;
            for (int yy = static_cast<int>(sy0); yy < std::min(static_cast<int>(std::ceil(sy1)), static_cast<int>(mh)); ++yy) {
                const float wy = std::min(sy1, yy + 1.0f) - std::max(sy0, static_cast<float>(yy));
                for (int xx = static_cast<int>(sx0); xx < std::min(static_cast<int>(std::ceil(sx1)), static_cast<int>(mw)); ++xx) {
                    const float w = wy * (std::min(sx1, xx + 1.0f) - std::max(sx0, static_cast<float>(xx)));
                    const float* s = &disp[(static_cast<size_t>(yy) * mw + xx) * 3];
                    acc[0] += s[0] * w; acc[1] += s[1] * w; acc[2] += s[2] * w;
                    wsum += w;
                }
            }
            uint8_t* o = &outRgb[(static_cast<size_t>(y) * dstW + x) * 3];
            for (int k = 0; k < 3; ++k) o[k] = static_cast<uint8_t>(std::clamp(acc[k] / std::max(wsum, 1e-6f), 0.0f, 1.0f) * 255.0f + 0.5f);
        }
    }

    mPendingPc = pc;
    mPendingFrame = mLastFrame;
    mPendingExtent = ext;
    mPendingFNumber = mVirtualFNumber.load();
    mPendingDiopters = mFocusDiopters.load();
    mPendingLens = mActiveLensIdx.load();
    mMasterPending = true;
    std::chrono::duration<float, std::milli> elapsed = std::chrono::high_resolution_clock::now() - cpuStart;
    LOGI("Master begin %ux%u (PREP+ISP+PYRAMID, %dx%d preview from mip %u) in %.1f ms", ext.width, ext.height,
         dstW, dstH, m, elapsed.count());
    return true;
}

bool VulkanRawPipeline::masterFinish(const float* disparity, const float* matte, int dw, int dh, float focusDisp,
                                     std::vector<uint8_t>& outRgba, int& outW, int& outH) {
    std::unique_lock<std::mutex> lock(mRenderMutex);
    if (!mMasterPending) return false;
    struct Clear { bool& f; ~Clear() { f = false; } } clear{mMasterPending};
    if (!mDevice) return false;
    auto cpuStart = std::chrono::high_resolution_clock::now();
    auto ms = [](std::chrono::high_resolution_clock::time_point a) {
        return std::chrono::duration<float, std::milli>(std::chrono::high_resolution_clock::now() - a).count();
    };

    FrameTargets& t = mMasterTargets;
    const VkExtent2D ext = mPendingExtent;
    const bool useDepth = disparity && dw >= 8 && dh >= 8 && static_cast<uint32_t>(dw) * dh <= kDepthMaxCells &&
                          mDepthMapped && t.cocMap.extent.width == ext.width && t.cocMap.extent.height == ext.height;
    float depthUpMs = 0.0f, bokehMs = 0.0f;

    PushConstants pc = mPendingPc;
    pc.flags = kFlagGat | kFlagLsc | kFlagMaster | kFlagSpatial;
    pc.level = 0;
    auto beginCmd = [&]() {
        vkResetCommandBuffer(mReadbackCommandBuffer, 0);
        VkCommandBufferBeginInfo bi = {};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(mReadbackCommandBuffer, &bi);
        vkCmdBindDescriptorSets(mReadbackCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelineLayout, 0, 1,
                                &t.descriptorSet, 0, nullptr);
    };
    auto groups = [](uint32_t n) { return (n + 15u) / 16u; };

    if (useDepth) {
        // Disparity grid (clamped to 0..1) + near-side blur reach: the positive CoC on the grid,
        // max-dilated by the largest CoC, bounds how far a blurry foreground can spill.
        const size_t n = static_cast<size_t>(dw) * dh;
        float* d = mDepthMapped;
        float* reach = mDepthMapped + n;
        float* mt = mDepthMapped + 2 * n;   // subject matte (portrait): 0 background .. 1 subject
        for (size_t i = 0; i < n; ++i) {
            d[i] = std::clamp(disparity[i], 0.0f, 1.0f);
            mt[i] = matte ? std::clamp(matte[i], 0.0f, 1.0f) : 0.0f;
        }
        fillDepthParams(t, true, std::clamp(focusDisp, 0.0f, 1.0f));
        DepthParamsUbo grid = *static_cast<const DepthParamsUbo*>(t.depthUboMapped);
        grid.d[1] = matte ? 1.0f : 0.0f;
        grid.b[1] = static_cast<float>(dw);
        grid.b[2] = static_cast<float>(dh);
        std::memcpy(t.depthUboMapped, &grid, sizeof(grid));
        std::vector<float> pos(n);
        for (size_t i = 0; i < n; ++i) {
            float cc = std::clamp(grid.a[1] * (d[i] - grid.a[2]) / std::max(grid.a[2], 0.05f), -grid.b[0], grid.b[0]);
            const float ac = std::fabs(cc);
            const float x = std::clamp((ac - grid.a[3]) / (2.0f * grid.a[3]), 0.0f, 1.0f);
            pos[i] = std::max(cc, 0.0f) * x * x * (3.0f - 2.0f * x) * (1.0f - mt[i]);
        }
        const float cellPx = static_cast<float>(std::max(ext.width, ext.height)) / static_cast<float>(std::max(dw, dh));
        const int k = static_cast<int>(std::ceil(grid.b[0] / cellPx)) + 1;
        std::vector<float> tmp(n);
        for (int y = 0; y < dh; ++y) {
            for (int x = 0; x < dw; ++x) {
                float mx = 0.0f;
                for (int xx = std::max(0, x - k); xx <= std::min(dw - 1, x + k); ++xx) mx = std::max(mx, pos[static_cast<size_t>(y) * dw + xx]);
                tmp[static_cast<size_t>(y) * dw + x] = mx;
            }
        }
        for (int y = 0; y < dh; ++y) {
            for (int x = 0; x < dw; ++x) {
                float mx = 0.0f;
                for (int yy = std::max(0, y - k); yy <= std::min(dh - 1, y + k); ++yy) mx = std::max(mx, tmp[static_cast<size_t>(yy) * dw + x]);
                reach[static_cast<size_t>(y) * dw + x] = mx;
            }
        }
        static_cast<FrameParamsUbo*>(t.uboMapped)->bokeh[0] = 1.0f;   // FILM consumes the bokeh image

        auto t0 = std::chrono::high_resolution_clock::now();
        beginCmd();
        vkCmdBindPipeline(mReadbackCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelines[5]);
        vkCmdPushConstants(mReadbackCommandBuffer, mPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(PushConstants), &pc);
        vkCmdDispatch(mReadbackCommandBuffer, groups(ext.width), groups(ext.height), 1);
        vkEndCommandBuffer(mReadbackCommandBuffer);
        if (!submitAndWait(mReadbackCommandBuffer)) { LOGE("Depth upsample failed"); return false; }
        depthUpMs = ms(t0);

        // BOKEH in horizontal bands, each its own submit: the live viewfinder renders in between.
        t0 = std::chrono::high_resolution_clock::now();
        const uint32_t bandRows = (((ext.height + kDepthBands - 1) / kDepthBands) + 15u) / 16u * 16u;
        for (uint32_t y0 = 0; y0 < ext.height; y0 += bandRows) {
            pc.reserved0 = y0;
            beginCmd();
            vkCmdBindPipeline(mReadbackCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mPipelines[3]);
            vkCmdPushConstants(mReadbackCommandBuffer, mPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(PushConstants), &pc);
            vkCmdDispatch(mReadbackCommandBuffer, groups(ext.width), groups(std::min(bandRows, ext.height - y0)), 1);
            vkEndCommandBuffer(mReadbackCommandBuffer);
            if (!submitAndWait(mReadbackCommandBuffer)) { LOGE("Depth bokeh band failed"); return false; }
            lock.unlock();
            lock.lock();
            if (!mDevice) return false;
        }
        pc.reserved0 = 0;
        bokehMs = ms(t0);
    } else {
        fillDepthParams(t, false, 0.0f);
    }

    auto t1 = std::chrono::high_resolution_clock::now();
    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);
    beginCmd();
    recordEngine(mReadbackCommandBuffer, t, pc, static_cast<uint32_t>(mPendingFrame.width),
                 static_cast<uint32_t>(mPendingFrame.height), kQueriesPerPass, useDepth ? kPassFilm : kPassBack);
    VkMemoryBarrier toTransfer = {};
    toTransfer.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(mReadbackCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 1, &toTransfer, 0, nullptr, 0, nullptr);
    VkBufferImageCopy copyRegion = {};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {ext.width, ext.height, 1};
    vkCmdCopyImageToBuffer(mReadbackCommandBuffer, t.storageRgba8.image, VK_IMAGE_LAYOUT_GENERAL,
                           mMasterReadbackBuffer, 1, &copyRegion);
    VkMemoryBarrier toHost = {};
    toHost.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(mReadbackCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 1, &toHost, 0, nullptr, 0, nullptr);
    vkEndCommandBuffer(mReadbackCommandBuffer);
    if (!submitAndWait(mReadbackCommandBuffer)) { LOGE("Master finish submit failed"); return false; }
    const float filmMs = ms(t1);

    outW = static_cast<int>(ext.width);
    outH = static_cast<int>(ext.height);
    const size_t pixels = static_cast<size_t>(outW) * static_cast<size_t>(outH);
    outRgba.resize(pixels * 4);
    mMasterGain.resize(pixels);
    mMasterGainW = outW;
    mMasterGainH = outH;
    const uint8_t* src = static_cast<const uint8_t*>(mMasterReadbackMapped);
    for (size_t i = 0; i < pixels; ++i) {
        outRgba[i * 4 + 0] = src[i * 4 + 0];
        outRgba[i * 4 + 1] = src[i * 4 + 1];
        outRgba[i * 4 + 2] = src[i * 4 + 2];
        outRgba[i * 4 + 3] = 255;
        mMasterGain[i] = src[i * 4 + 3];
    }
    LOGI("Master finish %dx%d: %s | depth upsample %.1f ms, bokeh %.1f ms (%d bands), film+readback %.1f ms, total %.1f ms (f/%.1f, focus %.2f D, d_f %.3f)",
         outW, outH, useDepth ? "depth bokeh" : "no depth", depthUpMs, bokehMs, kDepthBands, filmMs, ms(cpuStart),
         mPendingFNumber, mPendingDiopters, focusDisp);
    return true;
}

bool VulkanRawPipeline::copyMasterGainmap(std::vector<uint8_t>& outGain, int& outW, int& outH) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (mMasterGain.empty()) return false;
    outGain = mMasterGain;
    outW = mMasterGainW;
    outH = mMasterGainH;
    return true;
}


// =============================================================================
// Multi-frame stacking (burst average, HDR bracket, long exposure) on the RAW ring
// =============================================================================
namespace {
template <typename Fn>
void parallelRows(int rows, Fn fn) {
    unsigned n = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    if (n == 1 || rows < 64) { fn(0, rows); return; }
    std::vector<std::thread> pool;
    int chunk = ((rows + static_cast<int>(n) - 1) / static_cast<int>(n) + 1) & ~1;   // even: keeps Bayer cells whole
    for (int y0 = 0; y0 < rows; y0 += chunk) {
        int y1 = std::min(rows, y0 + chunk);
        pool.emplace_back([=]() { fn(y0, y1); });
    }
    for (auto& t : pool) t.join();
}
} // namespace

namespace {
// 8x8 box average of a raw frame (black-subtracted), used to find the hand-shake shift between burst frames.
void boxDown8(const uint16_t* src, int stride, int W, int H, float black, std::vector<float>& out, int& sw, int& sh) {
    sw = W / 8; sh = H / 8;
    out.assign(static_cast<size_t>(sw) * sh, 0.0f);
    float* o = out.data();
    parallelRows(sh, [=](int y0, int y1) {
        for (int by = y0; by < y1; ++by) {
            for (int bx = 0; bx < sw; ++bx) {
                float sum = 0.0f;
                for (int yy = 0; yy < 8; ++yy) {
                    const uint16_t* r = src + static_cast<size_t>(by * 8 + yy) * stride + bx * 8;
                    for (int xx = 0; xx < 8; ++xx) sum += std::max(static_cast<float>(r[xx]) - black, 0.0f);
                }
                o[static_cast<size_t>(by) * sw + bx] = sum * (1.0f / 64.0f);
            }
        }
    });
}

inline float cellLum(const uint16_t* p, int stride, int x, int y) {
    const uint16_t* r = p + static_cast<size_t>(y) * stride + x;
    return 0.25f * (static_cast<float>(r[0]) + r[1] + r[stride] + r[stride + 1]);
}
} // namespace

// Translation (even pixels) that maps this frame onto the reference: coarse 8x search, then a 2-pixel refine.
static void estimateShift(const std::vector<float>& refSmall, const std::vector<float>& curSmall, int sw, int sh,
                          const uint16_t* ref, int W, int H, const uint16_t* cur, int stride, float curScale, int& outX, int& outY) {
    const int R = 12, m = 14;
    double best = 1e30; int bx = 0, by = 0;
    for (int dy = -R; dy <= R; ++dy) {
        for (int dx = -R; dx <= R; ++dx) {
            double sad = 0.0; int cnt = 0;
            for (int y = m; y < sh - m; y += 2) {
                const float* a = &refSmall[static_cast<size_t>(y) * sw];
                const float* b = &curSmall[static_cast<size_t>(y + dy) * sw + dx];
                for (int x = m; x < sw - m; x += 2) { sad += std::fabs(a[x] - b[x]); ++cnt; }
            }
            sad /= std::max(cnt, 1);
            if (sad < best) { best = sad; bx = dx; by = dy; }
        }
    }
    // Refine at cell (2 px) resolution around the coarse answer.
    const int cx0 = bx * 8, cy0 = by * 8;
    double best2 = 1e30; int fx = cx0, fy = cy0;
    for (int dy = -8; dy <= 8; dy += 2) {
        for (int dx = -8; dx <= 8; dx += 2) {
            const int sx = cx0 + dx, sy = cy0 + dy;
            double sad = 0.0; int cnt = 0;
            for (int y = 128; y < H - 128; y += 16) {
                for (int x = 128; x < W - 128; x += 16) {
                    sad += std::fabs(cellLum(ref, W, x, y) - curScale * cellLum(cur, stride, x + sx, y + sy));
                    ++cnt;
                }
            }
            sad /= std::max(cnt, 1);
            if (sad < best2) { best2 = sad; fx = sx; fy = sy; }
        }
    }
    outX = fx & ~1; outY = fy & ~1;
}

bool VulkanRawPipeline::stackBegin(int mode) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (!mDevice || !mLastFrame.valid || mode < 0 || mode > 3) return false;
    StackState s;
    s.mode = mode;
    s.width = mLastFrame.width & ~1;
    s.height = mLastFrame.height & ~1;
    s.stride = mLastFrame.stride;
    s.black = std::max(mBlackLevel.load(), 0.0f);
    s.white = mLastFrame.whiteLevel;
    size_t px = static_cast<size_t>(s.width) * static_cast<size_t>(s.height);
    if (mode == 3) {
        s.peak.assign(px, 0);
    } else {
        s.num.assign(px, 0.0f);
        s.den.assign(px, 0.0f);
        if (mode == 0 || mode == 1) s.ref.assign(px, 0);
    }
    mStack = std::move(s);
    mStackActive.store(true);
    mForceSingleFrame.store(false);
    LOGI("Stack begin mode %d %dx%d black %.1f white %.1f", mode, mStack.width, mStack.height, mStack.black, mStack.white);
    return true;
}

bool VulkanRawPipeline::stackAdd(float relExposure) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (!mLastFrame.valid) return false;
    return stackAddSlot(mLastFrame.slot, mLastFrame.width, mLastFrame.height, mLastFrame.stride, relExposure);
}

// Caller holds mRenderMutex.
bool VulkanRawPipeline::stackAddSlot(uint32_t slot, int width, int height, int stride, float relExposure) {
    StackState& s = mStack;
    if (!mStackActive.load() || s.mode < 0 || !mMappedRawBuffer || slot >= mRingSlots) return false;
    if (width < s.width || height < s.height || stride != s.stride) return false;
    const size_t frameBytes = static_cast<size_t>(height) * static_cast<size_t>(s.stride) * 2;
    if (frameBytes * mRingSlots > mRawStorageBufferSize) return false;
    const uint16_t* src = reinterpret_cast<const uint16_t*>(
        static_cast<const uint8_t*>(mMappedRawBuffer) + static_cast<size_t>(slot) * frameBytes);

    const int W = s.width, H = s.height;
    const float black = s.black;
    const float range = std::max(s.white - s.black, 1.0f);
    const float e = std::max(relExposure, 1e-3f);
    const bool first = (s.frames == 0);
    const int mode = s.mode;
    float* num = s.num.data();
    float* den = s.den.data();
    uint16_t* ref = s.ref.data();
    uint16_t* peak = s.peak.data();

    // Burst: line each frame up with the first one (hand shake), whole Bayer cells so the pattern holds.
    int shx = 0, shy = 0;
    if (mode == 0 || mode == 1) {
        std::vector<float> cur;
        int sw = 0, sh = 0;
        boxDown8(src, stride, W, H, black, cur, sw, sh);
        if (first) {
            s.refSmall = cur; s.smallW = sw; s.smallH = sh;
        } else if (s.smallW == sw && s.smallH == sh) {
            float scale = 1.0f;   // HDR frames differ in exposure: match brightness before comparing
            if (mode == 1) {
                double ra = 0.0, ca = 0.0;
                for (size_t i = 0; i < cur.size(); ++i) { ra += s.refSmall[i]; ca += cur[i]; }
                scale = (ca > 1e-6) ? static_cast<float>(ra / ca) : 1.0f;
                for (auto& v : cur) v *= scale;
            }
            estimateShift(s.refSmall, cur, sw, sh, ref, W, H, src, stride, scale, shx, shy);
            LOGI("Stack align frame %d: shift %d,%d px", s.frames, shx, shy);
        }
    }

    parallelRows(H, [=](int y0, int y1) {
        for (int y = y0; y < y1; y += 2) {
            const int sy = y + shy;
            if (sy < 0 || sy + 1 >= H) continue;
            const uint16_t* r0 = src + static_cast<size_t>(sy) * stride;
            const uint16_t* r1 = r0 + stride;
            const size_t o0 = static_cast<size_t>(y) * W;
            const size_t o1 = o0 + W;
            for (int x = 0; x < W; x += 2) {
                const int sx = x + shx;
                if (sx < 0 || sx + 1 >= W) continue;
                const uint16_t v[4] = {r0[sx], r0[sx + 1], r1[sx], r1[sx + 1]};
                const size_t idx[4] = {o0 + x, o0 + x + 1, o1 + x, o1 + x + 1};
                if (mode == 3) {
                    for (int k = 0; k < 4; ++k) peak[idx[k]] = std::max(peak[idx[k]], v[k]);
                    continue;
                }
                float l[4];
                float cellMax = 0.0f, cellSum = 0.0f;
                for (int k = 0; k < 4; ++k) {
                    l[k] = std::max(static_cast<float>(v[k]) - black, 0.0f);
                    cellMax = std::max(cellMax, l[k]);
                    cellSum += l[k];
                }
                if (mode == 2) {
                    for (int k = 0; k < 4; ++k) { num[idx[k]] += l[k]; den[idx[k]] += 1.0f; }
                    continue;
                }
                if (first) {
                    // Reference frame: kept as is (burst) or weighted like the rest (HDR).
                    for (int k = 0; k < 4; ++k) ref[idx[k]] = v[k];
                }
                // Ghost test against the reference cell, scaled to this frame's exposure.
                float refSum = 0.0f, refMax = 0.0f;
                for (int k = 0; k < 4; ++k) {
                    float rl = std::max(static_cast<float>(ref[idx[k]]) - black, 0.0f);
                    refSum += rl;
                    refMax = std::max(refMax, rl);
                }
                const float zc = cellMax / range;
                float w;
                if (mode == 0) {
                    float tol = 0.03f * range + 0.18f * std::max(refSum, cellSum) * 0.25f;
                    w = (first || std::fabs(cellSum - refSum) * 0.25f <= tol) ? 1.0f : 0.0f;
                    for (int k = 0; k < 4; ++k) { num[idx[k]] += w * l[k]; den[idx[k]] += w; }
                } else {
                    // Hat weight on the whole Bayer cell so all four channels pick the same frames;
                    // saturated cells carry no information.
                    if (zc >= 0.97f) { w = 0.0f; }
                    else {
                        w = (zc < 0.5f) ? (zc + 0.02f) : (1.02f - zc);
                        w = std::max(w, 0.01f);
                        const float refSc = refSum * 0.25f * e;
                        const float curAvg = cellSum * 0.25f;
                        const float refZ = refMax / range;
                        if (!first && refZ < 0.9f && refSc > 0.005f * range) {
                            float tol = 0.03f * range * e + 0.25f * std::max(refSc, curAvg);
                            if (std::fabs(curAvg - refSc) > tol) w = 0.0f;
                        }
                    }
                    // Longer exposures are cleaner: weight by exposure, then divide it back out.
                    for (int k = 0; k < 4; ++k) { num[idx[k]] += w * l[k]; den[idx[k]] += w * e; }
                }
            }
        }
    });
    s.frames++;
    return true;
}

bool VulkanRawPipeline::stackFinish() {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    StackState& s = mStack;
    if (!mStackActive.load() || s.mode < 0 || s.frames < 1 || !mMappedRawBuffer || !mLastFrame.valid) return false;
    // The live render of the newest slot may still be reading it.
    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);

    const size_t frameBytes = static_cast<size_t>(mLastFrame.height) * static_cast<size_t>(s.stride) * 2;
    uint16_t* dst = reinterpret_cast<uint16_t*>(
        static_cast<uint8_t*>(mMappedRawBuffer) + static_cast<size_t>(mLastFrame.slot) * frameBytes);
    const int W = s.width, H = s.height, stride = s.stride;
    const float black = s.black;
    const float range = std::max(s.white - s.black, 1.0f);
    const float maxOut = s.white;
    const int mode = s.mode;
    const float* num = s.num.data();
    const float* den = s.den.data();
    const uint16_t* peak = s.peak.data();
    const uint16_t* ref = s.ref.data();

    parallelRows(H, [=](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            uint16_t* row = dst + static_cast<size_t>(y) * stride;
            const size_t o = static_cast<size_t>(y) * W;
            for (int x = 0; x < W; ++x) {
                float out;
                if (mode == 3) {
                    out = static_cast<float>(peak[o + x]);
                } else {
                    float d = den[o + x];
                    float lin;
                    if (d > 1e-6f) {
                        lin = num[o + x] / d;
                    } else {
                        // Every frame rejected or clipped here: fall back to the reference.
                        lin = std::max(static_cast<float>(ref ? ref[o + x] : 0) - black, 0.0f);
                    }
                    if (mode == 1) {
                        // Merged radiance can exceed the base frame's range: roll the top off
                        // (linear below 0.6, smooth shoulder to 0.98 of full scale).
                        float xr = lin / range;
                        if (xr > 0.6f) xr = 0.6f + 0.38f * (1.0f - std::exp(-(xr - 0.6f) / 0.38f));
                        lin = xr * range;
                    }
                    out = lin + black;
                }
                row[x] = static_cast<uint16_t>(std::min(std::max(out + 0.5f, 0.0f), maxOut));
            }
        }
    });
    mForceSingleFrame.store(true);
    LOGI("Stack finish mode %d, %d frames merged into slot %u", s.mode, s.frames, mLastFrame.slot);
    return true;
}

void VulkanRawPipeline::stackEnd() {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    mStackActive.store(false);
    mForceSingleFrame.store(false);
    mStack = StackState();
    mRingLockMask = 0;
}

void VulkanRawPipeline::setFrameMeta(int64_t timestampNs, int iso, int64_t exposureNs) {
    mPendingTimestampNs.store(timestampNs);
    mPendingIso.store(iso);
    mPendingExposureNs.store(exposureNs);
}

void VulkanRawPipeline::ringReset() {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    mRingEpoch++;
    mValidRingFrames = 0;
}

int VulkanRawPipeline::stackPrimeFromRing(uint64_t markSeq, int maxFrames) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    StackState& s = mStack;
    if (!mStackActive.load() || s.mode != 0 || s.frames != 0 || !mLastFrame.valid || markSeq == 0) return 0;
    // Keep two slots free so live frames still have somewhere to land while the merge runs.
    const int cap = std::min(maxFrames, static_cast<int>(mRingSlots) - 2);
    if (cap < 2) return 0;

    // Newest frame at or before the mark is the reference: the one on screen at the press.
    uint32_t order[kMaxRingSlots];
    int n = 0;
    for (uint32_t i = 0; i < mRingSlots; ++i) {
        const RingMeta& m = mRingMeta[i];
        if (m.seq == 0 || m.seq > markSeq || m.epoch != mRingEpoch) continue;
        if (m.camMatrixIdx != mLastFrame.camMatrixIdx || m.stride != s.stride
            || m.width < s.width || m.height < s.height) continue;
        order[n++] = i;
    }
    std::sort(order, order + n, [&](uint32_t a, uint32_t b) { return mRingMeta[a].seq > mRingMeta[b].seq; });
    if (n == 0) return 0;

    const RingMeta& ref = mRingMeta[order[0]];
    const double refProd = static_cast<double>(ref.iso) * static_cast<double>(ref.exposureNs);
    s.ringQueue.clear();
    s.ringQueue.push_back(order[0]);
    for (int k = 1; k < n && static_cast<int>(s.ringQueue.size()) < cap; ++k) {
        const RingMeta& m = mRingMeta[order[k]];
        if (ref.timestampNs > 0 && m.timestampNs > 0 && ref.timestampNs - m.timestampNs > 1'000'000'000LL) break;
        const double prod = static_cast<double>(m.iso) * static_cast<double>(m.exposureNs);
        // Burst merge has no exposure scaling: a frame more than 0.2 stop off would read as a ghost.
        if (refProd > 0.0 && prod > 0.0 && std::fabs(std::log2(prod / refProd)) > 0.2) continue;
        s.ringQueue.push_back(order[k]);
    }
    s.refProd = refProd;
    for (uint32_t slot : s.ringQueue) mRingLockMask |= (1u << slot);
    LOGI("Zero-lag: %zu ring frames from seq %llu (newest %llu), ISO %d, %lld us",
         s.ringQueue.size(), (unsigned long long) ref.seq, (unsigned long long) mRingSeq,
         ref.iso, (long long) (ref.exposureNs / 1000));
    return static_cast<int>(s.ringQueue.size());
}

int VulkanRawPipeline::stackAddFromRing() {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    StackState& s = mStack;
    if (!mStackActive.load() || s.ringQueue.empty()) return -1;
    const uint32_t slot = s.ringQueue.front();
    s.ringQueue.erase(s.ringQueue.begin());
    const RingMeta& m = mRingMeta[slot];
    bool ok = (m.epoch == mRingEpoch)
        && stackAddSlot(slot, m.width, m.height, m.stride, 1.0f);
    mRingLockMask &= ~(1u << slot);   // folded (or unusable): live frames may have it back
    if (!ok) {
        // The reference failing leaves nothing to merge against; a later frame failing just drops it.
        if (s.frames == 0) return -1;
        LOGW("Zero-lag: ring slot %u skipped", slot);
    }
    return static_cast<int>(s.ringQueue.size());
}

// =============================================================================
// JNI
// =============================================================================
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeInit(JNIEnv* env, jobject /*thiz*/, jobject surface, jobject assetManager) {
    if (!surface) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    AAssetManager* mgr = assetManager ? AAssetManager_fromJava(env, assetManager) : nullptr;

    if (gPipeline) {
        delete gPipeline;
        gPipeline = nullptr;
    }
    gPipeline = new VulkanRawPipeline();
    gPipeline->setPortraitMode(gPortraitMode);
    if (!gPipeline->init(window, mgr)) {
        delete gPipeline;
        gPipeline = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gPipeline) {
        delete gPipeline;
        gPipeline = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetFilmLook(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint stockIndex, jint lensIndex, jboolean burstEnabled)
{
    if (gPipeline) {
        gPipeline->setFilmLook(stockIndex, lensIndex, burstEnabled == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetMasterLook(JNIEnv* /*env*/, jobject /*thiz*/, jint lookIndex) {
    if (gPipeline) {
        gPipeline->setMasterLook(lookIndex);
    }
}

JNIEXPORT jint JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeGetLookCount(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(s24film::kTotalMasterLooks);
}

JNIEXPORT jstring JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeGetLookName(JNIEnv* env, jobject /*thiz*/, jint lookIndex) {
    if (lookIndex < 0 || lookIndex >= static_cast<jint>(s24film::kTotalMasterLooks)) return env->NewStringUTF("");
    return env->NewStringUTF(s24film::kMasterLooks120[lookIndex].displayName);
}

JNIEXPORT jstring JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeGetLookTag(JNIEnv* env, jobject /*thiz*/, jint lookIndex) {
    if (lookIndex < 0 || lookIndex >= static_cast<jint>(s24film::kTotalMasterLooks)) return env->NewStringUTF("");
    return env->NewStringUTF(s24film::kMasterLooks120[lookIndex].lookId);
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetDarkroomSliders(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat evStops, jfloat grainMult, jfloat halationMult)
{
    if (gPipeline) {
        gPipeline->setDarkroomSliders(evStops, grainMult, halationMult);
    }
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetCinemaParams(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint anamorphicMode, jint cinemaGate, jboolean gateWeave, jboolean stepPrint8fps)
{
    if (gPipeline) {
        gPipeline->setCinemaParams(anamorphicMode, cinemaGate,
                                   gateWeave == JNI_TRUE, stepPrint8fps == JNI_TRUE);
    }
}

JNIEXPORT jint JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeGetLookBoxIso(JNIEnv* /*env*/, jobject /*thiz*/, jint lookIndex) {
    if (lookIndex < 0 || lookIndex >= static_cast<jint>(s24film::kTotalMasterLooks)) return 0;
    return static_cast<jint>(s24film::kMasterLookBoxIso[lookIndex]);
}

JNIEXPORT jint JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeGetActiveLookIndex(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPipeline ? static_cast<jint>(gPipeline->activeLookIndex()) : -1;
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetPortraitMode(JNIEnv* /*env*/, jobject /*thiz*/, jboolean on) {
    gPortraitMode = (on == JNI_TRUE);
    if (gPipeline) gPipeline->setPortraitMode(gPortraitMode);
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetLensFxEnabled(JNIEnv* /*env*/, jobject /*thiz*/, jboolean on) {
    if (gPipeline) gPipeline->setLensFxEnabled(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetVirtualAperture(JNIEnv* /*env*/, jobject /*thiz*/, jfloat fNumber) {
    if (gPipeline) gPipeline->setVirtualAperture(fNumber);
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetFilmExposure(JNIEnv* /*env*/, jobject /*thiz*/,
                                                             jfloat digitalGain, jfloat pushStops) {
    if (gPipeline) gPipeline->setFilmExposure(digitalGain, pushStops);
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetFilmFormat(JNIEnv* /*env*/, jobject /*thiz*/, jint formatIdx)
{
    if (gPipeline) gPipeline->setFilmFormat(formatIdx);
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetForwardMatrix(
    JNIEnv* env, jobject /*thiz*/, jfloatArray fm)
{
    if (!gPipeline) return;
    if (fm == nullptr || env->GetArrayLength(fm) < 9) {
        gPipeline->setForwardMatrix(nullptr);
        return;
    }
    float v[9];
    env->GetFloatArrayRegion(fm, 0, 9, v);
    gPipeline->setForwardMatrix(v);
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetCaptureState(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint iso, jlong exposureNs, jfloat focusDiopters, jfloat noiseS, jfloat noiseO, jfloat blackLevel)
{
    if (gPipeline) {
        gPipeline->setCaptureState(iso, static_cast<int64_t>(exposureNs), focusDiopters, noiseS, noiseO, blackLevel);
    }
}

JNIEXPORT jfloat JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeGetLastRawMean(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPipeline ? gPipeline->lastRawMean() : 0.0f;
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeGetMeterZones(JNIEnv* env, jobject /*thiz*/, jfloatArray out) {
    if (!gPipeline || !out || env->GetArrayLength(out) < 26) return;
    float z[26];
    gPipeline->meterZones(z);
    env->SetFloatArrayRegion(out, 0, 26, z);
}

JNIEXPORT jboolean JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetEncoderSurface(
    JNIEnv* env, jobject /*thiz*/, jobject encoderSurface)
{
    if (!gPipeline) return JNI_FALSE;
    if (!encoderSurface) {
        gPipeline->setEncoderWindow(nullptr);
        return JNI_TRUE;
    }
    ANativeWindow* encWindow = ANativeWindow_fromSurface(env, encoderSurface);
    if (!encWindow) return JNI_FALSE;
    return gPipeline->setEncoderWindow(encWindow) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetEncoderHlg(JNIEnv* /*env*/, jobject /*thiz*/, jboolean hlg) {
    if (gPipeline) gPipeline->setEncoderHlg(hlg == JNI_TRUE);
}

JNIEXPORT jstring JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeGetEncoderInfo(JNIEnv* env, jobject /*thiz*/) {
    std::string info = gPipeline ? gPipeline->encoderInfo() : std::string("off");
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jfloat JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeRenderRaw(
    JNIEnv* env, jobject /*thiz*/,
    jobject hardwareBufferObj,
    jint width, jint height, jint stride,
    jint cfa, jfloat whiteLevel,
    jfloat gainR, jfloat gainG, jfloat gainB,
    jint sensorOrientation, jint camMatrixIdx)
{
    if (!gPipeline || !hardwareBufferObj) return 0.0f;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObj);
    if (!ahb) return 0.0f;
    return gPipeline->renderRawFrame(
        ahb, width, height, stride, cfa, whiteLevel,
        gainR, gainG, gainB, sensorOrientation, camMatrixIdx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeCaptureLastRgba(
    JNIEnv* env, jobject /*thiz*/, jintArray outDims)
{
    if (!gPipeline || !outDims) return nullptr;
    std::vector<uint8_t> rgba;
    int w = 0, h = 0;
    if (!gPipeline->copyLastRenderedRgba(rgba, w, h)) return nullptr;
    jint dims[2] = {w, h};
    env->SetIntArrayRegion(outDims, 0, 2, dims);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(rgba.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(rgba.size()), reinterpret_cast<const jbyte*>(rgba.data()));
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeRenderMasterStill(
    JNIEnv* env, jobject /*thiz*/, jintArray outDims)
{
    if (!gPipeline || !outDims) return nullptr;
    std::vector<uint8_t> rgba;
    int w = 0, h = 0;
    if (!gPipeline->renderMasterStill(rgba, w, h)) return nullptr;
    jint dims[2] = {w, h};
    env->SetIntArrayRegion(outDims, 0, 2, dims);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(rgba.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(rgba.size()), reinterpret_cast<const jbyte*>(rgba.data()));
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeMasterBegin(JNIEnv* env, jobject /*thiz*/, jint dstW, jint dstH) {
    if (!gPipeline) return nullptr;
    std::vector<uint8_t> rgb;
    if (!gPipeline->masterBegin(dstW, dstH, rgb)) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(rgb.size()));
    if (!arr) {
        // No Java copy to run depth on: finish the render without it so the pending state clears.
        std::vector<uint8_t> dummy;
        int w = 0, h = 0;
        gPipeline->masterFinish(nullptr, nullptr, 0, 0, 0.0f, dummy, w, h);
        return nullptr;
    }
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<const jbyte*>(rgb.data()));
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeMasterFinish(
    JNIEnv* env, jobject /*thiz*/, jfloatArray disparity, jfloatArray matteArr, jint dw, jint dh, jfloat focusDisp, jintArray outDims)
{
    if (!gPipeline || !outDims) return nullptr;
    std::vector<float> disp;
    if (disparity && dw > 0 && dh > 0 && env->GetArrayLength(disparity) >= dw * dh) {
        disp.resize(static_cast<size_t>(dw) * dh);
        env->GetFloatArrayRegion(disparity, 0, dw * dh, disp.data());
    }
    std::vector<float> mat;
    if (!disp.empty() && matteArr && env->GetArrayLength(matteArr) >= dw * dh) {
        mat.resize(disp.size());
        env->GetFloatArrayRegion(matteArr, 0, dw * dh, mat.data());
    }
    std::vector<uint8_t> rgba;
    int w = 0, h = 0;
    if (!gPipeline->masterFinish(disp.empty() ? nullptr : disp.data(), mat.empty() ? nullptr : mat.data(), dw, dh, focusDisp, rgba, w, h)) return nullptr;
    jint dims[2] = {w, h};
    env->SetIntArrayRegion(outDims, 0, 2, dims);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(rgba.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(rgba.size()), reinterpret_cast<const jbyte*>(rgba.data()));
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeGetMasterGainmap(
    JNIEnv* env, jobject /*thiz*/, jintArray outDims)
{
    if (!gPipeline || !outDims) return nullptr;
    std::vector<uint8_t> gain;
    int w = 0, h = 0;
    if (!gPipeline->copyMasterGainmap(gain, w, h)) return nullptr;
    jint dims[2] = {w, h};
    env->SetIntArrayRegion(outDims, 0, 2, dims);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(gain.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(gain.size()), reinterpret_cast<const jbyte*>(gain.data()));
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeStackBegin(JNIEnv* /*env*/, jobject /*thiz*/, jint mode) {
    return (gPipeline && gPipeline->stackBegin(mode)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeStackAdd(JNIEnv* /*env*/, jobject /*thiz*/, jfloat relExposure) {
    return (gPipeline && gPipeline->stackAdd(relExposure)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeStackFinish(JNIEnv* /*env*/, jobject /*thiz*/) {
    return (gPipeline && gPipeline->stackFinish()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeStackEnd(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gPipeline) gPipeline->stackEnd();
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeSetFrameMeta(JNIEnv* /*env*/, jobject /*thiz*/,
    jlong timestampNs, jint iso, jlong exposureNs) {
    if (gPipeline) gPipeline->setFrameMeta(timestampNs, iso, exposureNs);
}

JNIEXPORT jlong JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeRingMark(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPipeline ? static_cast<jlong>(gPipeline->ringMark()) : 0;
}

JNIEXPORT void JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeRingReset(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gPipeline) gPipeline->ringReset();
}

JNIEXPORT jint JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeRingSlots(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPipeline ? static_cast<jint>(gPipeline->ringSlots()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeStackPrimeFromRing(JNIEnv* /*env*/, jobject /*thiz*/,
    jlong markSeq, jint maxFrames) {
    return gPipeline ? gPipeline->stackPrimeFromRing(static_cast<uint64_t>(markSeq), maxFrames) : 0;
}

JNIEXPORT jint JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeStackAddFromRing(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPipeline ? gPipeline->stackAddFromRing() : -1;
}

} // extern "C"
