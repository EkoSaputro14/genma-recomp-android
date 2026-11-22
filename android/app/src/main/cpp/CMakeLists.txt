cmake_minimum_required(VERSION 3.22.1)
project(genma_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../..")
set(RUNTIME_DIR "${REPO_ROOT}/sdk/runtime")

add_library(genma_android SHARED
    android_bridge.cpp
    "${RUNTIME_DIR}/kernel/memory_android.cpp"
    "${RUNTIME_DIR}/core/touch_overlay.cpp"
    "${RUNTIME_DIR}/app/imgui_android.cpp"
    "${RUNTIME_DIR}/gpu/hd_texture_android.cpp"
)

target_include_directories(genma_android PRIVATE
    "${RUNTIME_DIR}"
)

target_compile_definitions(genma_android PRIVATE
    GENMA_ANDROID_PORT=1
    GENMA_MIN_SDK=29
    GENMA_VULKAN_10_FALLBACK=1
    GENMA_HD_TEXTURE_BUDGET_MB=1152
)

target_compile_options(genma_android PRIVATE
    -O3
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fno-omit-frame-pointer
)

find_library(ANDROID_LIB android)
find_library(LOG_LIB log)
find_library(VULKAN_LIB vulkan)

target_link_libraries(genma_android PRIVATE
    ${ANDROID_LIB}
    ${LOG_LIB}
    ${VULKAN_LIB}
    dl
)
