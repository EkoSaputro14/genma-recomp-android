#pragma once
// ARM64 (AArch64) compatibility shims for Xbox XBE statically recompiled C++20 code.

#include <cstdint>

namespace arm64_compat {

inline uint64_t ReadCycleCounter() {
#if defined(__aarch64__)
    uint64_t cnt = 0;
    uint64_t freq = 1;
    __asm__ volatile("mrs %0, cntvct_el0" : "=r"(cnt));
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(freq));
    // Scale monotonic ARM generic timer to Xbox Pentium III 733 MHz ticks
    constexpr uint64_t kXboxCpuHz = 733333333ull;
    if (freq == 0) return cnt;
    return static_cast<uint64_t>((static_cast<unsigned __int128>(cnt) * kXboxCpuHz) / freq);
#else
    return 0;
#endif
}

inline void CpuPause() {
#if defined(__aarch64__)
    __asm__ volatile("yield" ::: "memory");
#endif
}

inline void FullMemoryBarrier() {
#if defined(__aarch64__)
    __asm__ volatile("dmb ish" ::: "memory");
#else
    __asm__ volatile("" ::: "memory");
#endif
}

struct CpuidResult {
    uint32_t eax;
    uint32_t ebx;
    uint32_t ecx;
    uint32_t edx;
};

// Emulates original Xbox Intel Pentium III (Coppermine) CPUID signature
inline CpuidResult EmulateXboxCpuid(uint32_t leaf) {
    if (leaf == 0) {
        // GenuineIntel
        return {2u, 0x756E6547u, 0x6C65746Eu, 0x49656E69u};
    }
    if (leaf == 1) {
        // Family 6, Model 8 (Coppermine), MMX + SSE + FXSR + CMOV + TSC
        return {0x0000068Au, 0x00000002u, 0x00000000u, 0x0383F9FFu};
    }
    return {0u, 0u, 0u, 0u};
}

} // namespace arm64_compat
