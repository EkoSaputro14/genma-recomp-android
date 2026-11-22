#pragma once
// Force-included before every host source on Android by CMake.
// Provides <unistd.h> for ftruncate() and the memfd_create syscall wrapper for Android 10 (API 29).
#include <unistd.h>

#if defined(__ANDROID__) && __ANDROID_API__ < 30
#include <sys/syscall.h>
inline int memfd_create(const char* name, unsigned int flags) {
    return int(syscall(__NR_memfd_create, name, flags));
}
#endif
