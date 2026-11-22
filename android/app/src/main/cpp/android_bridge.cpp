#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <string>

#include "xdk/fiber_android.h"
#include "cpu/arm64_compat.h"
#include "gpu/vulkan_android.h"
#include "core/touch_overlay.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GenmaAndroid", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GenmaAndroid", __VA_ARGS__)

namespace mem_android {
bool InitGuestMemory();
bool VerifyPhysicalAlias();
void ShutdownGuestMemory();
}

namespace app_android {
void ToggleTouchMenu();
bool IsTouchMenuOpen();
}

namespace gpu_android {
class MobileHdTextureCache;
}

namespace {
void* g_recompiledGameLib = nullptr;
std::string g_lastStatus = "Runtime siap. Pilih folder game/libgenma_game.so hasil build PC.";
bool g_memoryOk = false;
bool g_aliasOk = false;
bool g_fiberOk = false;
std::string g_gpuName = "Belum diinisialisasi";
uint32_t g_vkApiVersion = VK_API_VERSION_1_0;

void ProbeVulkanDevice() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Genma Onimusha";
    appInfo.applicationVersion = VK_MAKE_VERSION(0, 7, 0);
    appInfo.pEngineName = "GenmaRecompAndroid";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = gpu_android::NegotiateAndroidVulkanVersion();

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&createInfo, nullptr, &instance) != VK_SUCCESS) {
        g_gpuName = "Vulkan tidak tersedia pada perangkat ini";
        return;
    }

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (count > 0) {
        std::vector<VkPhysicalDevice> gpus(count);
        vkEnumeratePhysicalDevices(instance, &count, gpus.data());
        auto caps = gpu_android::QueryDeviceCapabilities(gpus[0]);
        g_gpuName = caps.deviceName;
        g_vkApiVersion = caps.negotiatedApiVersion;
    }
    vkDestroyInstance(instance, nullptr);
}

static bool g_fiberEntered = false;
static void* g_mainFiber = nullptr;

void WINAPI TestFiberEntry(void*) {
    g_fiberEntered = true;
    SwitchToFiber(g_mainFiber);
}

bool SelfTestArm64Fiber() {
    g_mainFiber = ConvertThreadToFiber(nullptr);
    void* child = CreateFiberEx(0, 128 * 1024, 0, TestFiberEntry, nullptr);
    if (!child) return false;
    g_fiberEntered = false;
    SwitchToFiber(child);
    DeleteFiber(child);
    return g_fiberEntered;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeRunSelfCheck(JNIEnv* env, jobject) {
    g_memoryOk = mem_android::InitGuestMemory();
    g_aliasOk = g_memoryOk && mem_android::VerifyPhysicalAlias();
    g_fiberOk = SelfTestArm64Fiber();
    ProbeVulkanDevice();

    input_android::OverlaySettings overlay{};
    input_android::InitOverlay(overlay);

    const uint64_t ticks = arm64_compat::ReadCycleCounter();
    const uint32_t vkMajor = VK_VERSION_MAJOR(g_vkApiVersion);
    const uint32_t vkMinor = VK_VERSION_MINOR(g_vkApiVersion);

    std::string report =
        "ABI: arm64-v8a | API 29+\n"
        "ARM64 Bionic Fiber: " + std::string(g_fiberOk ? "OK" : "GAGAL") + "\n"
        "Guest 4 GiB Virtual Space: " + std::string(g_memoryOk ? "OK" : "GAGAL") + "\n"
        "Xbox Physical Alias (0x80000000/0xF0000000): " + std::string(g_aliasOk ? "OK" : "GAGAL") + "\n"
        "Vulkan GPU: " + g_gpuName + " (v" + std::to_string(vkMajor) + "." + std::to_string(vkMinor) + ")\n"
        "HD Texture Budget: 512 MB\n"
        "ARM64 Timer Ticks: " + std::to_string(ticks) + "\n"
        "Status: " + g_lastStatus;

    LOGI("Self-check completed: fiber=%d mem=%d alias=%d gpu=%s", g_fiberOk, g_memoryOk, g_aliasOk, g_gpuName.c_str());
    return env->NewStringUTF(report.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeLoadGameLibrary(
    JNIEnv* env, jobject, jstring soPath) {
    if (!soPath) return JNI_FALSE;
    const char* pathStr = env->GetStringUTFChars(soPath, nullptr);
    if (!pathStr) return JNI_FALSE;

    if (g_recompiledGameLib) {
        dlclose(g_recompiledGameLib);
        g_recompiledGameLib = nullptr;
    }

    g_recompiledGameLib = dlopen(pathStr, RTLD_NOW | RTLD_LOCAL);
    if (!g_recompiledGameLib) {
        const char* err = dlerror();
        g_lastStatus = std::string("Gagal memuat libgenma_game.so: ") + (err ? err : "unknown error");
        LOGE("%s", g_lastStatus.c_str());
        env->ReleaseStringUTFChars(soPath, pathStr);
        return JNI_FALSE;
    }

    g_lastStatus = std::string("Berhasil memuat modul game: ") + pathStr;
    LOGI("%s", g_lastStatus.c_str());
    env->ReleaseStringUTFChars(soPath, pathStr);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeTouchEvent(
    JNIEnv*, jobject, jint action, jint pointerId, jfloat normX, jfloat normY) {
    if (action == 0) input_android::OnTouchDown(pointerId, normX, normY);
    else if (action == 1) input_android::OnTouchMove(pointerId, normX, normY);
    else input_android::OnTouchUp(pointerId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeSetGamepadConnected(
    JNIEnv*, jobject, jboolean connected) {
    input_android::SetPhysicalGamepadConnected(connected == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeToggleImGuiMenu(JNIEnv*, jobject) {
    app_android::ToggleTouchMenu();
    return app_android::IsTouchMenuOpen() ? JNI_TRUE : JNI_FALSE;
}
