// Bounded HD Texture Pack loader (DDS / per-palette) tailored for Android Scoped Storage & mobile RAM.

#include <cstdint>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace gpu_android {

struct TextureEntry {
    uint64_t hash = 0;
    size_t bytes = 0;
    std::vector<uint8_t> ddsData;
};

class MobileHdTextureCache {
public:
    explicit MobileHdTextureCache(size_t budgetBytes = 536870912ull)
        : budgetBytes_(budgetBytes) {}

    void SetRootDirectory(const std::string& path);
    const std::string& RootDirectory() const { return rootDir_; }
    size_t BudgetBytes() const { return budgetBytes_; }
    size_t UsedBytes() const;
    void Insert(uint64_t hash, std::vector<uint8_t> payload);
    void Clear();

private:
    mutable std::mutex mutex_;
    std::string rootDir_;
    size_t budgetBytes_;
    size_t usedBytes_ = 0;
    std::list<TextureEntry> lru_;
    std::unordered_map<uint64_t, std::list<TextureEntry>::iterator> index_;
};

void MobileHdTextureCache::SetRootDirectory(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex_);
    rootDir_ = path;
}

size_t MobileHdTextureCache::UsedBytes() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return usedBytes_;
}

void MobileHdTextureCache::Insert(uint64_t hash, std::vector<uint8_t> payload) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (payload.size() > budgetBytes_) {
        return;
    }
    auto existing = index_.find(hash);
    if (existing != index_.end()) {
        usedBytes_ -= existing->second->bytes;
        lru_.erase(existing->second);
        index_.erase(existing);
    }

    while (usedBytes_ + payload.size() > budgetBytes_ && !lru_.empty()) {
        auto& victim = lru_.back();
        usedBytes_ -= victim.bytes;
        index_.erase(victim.hash);
        lru_.pop_back();
    }

    const size_t size = payload.size();
    lru_.push_front(TextureEntry{hash, size, std::move(payload)});
    index_[hash] = lru_.begin();
    usedBytes_ += size;
}

void MobileHdTextureCache::Clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    lru_.clear();
    index_.clear();
    usedBytes_ = 0;
}

MobileHdTextureCache& GetMobileTextureCache() {
    static MobileHdTextureCache cache(536870912ull);
    return cache;
}

void SetMobileTextureRootDirectory(const std::string& path) {
    GetMobileTextureCache().SetRootDirectory(path);
}

} // namespace gpu_android
