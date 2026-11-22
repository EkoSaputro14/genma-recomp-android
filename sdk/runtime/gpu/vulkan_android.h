#pragma once
// Android Vulkan 1.0+ Fallback capability probe and shader loader.
// Eliminates desktop posix_spawn("dxc") calls blocked by Android SELinux W^X policies.

#include <vulkan/vulkan.h>
#include <cstdint>
#include <string>
#include <vector>

namespace gpu_android {

struct VulkanCapabilities {
    uint32_t negotiatedApiVersion = VK_API_VERSION_1_0;
    bool isVulkan10Fallback = true;
    bool supportsAnisotropy = false;
    bool supportsDepthClamp = false;
    bool supportsBcTextures = false;
    bool supportsAstcTextures = false;
    bool supportsFillModeNonSolid = false;
    uint32_t maxImageDimension2D = 4096;
    std::string deviceName;
    uint32_t vendorId = 0;
};

inline uint32_t NegotiateAndroidVulkanVersion() {
    uint32_t instanceVersion = VK_API_VERSION_1_0;
    auto enumerateVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion"));
    if (enumerateVersion) {
        enumerateVersion(&instanceVersion);
    }
#if 1
    // Clamp to Vulkan 1.0 for maximum compatibility across Android 10 (API 29) Adreno/Mali drivers
    return VK_API_VERSION_1_0;
#else
    return instanceVersion >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;
#endif
}

inline VulkanCapabilities QueryDeviceCapabilities(VkPhysicalDevice gpu) {
    VulkanCapabilities caps{};
    VkPhysicalDeviceProperties props{};
    VkPhysicalDeviceFeatures feats{};
    vkGetPhysicalDeviceProperties(gpu, &props);
    vkGetPhysicalDeviceFeatures(gpu, &feats);

    caps.deviceName = props.deviceName;
    caps.vendorId = props.vendorID;
    caps.maxImageDimension2D = props.limits.maxImageDimension2D;
    caps.supportsAnisotropy = feats.samplerAnisotropy == VK_TRUE;
    caps.supportsDepthClamp = feats.depthClamp == VK_TRUE;
    caps.supportsBcTextures = feats.textureCompressionBC == VK_TRUE;
    caps.supportsAstcTextures = feats.textureCompressionASTC_LDR == VK_TRUE;
    caps.supportsFillModeNonSolid = feats.fillModeNonSolid == VK_TRUE;

    const uint32_t target = NegotiateAndroidVulkanVersion();
    caps.negotiatedApiVersion = props.apiVersion < target ? props.apiVersion : target;
    caps.isVulkan10Fallback = (caps.negotiatedApiVersion < VK_API_VERSION_1_1);
    return caps;
}

inline VkPhysicalDeviceFeatures BuildSafeDeviceFeatures(const VulkanCapabilities& caps) {
    VkPhysicalDeviceFeatures enabled{};
    enabled.samplerAnisotropy = caps.supportsAnisotropy ? VK_TRUE : VK_FALSE;
    enabled.depthClamp = caps.supportsDepthClamp ? VK_TRUE : VK_FALSE;
    enabled.textureCompressionBC = caps.supportsBcTextures ? VK_TRUE : VK_FALSE;
    enabled.fillModeNonSolid = caps.supportsFillModeNonSolid ? VK_TRUE : VK_FALSE;
    return enabled;
}

constexpr bool kUsePrecompiledSpirvCache = true;

} // namespace gpu_android
