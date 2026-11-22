// Android API 29+ guest memory backend for Genma Onimusha (ARM64).
// Supports dual-mapped Xbox physical windows (0x80000000 and 0xF0000000) on Android 10+.

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <android/log.h>
#include <android/sharedmem.h>
#define MEM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GenmaMem", __VA_ARGS__)
#define MEM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GenmaMem", __VA_ARGS__)
#else
#include <cstdio>
#define MEM_LOGI(...) std::printf(__VA_ARGS__)
#define MEM_LOGE(...) std::fprintf(stderr, __VA_ARGS__)
#endif

namespace mem_android {

constexpr uint64_t kGuestSpaceSize   = 1ull << 32; // 4 GiB virtual address space
constexpr uint32_t kPhysicalSize     = 64u * 1024u * 1024u; // 64 MiB Xbox RAM
constexpr uint32_t kContiguousBase   = 0x80000000u;
constexpr uint32_t kWriteCombineBase = 0xF0000000u;

static uint8_t* g_base = nullptr;

static int CreateSharedRamFd(size_t bytes) {
    // 1. Try direct kernel syscall (works on Android 10 / API 29 even though Bionic wrapper was added in API 30)
#if defined(__NR_memfd_create)
    const int memfd = static_cast<int>(syscall(__NR_memfd_create, "genma_xbox_ram", 1u /* MFD_CLOEXEC */));
    if (memfd >= 0) {
        if (ftruncate(memfd, static_cast<off_t>(bytes)) == 0) {
            return memfd;
        }
        close(memfd);
    }
#endif

    // 2. Fallback to Android NDK ASharedMemory (available since API 26, SELinux-safe)
#if defined(__ANDROID__)
    const int ashmem = ASharedMemory_create("genma_xbox_ram", bytes);
    if (ashmem >= 0) {
        return ashmem;
    }
#endif
    return -1;
}

bool InitGuestMemory() {
    if (g_base) return true;

    // Try a clean aligned hint first, then let the ARM64 kernel choose under ASLR
    const uint64_t hints[] = {0x100000000ull, 0x200000000ull, 0ull};
    for (uint64_t hint : hints) {
        void* reserved = mmap(
            reinterpret_cast<void*>(hint),
            kGuestSpaceSize,
            PROT_NONE,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE,
            -1,
            0);
        if (reserved == MAP_FAILED) {
            continue;
        }

        auto* candidate = static_cast<uint8_t*>(reserved);
        const int fd = CreateSharedRamFd(kPhysicalSize);
        bool ok = false;
        if (fd >= 0) {
            void* viewA = mmap(
                candidate + kContiguousBase,
                kPhysicalSize,
                PROT_READ | PROT_WRITE,
                MAP_SHARED | MAP_FIXED,
                fd,
                0);
            void* viewB = mmap(
                candidate + kWriteCombineBase,
                kPhysicalSize,
                PROT_READ | PROT_WRITE,
                MAP_SHARED | MAP_FIXED,
                fd,
                0);
            ok = (viewA != MAP_FAILED && viewB != MAP_FAILED);
            close(fd);
        }

        if (ok) {
            g_base = candidate;
            MEM_LOGI("Guest 4 GiB space mapped at %p (dual physical alias verified)", static_cast<void*>(g_base));
            return true;
        }
        munmap(candidate, kGuestSpaceSize);
    }

    MEM_LOGE("Failed to reserve 4 GiB virtual guest space (errno=%d)", errno);
    return false;
}

bool VerifyPhysicalAlias() {
    if (!g_base) return false;
    auto* a = reinterpret_cast<volatile uint32_t*>(g_base + kContiguousBase + 0x1000);
    auto* b = reinterpret_cast<volatile uint32_t*>(g_base + kWriteCombineBase + 0x1000);
    const uint32_t magic = 0x47454E4Du; // 'GENM'
    *a = magic;
    const bool matches = (*b == magic);
    *a = 0;
    return matches;
}

uint8_t* Base() {
    return g_base;
}

void ShutdownGuestMemory() {
    if (g_base) {
        munmap(g_base, kGuestSpaceSize);
        g_base = nullptr;
    }
}

} // namespace mem_android
