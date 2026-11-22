#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <string>
#include <vector>

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
void SetMobileTextureRootDirectory(const std::string& path);
}

namespace {
void* g_recompiledGameLib = nullptr;
std::string g_gameDataPath = "/storage/emulated/0/GenmaOnimusha";
std::string g_texturePackPath = "/storage/emulated/0/GenmaOnimusha/textures";
std::string g_lastStatus = "Menunggu berkas ISO / default.xbe di /storage/emulated/0/GenmaOnimusha";
bool g_memoryOk = false;
bool g_aliasOk = false;
bool g_fiberOk = false;
bool g_gameLibLoaded = false;
std::string g_gpuName = "Belum diinisialisasi";
uint32_t g_vkApiVersion = VK_API_VERSION_1_0;

void TryAutoLoadBundledGameLib() {
    if (g_recompiledGameLib) return;
    // When built via CI standalone mode (ci/default.xbe.enc + XBE_KEY), libgenma_game.so is bundled inside the APK
    g_recompiledGameLib = dlopen("libgenma_game.so", RTLD_NOW | RTLD_LOCAL);
    if (g_recompiledGameLib) {
        g_gameLibLoaded = true;
        g_lastStatus = "Modul recompiled libgenma_game.so terbundel di dalam APK (siap dijalankan langsung di HP).";
        LOGI("Auto-loaded bundled libgenma_game.so");
    } else {
        g_gameLibLoaded = false;
        g_lastStatus = "Mode runtime asset-free: tambahkan ci/default.xbe.enc + secret XBE_KEY di GitHub Actions untuk membundel libgenma_game.so langsung ke APK.";
    }
}

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
    TryAutoLoadBundledGameLib();

    input_android::OverlaySettings overlay{};
    input_android::InitOverlay(overlay);

    const uint64_t ticks = arm64_compat::ReadCycleCounter();
    const uint32_t vkMajor = VK_VERSION_MAJOR(g_vkApiVersion);
    const uint32_t vkMinor = VK_VERSION_MINOR(g_vkApiVersion);

    std::string report =
        "ABI: arm64-v8a | API 29+ | Mode: Standalone APK (CI Recomp)\n"
        "ARM64 Bionic Fiber: " + std::string(g_fiberOk ? "OK" : "GAGAL") + "\n"
        "Guest 4 GiB Virtual Space: " + std::string(g_memoryOk ? "OK" : "GAGAL") + "\n"
        "Xbox Physical Alias (0x80000000/0xF0000000): " + std::string(g_aliasOk ? "OK" : "GAGAL") + "\n"
        "Vulkan GPU: " + g_gpuName + " (v" + std::to_string(vkMajor) + "." + std::to_string(vkMinor) + ")\n"
        "Bundled Game Core (libgenma_game.so): " + std::string(g_gameLibLoaded ? "TERBUNDEL (SIAP)" : "BELUM TERBUNDEL") + "\n"
        "Folder Data Game: " + g_gameDataPath + "\n"
        "Folder HD Textures: " + g_texturePackPath + " (Cache 512 MB)\n"
        "ARM64 Timer Ticks: " + std::to_string(ticks) + "\n"
        "Status: " + g_lastStatus;

    LOGI("Self-check completed: fiber=%d mem=%d alias=%d gameLib=%d gpu=%s", g_fiberOk, g_memoryOk, g_aliasOk, g_gameLibLoaded, g_gpuName.c_str());
    return env->NewStringUTF(report.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeSetGamePaths(
    JNIEnv* env, jobject, jstring dataDir, jstring texDir) {
    if (dataDir) {
        const char* p = env->GetStringUTFChars(dataDir, nullptr);
        if (p) {
            g_gameDataPath = p;
            env->ReleaseStringUTFChars(dataDir, p);
        }
    }
    if (texDir) {
        const char* t = env->GetStringUTFChars(texDir, nullptr);
        if (t) {
            g_texturePackPath = t;
            gpu_android::SetMobileTextureRootDirectory(g_texturePackPath);
            env->ReleaseStringUTFChars(texDir, t);
        }
    }
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
