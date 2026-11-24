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
#include "display_math.h"
#include "gpu/backend.h"
#include "host.h"
#include "image_symbols.h"
#include "kernel/kernel.h"
#include "lang_id.h"

namespace {

SDL_Window* g_window = nullptr;
std::atomic<bool> g_foreground{true};
std::atomic<uint32_t> g_surface_epoch{0};
std::atomic<uint64_t> g_size{0};
std::mutex g_foreground_lock;
std::condition_variable g_foreground_cv;
std::recursive_mutex g_ui_lock;

// Loose OEM controller state & visual TouchOverlayView state forwarded from GenmaActivity.java.
std::mutex g_loose_lock;
input::Pad g_loose_pad{};
input::Pad g_touch_pad{};

// Android graphics / upscale & localization state bridged with Vulkan, VFS, & Settings UI
// (defaults preserve v0.8.0 baseline: 1x 480p, Native 4:3, V-Sync On, Bilinear).
std::atomic<float> g_render_scale{1.0f};
std::atomic<bool> g_nearest_present{false};
std::atomic<float> g_max_aniso{1.0f};
std::atomic<uint32_t> g_max_image_dim{4096};
std::atomic<bool> g_settings_loaded{false};
std::atomic<uint32_t> g_display_mode{0};
std::atomic<bool> g_display_mode_explicit{false};
std::atomic<bool> g_fill_no_stretch{false};
std::atomic<float> g_wide_vertical_pan{0.70f};
std::atomic<bool> g_indonesian_game{false};
std::atomic<bool> g_gamepad_refresh_requested{false};
std::atomic<uint64_t> g_last_logged_display_sig{0};
std::mutex g_game_dir_lock;
std::string g_game_dir;

app::Aspect DisplayModeToAspect(uint32_t mode) {
    switch (mode) {
        case 1: return app::Aspect::Ratio16x9;
        case 2: return app::Aspect::Stretch;
        default: return app::Aspect::Ratio4x3;
    }
}

uint32_t AspectToDisplayMode(app::Aspect aspect) {
    switch (aspect) {
        case app::Aspect::Ratio16x9: return 1;
        case app::Aspect::Stretch: return 2;
        default: return 0;
    }
}

void DetectPhysicalDisplay(int& pw, int& ph, float& density) {
    const SDL_DisplayID id = g_window ? SDL_GetDisplayForWindow(g_window) : SDL_GetPrimaryDisplay();
    const SDL_DisplayMode* m = SDL_GetCurrentDisplayMode(id);
    if (!m) m = SDL_GetDesktopDisplayMode(id);
    density = (m && m->pixel_density > 0.0f) ? m->pixel_density : 1.0f;
    const int raw_w = m ? std::max(1, int(std::lround(float(m->w) * density))) : 1920;
    const int raw_h = m ? std::max(1, int(std::lround(float(m->h) * density))) : 1080;
    const auto land = display_math::NormalizeLandscape(uint32_t(raw_w), uint32_t(raw_h));
    pw = int(land.width);
    ph = int(land.height);
}

void LogDisplayState(bool force) {
    int pw = 0, ph = 0;
    float density = 1.0f;
    DetectPhysicalDisplay(pw, ph, density);
    int ww = 0, wh = 0;
    if (g_window) SDL_GetWindowSizeInPixels(g_window, &ww, &wh);
    uint32_t ow = 0, oh = 0;
    host::ScreenMode(ow, oh);
    const uint32_t mode = host::DisplayMode();
    const uint64_t sig = (uint64_t(uint32_t(pw)) << 48) ^ (uint64_t(uint32_t(ph)) << 32) ^
                         (uint64_t(uint32_t(ww)) << 18) ^ (uint64_t(uint32_t(wh)) << 4) ^ mode;
    if (!force && g_last_logged_display_sig.load() == sig) return;
    g_last_logged_display_sig.store(sig);
    const float dasp = (oh > 0) ? (float(ow) / float(oh)) : (float(pw) / float(std::max(1, ph)));
    const auto dmode = static_cast<display_math::DisplayMode>(std::min(mode, 2u));
    LOGR("[DISPLAY] physical=%dx%d (density=%.2f) sdl_window=%dx%d output=%ux%u display_aspect=%.4f mode=%s",
         pw, ph, density, ww, wh, ow, oh, dasp, display_math::DisplayModeName(dmode));
}

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
    if (w <= 0 || h <= 0) return;
    if (w < h) {
        static std::atomic<bool> logged_portrait{false};
        if (!logged_portrait.exchange(true)) {
            LOGI("[DISPLAY] ignoring transient portrait window %dx%d while waiting for landscape", w, h);
        }
        return;
    }
    const uint64_t next = (uint64_t(uint32_t(w)) << 32) | uint32_t(h);
    if (g_size.exchange(next) != next) {
        LogDisplayState(false);
    }
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
        case SDL_EVENT_GAMEPAD_ADDED:
        case SDL_EVENT_GAMEPAD_REMOVED:
            input::OnGamepadEvent(e.type, uint32_t(e.gdevice.which));
            break;
        case SDL_EVENT_JOYSTICK_ADDED:
        case SDL_EVENT_JOYSTICK_REMOVED:
        case SDL_EVENT_GAMEPAD_REMAPPED:
            g_gamepad_refresh_requested.store(true);
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
        case 104: setAnalog(6); break;     // KEYCODE_BUTTON_L2 (Left Trigger)
        case 105: setAnalog(7); break;     // KEYCODE_BUTTON_R2 (Right Trigger)
        default: break;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeRefreshGamepads(JNIEnv*, jclass) {
    g_gamepad_refresh_requested.store(true);
    SDL_Event wake{};
    wake.type = SDL_EVENT_USER;
    SDL_PushEvent(&wake);
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

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeSetRenderOptions(
    JNIEnv*, jclass, jfloat renderScale, jboolean nearestPresent) {
    host::SetRenderScale(renderScale);
    host::SetNearestPresent(nearestPresent == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeSetDisplayMode(
    JNIEnv*, jclass, jint displayMode, jfloat widePan) {
    const uint32_t clampedMode = uint32_t(std::clamp(int(displayMode), 0, 2));
    g_display_mode_explicit.store(true);
    host::SetDisplayMode(clampedMode);
    host::SetWideVerticalPan(widePan);
    {
        std::lock_guard lock(app::UiLock());
        app::Get().aspect = DisplayModeToAspect(clampedMode);
        if (g_settings_loaded.load()) {
            app::Save();
        }
    }
    LogDisplayState(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeSetExtendedOptions(
    JNIEnv*, jclass, jboolean fillNoStretch, jfloat widePan, jboolean indonesianGame) {
    const bool wantFill = (fillNoStretch == JNI_TRUE);
    if (!wantFill) {
        host::SetDisplayMode(0);
    } else if (host::DisplayMode() == 0) {
        host::SetDisplayMode(2);
    }
    host::SetWideVerticalPan(widePan);
    const bool id = (indonesianGame == JNI_TRUE);
    host::SetIndonesianGame(id);
    lang_id::ApplyXbeLanguage(id);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeGetGraphicsSettings(JNIEnv* env, jclass) {
    std::lock_guard lock(app::UiLock());
    const app::Settings& s = app::Get();
    const bool loaded = g_settings_loaded.load();
    const uint32_t mode = host::DisplayMode();
    const app::Aspect aspect = loaded ? s.aspect : DisplayModeToAspect(mode);
    const float overscan = loaded ? s.overscan : app::kGame.overscan;
    const bool modern = (!app::kGame.options.empty() && app::kGame.options[0].flag)
                            ? *app::kGame.options[0].flag
                            : true;
    jint vals[15] = {
        jint(std::lround(host::RenderScale() * 100.0f)),
        jint(aspect),
        jint(s.aniso),
        jint(s.vsync ? 1 : 0),
        jint(s.fps_cap),
        jint(std::lround(overscan * 100.0f)),
        jint(s.point_nudge ? 1 : 0),
        jint(s.precise_depth ? 1 : 0),
        jint(host::NearestPresent() ? 1 : 0),
        jint(modern ? 1 : 0),
        jint(std::max(1, int(std::floor(host::MaxAniso())))),
        jint(host::FillNoStretch() ? 1 : 0),
        jint(std::lround(host::WideVerticalPan() * 100.0f)),
        jint(host::IndonesianGame() ? 1 : 0),
        jint(mode),
    };
    jintArray out = env->NewIntArray(15);
    if (out) env->SetIntArrayRegion(out, 0, 15, vals);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_genmarecomp_android_GenmaActivity_nativeApplyGraphicsSettings(
    JNIEnv*, jclass, jint scalePct, jint aspect, jint aniso, jboolean vsync, jint fpsCap,
    jint overscanX100, jboolean pointNudge, jboolean preciseDepth, jboolean nearestPresent,
    jboolean modernControls, jboolean fillNoStretch, jint widePanPct, jboolean indonesianGame) {
    host::SetRenderScale(std::clamp(float(scalePct) / 100.0f, 0.5f, 3.0f));
    host::SetNearestPresent(nearestPresent == JNI_TRUE);
    uint32_t resolvedMode = 0;
    if (aspect == 2) resolvedMode = 1;
    else if (aspect == 3) resolvedMode = 2;
    else if (aspect == 0 && fillNoStretch == JNI_TRUE) resolvedMode = 1;
    g_display_mode_explicit.store(true);
    host::SetDisplayMode(resolvedMode);
    host::SetWideVerticalPan(float(std::clamp(int(widePanPct), 0, 100)) / 100.0f);
    const bool id = (indonesianGame == JNI_TRUE);
    host::SetIndonesianGame(id);
    lang_id::ApplyXbeLanguage(id);
    {
        std::lock_guard lock(app::UiLock());
        app::Settings& s = app::Get();
        s.aspect = DisplayModeToAspect(resolvedMode);
        s.aniso = uint32_t(std::clamp(int(aniso), 0, 16));
        s.vsync = (vsync == JNI_TRUE);
        s.fps_cap = uint32_t(std::max(0, int(fpsCap)));
        s.overscan = std::clamp(float(overscanX100) / 100.0f, 0.0f, 15.0f);
        s.point_nudge = (pointNudge == JNI_TRUE);
        s.precise_depth = (preciseDepth == JNI_TRUE);
        if (!app::kGame.options.empty() && app::kGame.options[0].flag) {
            *app::kGame.options[0].flag = (modernControls == JNI_TRUE);
        }
        if (g_settings_loaded.load()) {
            app::Save();
        }
        LOGR("settings: scale=%.2fx display_mode=%u (%s) aspect=%d fillNoStretch=%d pan=%.2f aniso=%u vsync=%d fps=%u overscan=%.2f%% nudge=%d pdepth=%d nearest=%d modern=%d lang_id=%d",
             host::RenderScale(), resolvedMode,
             display_math::DisplayModeName(static_cast<display_math::DisplayMode>(resolvedMode)),
             int(s.aspect), int(host::FillNoStretch()), host::WideVerticalPan(),
             s.aniso, int(s.vsync), s.fps_cap, s.overscan, int(s.point_nudge), int(s.precise_depth),
             int(host::NearestPresent()),
             (!app::kGame.options.empty() && app::kGame.options[0].flag) ? int(*app::kGame.options[0].flag) : 1,
             int(host::IndonesianGame()));
    }
    LogDisplayState(true);
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

void PhysicalDisplay(int& w, int& h, float& density) {
    DetectPhysicalDisplay(w, h, density);
}

void ScreenMode(uint32_t& w, uint32_t& h) {
    if (g_window) {
        int ww = 0, wh = 0;
        WindowPixels(ww, wh);
        if (ww >= wh && wh > 0) {
            w = uint32_t(ww);
            h = uint32_t(wh);
            return;
        }
    }
    int pw = 0, ph = 0;
    float density = 1.0f;
    DetectPhysicalDisplay(pw, ph, density);
    w = uint32_t(std::max(1, pw));
    h = uint32_t(std::max(1, ph));
}

float RenderScale() { return g_render_scale.load(); }
void SetRenderScale(float scale) { g_render_scale.store(std::clamp(scale, 0.5f, 3.0f)); }
bool NearestPresent() { return g_nearest_present.load(); }
void SetNearestPresent(bool nearest) { g_nearest_present.store(nearest); }
void SetGpuCaps(float max_aniso, uint32_t max_image_dim) {
    g_max_aniso.store(std::max(1.0f, max_aniso));
    g_max_image_dim.store(std::max(2048u, max_image_dim));
}
float MaxAniso() { return g_max_aniso.load(); }
uint32_t MaxImageDim() { return g_max_image_dim.load(); }
uint32_t DisplayMode() { return g_display_mode.load(); }
void SetDisplayMode(uint32_t mode) {
    const uint32_t m = std::min(mode, 2u);
    g_display_mode.store(m);
    g_fill_no_stretch.store(m != 0);
}
bool FillNoStretch() { return g_display_mode.load() != 0; }
void SetFillNoStretch(bool fill) {
    if (!fill) {
        SetDisplayMode(0);
    } else if (g_display_mode.load() == 0) {
        SetDisplayMode(2);
    }
}
float WideVerticalPan() { return g_wide_vertical_pan.load(); }
void SetWideVerticalPan(float pan) { g_wide_vertical_pan.store(std::clamp(pan, 0.0f, 1.0f)); }
bool IndonesianGame() { return g_indonesian_game.load(); }
void SetIndonesianGame(bool enabled) { g_indonesian_game.store(enabled); }
void OnXbeLoaded() { lang_id::OnXbeLoaded(); }
std::string GameDir() {
    std::lock_guard<std::mutex> lock(g_game_dir_lock);
    return g_game_dir;
}
void SetGameDir(const std::string& dir) {
    std::lock_guard<std::mutex> lock(g_game_dir_lock);
    g_game_dir = dir;
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
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_GAMEPAD | SDL_INIT_AUDIO)) {
        __android_log_print(ANDROID_LOG_ERROR, "GENMA", "SDL_Init failed: %s", SDL_GetError());
        return 1;
    }

    logx::Init((app::ExeDir() / "logs").string(), app::kGame.log, false);
    app::Load();
    {
        std::lock_guard lock(app::UiLock());
        if (g_display_mode_explicit.load()) {
            app::Get().aspect = DisplayModeToAspect(host::DisplayMode());
        } else {
            host::SetDisplayMode(AspectToDisplayMode(app::Get().aspect));
        }
    }
    g_settings_loaded.store(true);
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
    LogDisplayState(true);

    input_android::OverlaySettings overlay{};
    input_android::InitOverlay(overlay);

    app::GameFiles game;
    if (!app::FindGame(game)) {
        logx::Flush();
        return 1;
    }
    host::SetGameDir(game.dir.string());
    lang_id::LoadCustomOverrides(game.dir);

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
        if (g_gamepad_refresh_requested.exchange(false)) {
            SDL_UpdateGamepads();
            input::RefreshGamepads();
        }
        input::Pump();
        SyncTouchAndLoosePad();
        RefreshWindowSize();
    }
}
