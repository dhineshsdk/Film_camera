#include "vulkan_raw_pipeline.h"
#include "GeneratedFilmDatabase.h"
#include "shaders/demosaic_spv.h"

#include <cstring>
#include <algorithm>
#include <cmath>

static VulkanRawPipeline* gPipeline = nullptr;

VulkanRawPipeline::VulkanRawPipeline() = default;

VulkanRawPipeline::~VulkanRawPipeline() {
    destroy();
}

void VulkanRawPipeline::setFilmLook(int stockIndex, int lensIndex, bool burstEnabled) {
    mActiveStockIdx.store(std::clamp(stockIndex, 0, s24film::kNumFeaturedStocks - 1));
    mActiveLensIdx.store(std::clamp(lensIndex, 0, 4));
    mBurstModeEnabled.store(burstEnabled);
}

uint32_t VulkanRawPipeline::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(mPhysicalDevice, &memProperties);

    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) &&
            (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    LOGE("Failed to find suitable memory type!");
    return 0;
}

bool VulkanRawPipeline::createInstance() {
    VkApplicationInfo appInfo = {};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "S24Film";
    appInfo.applicationVersion = VK_MAKE_VERSION(2, 0, 0);
    appInfo.pEngineName = "S24VulkanISP";
    appInfo.engineVersion = VK_MAKE_VERSION(2, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_3;

    std::vector<const char*> extensions = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME
    };

    VkInstanceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();

    VkResult res = vkCreateInstance(&createInfo, nullptr, &mInstance);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateInstance failed: %d", res);
        return false;
    }
    return true;
}

bool VulkanRawPipeline::selectPhysicalDevice() {
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, nullptr);
    if (deviceCount == 0) return false;

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, devices.data());

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
                return true;
            }
        }
    }
    mPhysicalDevice = devices[0];
    mQueueFamilyIndex = 0;
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

    mSwapchainFormat = VK_FORMAT_R8G8B8A8_UNORM;
    for (const auto& fmt : formats) {
        if (fmt.format == VK_FORMAT_R8G8B8A8_UNORM || fmt.format == VK_FORMAT_B8G8R8A8_UNORM) {
            mSwapchainFormat = fmt.format;
            break;
        }
    }

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
    swapchainCreateInfo.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    swapchainCreateInfo.imageExtent = mSwapchainExtent;
    swapchainCreateInfo.imageArrayLayers = 1;
    swapchainCreateInfo.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    swapchainCreateInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swapchainCreateInfo.preTransform = capabilities.currentTransform;
    swapchainCreateInfo.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    swapchainCreateInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    swapchainCreateInfo.clipped = VK_TRUE;

    if (vkCreateSwapchainKHR(mDevice, &swapchainCreateInfo, nullptr, &mSwapchain) != VK_SUCCESS) {
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

bool VulkanRawPipeline::createComputeResources() {
    mStorageImageExtent = mSwapchainExtent;

    VkImageCreateInfo imageInfo = {};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {mStorageImageExtent.width, mStorageImageExtent.height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(mDevice, &imageInfo, nullptr, &mStorageImage) != VK_SUCCESS) return false;

    VkMemoryRequirements memReqs;
    vkGetImageMemoryRequirements(mDevice, mStorageImage, &memReqs);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

    if (vkAllocateMemory(mDevice, &allocInfo, nullptr, &mStorageImageMemory) != VK_SUCCESS) return false;
    vkBindImageMemory(mDevice, mStorageImage, mStorageImageMemory, 0);

    VkImageViewCreateInfo viewInfo = {};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = mStorageImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;
    vkCreateImageView(mDevice, &viewInfo, nullptr, &mStorageImageView);

    // 2. 4-Frame Temporal Ring Buffer (104 MB for 4 x 25 MB RAW_SENSOR frames)
    mRawStorageBufferSize = 104 * 1024 * 1024;
    VkBufferCreateInfo bufInfo = {};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = mRawStorageBufferSize;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(mDevice, &bufInfo, nullptr, &mRawStorageBuffer) != VK_SUCCESS) return false;

    VkMemoryRequirements bufReqs;
    vkGetBufferMemoryRequirements(mDevice, mRawStorageBuffer, &bufReqs);

    VkMemoryAllocateInfo bufAlloc = {};
    bufAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    bufAlloc.allocationSize = bufReqs.size;
    bufAlloc.memoryTypeIndex = findMemoryType(
        bufReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    if (vkAllocateMemory(mDevice, &bufAlloc, nullptr, &mRawStorageBufferMemory) != VK_SUCCESS) return false;
    vkBindBufferMemory(mDevice, mRawStorageBuffer, mRawStorageBufferMemory, 0);
    vkMapMemory(mDevice, mRawStorageBufferMemory, 0, mRawStorageBufferSize, 0, &mMappedRawBuffer);

    // 3. Readback Buffer for saving Master Graded JPEG alongside DNG
    mReadbackBufferSize = static_cast<VkDeviceSize>(mStorageImageExtent.width) *
                          static_cast<VkDeviceSize>(mStorageImageExtent.height) * 4;
    VkBufferCreateInfo rbInfo = {};
    rbInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    rbInfo.size = mReadbackBufferSize;
    rbInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    rbInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(mDevice, &rbInfo, nullptr, &mReadbackBuffer) == VK_SUCCESS) {
        VkMemoryRequirements rbReqs;
        vkGetBufferMemoryRequirements(mDevice, mReadbackBuffer, &rbReqs);
        VkMemoryAllocateInfo rbAlloc = {};
        rbAlloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        rbAlloc.allocationSize = rbReqs.size;
        rbAlloc.memoryTypeIndex = findMemoryType(
            rbReqs.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (vkAllocateMemory(mDevice, &rbAlloc, nullptr, &mReadbackBufferMemory) == VK_SUCCESS) {
            vkBindBufferMemory(mDevice, mReadbackBuffer, mReadbackBufferMemory, 0);
            vkMapMemory(mDevice, mReadbackBufferMemory, 0, mReadbackBufferSize, 0, &mMappedReadbackBuffer);
        }
    }

    // 4. Descriptor Set Layout
    VkDescriptorSetLayoutBinding bindings[2] = {};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 2;
    layoutInfo.pBindings = bindings;

    if (vkCreateDescriptorSetLayout(mDevice, &layoutInfo, nullptr, &mDescriptorSetLayout) != VK_SUCCESS) return false;

    VkDescriptorPoolSize poolSizes[2] = {};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 1;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    vkCreateDescriptorPool(mDevice, &poolInfo, nullptr, &mDescriptorPool);

    VkDescriptorSetAllocateInfo setAlloc = {};
    setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAlloc.descriptorPool = mDescriptorPool;
    setAlloc.descriptorSetCount = 1;
    setAlloc.pSetLayouts = &mDescriptorSetLayout;
    vkAllocateDescriptorSets(mDevice, &setAlloc, &mDescriptorSet);

    VkDescriptorBufferInfo dbi = {};
    dbi.buffer = mRawStorageBuffer;
    dbi.offset = 0;
    dbi.range = VK_WHOLE_SIZE;

    VkDescriptorImageInfo dii = {};
    dii.imageView = mStorageImageView;
    dii.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet writes[2] = {};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = mDescriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &dbi;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = mDescriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &dii;
    vkUpdateDescriptorSets(mDevice, 2, writes, 0, nullptr);

    // 5. Pipeline Layout (112-Byte PushConstants)
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

    // 6. Shader Module & Wave32 Compute Pipeline
    VkShaderModuleCreateInfo smInfo = {};
    smInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smInfo.codeSize = demosaic_spv_size;
    smInfo.pCode = demosaic_spv;

    VkShaderModule shaderModule = VK_NULL_HANDLE;
    if (vkCreateShaderModule(mDevice, &smInfo, nullptr, &shaderModule) != VK_SUCCESS) return false;

    VkPipelineShaderStageRequiredSubgroupSizeCreateInfo reqSubgroup = {};
    reqSubgroup.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO;
    reqSubgroup.requiredSubgroupSize = 32; // Enforce Wave32 on Xclipse 940

    VkComputePipelineCreateInfo compPipelineInfo = {};
    compPipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    compPipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    compPipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    compPipelineInfo.stage.module = shaderModule;
    compPipelineInfo.stage.pName = "main";
    compPipelineInfo.stage.pNext = &reqSubgroup;
    compPipelineInfo.stage.flags = VK_PIPELINE_SHADER_STAGE_CREATE_REQUIRE_FULL_SUBGROUPS_BIT;
    compPipelineInfo.layout = mPipelineLayout;

    VkResult res = vkCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &compPipelineInfo, nullptr, &mComputePipeline);
    if (res != VK_SUCCESS) {
        compPipelineInfo.stage.pNext = nullptr;
        compPipelineInfo.stage.flags = 0;
        res = vkCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &compPipelineInfo, nullptr, &mComputePipeline);
        if (res != VK_SUCCESS) {
            vkDestroyShaderModule(mDevice, shaderModule, nullptr);
            return false;
        }
    }
    vkDestroyShaderModule(mDevice, shaderModule, nullptr);

    VkCommandPoolCreateInfo cmdPoolInfo = {};
    cmdPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cmdPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cmdPoolInfo.queueFamilyIndex = mQueueFamilyIndex;
    vkCreateCommandPool(mDevice, &cmdPoolInfo, nullptr, &mCommandPool);

    VkCommandBufferAllocateInfo cmdAlloc = {};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = mCommandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    vkAllocateCommandBuffers(mDevice, &cmdAlloc, &mCommandBuffer);

    VkSemaphoreCreateInfo semInfo = {};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    vkCreateSemaphore(mDevice, &semInfo, nullptr, &mImageAvailableSemaphore);
    vkCreateSemaphore(mDevice, &semInfo, nullptr, &mRenderFinishedSemaphore);

    VkFenceCreateInfo fenceInfo = {};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    vkCreateFence(mDevice, &fenceInfo, nullptr, &mInFlightFence);

    LOGI("Phase 2 + Phase 3 Wave32 Vulkan Pipeline Initialized!");
    return true;
}

void VulkanRawPipeline::destroyComputeResources() {
    if (mInFlightFence != VK_NULL_HANDLE) { vkDestroyFence(mDevice, mInFlightFence, nullptr); mInFlightFence = VK_NULL_HANDLE; }
    if (mRenderFinishedSemaphore != VK_NULL_HANDLE) { vkDestroySemaphore(mDevice, mRenderFinishedSemaphore, nullptr); mRenderFinishedSemaphore = VK_NULL_HANDLE; }
    if (mImageAvailableSemaphore != VK_NULL_HANDLE) { vkDestroySemaphore(mDevice, mImageAvailableSemaphore, nullptr); mImageAvailableSemaphore = VK_NULL_HANDLE; }
    if (mCommandPool != VK_NULL_HANDLE) { vkDestroyCommandPool(mDevice, mCommandPool, nullptr); mCommandPool = VK_NULL_HANDLE; }
    if (mComputePipeline != VK_NULL_HANDLE) { vkDestroyPipeline(mDevice, mComputePipeline, nullptr); mComputePipeline = VK_NULL_HANDLE; }
    if (mPipelineLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(mDevice, mPipelineLayout, nullptr); mPipelineLayout = VK_NULL_HANDLE; }
    if (mDescriptorPool != VK_NULL_HANDLE) { vkDestroyDescriptorPool(mDevice, mDescriptorPool, nullptr); mDescriptorPool = VK_NULL_HANDLE; }
    if (mDescriptorSetLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(mDevice, mDescriptorSetLayout, nullptr); mDescriptorSetLayout = VK_NULL_HANDLE; }
    if (mMappedReadbackBuffer) { vkUnmapMemory(mDevice, mReadbackBufferMemory); mMappedReadbackBuffer = nullptr; }
    if (mReadbackBufferMemory != VK_NULL_HANDLE) { vkFreeMemory(mDevice, mReadbackBufferMemory, nullptr); mReadbackBufferMemory = VK_NULL_HANDLE; }
    if (mReadbackBuffer != VK_NULL_HANDLE) { vkDestroyBuffer(mDevice, mReadbackBuffer, nullptr); mReadbackBuffer = VK_NULL_HANDLE; }
    if (mMappedRawBuffer) { vkUnmapMemory(mDevice, mRawStorageBufferMemory); mMappedRawBuffer = nullptr; }
    if (mRawStorageBufferMemory != VK_NULL_HANDLE) { vkFreeMemory(mDevice, mRawStorageBufferMemory, nullptr); mRawStorageBufferMemory = VK_NULL_HANDLE; }
    if (mRawStorageBuffer != VK_NULL_HANDLE) { vkDestroyBuffer(mDevice, mRawStorageBuffer, nullptr); mRawStorageBuffer = VK_NULL_HANDLE; }
    if (mStorageImageView != VK_NULL_HANDLE) { vkDestroyImageView(mDevice, mStorageImageView, nullptr); mStorageImageView = VK_NULL_HANDLE; }
    if (mStorageImageMemory != VK_NULL_HANDLE) { vkFreeMemory(mDevice, mStorageImageMemory, nullptr); mStorageImageMemory = VK_NULL_HANDLE; }
    if (mStorageImage != VK_NULL_HANDLE) { vkDestroyImage(mDevice, mStorageImage, nullptr); mStorageImage = VK_NULL_HANDLE; }
}

bool VulkanRawPipeline::init(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    mWindow = window;
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

float VulkanRawPipeline::renderRawFrame(
    AHardwareBuffer* ahb, int width, int height, int stride,
    int cfa, float whiteLevel, float gainR, float gainG, float gainB,
    int sensorOrientation, int camMatrixIdx)
{
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (!mDevice || !mSwapchain || !ahb) return 0.0f;

    auto startTime = std::chrono::high_resolution_clock::now();

    // Reset temporal ring buffer if sensor resolution changed (e.g., switching lenses)
    if (width != mLastWidth || height != mLastHeight) {
        mLastWidth = width;
        mLastHeight = height;
        mValidRingFrames = 0;
        mRingSlot = 0;
    }

    // 1. Copy incoming frame into slot 0 (and rotate older frames in slots 1..3 for 4-frame burst merge)
    void* rawAddress = nullptr;
    int lockResult = AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &rawAddress);
    if (lockResult == 0 && rawAddress != nullptr && mMappedRawBuffer != nullptr) {
        size_t frameBytes = static_cast<size_t>(height) * static_cast<size_t>(stride) * 2;
        if (frameBytes * 4 <= mRawStorageBufferSize) {
            uint8_t* basePtr = static_cast<uint8_t*>(mMappedRawBuffer);
            // Copy previous slot 0 into revolving history slot (1..3)
            if (mValidRingFrames > 0) {
                uint32_t histSlot = 1u + (mRingSlot % 3u);
                std::memcpy(basePtr + histSlot * frameBytes, basePtr, frameBytes);
                mRingSlot++;
            }
            // Write newest frame to slot 0 (reference frame)
            std::memcpy(basePtr, rawAddress, frameBytes);
            if (mValidRingFrames < 4u) mValidRingFrames++;
        }
        AHardwareBuffer_unlock(ahb, nullptr);
    } else {
        return 0.0f;
    }

    // 2. Acquire Swapchain Image
    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);
    vkResetFences(mDevice, 1, &mInFlightFence);

    uint32_t imageIndex = 0;
    if (vkAcquireNextImageKHR(mDevice, mSwapchain, UINT64_MAX,
                              mImageAvailableSemaphore, VK_NULL_HANDLE, &imageIndex) != VK_SUCCESS) {
        return 0.0f;
    }

    vkResetCommandBuffer(mCommandBuffer, 0);
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vkBeginCommandBuffer(mCommandBuffer, &beginInfo);

    VkImageMemoryBarrier barrierToGeneral = {};
    barrierToGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrierToGeneral.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrierToGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrierToGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrierToGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrierToGeneral.image = mStorageImage;
    barrierToGeneral.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrierToGeneral.subresourceRange.baseMipLevel = 0;
    barrierToGeneral.subresourceRange.levelCount = 1;
    barrierToGeneral.subresourceRange.baseArrayLayer = 0;
    barrierToGeneral.subresourceRange.layerCount = 1;
    barrierToGeneral.srcAccessMask = 0;
    barrierToGeneral.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;

    vkCmdPipelineBarrier(mCommandBuffer,
                         VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &barrierToGeneral);

    vkCmdBindPipeline(mCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, mComputePipeline);
    vkCmdBindDescriptorSets(mCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            mPipelineLayout, 0, 1, &mDescriptorSet, 0, nullptr);

    // 3. Populate 112-byte PushConstants from active StockPreset & LensPreset
    int stockIdx = mActiveStockIdx.load();
    const auto& st = s24film::kFeaturedStocks[stockIdx];

    PushConstants pc = {};
    pc.width = static_cast<uint32_t>(width);
    pc.height = static_cast<uint32_t>(height);
    pc.stride = static_cast<uint32_t>(stride);
    pc.cfa = static_cast<uint32_t>(cfa);
    pc.whiteLevel = whiteLevel;
    pc.gainR = gainR;
    pc.gainG = gainG;
    pc.gainB = gainB;
    pc.sensorOrientation = static_cast<uint32_t>(sensorOrientation);
    pc.stockType = static_cast<uint32_t>(st.stockType);
    pc.lensType = static_cast<uint32_t>(mActiveLensIdx.load());
    pc.frameSeed = ++mFrameCounter;
    pc.dMaxR = st.dMaxR;
    pc.dMaxG = st.dMaxG;
    pc.dMaxB = st.dMaxB;
    pc.nu = st.nu;
    pc.gammaR = st.gammaR;
    pc.gammaG = st.gammaG;
    pc.gammaB = st.gammaB;
    pc.dirSatBoost = st.dirSatBoost;
    pc.printGammaR = st.printGammaR;
    pc.printGammaG = st.printGammaG;
    pc.printGammaB = st.printGammaB;
    pc.grainStrength = st.grainStrength;
    pc.halationStrength = st.halationStrength;
    pc.exposureScale = 2.4f;
    pc.camMatrixIdx = static_cast<uint32_t>(camMatrixIdx);
    pc.burstCount = (mBurstModeEnabled.load() && mValidRingFrames >= 4u) ? 4u : 1u;

    vkCmdPushConstants(mCommandBuffer, mPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(PushConstants), &pc);

    uint32_t groupX = (mStorageImageExtent.width + 15) / 16;
    uint32_t groupY = (mStorageImageExtent.height + 15) / 16;
    vkCmdDispatch(mCommandBuffer, groupX, groupY, 1);

    VkImageMemoryBarrier barrierToSrc = barrierToGeneral;
    barrierToSrc.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrierToSrc.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrierToSrc.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrierToSrc.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;

    VkImageMemoryBarrier barrierToDst = {};
    barrierToDst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrierToDst.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrierToDst.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrierToDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrierToDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrierToDst.image = mSwapchainImages[imageIndex];
    barrierToDst.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrierToDst.subresourceRange.baseMipLevel = 0;
    barrierToDst.subresourceRange.levelCount = 1;
    barrierToDst.subresourceRange.baseArrayLayer = 0;
    barrierToDst.subresourceRange.layerCount = 1;
    barrierToDst.srcAccessMask = 0;
    barrierToDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;

    VkImageMemoryBarrier preBlitBarriers[2] = {barrierToSrc, barrierToDst};
    vkCmdPipelineBarrier(mCommandBuffer,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 2, preBlitBarriers);

    VkImageBlit blitRegion = {};
    blitRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blitRegion.srcSubresource.layerCount = 1;
    blitRegion.srcOffsets[0] = {0, 0, 0};
    blitRegion.srcOffsets[1] = {static_cast<int32_t>(mStorageImageExtent.width),
                                static_cast<int32_t>(mStorageImageExtent.height), 1};
    blitRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blitRegion.dstSubresource.layerCount = 1;
    blitRegion.dstOffsets[0] = {0, 0, 0};
    blitRegion.dstOffsets[1] = {static_cast<int32_t>(mSwapchainExtent.width),
                                static_cast<int32_t>(mSwapchainExtent.height), 1};

    vkCmdBlitImage(mCommandBuffer,
                   mStorageImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   mSwapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   1, &blitRegion, VK_FILTER_LINEAR);

    // Also copy rendered Storage Image into mReadbackBuffer so shutter button can save Master JPEG!
    if (mReadbackBuffer != VK_NULL_HANDLE) {
        VkBufferImageCopy copyRegion = {};
        copyRegion.bufferOffset = 0;
        copyRegion.bufferRowLength = 0;
        copyRegion.bufferImageHeight = 0;
        copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.imageSubresource.mipLevel = 0;
        copyRegion.imageSubresource.baseArrayLayer = 0;
        copyRegion.imageSubresource.layerCount = 1;
        copyRegion.imageOffset = {0, 0, 0};
        copyRegion.imageExtent = {mStorageImageExtent.width, mStorageImageExtent.height, 1};
        vkCmdCopyImageToBuffer(mCommandBuffer, mStorageImage,
                               VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               mReadbackBuffer, 1, &copyRegion);
    }

    VkImageMemoryBarrier barrierToPresent = barrierToDst;
    barrierToPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrierToPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    barrierToPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrierToPresent.dstAccessMask = 0;

    vkCmdPipelineBarrier(mCommandBuffer,
                         VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &barrierToPresent);

    vkEndCommandBuffer(mCommandBuffer);

    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_TRANSFER_BIT};
    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = &mImageAvailableSemaphore;
    submitInfo.pWaitDstStageMask = waitStages;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &mCommandBuffer;
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = &mRenderFinishedSemaphore;

    vkQueueSubmit(mQueue, 1, &submitInfo, mInFlightFence);

    VkPresentInfoKHR presentInfo = {};
    presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = &mRenderFinishedSemaphore;
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = &mSwapchain;
    presentInfo.pImageIndices = &imageIndex;

    vkQueuePresentKHR(mQueue, &presentInfo);

    auto endTime = std::chrono::high_resolution_clock::now();
    std::chrono::duration<float, std::milli> elapsed = endTime - startTime;
    return elapsed.count();
}

bool VulkanRawPipeline::copyLastRenderedRgba(std::vector<uint8_t>& outRgba, int& outW, int& outH) {
    std::lock_guard<std::mutex> lock(mRenderMutex);
    if (!mDevice || !mMappedReadbackBuffer || mStorageImageExtent.width == 0) return false;
    vkWaitForFences(mDevice, 1, &mInFlightFence, VK_TRUE, UINT64_MAX);
    outW = static_cast<int>(mStorageImageExtent.width);
    outH = static_cast<int>(mStorageImageExtent.height);
    size_t numBytes = static_cast<size_t>(outW) * static_cast<size_t>(outH) * 4;
    outRgba.resize(numBytes);
    std::memcpy(outRgba.data(), mMappedReadbackBuffer, numBytes);
    return true;
}

// -----------------------------------------------------------------------------
// JNI Export Functions
// -----------------------------------------------------------------------------
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_s24film_camera_VulkanRenderer_nativeInit(JNIEnv* env, jobject /*thiz*/, jobject surface) {
    if (!surface) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    if (gPipeline) {
        delete gPipeline;
        gPipeline = nullptr;
    }
    gPipeline = new VulkanRawPipeline();
    if (!gPipeline->init(window)) {
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

} // extern "C"
