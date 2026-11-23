#include "lang_id.h"
#include "lang_id_data.h"
#include "host.h"
#include "kernel/kernel.h"
#include "kernel/memory.h"
#include "core/log.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstring>
#include <fstream>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace lang_id {

namespace {

uint64_t Fnv1a64(const uint8_t* data, size_t len) {
    uint64_t h = 0xCBF29CE484222325ull;
    for (size_t i = 0; i < len; ++i) {
        h ^= data[i];
        h *= 0x100000001B3ull;
    }
    return h;
}

uint32_t ReadU32(const uint8_t* p) {
    uint32_t v;
    std::memcpy(&v, p, 4);
    return v;
}

uint16_t ReadU16(const uint8_t* p) {
    uint16_t v;
    std::memcpy(&v, p, 2);
    return v;
}

void WriteU32(uint8_t* p, uint32_t v) { std::memcpy(p, &v, 4); }
void WriteU16(uint8_t* p, uint16_t v) { std::memcpy(p, &v, 2); }

uint8_t ParseHexByte(const char* p) {
    auto hex = [](char c) -> uint8_t {
        if (c >= '0' && c <= '9') return uint8_t(c - '0');
        if (c >= 'a' && c <= 'f') return uint8_t(10 + (c - 'a'));
        if (c >= 'A' && c <= 'F') return uint8_t(10 + (c - 'A'));
        return 0;
    };
    return uint8_t((hex(p[0]) << 4) | hex(p[1]));
}

// Encodes UTF-8/ASCII markup ({F4:00}, {PAGE}, {END}, {F7}, {FD}, {FA:74}, \n) to Genma's bytecode.
std::vector<uint8_t> EncodeGenma(const std::string& text) {
    std::vector<uint8_t> out;
    out.reserve(text.size() * 2);
    for (size_t i = 0; i < text.size();) {
        char c = text[i];
        if (c == '{') {
            size_t close = text.find('}', i + 1);
            if (close != std::string::npos) {
                std::string tag = text.substr(i + 1, close - (i + 1));
                if (tag == "END") {
                    out.push_back(0xF0);
                } else if (tag == "PAGE") {
                    out.push_back(0xF1);
                } else if (tag == "FD") {
                    out.push_back(0xFD);
                } else if (tag == "F7") {
                    out.push_back(0xF7);
                } else if (tag.rfind("RAW:", 0) == 0 && tag.size() == 6) {
                    out.push_back(ParseHexByte(tag.c_str() + 4));
                } else if (tag.size() == 5 && tag[2] == ':') {
                    out.push_back(ParseHexByte(tag.c_str()));
                    out.push_back(ParseHexByte(tag.c_str() + 3));
                }
                i = close + 1;
                continue;
            }
        }
        if (c == '\n') {
            out.push_back(0xF2);
            i++;
        } else if (c == ' ') {
            out.push_back(0x00);
            i++;
        } else {
            uint8_t u = uint8_t(c);
            if (u >= 0x21 && u <= 0x7E) {
                out.push_back(0xFA);
                out.push_back(uint8_t(u - 0x20));
            } else {
                out.push_back(0x00);
            }
            i++;
        }
    }
    return out;
}

std::string UnescapeTsv(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '\\' && i + 1 < s.size()) {
            if (s[i + 1] == 'n') {
                out.push_back('\n');
                ++i;
                continue;
            }
            if (s[i + 1] == 't') {
                out.push_back('\t');
                ++i;
                continue;
            }
            if (s[i + 1] == '\\') {
                out.push_back('\\');
                ++i;
                continue;
            }
        }
        out.push_back(s[i]);
    }
    return out;
}

struct CustomOverride {
    std::vector<uint8_t> bytes;
    bool needs_f0_arg = false;
};

std::mutex g_override_lock;
std::unordered_map<uint64_t, CustomOverride> g_custom_overrides;

bool FindCustomOverride(uint64_t hash, CustomOverride& out) {
    std::lock_guard<std::mutex> lock(g_override_lock);
    auto it = g_custom_overrides.find(hash);
    if (it == g_custom_overrides.end()) return false;
    out = it->second;
    return true;
}

const char* FindBuiltinRdt(uint64_t hash) {
    const auto* begin = data::kRdtMessagesId;
    const auto* end = data::kRdtMessagesId + data::kRdtMessageCount;
    auto it = std::lower_bound(begin, end, hash, [](const data::HashTranslation& e, uint64_t h) {
        return e.hash < h;
    });
    if (it != end && it->hash == hash) {
        return it->text;
    }
    return nullptr;
}

// ---- Default.xbe `.data` table hot-swapping ----

struct XbeTableSpec {
    const char* name;
    uint32_t data_va;
    uint32_t data_cap;
    uint32_t table_va;
    size_t count;
    uint8_t term_byte;
    const char* const* translations;
    std::vector<uint8_t> orig_data;
    std::vector<uint8_t> orig_table;
};

std::mutex g_xbe_lock;
bool g_xbe_captured = false;
bool g_xbe_applied_id = false;

XbeTableSpec g_xbe_tables[4] = {
    {"items", 0x00202D80u, 3304u, 0x00203A68u, data::kXbeItemNameCount, 0xF7, data::kXbeItemNamesId, {}, {}},
    {"sys_msgs", 0x00203BB8u, 2596u, 0x002045DCu, data::kXbeSysMsgCount, 0xF0, data::kXbeSysMsgsId, {}, {}},
    {"item_descs", 0x00204610u, 6464u, 0x00205F50u, data::kXbeItemDescCount, 0xF0, data::kXbeItemDescsId, {}, {}},
    {"choices", 0x00206028u, 232u, 0x00206110u, data::kXbeChoiceCount, 0xF0, data::kXbeChoicesId, {}, {}},
};

bool VerifyUsaRetailTables() {
    if (!kernel::g_base) return false;
    for (const auto& t : g_xbe_tables) {
        if (!mem::IsCommitted(t.data_va) || !mem::IsCommitted(t.data_va + t.data_cap - 1) ||
            !mem::IsCommitted(t.table_va) || !mem::IsCommitted(t.table_va + uint32_t(t.count * 2) - 1)) {
            return false;
        }
    }
    return kernel::R16(0x00203A68u) == 0 && kernel::R16(0x00203A6Au) == 7 &&
           kernel::R16(0x002045DCu) == 0 && kernel::R16(0x002045DEu) == 0x2Eu &&
           kernel::R16(0x00205F50u) == 0 && kernel::R16(0x00205F52u) == 2 && kernel::R16(0x00205F54u) == 0x55u &&
           kernel::R16(0x00206110u) == 0 && kernel::R16(0x00206112u) == 8;
}

bool PackXbeTableLocked(const XbeTableSpec& spec, std::vector<uint8_t>& out_data, std::vector<uint8_t>& out_table) {
    out_data.clear();
    out_data.reserve(spec.data_cap);
    out_table.resize(spec.count * 2, 0);
    std::unordered_map<std::string, uint16_t> dedup;
    dedup.reserve(spec.count);

    for (size_t i = 0; i < spec.count; ++i) {
        std::vector<uint8_t> entry;
        const uint16_t orig_off = ReadU16(spec.orig_table.data() + i * 2);
        const uint8_t* p = (orig_off < spec.orig_data.size()) ? (spec.orig_data.data() + orig_off) : nullptr;
        const size_t rem = p ? (spec.orig_data.size() - orig_off) : 0;
        const void* found = p ? std::memchr(p, spec.term_byte, rem) : nullptr;
        const size_t term_idx = found ? size_t(static_cast<const uint8_t*>(found) - p) : 0;
        // 0xF0 ({END}) is a 2-byte opcode (`0xF0 <arg>`) in Genma's text VM; 0xF7 is a 1-byte terminator.
        const uint8_t orig_arg = (spec.term_byte == 0xF0 && found && term_idx + 1 < rem) ? p[term_idx + 1] : 0x00;

        if (spec.translations[i] != nullptr) {
            entry = EncodeGenma(spec.translations[i]);
            if (spec.term_byte == 0xF0 && !entry.empty() && entry.back() == 0xF0) {
                entry.push_back(orig_arg);
            }
        } else {
            if (p && rem > 0) {
                size_t len = found ? (term_idx + (spec.term_byte == 0xF0 ? 2u : 1u)) : std::min<size_t>(rem, 1);
                len = std::min(len, rem);
                entry.assign(p, p + len);
            } else {
                entry.push_back(spec.term_byte);
                if (spec.term_byte == 0xF0) {
                    entry.push_back(0x00);
                }
            }
        }
        std::string key(reinterpret_cast<const char*>(entry.data()), entry.size());
        auto it = dedup.find(key);
        uint16_t new_off = 0;
        if (it != dedup.end()) {
            new_off = it->second;
        } else {
            if (out_data.size() + entry.size() > spec.data_cap) {
                LOGW("lang_id: XBE table %s overflow (%zu > %u)", spec.name, out_data.size() + entry.size(), spec.data_cap);
                return false;
            }
            new_off = uint16_t(out_data.size());
            dedup.emplace(std::move(key), new_off);
            out_data.insert(out_data.end(), entry.begin(), entry.end());
        }
        WriteU16(out_table.data() + i * 2, new_off);
    }
    if (out_data.size() < spec.data_cap) {
        out_data.resize(spec.data_cap, 0);
    }
    return true;
}

void ApplyXbeLocked(bool indonesian) {
    if (!g_xbe_captured || g_xbe_applied_id == indonesian) return;
    if (!indonesian) {
        for (const auto& t : g_xbe_tables) {
            std::memcpy(kernel::HostPtr(t.data_va), t.orig_data.data(), t.orig_data.size());
            std::memcpy(kernel::HostPtr(t.table_va), t.orig_table.data(), t.orig_table.size());
        }
        g_xbe_applied_id = false;
        LOGR("lang_id: XBE tables restored to English");
        return;
    }
    std::vector<uint8_t> datas[4];
    std::vector<uint8_t> tables[4];
    for (int i = 0; i < 4; ++i) {
        if (!PackXbeTableLocked(g_xbe_tables[i], datas[i], tables[i])) {
            return;
        }
    }
    for (int i = 0; i < 4; ++i) {
        std::memcpy(kernel::HostPtr(g_xbe_tables[i].data_va), datas[i].data(), datas[i].size());
        std::memcpy(kernel::HostPtr(g_xbe_tables[i].table_va), tables[i].data(), tables[i].size());
    }
    g_xbe_applied_id = true;
    LOGR("lang_id: XBE Bahasa Indonesia aktif (items=%zu, sys=%zu, descs=%zu, choices=%zu)",
         g_xbe_tables[0].count, g_xbe_tables[1].count, g_xbe_tables[2].count, g_xbe_tables[3].count);
}

std::string NormalizeUnixLower(std::string s) {
    std::replace(s.begin(), s.end(), '\\', '/');
    for (char& c : s) c = char(std::tolower(uint8_t(c)));
    return s;
}

bool EndsWith(const std::string& s, const char* suffix) {
    size_t n = std::strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

}  // namespace

void OnXbeLoaded() {
    std::lock_guard<std::mutex> lock(g_xbe_lock);
    if (!VerifyUsaRetailTables()) {
        LOGW("lang_id: XBE language tables did not match USA retail signature; XBE text hook disabled");
        return;
    }
    for (auto& t : g_xbe_tables) {
        t.orig_data.resize(t.data_cap);
        t.orig_table.resize(t.count * 2);
        std::memcpy(t.orig_data.data(), kernel::HostPtr(t.data_va), t.data_cap);
        std::memcpy(t.orig_table.data(), kernel::HostPtr(t.table_va), t.count * 2);
    }
    g_xbe_captured = true;
    g_xbe_applied_id = false;
    if (host::IndonesianGame()) {
        ApplyXbeLocked(true);
    }
}

void ApplyXbeLanguage(bool indonesian) {
    std::lock_guard<std::mutex> lock(g_xbe_lock);
    ApplyXbeLocked(indonesian);
}

void LoadCustomOverrides(const std::filesystem::path& game_dir) {
    std::filesystem::path tsv = game_dir / "lang" / "id" / "custom_id.tsv";
    std::ifstream in(tsv);
    if (!in) return;

    std::unordered_map<uint64_t, CustomOverride> loaded;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        if (!line.empty() && line.back() == '\r') line.pop_back();
        size_t tab = line.find('\t');
        if (tab == std::string::npos || tab == 0) continue;
        std::string hash_str = line.substr(0, tab);
        std::string markup = UnescapeTsv(line.substr(tab + 1));
        uint64_t h = 0;
        try {
            h = std::stoull(hash_str, nullptr, 16);
        } catch (...) {
            continue;
        }
        CustomOverride ov;
        ov.bytes = EncodeGenma(markup);
        ov.needs_f0_arg = EndsWith(markup, "{END}") && !ov.bytes.empty() && ov.bytes.back() == 0xF0;
        loaded[h] = std::move(ov);
    }
    {
        std::lock_guard<std::mutex> lock(g_override_lock);
        g_custom_overrides = std::move(loaded);
        LOGR("lang_id: loaded %zu custom overrides from %s", g_custom_overrides.size(), tsv.c_str());
    }
}

std::vector<BytePatch> BuildFilePatches(const std::string& xbox_path, uint64_t file_size, const ReadRangeFn& reader) {
    std::vector<BytePatch> result;
    if (!host::IndonesianGame() || !reader || file_size < 16) return result;

    const std::string lower = NormalizeUnixLower(xbox_path);
    int target_sec = -1;
    bool is_rdt = false;
    if (EndsWith(lower, ".rdt")) {
        target_sec = 10;
        is_rdt = true;
    } else if (EndsWith(lower, "/cardck.ldt") || lower == "cardck.ldt") {
        target_sec = 1;
    } else if (EndsWith(lower, "/e_title.ldt") || EndsWith(lower, "/title.ldt") ||
               lower == "e_title.ldt" || lower == "title.ldt") {
        target_sec = 2;
    } else {
        return result;
    }

    uint8_t link_head[8]{};
    if (reader(0, link_head, sizeof(link_head)) != sizeof(link_head) ||
        std::memcmp(link_head, "LINK", 4) != 0) {
        return result;
    }
    const uint32_t count = ReadU32(link_head + 4);
    const uint32_t min_count = is_rdt ? 13u : uint32_t(target_sec + 1);
    if (count < min_count || count > 256u) return result;

    const size_t table_bytes = size_t(count) * 12u;
    if (8ull + uint64_t(table_bytes) > file_size) return result;
    std::vector<uint8_t> link_tbl(table_bytes);
    if (reader(8, link_tbl.data(), link_tbl.size()) != link_tbl.size()) return result;

    const size_t e_off = size_t(target_sec) * 12;
    const uint32_t sec_type = ReadU32(link_tbl.data() + e_off);
    const uint32_t sec_off = ReadU32(link_tbl.data() + e_off + 4);
    const uint32_t sec_sz = ReadU32(link_tbl.data() + e_off + 8);
    if (sec_sz < 4 || uint64_t(sec_off) + sec_sz > file_size || sec_sz > 262144u) return result;

    uint32_t window_cap = sec_sz;
    if (is_rdt) {
        const size_t e11 = 11 * 12;
        const size_t e12 = 12 * 12;
        const uint32_t off11 = ReadU32(link_tbl.data() + e11 + 4);
        const uint32_t off12 = ReadU32(link_tbl.data() + e12 + 4);
        const uint32_t sz12 = ReadU32(link_tbl.data() + e12 + 8);
        const uint32_t aligned10_end = (sec_off + sec_sz + 15u) & ~15u;
        if (off11 == aligned10_end && off12 >= off11 && uint64_t(off12) + sz12 <= file_size) {
            window_cap = (off12 + sz12) - sec_off;
        }
    }

    std::vector<uint8_t> orig_sec(sec_sz);
    if (reader(sec_off, orig_sec.data(), sec_sz) != sec_sz) return result;

    const uint16_t first_off = ReadU16(orig_sec.data());
    if (first_off < 2 || (first_off & 1u) != 0 || first_off >= sec_sz) return result;
    const size_t n_msgs = first_off / 2;

    std::vector<uint8_t> new_sec(first_off, 0);
    std::unordered_map<std::string, uint16_t> dedup;
    dedup.reserve(n_msgs);
    size_t replaced = 0;

    for (size_t i = 0; i < n_msgs; ++i) {
        const uint16_t s = ReadU16(orig_sec.data() + i * 2);
        const uint16_t e = (i + 1 < n_msgs) ? ReadU16(orig_sec.data() + (i + 1) * 2) : uint16_t(sec_sz);
        if (s >= sec_sz || e < s) return {};
        const uint8_t* raw_ptr = orig_sec.data() + s;
        size_t raw_len = e - s;
        const void* f0 = std::memchr(raw_ptr, 0xF0, raw_len);
        const size_t key_len = f0 ? size_t(static_cast<const uint8_t*>(f0) - raw_ptr) + 1 : raw_len;
        const uint8_t orig_f0_arg = (f0 && key_len < raw_len) ? raw_ptr[key_len] : 0x00;

        const uint64_t h = Fnv1a64(raw_ptr, key_len);
        std::vector<uint8_t> payload;
        CustomOverride custom;
        if (FindCustomOverride(h, custom)) {
            payload = std::move(custom.bytes);
            if (f0 && custom.needs_f0_arg) {
                payload.push_back(orig_f0_arg);
            }
            replaced++;
        } else if (const char* id_str = FindBuiltinRdt(h)) {
            payload = EncodeGenma(id_str);
            if (f0 && !payload.empty() && payload.back() == 0xF0) {
                payload.push_back(orig_f0_arg);
            }
            replaced++;
        } else {
            // Preserve untranslated slices verbatim (including 0xF0 <arg> cutscene timers and non-0xF0 records).
            payload.assign(raw_ptr, raw_ptr + raw_len);
        }

        std::string dedup_key(reinterpret_cast<const char*>(payload.data()), payload.size());
        auto it = dedup.find(dedup_key);
        uint16_t dst_off = 0;
        if (it != dedup.end()) {
            dst_off = it->second;
        } else {
            if (new_sec.size() + payload.size() > 65535u || new_sec.size() + payload.size() > window_cap) {
                LOGW("lang_id: %s sec[%d] overflow (%zu > %u), keeping English", xbox_path.c_str(), target_sec,
                     new_sec.size() + payload.size(), window_cap);
                return {};
            }
            dst_off = uint16_t(new_sec.size());
            dedup.emplace(std::move(dedup_key), dst_off);
            new_sec.insert(new_sec.end(), payload.begin(), payload.end());
        }
        WriteU16(new_sec.data() + i * 2, dst_off);
    }

    if (replaced == 0) return result;

    if (new_sec.size() <= sec_sz) {
        new_sec.resize(sec_sz, 0);
        result.push_back(BytePatch{sec_off, std::move(new_sec)});
    } else {
        uint32_t aligned_new = (uint32_t(new_sec.size()) + 15u) & ~15u;
        if (aligned_new > window_cap) {
            LOGW("lang_id: %s aligned sec[%d] overflow (%u > %u), keeping English", xbox_path.c_str(), target_sec,
                 aligned_new, window_cap);
            return {};
        }
        new_sec.resize(aligned_new, 0);
        std::vector<uint8_t> hdr_patch(36, 0);
        WriteU32(hdr_patch.data() + 0, sec_type);
        WriteU32(hdr_patch.data() + 4, sec_off);
        WriteU32(hdr_patch.data() + 8, aligned_new);
        WriteU32(hdr_patch.data() + 12, 0u);
        WriteU32(hdr_patch.data() + 16, sec_off + aligned_new);
        WriteU32(hdr_patch.data() + 20, 0u);
        WriteU32(hdr_patch.data() + 24, 0u);
        WriteU32(hdr_patch.data() + 28, sec_off + aligned_new);
        WriteU32(hdr_patch.data() + 32, 0u);
        result.push_back(BytePatch{8ull + uint64_t(e_off), std::move(hdr_patch)});
        result.push_back(BytePatch{sec_off, std::move(new_sec)});
    }

    static std::atomic<int> logged{0};
    if (logged.fetch_add(1) < 12) {
        LOGR("lang_id: patched %s sec[%d] (%zu/%zu msgs -> %zu B)", xbox_path.c_str(), target_sec, replaced, n_msgs,
             result.back().data.size());
    }
    return result;
}

void ApplyBytePatches(const std::vector<BytePatch>& patches, uint64_t file_offset, void* dst, size_t len) {
    if (patches.empty() || !dst || len == 0) return;
    const uint64_t read_end = file_offset + len;
    uint8_t* out = static_cast<uint8_t*>(dst);
    for (const auto& p : patches) {
        if (p.data.empty()) continue;
        const uint64_t p_start = p.offset;
        const uint64_t p_end = p.offset + p.data.size();
        if (p_end <= file_offset || p_start >= read_end) continue;
        const uint64_t lo = std::max(file_offset, p_start);
        const uint64_t hi = std::min(read_end, p_end);
        std::memcpy(out + (lo - file_offset), p.data.data() + (lo - p_start), size_t(hi - lo));
    }
}

}  // namespace lang_id
