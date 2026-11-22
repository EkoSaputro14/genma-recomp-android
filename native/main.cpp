// Android SDL3 host for Genma Onimusha (ARM64, Vulkan) following RanggaCasper/Black-Android architecture.
#include <jni.h>
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <ctime>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>

#include "app/app.h"
#include "build_info.h"
#include "core/frame_clock.h"
#include "core/input.h"
#include "core/log.h"
#include "core/touch_overlay.h"
#include "gpu/backend.h"
#include "host.h"
#include "image_symbols.h"
#include "kernel/kernel.h"

namespace {

SDL_Window* g_window = nullptr;
std::atomic<bool> g_foreground{true};
std::atomic<uint32_t> g_surface_epoch{0};
std::atomic<uint64_t> g_size{uint64_t(1280) << 32 | 720};
std::mutex g_foreground_lock;
std::condition_variable g_foreground_cv;
std::recursive_mutex g_ui_lock;

// Loose OEM controller state & visual TouchOverlayView state forwarded from GenmaActivity.java.
std::mutex g_loose_lock;
input::Pad g_loose_pad{};
input::Pad g_touch_pad{};

std::string Utf8(const wchar_t* w) {
    std::string s;
    for (; w && *w; w++) s += *w < 0x80 ? char(*w) : '?';
    return s;
}

void LogToLogcat() {
    int fds[2];
    if (pipe(fds)) return;
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    std::thread([fd = fds[0]] {
        char buf[1024];
        std::string line;
        for (ssize_t n; (n = read(fd, buf, sizeof(buf))) > 0;) {
            line.append(buf, size_t(n));
            for (size_t nl; (nl = line.find('\n')) != std::string::npos; line.erase(0, nl + 1))
                __android_log_write(ANDROID_LOG_INFO, "GENMA", line.substr(0, nl).c_str());
        }
    }).detach();
}

std::string Property(const char* name) {
    char v[PROP_VALUE_MAX] = {};
    __system_property_get(name, v);
    return v;
}

void SetForeground(bool on) {
    {
        std::lock_guard lock(g_foreground_lock);
        if (on && !g_foreground) g_surface_epoch++;
        g_foreground = on;
    }
    g_foreground_cv.notify_all();
    LOGI("app: %s", on ? "in front" : "in the background");
}

void RefreshWindowSize() {
    if (!g_window) return;
    int w = 0, h = 0;
    SDL_GetWindowSizeInPixels(g_window, &w, &h);
    if (w > 0 && h > 0) g_size = (uint64_t(uint32_t(w)) << 32) | uint32_t(h);
}

void SyncTouchAndLoosePad() {
    input::Pad merged{};
    {
        std::lock_guard lock(g_loose_lock);
        merged = g_loose_pad;
        if (g_touch_pad.connected) {
            merged.connected = true;
            merged.buttons |= g_touch_pad.buttons;
            for (int i = 0; i < 8; i++) merged.analog[i] = std::max(merged.analog[i], g_touch_pad.analog[i]);
            if (g_touch_pad.lx) merged.lx = g_touch_pad.lx;
            if (g_touch_pad.ly) merged.ly = g_touch_pad.ly;
        }
    }
    const input_android::VirtualPadState vp = input_android::PollVirtualPad();
    if (vp.leftStickX || vp.leftStickY || vp.buttons || vp.dpadUp || vp.dpadDown || vp.dpadLeft || vp.dpadRight) {
        merged.connected = true;
        if (vp.dpadUp) merged.buttons |= (1u << 0);
        if (vp.dpadDown) merged.buttons |= (1u << 1);
        if (vp.dpadLeft) merged.buttons |= (1u << 2);
        if (vp.dpadRight) merged.buttons |= (1u << 3);
        if (vp.buttons & 0x0010u) merged.buttons |= (1u << 4);  // Start
        if (vp.buttons & 0x1000u) merged.analog[0] = 255;       // A (Absorb / Confirm)
        if (vp.buttons & 0x4000u) merged.analog[2] = 255;       // X (Attack)
        if (vp.buttons & 0x8000u) merged.analog[3] = 255;       // Y (Oni Magic)
        if (vp.buttons & 0x0100u) merged.analog[5] = 255;       // White / LB (Guard)
        if (vp.leftStickX) merged.lx = vp.leftStickX;
        if (vp.leftStickY) merged.ly = vp.leftStickY;
    }
    input::SetKeyboardPad(merged);
}

void Dispatch(const SDL_Event& e) {
    switch (e.type) {
        case SDL_EVENT_QUIT:
        case SDL_EVENT_TERMINATING:
            logx::Flush();
            kernel::Shutdown(0);
            break;
        case SDL_EVENT_WILL_ENTER_BACKGROUND:
            SetForeground(false);
            break;
        case SDL_EVENT_DID_ENTER_FOREGROUND:
            SetForeground(true);
            RefreshWindowSize();
            break;
        case SDL_EVENT_WINDOW_RESIZED:
        case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
            RefreshWindowSize();
            break;
        case SDL_EVENT_LOW_MEMORY:
            LOGW("app: Android is low on memory");
            break;
        case SDL_EVENT_FINGER_DOWN:
            input_android::OnTouchDown(int(e.tfinger.fingerID), e.tfinger.x, e.tfinger.y);
            break;
        case SDL_EVENT_FINGER_MOTION:
            input_android::OnTouchMove(int(e.tfinger.fingerID), e.tfinger.x, e.tfinger.y);
            break;
        case SDL_EVENT_FINGER_UP:
        case SDL_EVENT_FINGER_CANCELED:
            input_android::OnTouchUp(int(e.tfinger.fingerID));
            break;
        case SDL_EVENT_KEY_DOWN:
            if (!e.key.repeat && e.key.key == SDLK_AC_BACK) {
                std::lock_guard lock(g_loose_lock);
                g_loose_pad.connected = true;
                g_loose_pad.buttons |= (1u << 4);  // Back button -> Start / Pause
            }
            break;
        case SDL_EVENT_KEY_UP:
            if (e.key.key == SDLK_AC_BACK) {
                std::lock_guard lock(g_loose_lock);
                g_loose_pad.buttons &= uint16_t(~(1u << 4));
            }
            break;
        default:
            break;
    }
}

}  // namespace

// JNI callback from TouchOverlayView (GenmaActivity.java) for visual virtual gamepad controls.
extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeTouchPad(
    JNIEnv* env, jclass, jint buttons, jbyteArray analog, jshort lx, jshort ly) {
    std::lock_guard lock(g_loose_lock);
    g_touch_pad = {};
    g_touch_pad.connected = true;
    g_touch_pad.buttons = uint16_t(buttons & 0xFFFF);
    g_touch_pad.lx = int16_t(lx);
    g_touch_pad.ly = int16_t(ly);
    if (analog && env->GetArrayLength(analog) >= 8) {
        jbyte tmp[8]{};
        env->GetByteArrayRegion(analog, 0, 8, tmp);
        for (int i = 0; i < 8; i++) g_touch_pad.analog[i] = uint8_t(tmp[i]);
    }
}

// JNI callbacks from GenmaActivity.java for OEM virtualized controllers (Black-Android pattern).
extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeLooseKey(JNIEnv*, jclass, jint keyCode, jboolean down) {
    std::lock_guard lock(g_loose_lock);
    const bool pressed = (down == JNI_TRUE);
    g_loose_pad.connected = true;
    auto setBit = [&](uint16_t bit) {
        if (pressed) g_loose_pad.buttons |= bit;
        else g_loose_pad.buttons &= uint16_t(~bit);
    };
    auto setAnalog = [&](int idx) { g_loose_pad.analog[idx] = pressed ? 255 : 0; };
    switch (keyCode) {
        case 19: setBit(1u << 0); break;   // KEYCODE_DPAD_UP
        case 20: setBit(1u << 1); break;   // KEYCODE_DPAD_DOWN
        case 21: setBit(1u << 2); break;   // KEYCODE_DPAD_LEFT
        case 22: setBit(1u << 3); break;   // KEYCODE_DPAD_RIGHT
        case 108: setBit(1u << 4); break;  // KEYCODE_BUTTON_START
        case 109: setBit(1u << 5); break;  // KEYCODE_BUTTON_SELECT
        case 106: setBit(1u << 6); break;  // KEYCODE_BUTTON_THUMBL
        case 107: setBit(1u << 7); break;  // KEYCODE_BUTTON_THUMBR
        case 96: setAnalog(0); break;      // KEYCODE_BUTTON_A
        case 97: setAnalog(1); break;      // KEYCODE_BUTTON_B
        case 99: setAnalog(2); break;      // KEYCODE_BUTTON_X
        case 100: setAnalog(3); break;     // KEYCODE_BUTTON_Y
        case 103: setAnalog(4); break;     // KEYCODE_BUTTON_R1 (Black)
        case 102: setAnalog(5); break;     // KEYCODE_BUTTON_L1 (White)
        default: break;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeLooseAxes(
    JNIEnv*, jclass, jfloat lx, jfloat ly, jfloat rx, jfloat ry, jfloat lt, jfloat rt, jfloat hx, jfloat hy) {
    std::lock_guard lock(g_loose_lock);
    g_loose_pad.connected = true;
    auto toAxis = [](float v) { return int16_t(std::clamp(v, -1.0f, 1.0f) * 32767.0f); };
    g_loose_pad.lx = toAxis(lx);
    g_loose_pad.ly = toAxis(-ly);
    g_loose_pad.rx = toAxis(rx);
    g_loose_pad.ry = toAxis(-ry);
    g_loose_pad.analog[6] = uint8_t(std::clamp(lt, 0.0f, 1.0f) * 255.0f);
    g_loose_pad.analog[7] = uint8_t(std::clamp(rt, 0.0f, 1.0f) * 255.0f);
    g_loose_pad.buttons &= uint16_t(~0x0Fu);
    if (hy < -0.5f) g_loose_pad.buttons |= (1u << 0);
    if (hy > 0.5f) g_loose_pad.buttons |= (1u << 1);
    if (hx < -0.5f) g_loose_pad.buttons |= (1u << 2);
    if (hx > 0.5f) g_loose_pad.buttons |= (1u << 3);
}

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeLooseRelease(JNIEnv*, jclass) {
    std::lock_guard lock(g_loose_lock);
    g_loose_pad = {};
}

namespace host {

SDL_Window* Window() { return g_window; }
bool Foreground() { return g_foreground.load(); }
uint32_t SurfaceEpoch() { return g_surface_epoch.load(); }

void WaitForeground() {
    std::unique_lock lock(g_foreground_lock);
    g_foreground_cv.wait(lock, [] { return g_foreground.load(); });
}

void WindowPixels(int& w, int& h) {
    w = h = 0;
    if (g_window) SDL_GetWindowSizeInPixels(g_window, &w, &h);
}

}  // namespace host

namespace app {

void* Window() { return g_window; }

Mode LinuxWindowSize() {
    uint64_t n = g_size.load();
    return {uint32_t(n >> 32), uint32_t(n)};
}

std::filesystem::path ExeDir() {
    static const std::filesystem::path dir = [] {
        const char* p = nullptr;
        if (SDL_GetAndroidExternalStorageState() & SDL_ANDROID_EXTERNAL_STORAGE_WRITE)
            p = SDL_GetAndroidExternalStoragePath();
        if (!p) p = SDL_GetAndroidInternalStoragePath();
        return std::filesystem::path(p ? p : ".");
    }();
    return dir;
}

int Instance() { return 0; }
bool MenuOpen() { return false; }
void ToggleMenu() {}
void AskQuit() {
    SDL_Event e{};
    e.type = SDL_EVENT_QUIT;
    SDL_PushEvent(&e);
}
void DrawMenu(double) {}
std::recursive_mutex& UiLock() { return g_ui_lock; }
void ApplyDisplay() {}
void OpenGameWindow(bool) {}
void SplashFirstFrame() {}
bool OpenSplash() { return false; }
void CloseSplash() {}
bool StartHandover() { return false; }
bool HandingOver() { return false; }
void FinishHandover() {}

void LogSystem() {
    std::time_t now = std::time(nullptr);
    char date[32] = {};
    std::strftime(date, sizeof(date), "%Y-%m-%d %H:%M:%S", std::localtime(&now));
    LOGR("%ls (%s), %s, build %s", kGame.title, IMAGE_XBE, date, GENMA_COMMIT);
    LOGR("Path: %s", ExeDir().c_str());
    LOGR("Device: %s %s, Android %s (API %d)", Property("ro.product.manufacturer").c_str(),
         Property("ro.product.model").c_str(), Property("ro.build.version.release").c_str(), SDL_GetAndroidSDKVersion());
    LOGR("CPU: %s (%d threads)", Property("ro.soc.model").c_str(), SDL_GetNumLogicalCPUCores());
    LOGR("RAM: %.1f GB", SDL_GetSystemRAM() / 1024.0);
    LogSettings();
}

void Pace(bool guest_vblank) {
    static timing::FrameDeadline deadline;
    timing::WaitUntil(deadline.Next(timing::Clock::now(), Get().fps_cap, guest_vblank));
}

}  // namespace app

int main(int, char**) {
    LogToLogcat();
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS, "0");
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_GAMEPAD | SDL_INIT_AUDIO)) {
        __android_log_print(ANDROID_LOG_ERROR, "GENMA", "SDL_Init failed: %s", SDL_GetError());
        return 1;
    }

    logx::Init((app::ExeDir() / "logs").string(), app::kGame.log, false);
    app::Load();
    if (app::kGame.init) app::kGame.init();
    app::LogSystem();

    std::string title = Utf8(app::kGame.title);
    g_window = SDL_CreateWindow(title.c_str(), 0, 0,
                                SDL_WINDOW_FULLSCREEN | SDL_WINDOW_VULKAN | SDL_WINDOW_HIGH_PIXEL_DENSITY);
    if (!g_window) {
        LOGE("app: failed to create Vulkan window: %s", SDL_GetError());
        return 1;
    }
    RefreshWindowSize();

    input_android::OverlaySettings overlay{};
    input_android::InitOverlay(overlay);

    app::GameFiles game;
    if (!app::FindGame(game)) {
        logx::Flush();
        return 1;
    }

    input::Init();
    if (app::kGame.window) app::kGame.window(g_window);
    if (!kernel::Boot(std::move(game.xbe), IMAGE_XBE, game.dir.string(), (app::ExeDir() / "hdd").string())) {
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, Utf8(app::kGame.short_title).c_str(),
                                 "Genma Onimusha failed to start. Check the log in Android/data/com.genmarecomp.android/files/logs.",
                                 g_window);
        return 1;
    }

    for (;;) {
        SDL_Event e;
        if (SDL_WaitEventTimeout(&e, 16)) {
            Dispatch(e);
            while (SDL_PollEvent(&e)) Dispatch(e);
        }
        input::Pump();
        SyncTouchAndLoosePad();
        RefreshWindowSize();
    }
}
