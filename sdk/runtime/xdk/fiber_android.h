#pragma once
// Android ARM64 (AArch64) host fiber implementation for Genma Onimusha (XDK 3911).
// Android Bionic libc omits POSIX <ucontext.h> (getcontext/makecontext/swapcontext).
// This header provides a deterministic AArch64 ABI callee-saved context switcher.

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <sys/mman.h>
#include <thread>
#include <unistd.h>

#if defined(__ANDROID__)
#include <android/log.h>
#define GENMA_FIBER_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GenmaFiber", __VA_ARGS__)
#else
#include <cstdio>
#define GENMA_FIBER_LOGE(...) std::fprintf(stderr, __VA_ARGS__)
#endif

#ifndef WINAPI
#define WINAPI
#endif

constexpr unsigned FIBER_FLAG_FLOAT_SWITCH = 0;

namespace android_fiber {

// AAPCS64 callee-saved register snapshot:
// x19..x28 (10 regs), x29 (fp), x30 (lr), sp, d8..d15 (8 NEON/FP regs) = 21 * 8 = 168 bytes
struct alignas(16) Context {
    uint64_t x19 = 0;
    uint64_t x20 = 0;
    uint64_t x21 = 0;
    uint64_t x22 = 0;
    uint64_t x23 = 0;
    uint64_t x24 = 0;
    uint64_t x25 = 0;
    uint64_t x26 = 0;
    uint64_t x27 = 0;
    uint64_t x28 = 0;
    uint64_t fp  = 0; // x29
    uint64_t lr  = 0; // x30
    uint64_t sp  = 0;
    uint64_t d8  = 0;
    uint64_t d9  = 0;
    uint64_t d10 = 0;
    uint64_t d11 = 0;
    uint64_t d12 = 0;
    uint64_t d13 = 0;
    uint64_t d14 = 0;
    uint64_t d15 = 0;
};

struct Host {
    Context ctx{};
    void* allocation = nullptr;
    size_t bytes = 0;
    void (*entry)(void*) = nullptr;
    void* arg = nullptr;
    std::thread::id owner = std::this_thread::get_id();

    ~Host() {
        if (allocation) {
            munmap(allocation, bytes);
        }
    }
};

inline thread_local Host root{};
inline thread_local Host* current = nullptr;

#if defined(__aarch64__)
extern "C" __attribute__((weak, naked, noinline)) void genma_arm64_swap_context(Context* from, const Context* to) {
    __asm__ volatile(
        // Save callee-saved GPRs into *from (x0)
        "stp x19, x20, [x0, #0]\n"
        "stp x21, x22, [x0, #16]\n"
        "stp x23, x24, [x0, #32]\n"
        "stp x25, x26, [x0, #48]\n"
        "stp x27, x28, [x0, #64]\n"
        "stp x29, x30, [x0, #80]\n"
        "mov x9, sp\n"
        "str x9, [x0, #96]\n"
        // Save callee-saved FP/SIMD registers d8..d15
        "stp d8,  d9,  [x0, #104]\n"
        "stp d10, d11, [x0, #120]\n"
        "stp d12, d13, [x0, #136]\n"
        "stp d14, d15, [x0, #152]\n"
        // Restore callee-saved GPRs from *to (x1)
        "ldp x19, x20, [x1, #0]\n"
        "ldp x21, x22, [x1, #16]\n"
        "ldp x23, x24, [x1, #32]\n"
        "ldp x25, x26, [x1, #48]\n"
        "ldp x27, x28, [x1, #64]\n"
        "ldp x29, x30, [x1, #80]\n"
        "ldr x9, [x1, #96]\n"
        "mov sp, x9\n"
        // Restore d8..d15
        "ldp d8,  d9,  [x1, #104]\n"
        "ldp d10, d11, [x1, #120]\n"
        "ldp d12, d13, [x1, #136]\n"
        "ldp d14, d15, [x1, #152]\n"
        "ret\n"
    );
}

extern "C" __attribute__((weak, used, noinline, noreturn)) void genma_arm64_trampoline_c(Host* host) {
    host->entry(host->arg);
    GENMA_FIBER_LOGE("fiber: host entry returned unexpectedly");
    std::abort();
}

extern "C" __attribute__((weak, naked, noinline)) void genma_arm64_trampoline() {
    __asm__ volatile(
        // Host* was preloaded into x19 when constructing the fiber context
        "mov x0, x19\n"
        "mov x29, #0\n"
        "bl genma_arm64_trampoline_c\n"
    );
}
#endif

} // namespace android_fiber

inline bool IsThreadAFiber() {
    return android_fiber::current != nullptr;
}

inline void* ConvertThreadToFiber(void*) {
    android_fiber::root.owner = std::this_thread::get_id();
    return android_fiber::current = &android_fiber::root;
}

inline void* GetCurrentFiber() {
    return android_fiber::current;
}

inline unsigned long GetLastError() {
    return static_cast<unsigned long>(errno);
}

inline void* CreateFiberEx(size_t, size_t reserve, unsigned, void (*entry)(void*), void* arg) {
    auto host = std::make_unique<android_fiber::Host>();
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    const size_t minStack = 256 * 1024;
    const size_t requested = reserve < minStack ? minStack : reserve;
    const size_t usable = (requested + page - 1) & ~(page - 1);

    // Surround stack with top and bottom PROT_NONE guard pages
    host->bytes = usable + 2 * page;
    void* mem = mmap(nullptr, host->bytes, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        return nullptr;
    }
    host->allocation = mem;

    auto* stackBase = static_cast<uint8_t*>(mem) + page;
    if (mprotect(stackBase, usable, PROT_READ | PROT_WRITE) != 0) {
        return nullptr;
    }

    host->entry = entry;
    host->arg = arg;
    host->owner = std::this_thread::get_id();

#if defined(__aarch64__)
    const uintptr_t stackTop = (reinterpret_cast<uintptr_t>(stackBase + usable) & ~uintptr_t(15));
    host->ctx.sp = static_cast<uint64_t>(stackTop);
    host->ctx.lr = reinterpret_cast<uint64_t>(&android_fiber::genma_arm64_trampoline);
    host->ctx.x19 = reinterpret_cast<uint64_t>(host.get());
#endif

    return host.release();
}

inline void SwitchToFiber(void* value) {
    auto* from = android_fiber::current;
    auto* to = static_cast<android_fiber::Host*>(value);
    if (!from || !to || to->owner != std::this_thread::get_id()) {
        GENMA_FIBER_LOGE("fiber: cross-thread or unconverted SwitchToFiber");
        std::abort();
    }
    android_fiber::current = to;
#if defined(__aarch64__)
    android_fiber::genma_arm64_swap_context(&from->ctx, &to->ctx);
#endif
    android_fiber::current = from;
}

inline void DeleteFiber(void* value) {
    delete static_cast<android_fiber::Host*>(value);
}
