#pragma once
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>

namespace lang_id {

struct BytePatch {
    uint64_t offset = 0;
    std::vector<uint8_t> data;
};

using ReadRangeFn = std::function<size_t(uint64_t off, void* dst, size_t len)>;

// Captures the 4 XBE text tables after image load and applies Bahasa Indonesia if enabled.
void OnXbeLoaded();

// Thread-safe live toggle for the 4 XBE RAM text tables (item names, sys msgs, descriptions, choices).
void ApplyXbeLanguage(bool indonesian);

// Loads optional <game_dir>/lang/id/custom_id.tsv (<16-hex-hash>\t<markup>) community overrides.
void LoadCustomOverrides(const std::filesystem::path& game_dir);

// Builds non-destructive read-time patches for .rdt / cardck.ldt / e_title.ldt / title.ldt.
std::vector<BytePatch> BuildFilePatches(const std::string& xbox_path, uint64_t file_size, const ReadRangeFn& reader);

// Applies any intersecting patches into dst for bytes read from [file_offset, file_offset + len).
void ApplyBytePatches(const std::vector<BytePatch>& patches, uint64_t file_offset, void* dst, size_t len);

}  // namespace lang_id
