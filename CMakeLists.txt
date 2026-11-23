# Genma Onimusha (original Xbox), statically recompiled.
# Dual-mode root CMakeLists.txt:
#   1. ANDROID (invoked by Gradle): builds libmain.so (SDL3 + Vulkan + glslang + SDK runtime + src/ + generated/game/),
#      following RanggaCasper/Black-Android's unified library architecture.
#   2. Host PC (Linux/Windows, e.g. cmake --preset linux-core): builds xbe_recomp & xbe_symbols via sdk/cmake/XbeRecomp.cmake.

cmake_minimum_required(VERSION 3.22)
project(genma_recomp LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID)
    set(REPO ${CMAKE_CURRENT_SOURCE_DIR})
    set(SDK ${REPO}/sdk)
    set(NATIVE ${REPO}/native)
    set(GAME ${REPO}/src)

    set(XBE_GENERATED ${REPO}/generated/game CACHE PATH "Directory containing xbe_recomp output")
    set(XBE_IMAGE ${REPO}/assets/default.xbe CACHE FILEPATH "Path to default.xbe used during recompilation")

    if(NOT EXISTS ${XBE_GENERATED}/x86_func_mapping.cpp OR NOT EXISTS ${XBE_IMAGE})
        message(FATAL_ERROR
            "Missing recompiled game code (${XBE_GENERATED}/x86_func_mapping.cpp) or ${XBE_IMAGE}. "
            "Run xbe_recomp config/game.toml --xdk sdk/xdk first (or use tools/build_android_pc.sh / CI workflow).")
    endif()

    file(STRINGS ${REPO}/config/game.toml GAME_PARTITIONS REGEX "^partitions *=")
    string(REGEX MATCH "[0-9]+" GAME_PARTITIONS "${GAME_PARTITIONS}")
    if(NOT GAME_PARTITIONS)
        set(GAME_PARTITIONS 32)
    endif()
    math(EXPR last "${GAME_PARTITIONS} - 1")
    set(GEN_SOURCES ${XBE_GENERATED}/x86_func_mapping.cpp)
    foreach(i RANGE ${last})
        list(APPEND GEN_SOURCES ${XBE_GENERATED}/x86_recomp.${i}.cpp)
    endforeach()

    file(SHA1 ${XBE_IMAGE} xbe_sha1)
    file(CONFIGURE OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/image_sha1.cpp
         CONTENT "extern const char kImageSha1[] = \"${xbe_sha1}\";\n")

    set(BUILD_COMMIT "unknown")
    find_package(Git QUIET)
    if(GIT_EXECUTABLE)
        execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse --short HEAD WORKING_DIRECTORY ${REPO}
                        OUTPUT_VARIABLE head OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET RESULT_VARIABLE failed)
        if(NOT failed AND head)
            set(BUILD_COMMIT ${head})
        endif()
    endif()
    file(CONFIGURE OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/build_info.h
         CONTENT "#define GENMA_COMMIT \"${BUILD_COMMIT}\"\n")

    # SDL3 shared library (cloned by Gradle fetchSDL task into third_party/SDL)
    set(SDL_DIR ${REPO}/third_party/SDL)
    if(NOT EXISTS ${SDL_DIR}/CMakeLists.txt)
        message(FATAL_ERROR "SDL3 is missing from ${SDL_DIR}: run Gradle fetchSDL task first.")
    endif()
    set(SDL_SHARED ON CACHE BOOL "" FORCE)
    set(SDL_STATIC OFF CACHE BOOL "" FORCE)
    set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
    set(SDL_RENDER OFF CACHE BOOL "" FORCE)
    set(SDL_GPU OFF CACHE BOOL "" FORCE)
    set(SDL_CAMERA OFF CACHE BOOL "" FORCE)
    add_subdirectory(${SDL_DIR} ${CMAKE_CURRENT_BINARY_DIR}/SDL EXCLUDE_FROM_ALL)

    # Fetch glslang (in-process HLSL->SPIR-V) and toml++
    include(FetchContent)
    set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
    set(ENABLE_HLSL ON CACHE BOOL "" FORCE)
    set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
    set(ENABLE_SPVREMAPPER OFF CACHE BOOL "" FORCE)
    set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
    set(GLSLANG_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
    set(BUILD_EXTERNAL OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(glslang GIT_REPOSITORY https://github.com/KhronosGroup/glslang.git GIT_TAG 16.0.0 GIT_SHALLOW TRUE)
    FetchContent_Declare(tomlplusplus GIT_REPOSITORY https://github.com/marzer/tomlplusplus.git GIT_TAG v3.4.0 GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(glslang tomlplusplus)

    # SDK portable runtime (matching rafaelkenedy/genma-recomp Linux/Vulkan selection, plus Android touch overlay)
    file(GLOB RUNTIME_SOURCES CONFIGURE_DEPENDS
         ${SDK}/runtime/kernel/*.cpp
         ${SDK}/runtime/xdk/*.cpp
         ${SDK}/runtime/core/*.cpp
         ${SDK}/runtime/cpu/*.cpp)
    list(FILTER RUNTIME_SOURCES EXCLUDE REGEX "/(xnet|memory_android|wsl_controller_bridge)\\.cpp$")
    list(APPEND RUNTIME_SOURCES
         ${SDK}/runtime/app/settings.cpp
         ${SDK}/runtime/audio/sdl_audio.cpp
         ${SDK}/runtime/gpu/render_thread.cpp
         ${SDK}/runtime/gpu/vulkan.cpp
         ${SDK}/runtime/gpu/shaders.cpp
         ${SDK}/runtime/gpu/texture.cpp
         ${SDK}/runtime/gpu/vertex.cpp)

    file(GLOB GAME_HOST_SOURCES CONFIGURE_DEPENDS ${GAME}/*/*.cpp)
    list(FILTER GAME_HOST_SOURCES EXCLUDE REGEX "/acos_experiment\\.cpp$")
    file(GLOB ANDROID_SOURCES CONFIGURE_DEPENDS ${NATIVE}/*.cpp)

    add_library(main SHARED
        ${RUNTIME_SOURCES}
        ${GAME_HOST_SOURCES}
        ${ANDROID_SOURCES}
        ${GEN_SOURCES}
        ${CMAKE_CURRENT_BINARY_DIR}/image_sha1.cpp)

    target_include_directories(main PRIVATE
        ${NATIVE}/compat
        ${NATIVE}
        ${GAME}
        ${SDK}/runtime
        ${XBE_GENERATED}
        ${CMAKE_CURRENT_BINARY_DIR}
        ${glslang_SOURCE_DIR})

    # Match x86 signed-char and strict-aliasing semantics for Xbox recompiled code
    target_compile_options(main PRIVATE -fno-strict-aliasing -fsigned-char)
    target_compile_definitions(main PRIVATE XBE_RAW_VERTICES=0 XBE_DISABLE_CONTROLLER_BRIDGE=1)

    set(HOST_SOURCES ${RUNTIME_SOURCES} ${GAME_HOST_SOURCES} ${ANDROID_SOURCES})
    set_source_files_properties(${HOST_SOURCES} PROPERTIES COMPILE_OPTIONS "-include;${NATIVE}/compat/crt.h")
    set_source_files_properties(${GEN_SOURCES} PROPERTIES
        COMPILE_OPTIONS "-O2;-w;-fno-strict-aliasing;-fsigned-char")
    set_source_files_properties(${NATIVE}/vk_shader.cpp PROPERTIES COMPILE_DEFINITIONS ENABLE_HLSL)

    find_library(ANDROID_LIB android)
    find_library(LOG_LIB log)
    find_library(VULKAN_LIB vulkan)

    target_link_libraries(main PRIVATE
        SDL3::SDL3
        tomlplusplus::tomlplusplus
        glslang
        glslang-default-resource-limits
        ${VULKAN_LIB}
        ${ANDROID_LIB}
        ${LOG_LIB}
        dl)

    # 16 KB ELF page alignment for Android 15+ devices
    target_link_options(main PRIVATE -Wl,-z,max-page-size=16384)
    return()
endif()

# ---- Host PC build (xbe_recomp, xbe_symbols, and optional desktop runtime/tests) ----
set(SDK ${CMAKE_CURRENT_SOURCE_DIR}/sdk)
include(${SDK}/cmake/XbeRecomp.cmake)

file(GLOB XBE_HOST_SOURCES CONFIGURE_DEPENDS src/*/*.cpp)
list(FILTER XBE_HOST_SOURCES EXCLUDE REGEX "/src/game/")

set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS config/game.toml)
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/config/game.toml)
    file(STRINGS config/game.toml GAME_PARTITIONS REGEX "^partitions *=")
    string(REGEX MATCH "[0-9]+" GAME_PARTITIONS "${GAME_PARTITIONS}")
endif()
if(NOT GAME_PARTITIONS)
    set(GAME_PARTITIONS 32)
endif()

option(XBE_RELEASE "Build the asset-free release with the platform's bundled toolchain" OFF)
set(XBE_RELEASE_TITLE "Genma Onimusha")
set(XBE_UPDATE_REPOSITORY "EkoSaputro14/genma-recomp-android")
if(NOT XBE_BUILD_RUNTIME)
    if(XBE_RELEASE)
        message(FATAL_ERROR "XBE_RELEASE requires XBE_BUILD_RUNTIME=ON")
    endif()
    message(STATUS "Building native tools only (xbe_recomp, xbe_symbols)")
elseif(XBE_RELEASE)
    add_xbe_release(GenmaOnimusha default.xbe game.toml game)
elseif(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/assets/default.xbe)
    add_xbe_image(GenmaOnimusha default.xbe game.toml ${GAME_PARTITIONS} game)
else()
    message(STATUS "assets/default.xbe is missing: only the tools are built")
endif()

option(XBE_BUILD_TESTS "Build asset-free core and game-policy regression tests" OFF)
if(XBE_BUILD_TESTS)
    enable_testing()
    include(cmake/CoreTests.cmake)
endif()
