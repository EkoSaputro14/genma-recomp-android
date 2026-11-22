#pragma once
// In-process HLSL to SPIR-V compiler via glslang with on-disk cache.
// Replaces desktop posix_spawnp("dxc") calls blocked by Android SELinux W^X policies.
// Descriptor set 0 bindings match sdk/runtime/gpu/vulkan.cpp:
//   b0..b1 -> 0..1, t0..t3 -> 2..5, s0..s3 -> 6..9.
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace vkshader {

constexpr uint32_t kBindingConstants = 0;  // b0
constexpr uint32_t kBindingTextures = 2;   // t0
constexpr uint32_t kBindingSamplers = 6;   // s0

enum class Stage { Vertex, Pixel };

void Init(const std::filesystem::path& cache_dir);
bool Compile(const std::string& hlsl, const char* entry, Stage stage, uint64_t hash, std::vector<uint32_t>& spirv);

}  // namespace vkshader
