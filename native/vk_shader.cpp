// HLSL to SPIR-V for Android Vulkan through glslang's HLSL front end, with disk cache.
// Adapted from RanggaCasper/Black-Android for rafaelkenedy/genma-recomp's Vulkan backend.
#include "vk_shader.h"

#include <cstdio>
#include <fstream>
#include <mutex>
#include <regex>

#include <SPIRV/GlslangToSpv.h>
#include <glslang/Public/ResourceLimits.h>
#include <glslang/Public/ShaderLang.h>

#include "core/log.h"

namespace vkshader {

namespace {

std::filesystem::path g_dir;
std::mutex g_lock;
bool g_initialized = false;
constexpr uint32_t kCacheVersion = 1;

struct Param {
    std::string type, name;
};

bool Opaque(const std::string& type) {
    return type.find("Texture") != std::string::npos || type.find("Sampler") != std::string::npos;
}

std::vector<Param> Params(const std::string& list) {
    std::vector<Param> out;
    static const std::regex one(R"(\s*(.*?)\s*(\w+)\s*(,|$))");
    for (auto it = std::sregex_iterator(list.begin(), list.end(), one); it != std::sregex_iterator(); ++it)
        if ((*it)[2].length()) out.push_back({(*it)[1], (*it)[2]});
    return out;
}

size_t CloseOf(const std::string& s, size_t open) {
    int depth = 0;
    for (size_t i = open; i < s.size(); i++) {
        if (s[i] == '{') depth++;
        if (s[i] == '}' && --depth == 0) return i + 1;
    }
    return std::string::npos;
}

// Specialize helper functions taking Texture/Sampler parameters to avoid opaque-type store VUID 06924 on Mali GPUs.
std::string Specialize(const std::string& hlsl) {
    static const std::regex def(R"(\n(\w[\w<>]*)[ \t]+(\w+)\(([^()]*)\)\s*\{)");
    std::string s = hlsl, made;
    size_t from = 0;
    for (std::smatch m; std::regex_search(s.cbegin() + long(from), s.cend(), m, def);) {
        size_t start = from + size_t(m.position(0));
        size_t open = start + size_t(m.length(0)) - 1;
        size_t end = CloseOf(s, open);
        std::vector<Param> params = Params(m[3]);
        size_t opaque = 0;
        while (opaque < params.size() && Opaque(params[opaque].type)) opaque++;
        if (!opaque || end == std::string::npos) {
            from = open;
            continue;
        }
        std::string type = m[1], name = m[2], body = s.substr(open, end - open), rest;
        for (size_t i = opaque; i < params.size(); i++)
            rest += (i > opaque ? ", " : "") + params[i].type + " " + params[i].name;
        s.erase(start, end - start);
        std::string pattern = "\\b" + name + "\\(";
        for (size_t i = 0; i < opaque; i++) pattern += R"(\s*(\w+)\s*,)";
        const std::regex call(pattern);
        std::string out;
        size_t at = 0;
        for (std::smatch c; std::regex_search(s.cbegin() + long(at), s.cend(), c, call);) {
            std::string spec = name, copy = body;
            for (size_t i = 0; i < opaque; i++) {
                spec += "_" + c[i + 1].str();
                copy = std::regex_replace(copy, std::regex("\\b" + params[i].name + "\\b"), c[i + 1].str());
            }
            std::string decl = "\n" + type + " " + spec + "(" + rest + ") " + copy + "\n";
            if (made.find(decl) == std::string::npos) made += decl;
            out += s.substr(at, size_t(c.position(0))) + spec + "(";
            at += size_t(c.position(0)) + size_t(c.length(0));
        }
        s = out + s.substr(at);
    }
    if (made.empty()) return hlsl;
    size_t main_pos = s.rfind("\nfloat4 main(");
    if (main_pos == std::string::npos) return hlsl;
    return s.insert(main_pos, made);
}

std::string Adapt(const std::string& hlsl) {
    std::string s = Specialize(hlsl);
    for (size_t at; (at = s.find("1.#INF")) != std::string::npos;) s.replace(at, 6, "asfloat(0x7F800000u)");
    return s;
}

std::filesystem::path CachePath(uint64_t hash) {
    char name[48];
    std::snprintf(name, sizeof(name), "%016llx_v%u.spv", (unsigned long long)hash, kCacheVersion);
    return g_dir / name;
}

bool Load(uint64_t hash, std::vector<uint32_t>& spirv) {
    if (g_dir.empty()) return false;
    std::ifstream f(CachePath(hash), std::ios::binary | std::ios::ate);
    if (!f) return false;
    std::streamsize n = f.tellg();
    if (n <= 0 || n % 4) return false;
    spirv.resize(size_t(n) / 4);
    f.seekg(0);
    return bool(f.read(reinterpret_cast<char*>(spirv.data()), n));
}

void Store(uint64_t hash, const std::vector<uint32_t>& spirv) {
    if (g_dir.empty()) return;
    std::filesystem::path p = CachePath(hash), tmp = p;
    tmp += ".tmp";
    {
        std::ofstream f(tmp, std::ios::binary);
        if (!f) return;
        f.write(reinterpret_cast<const char*>(spirv.data()), std::streamsize(spirv.size() * 4));
    }
    std::error_code ec;
    std::filesystem::rename(tmp, p, ec);
}

}  // namespace

void Init(const std::filesystem::path& cache_dir) {
    std::lock_guard lock(g_lock);
    if (!g_initialized) {
        glslang::InitializeProcess();
        g_initialized = true;
    }
    std::error_code ec;
    std::filesystem::create_directories(cache_dir, ec);
    g_dir = ec ? std::filesystem::path() : cache_dir;
}

bool Compile(const std::string& hlsl, const char* entry, Stage stage, uint64_t hash, std::vector<uint32_t>& spirv) {
    if (Load(hash, spirv)) return true;
    std::lock_guard lock(g_lock);
    if (!g_initialized) {
        glslang::InitializeProcess();
        g_initialized = true;
    }
    EShLanguage lang = stage == Stage::Vertex ? EShLangVertex : EShLangFragment;
    std::string text = Adapt(hlsl);
    const char* src = text.c_str();
    glslang::TShader sh(lang);
    sh.setStrings(&src, 1);
    sh.setEntryPoint("main");
    sh.setSourceEntryPoint(entry);
    sh.setEnvInput(glslang::EShSourceHlsl, lang, glslang::EShClientVulkan, 100);
    sh.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_1);
    sh.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_3);
    sh.setHlslIoMapping(true);
    sh.setAutoMapBindings(true);
    sh.setAutoMapLocations(true);
    sh.setShiftBinding(glslang::EResUbo, kBindingConstants);
    sh.setShiftBinding(glslang::EResTexture, kBindingTextures);
    sh.setShiftBinding(glslang::EResSampler, kBindingSamplers);
    if (stage == Stage::Vertex) {
        sh.setInvertY(true);
    }
    auto messages = EShMessages(EShMsgSpvRules | EShMsgVulkanRules | EShMsgReadHlsl | EShMsgHlslOffsets);
    if (!sh.parse(GetDefaultResources(), 100, false, messages)) {
        LOGE("vulkan: %s %s does not compile: %s", stage == Stage::Vertex ? "vertex" : "pixel", entry, sh.getInfoLog());
        return false;
    }
    glslang::TProgram program;
    program.addShader(&sh);
    if (!program.link(messages) || !program.mapIO()) {
        LOGE("vulkan: %s does not link: %s", entry, program.getInfoLog());
        return false;
    }
    glslang::SpvOptions options;
    options.disableOptimizer = true;
    options.validate = false;
    spirv.clear();
    glslang::GlslangToSpv(*program.getIntermediate(lang), spirv, &options);
    if (spirv.empty()) return false;
    Store(hash, spirv);
    return true;
}

}  // namespace vkshader
