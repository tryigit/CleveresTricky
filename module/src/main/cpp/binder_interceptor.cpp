#include "kernel/binder.h"
#include <android/log.h>
#include <binder/Binder.h>
#include <binder/Common.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <binder/Parcel.h>
#include <cstring>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <unistd.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <mutex>
#include <shared_mutex>
#include <set>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include <sys/system_properties.h>

#include "cleverestricky_native_core.h"
#include "lsplt.hpp"

#define LOG_TAG "CleveresTricky"
#ifdef DEBUG_BUILD
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) (void)0
#endif
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace android;

namespace cleverestricky::kernel_identity {
namespace {
std::mutex g_config_mutex;
std::atomic<bool> g_enabled{false};
std::atomic<bool> g_hooks_installed{false};
std::string g_release;
std::string g_version;

bool valid_field(const std::string &value, size_t capacity) {
  if (value.empty() || value.size() >= capacity) return false;
  for (const unsigned char ch : value) {
    if (ch < 0x20 || ch > 0x7e || ch == '|') return false;
  }
  return true;
}

void copy_field(char *destination, size_t capacity, const std::string &value) {
  std::memset(destination, 0, capacity);
  std::memcpy(destination, value.data(), value.size());
}

int hooked_uname(struct utsname *buffer) {
  const int result = static_cast<int>(syscall(SYS_uname, buffer));
  if (result != 0 || buffer == nullptr || !g_enabled.load(std::memory_order_acquire)) return result;
  std::lock_guard<std::mutex> guard(g_config_mutex);
  if (!g_enabled.load(std::memory_order_relaxed)) return result;
  copy_field(buffer->release, sizeof(buffer->release), g_release);
  copy_field(buffer->version, sizeof(buffer->version), g_version);
  return result;
}

bool candidate_path(const std::string &path) {
  if (path.empty() || path[0] == '[' || path.find("libcleverestricky.so") != std::string::npos) return false;
  if (path.ends_with("/keystore2")) return true;
  return path.ends_with(".so") &&
         (path.starts_with("/system/") || path.starts_with("/apex/") || path.starts_with("/vendor/") ||
          path.starts_with("/product/") || path.starts_with("/system_ext/"));
}
}  // namespace

void configure(const char *payload) {
  bool enabled = false;
  std::string release;
  std::string version;
  if (payload != nullptr) {
    const std::string value(payload);
    const size_t first = value.find('|');
    const size_t second = first == std::string::npos ? std::string::npos : value.find('|', first + 1);
    if (first != std::string::npos && second != std::string::npos && value.substr(0, first) == "1") {
      release = value.substr(first + 1, second - first - 1);
      version = value.substr(second + 1);
      enabled = valid_field(release, sizeof(utsname{}.release)) && valid_field(version, sizeof(utsname{}.version));
    }
  }
  {
    std::lock_guard<std::mutex> guard(g_config_mutex);
    g_release = enabled ? release : std::string{};
    g_version = enabled ? version : std::string{};
    g_enabled.store(enabled, std::memory_order_release);
  }
}

bool install_hooks_if_enabled() {
  if (!g_enabled.load(std::memory_order_acquire)) return true;
  if (g_hooks_installed.load(std::memory_order_acquire)) return true;

  auto maps = lsplt::MapInfo::Scan();
  std::set<std::pair<dev_t, ino_t>> seen;
  size_t installed = 0;
  for (const auto &map : maps) {
    if (!candidate_path(map.path) || map.dev == 0 || map.inode == 0 || !seen.emplace(map.dev, map.inode).second) continue;
    void *backup = nullptr;
    if (!lsplt::RegisterHook(map.dev, map.inode, "uname", reinterpret_cast<void *>(hooked_uname), &backup)) continue;
    const bool committed = lsplt::CommitHook();
    if (committed && backup != nullptr) ++installed;
  }
  if (installed == 0) return false;
  g_hooks_installed.store(true, std::memory_order_release);
  return true;
}
}  // namespace cleverestricky::kernel_identity

struct OffsetCache {
  size_t target_ptr_offset = 0;
  size_t cookie_offset = 0;
  size_t code_offset = 0;
  size_t flags_offset = 0;
  size_t sender_pid_offset = 0;
  size_t sender_euid_offset = 0;
  size_t data_size_offset = 0;
  size_t data_ptr_offset = 0;
  size_t transaction_data_size = 0;
  size_t transaction_data_secctx_size = 0;
  size_t bwr_write_size_offset = 0;
  size_t bwr_write_consumed_offset = 0;
  size_t bwr_write_buffer_offset = 0;
  size_t bwr_read_size_offset = 0;
  size_t bwr_read_consumed_offset = 0;
  size_t bwr_read_buffer_offset = 0;
  size_t bwr_total_size = 0;
  bool valid = false;
  static OffsetCache &instance();
  bool validateOffsets() const;
};

class RuntimeLayoutValidator {
public:
  static bool validateLayout(OffsetCache &cache);

private:
  static bool sendPingProbe(uint8_t *out_buf, size_t buf_size, size_t &out_len);
  static bool analyzeProbeResult(const uint8_t *buf, size_t len,
                                 OffsetCache &cache);
};

class BinderStreamParser {
public:
  using ParsedTransaction = RustParsedTransaction;

  static bool parse(uintptr_t buffer, size_t consumed, size_t buffer_size,
                    const OffsetCache &cache, ParsedTransaction *out_txns,
                    size_t max_txns, size_t &out_txn_count);
  static bool writeBack(uintptr_t buffer_ptr, size_t consumed,
                        const ParsedTransaction &txn, const OffsetCache &cache);
};

class AdaptiveBinderInterceptor {
public:
  bool initialize();
  static int detectApiLevel();
  static bool parseKernelVersion(int &major, int &minor);

private:
  bool initFallback(OffsetCache &cache, int api_level);
};

struct WpIBinderHash {
  std::size_t operator()(const wp<IBinder> &ptr) const {
    return std::hash<IBinder *>()(ptr.unsafe_get());
  }
};

struct WpIBinderEqual {
  bool operator()(const wp<IBinder> &lhs, const wp<IBinder> &rhs) const {
    return lhs == rhs;
  }
};

class BinderInterceptor : public BBinder {
public:
  bool handleIntercept(sp<BBinder> target, uint32_t code, const Parcel &data,
                       Parcel *reply, uint32_t flags, status_t &result);
  bool shouldIntercept(const wp<BBinder> &target, uint32_t code);
  status_t onTransact(uint32_t code, const Parcel &data, Parcel *reply,
                      uint32_t flags) override;

private:
  enum {
    REGISTER_INTERCEPTOR = 1,
    UNREGISTER_INTERCEPTOR = 2,
    PARK_HOOK = 3,
    CLEAR_AND_PARK = 4,
  };
  enum {
    PRE_TRANSACT = 1,
    POST_TRANSACT,
    INTERCEPTOR_REPLACED = 3,
  };
  enum {
    SKIP = 1,
    CONTINUE,
    OVERRIDE_REPLY,
    OVERRIDE_DATA,
  };

  struct InterceptItem {
    wp<IBinder> target{};
    sp<IBinder> interceptor;
    std::vector<uint32_t> filtered_codes;
  };

  using RwLock = std::shared_mutex;
  using WriteGuard = std::unique_lock<RwLock>;
  using ReadGuard = std::shared_lock<RwLock>;
  static constexpr size_t kMaxInterceptorRegistrations = 512;

  void pruneDeadItemsLocked() {
    for (auto it = items.begin(); it != items.end();) {
      if (it->first.promote() == nullptr) {
        it = items.erase(it);
      } else {
        ++it;
      }
    }
  }

  RwLock lock;
  std::unordered_map<wp<IBinder>, InterceptItem, WpIBinderHash, WpIBinderEqual>
      items{};
};

static_assert(sizeof(uintptr_t) == 8);
static_assert(sizeof(RustOffsetCacheView) == 144);
static_assert(offsetof(RustOffsetCacheView, valid) == 136);
static_assert(sizeof(RustParsedTransaction) == 80);
static_assert(offsetof(RustParsedTransaction, raw_ptr) == 56);
static_assert(offsetof(RustParsedTransaction, valid) == 72);
static_assert(sizeof(RustBinderReadSnapshot) == 32);
static_assert(offsetof(RustBinderReadSnapshot, valid) == 24);

OffsetCache &OffsetCache::instance() {
  static OffsetCache cache;
  return cache;
}

namespace {
constexpr int kMinimumSupportedAndroidApi = 31;
constexpr int kMaximumValidatedCompilerFallbackApi = 37;

RustOffsetCacheView rustOffsetView(const OffsetCache &cache) {
  return RustOffsetCacheView{cache.target_ptr_offset,
                             cache.cookie_offset,
                             cache.code_offset,
                             cache.flags_offset,
                             cache.sender_pid_offset,
                             cache.sender_euid_offset,
                             cache.data_size_offset,
                             cache.data_ptr_offset,
                             cache.transaction_data_size,
                             cache.transaction_data_secctx_size,
                             cache.bwr_write_size_offset,
                             cache.bwr_write_consumed_offset,
                             cache.bwr_write_buffer_offset,
                             cache.bwr_read_size_offset,
                             cache.bwr_read_consumed_offset,
                             cache.bwr_read_buffer_offset,
                             cache.bwr_total_size,
                             static_cast<uint8_t>(cache.valid ? 1 : 0)};
}

void populateCompilerLayout(OffsetCache &cache) {
  cache.target_ptr_offset = offsetof(binder_transaction_data, target.ptr);
  cache.cookie_offset = offsetof(binder_transaction_data, cookie);
  cache.code_offset = offsetof(binder_transaction_data, code);
  cache.flags_offset = offsetof(binder_transaction_data, flags);
  cache.sender_pid_offset = offsetof(binder_transaction_data, sender_pid);
  cache.sender_euid_offset = offsetof(binder_transaction_data, sender_euid);
  cache.data_size_offset = offsetof(binder_transaction_data, data_size);
  cache.data_ptr_offset = offsetof(binder_transaction_data, data.ptr.buffer);
  cache.transaction_data_size = sizeof(binder_transaction_data);
  cache.transaction_data_secctx_size = sizeof(binder_transaction_data_secctx);
  cache.bwr_write_size_offset = offsetof(binder_write_read, write_size);
  cache.bwr_write_consumed_offset = offsetof(binder_write_read, write_consumed);
  cache.bwr_write_buffer_offset = offsetof(binder_write_read, write_buffer);
  cache.bwr_read_size_offset = offsetof(binder_write_read, read_size);
  cache.bwr_read_consumed_offset = offsetof(binder_write_read, read_consumed);
  cache.bwr_read_buffer_offset = offsetof(binder_write_read, read_buffer);
  cache.bwr_total_size = sizeof(binder_write_read);
  cache.valid = true;
}
}

bool OffsetCache::validateOffsets() const {
  const RustOffsetCacheView view = rustOffsetView(*this);
  return rust_validate_offset_cache(&view);
}

bool RuntimeLayoutValidator::sendPingProbe(uint8_t *out_buf, size_t buf_size,
                                           size_t &out_len) {
  int fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
  if (fd < 0) {
    fd = open("/dev/vndbinder", O_RDWR | O_CLOEXEC);
  }
  if (fd < 0) {
    LOGW("Live validation cannot open a Binder device");
    return false;
  }

  struct {
    uint32_t cmd;
    binder_transaction_data txn;
  } __attribute__((packed)) write_data{};

  write_data.cmd = BC_TRANSACTION;
  memset(&write_data.txn, 0, sizeof(write_data.txn));
  write_data.txn.target.handle = 0;
  write_data.txn.code = 1599098439;
  write_data.txn.flags = 0;

  binder_write_read bwr{};
  bwr.write_size = sizeof(write_data);
  bwr.write_buffer = reinterpret_cast<binder_uintptr_t>(&write_data);
  bwr.read_size = buf_size;
  bwr.read_buffer = reinterpret_cast<binder_uintptr_t>(out_buf);

  int ret = static_cast<int>(syscall(SYS_ioctl, fd, BINDER_WRITE_READ, &bwr));
  close(fd);

  if (ret < 0) {
    LOGW("Live Binder validation ioctl failed: %d", errno);
    return false;
  }

  if (bwr.read_consumed > buf_size) {
    LOGW("Live Binder validation reported an oversized read buffer");
    return false;
  }
  out_len = static_cast<size_t>(bwr.read_consumed);
  return out_len > sizeof(uint32_t);
}

bool RuntimeLayoutValidator::analyzeProbeResult(const uint8_t *buf, size_t len,
                                                OffsetCache &cache) {
  if (!rust_validate_binder_probe(buf, len, sizeof(binder_transaction_data))) {
    return false;
  }

  populateCompilerLayout(cache);
  if (!cache.validateOffsets()) {
    cache.valid = false;
    return false;
  }
  LOGI("ABI validation: live Binder layout confirmed (%zu bytes)",
       sizeof(binder_transaction_data));
  return true;
}

bool RuntimeLayoutValidator::validateLayout(OffsetCache &cache) {
  LOGI("Starting live Binder UAPI validation via PING_TRANSACTION");

  uint8_t probe_buf[4096];
  size_t probe_len = 0;

  if (!sendPingProbe(probe_buf, sizeof(probe_buf), probe_len)) {
    LOGW("Live Binder validation failed; trying compiler layout fallback");
    return false;
  }

  if (!analyzeProbeResult(probe_buf, probe_len, cache)) {
    LOGW("Live Binder validation returned an incompatible layout");
    return false;
  }

  return cache.valid;
}

bool BinderStreamParser::parse(uintptr_t buffer, size_t consumed,
                               size_t buffer_size, const OffsetCache &cache,
                               ParsedTransaction *out_txns, size_t max_txns,
                               size_t &out_txn_count) {
  out_txn_count = 0;

  constexpr size_t kMaxTransactionsPerCall = 1024;
  if (buffer == 0 || consumed == 0 || consumed > buffer_size || !cache.valid ||
      out_txns == nullptr || max_txns == 0 ||
      max_txns > kMaxTransactionsPerCall) {
    return false;
  }

  const RustOffsetCacheView rust_cache = rustOffsetView(cache);

  size_t rust_count = 0;
  if (!rust_parse_binder_stream(reinterpret_cast<const uint8_t *>(buffer),
                                consumed, consumed, &rust_cache, out_txns,
                                max_txns, &rust_count) ||
      rust_count == 0 || rust_count > max_txns) {
    return false;
  }
  out_txn_count = rust_count;
  return true;
}

bool BinderStreamParser::writeBack(uintptr_t buffer_ptr, size_t consumed,
                                   const ParsedTransaction &txn,
                                   const OffsetCache &cache) {
  const RustOffsetCacheView rust_cache = rustOffsetView(cache);
  return rust_write_binder_transaction(reinterpret_cast<uint8_t *>(buffer_ptr),
                                       consumed, &txn, &rust_cache);
}

int AdaptiveBinderInterceptor::detectApiLevel() {
  char sdk_str[PROP_VALUE_MAX] = {};
  const int length = __system_property_get("ro.build.version.sdk", sdk_str);
  if (length <= 0)
    return 0;
  return rust_parse_android_api_level(
      reinterpret_cast<const uint8_t *>(sdk_str), static_cast<size_t>(length));
}

bool AdaptiveBinderInterceptor::parseKernelVersion(int &major, int &minor) {
  struct utsname uts {};
  if (uname(&uts) != 0) {
    LOGE("Failed to get kernel version via uname");
    return false;
  }

  const size_t length = strnlen(uts.release, sizeof(uts.release));
  int32_t parsed_major = 0;
  int32_t parsed_minor = 0;
  if (!rust_parse_kernel_release(reinterpret_cast<const uint8_t *>(uts.release),
                                 length, &parsed_major, &parsed_minor)) {
    LOGE("Failed to parse kernel version from '%s'", uts.release);
    return false;
  }
  major = parsed_major;
  minor = parsed_minor;
  return true;
}

bool AdaptiveBinderInterceptor::initFallback(OffsetCache &cache,
                                             int api_level) {
  if (api_level < kMinimumSupportedAndroidApi ||
      api_level > kMaximumValidatedCompilerFallbackApi) {
    LOGE("Fallback layout rejected unsupported Android API %d", api_level);
    return false;
  }
  populateCompilerLayout(cache);
  if (!cache.validateOffsets()) {
    cache.valid = false;
    return false;
  }
  LOGI("Validated compiler Binder UAPI fallback for API=%d", api_level);
  return true;
}

bool AdaptiveBinderInterceptor::initialize() {
  OffsetCache &cache = OffsetCache::instance();

  const int android_api_level = detectApiLevel();
  if (android_api_level < kMinimumSupportedAndroidApi ||
      android_api_level > kMaximumValidatedCompilerFallbackApi) {
    LOGE("AdaptiveBinderInterceptor: Android API %d is outside the "
         "compiler-validated Binder UAPI range %d-%d",
         android_api_level, kMinimumSupportedAndroidApi,
         kMaximumValidatedCompilerFallbackApi);
    return false;
  }
  std::string kernel_version;
  int kmajor = 0, kminor = 0;
  if (parseKernelVersion(kmajor, kminor)) {
    char kver[64];
    snprintf(kver, sizeof(kver), "%d.%d", kmajor, kminor);
    kernel_version = kver;
  }

  LOGI("AdaptiveBinderInterceptor: API=%d kernel=%s", android_api_level,
       kernel_version.c_str());

  if (RuntimeLayoutValidator::validateLayout(cache)) {
    LOGI("Strategy: live Binder UAPI validation succeeded");
    return true;
  }

  if (initFallback(cache, android_api_level)) {
    LOGI("Strategy: compiler Binder layout fallback activated");
    return true;
  }

  LOGE("Binder ABI validation failed; interception remains disabled");
  cache.valid = false;
  return false;
}

static AdaptiveBinderInterceptor g_adaptive;
static constexpr uint32_t kControlEndpointTransactionCode = 0xdeadbeefU;
static sp<BinderInterceptor> gBinderInterceptor = nullptr;
static std::atomic_bool gHookPaused{false};
static std::atomic_bool gHooksInitialized{false};
static std::mutex gHookInitializationMutex;

struct ThreadTransactionInfo {
  uint32_t code;
  wp<BBinder> target;
};

class ThreadTransactionQueue {
public:
  [[nodiscard]] bool tryPush(const ThreadTransactionInfo &value) {
    if (size_ == entries_.size()) {
      return false;
    }
    entries_[(head_ + size_) % entries_.size()] = value;
    ++size_;
    return true;
  }

  [[nodiscard]] bool tryPop(ThreadTransactionInfo &value) {
    if (size_ == 0) {
      return false;
    }
    value = std::move(entries_[head_]);
    entries_[head_] = {};
    head_ = (head_ + 1) % entries_.size();
    --size_;
    return true;
  }

  void discardNewest() {
    if (size_ == 0) {
      return;
    }
    const size_t index = (head_ + size_ - 1) % entries_.size();
    entries_[index] = {};
    --size_;
  }

private:
  static constexpr size_t kCapacity = 64;
  std::array<ThreadTransactionInfo, kCapacity> entries_{};
  size_t head_ = 0;
  size_t size_ = 0;
};

thread_local ThreadTransactionQueue gThreadTransactions;

class BinderStub : public BBinder {
  status_t onTransact(uint32_t code, const android::Parcel &data,
                      android::Parcel *reply, uint32_t flags) override {
    LOGD("BinderStub %d", code);
    ThreadTransactionInfo transaction_info{};
    if (gThreadTransactions.tryPop(transaction_info)) {
      if (transaction_info.target == nullptr &&
          transaction_info.code == kControlEndpointTransactionCode && reply) {
        LOGD("Native Binder control endpoint requested");
        reply->writeStrongBinder(gBinderInterceptor);
        return OK;
      } else if (transaction_info.target != nullptr) {
        LOGD("intercepting");
        auto p = transaction_info.target.promote();
        if (p) {
          LOGD("calling interceptor");
          status_t result;
          if (!gBinderInterceptor->handleIntercept(
                  p, transaction_info.code, data, reply, flags, result)) {
            LOGD("calling orig");
            result = p->transact(transaction_info.code, data, reply, flags);
          }
          return result;
        } else {
          LOGE("promote failed");
        }
      }
    }
    return UNKNOWN_TRANSACTION;
  }
};

static sp<BinderStub> gBinderStub = nullptr;
static int (*old_ioctl)(int fd, unsigned long request, ...) = nullptr;

int new_ioctl(int fd, unsigned long request, ...) {
  va_list list;
  va_start(list, request);
  auto arg = va_arg(list, void *);
  va_end(list);
  const bool hook_ready = gHooksInitialized.load(std::memory_order_acquire);
  const int result =
      hook_ready && old_ioctl != nullptr
          ? old_ioctl(fd, request, arg)
          : static_cast<int>(syscall(SYS_ioctl, fd, request, arg));

  if (result >= 0 && request == BINDER_WRITE_READ) {
    // CommitHook can make this trampoline observable by another Binder thread
    // before initialize_hooks() finishes publishing the interceptor objects
    // and validated offsets. The acquire pairs with the release store after
    // hook installation and keeps that short window pass-through only.
    if (!hook_ready) {
      return result;
    }
    if (gHookPaused.load(std::memory_order_acquire)) {
      return result;
    }

    if (arg == nullptr) {
      return result;
    }

    if (!rust_is_binder_fd_after_successful_ioctl(
            fd, reinterpret_cast<uintptr_t>(arg))) {
      return result;
    }

    const OffsetCache &cache = OffsetCache::instance();
    if (!cache.valid) {
      return result;
    }

    const RustOffsetCacheView rust_cache = rustOffsetView(cache);
    RustBinderReadSnapshot bwr{};
    if (!rust_read_binder_write_read(reinterpret_cast<const uint8_t *>(arg),
                                     &rust_cache, &bwr) ||
        !bwr.valid) {
      LOGE("new_ioctl: failed to safely read binder_write_read");
      return result;
    }

    if (bwr.read_buffer == 0 || bwr.read_size == 0) {
      return result;
    }

    LOGD("Binder read size %llu consumed %llu",
         (unsigned long long)bwr.read_size,
         (unsigned long long)bwr.read_consumed);

    constexpr binder_size_t kMaxBinderReadBytes = 8U * 1024U * 1024U;
    if (bwr.read_consumed <= sizeof(int32_t) ||
        bwr.read_consumed > bwr.read_size ||
        bwr.read_consumed > kMaxBinderReadBytes ||
        gBinderInterceptor == nullptr || gBinderStub == nullptr) {
      return result;
    }

    static constexpr size_t kMaxTransactions = 64;
    BinderStreamParser::ParsedTransaction txns[kMaxTransactions];
    size_t txn_count = 0;

    if (!BinderStreamParser::parse(bwr.read_buffer, bwr.read_consumed,
                                   bwr.read_size, cache, txns, kMaxTransactions,
                                   txn_count)) {
      return result;
    }

    for (size_t i = 0; i < txn_count; ++i) {
      auto &txn = txns[i];
      if (!txn.valid)
        continue;

      const uintptr_t wt = txn.target_ptr;
      if (wt == 0 || txn.cookie == 0)
        continue;

      bool need_intercept = false;
      ThreadTransactionInfo transaction_info{};

      if (txn.code == kControlEndpointTransactionCode && txn.sender_euid == 0) {
        transaction_info.code = kControlEndpointTransactionCode;
        transaction_info.target = nullptr;
        need_intercept = true;
      } else if (reinterpret_cast<RefBase::weakref_type *>(wt)
                     ->attemptIncStrong(nullptr)) {
        auto *b = reinterpret_cast<BBinder *>(txn.cookie);
        auto wb = wp<BBinder>::fromExisting(b);
        if (gBinderInterceptor->shouldIntercept(wb, txn.code)) {
          transaction_info.code = txn.code;
          transaction_info.target = wb;
          need_intercept = true;
          LOGD("intercepting registered transaction code=%d", txn.code);
        }
        b->decStrong(nullptr);
      }

      if (need_intercept) {
        if (!gThreadTransactions.tryPush(transaction_info)) {
          LOGW(
              "Binder interception queue is full; passing transaction through");
          continue;
        }
        LOGD("add intercept item!");
        txn.target_ptr =
            reinterpret_cast<uintptr_t>(gBinderStub->getWeakRefs());
        txn.cookie = reinterpret_cast<uintptr_t>(gBinderStub.get());
        txn.code = kControlEndpointTransactionCode;

        if (BinderStreamParser::writeBack(bwr.read_buffer, bwr.read_consumed,
                                          txn, cache)) {
          continue;
        } else {
          gThreadTransactions.discardNewest();
          LOGE("Failed to write intercepted Binder transaction");
        }
      }
    }
  }
  return result;
}

bool BinderInterceptor::shouldIntercept(const wp<BBinder> &target,
                                        uint32_t code) {
  ReadGuard g{lock};
  auto it = items.find(target);
  if (it == items.end())
    return false;
  const auto &codes = it->second.filtered_codes;
  return std::binary_search(codes.begin(), codes.end(), code);
}

status_t BinderInterceptor::onTransact(uint32_t code,
                                       const android::Parcel &data,
                                       android::Parcel *reply, uint32_t flags) {
  IPCThreadState *thread_state = IPCThreadState::self();
  if (thread_state == nullptr || thread_state->getCallingUid() != 0) {
    LOGW("Rejected a non-root Binder control request");
    return PERMISSION_DENIED;
  }
  if (code == REGISTER_INTERCEPTOR) {
    if (reply == nullptr)
      return BAD_VALUE;
    sp<IBinder> target, interceptor;
    if (data.readStrongBinder(&target) != OK) {
      return BAD_VALUE;
    }
    if (target == nullptr || !target->localBinder()) {
      return BAD_VALUE;
    }
    if (data.readStrongBinder(&interceptor) != OK) {
      return BAD_VALUE;
    }
    if (interceptor == nullptr) {
      return BAD_VALUE;
    }
    std::vector<uint32_t> codes;
    int32_t code_count = 0;
    constexpr int32_t kMaxFilteredCodes = 1024;
    if (data.readInt32(&code_count) != OK || code_count <= 0 ||
        code_count > kMaxFilteredCodes ||
        static_cast<size_t>(code_count) > data.dataAvail() / sizeof(uint32_t)) {
      return BAD_VALUE;
    }
    codes.reserve(static_cast<size_t>(code_count));
    for (int32_t i = 0; i < code_count; ++i) {
      uint32_t filtered_code = 0;
      if (data.readUint32(&filtered_code) != OK || filtered_code == 0 ||
          filtered_code == kControlEndpointTransactionCode) {
        return BAD_VALUE;
      }
      codes.push_back(filtered_code);
    }
    if (data.dataAvail() != 0)
      return BAD_VALUE;
    std::sort(codes.begin(), codes.end());
    if (std::adjacent_find(codes.begin(), codes.end()) != codes.end()) {
      return BAD_VALUE;
    }
    LOGI("Interceptor registered with %zu explicitly filtered codes",
         codes.size());
    sp<IBinder> replaced_interceptor;
    {
      WriteGuard wg{lock};
      pruneDeadItemsLocked();
      wp<IBinder> t = target;
      auto it = items.find(t);
      if (it == items.end()) {
        if (items.size() >= kMaxInterceptorRegistrations) {
          LOGW("Interceptor registration limit reached");
          return BAD_VALUE;
        }
        it = items.emplace(t, InterceptItem{}).first;
        it->second.target = t;
      } else if (it->second.interceptor != nullptr &&
                 it->second.interceptor != interceptor) {
        replaced_interceptor = it->second.interceptor;
      }
      it->second.interceptor = interceptor;
      it->second.filtered_codes = std::move(codes);
    }
    if (replaced_interceptor != nullptr) {
      Parcel notification;
      replaced_interceptor->transact(INTERCEPTOR_REPLACED, notification,
                                     nullptr, IBinder::FLAG_ONEWAY);
    }
    const status_t status = reply->writeInt32(0);
    if (status == OK) {
      gHookPaused.store(false, std::memory_order_release);
    }
    return status;
  } else if (code == UNREGISTER_INTERCEPTOR) {
    if (reply == nullptr)
      return BAD_VALUE;
    sp<IBinder> target, interceptor;
    if (data.readStrongBinder(&target) != OK) {
      return BAD_VALUE;
    }
    if (target == nullptr || !target->localBinder()) {
      return BAD_VALUE;
    }
    if (data.readStrongBinder(&interceptor) != OK) {
      return BAD_VALUE;
    }
    if (interceptor == nullptr) {
      return BAD_VALUE;
    }
    if (data.dataAvail() != 0)
      return BAD_VALUE;
    size_t remaining = 0;
    {
      WriteGuard wg{lock};
      wp<IBinder> t = target;
      auto it = items.find(t);
      if (it != items.end()) {
        if (it->second.interceptor != interceptor) {
          return BAD_VALUE;
        }
        items.erase(it);
        remaining = items.size();
      } else {
        return BAD_VALUE;
      }
    }
    const status_t status = reply->writeInt32(0);
    if (status == OK && remaining == 0) {
      gHookPaused.store(true, std::memory_order_release);
    }
    return status;
  } else if (code == PARK_HOOK) {
    if (reply == nullptr || data.dataAvail() != 0)
      return BAD_VALUE;
    {
      ReadGuard rg{lock};
      if (!items.empty())
        return INVALID_OPERATION;
    }
    const status_t status = reply->writeInt32(0);
    if (status == OK) {
      gHookPaused.store(true, std::memory_order_release);
    }
    return status;
  } else if (code == CLEAR_AND_PARK) {
    if (reply == nullptr || data.dataAvail() != 0)
      return BAD_VALUE;
    {
      WriteGuard wg{lock};
      items.clear();
    }
    const status_t status = reply->writeInt32(0);
    if (status == OK) {
      gHookPaused.store(true, std::memory_order_release);
    }
    return status;
  }
  return UNKNOWN_TRANSACTION;
}

bool BinderInterceptor::handleIntercept(sp<BBinder> target, uint32_t code,
                                        const Parcel &data, Parcel *reply,
                                        uint32_t flags, status_t &result) {
  constexpr size_t kMaxInterceptParcelBytes = 8U * 1024U * 1024U;
  if (target == nullptr || data.dataSize() > kMaxInterceptParcelBytes ||
      (reply != nullptr && reply->dataSize() > kMaxInterceptParcelBytes)) {
    return false;
  }
#define CHECK(expr)                                                            \
  ({                                                                           \
    auto __result = (expr);                                                    \
    if (__result != OK) {                                                      \
      LOGE(#expr " = %d", __result);                                           \
      return false;                                                            \
    }                                                                          \
  })
  sp<IBinder> interceptor;
  {
    ReadGuard rg{lock};
    auto it = items.find(target);
    if (it == items.end()) {
      LOGE("No matching Binder interception registration");
      return false;
    }
    interceptor = it->second.interceptor;
  }
  if (interceptor == nullptr)
    return false;
  IPCThreadState *thread_state = IPCThreadState::self();
  if (thread_state == nullptr)
    return false;
  const uid_t calling_uid = thread_state->getCallingUid();
  const pid_t calling_pid = thread_state->getCallingPid();
  LOGD("intercept code=%d flags=%d reply=%s", code, flags,
       reply ? "true" : "false");
  Parcel tmpData, tmpReply, realData;
  CHECK(tmpData.writeStrongBinder(target));
  CHECK(tmpData.writeUint32(code));
  CHECK(tmpData.writeUint32(flags));
  CHECK(tmpData.writeInt32(static_cast<int32_t>(calling_uid)));
  CHECK(tmpData.writeInt32(static_cast<int32_t>(calling_pid)));
  CHECK(tmpData.writeUint64(data.dataSize()));
  CHECK(tmpData.appendFrom(&data, 0, data.dataSize()));
  CHECK(interceptor->transact(PRE_TRANSACT, tmpData, &tmpReply));
  int32_t preType;
  CHECK(tmpReply.readInt32(&preType));
  LOGD("pre transact type %d", preType);
  if (preType == SKIP) {
    return false;
  } else if (preType == OVERRIDE_REPLY) {
    int32_t override_result = OK;
    uint64_t encoded_size = 0;
    CHECK(tmpReply.readInt32(&override_result));
    CHECK(tmpReply.readUint64(&encoded_size));
    if (encoded_size != tmpReply.dataAvail() ||
        encoded_size > kMaxInterceptParcelBytes ||
        (reply == nullptr && encoded_size != 0)) {
      LOGE("invalid pre-transaction reply size: %llu",
           static_cast<unsigned long long>(encoded_size));
      return false;
    }
    result = override_result;
    if (reply) {
      CHECK(reply->appendFrom(&tmpReply, tmpReply.dataPosition(),
                              static_cast<size_t>(encoded_size)));
    }
    return true;
  } else if (preType == OVERRIDE_DATA) {
    uint64_t encoded_size = 0;
    CHECK(tmpReply.readUint64(&encoded_size));
    if (encoded_size != tmpReply.dataAvail() ||
        encoded_size > kMaxInterceptParcelBytes) {
      LOGE("invalid replacement request size: %llu",
           static_cast<unsigned long long>(encoded_size));
      return false;
    }
    CHECK(realData.appendFrom(&tmpReply, tmpReply.dataPosition(),
                              static_cast<size_t>(encoded_size)));
  } else if (preType == CONTINUE) {
    CHECK(realData.appendFrom(&data, 0, data.dataSize()));
  } else {
    LOGE("invalid pre-transaction response type: %d", preType);
    return false;
  }
  result = target->transact(code, realData, reply, flags);
  if (reply != nullptr && reply->dataSize() > kMaxInterceptParcelBytes) {
    LOGW("Skipping post-interception for oversized Binder reply");
    return true;
  }

#define CHECK_POST(expr)                                                       \
  ({                                                                           \
    auto __result = (expr);                                                    \
    if (__result != OK) {                                                      \
      LOGE(#expr " = %d", __result);                                           \
      return true;                                                             \
    }                                                                          \
  })

  tmpData.freeData();
  tmpReply.freeData();

  CHECK_POST(tmpData.writeStrongBinder(target));
  CHECK_POST(tmpData.writeUint32(code));
  CHECK_POST(tmpData.writeUint32(flags));
  CHECK_POST(tmpData.writeInt32(static_cast<int32_t>(calling_uid)));
  CHECK_POST(tmpData.writeInt32(static_cast<int32_t>(calling_pid)));
  CHECK_POST(tmpData.writeInt32(result));
  CHECK_POST(tmpData.writeUint64(data.dataSize()));
  CHECK_POST(tmpData.appendFrom(&data, 0, data.dataSize()));
  CHECK_POST(tmpData.writeUint64(reply == nullptr ? 0 : reply->dataSize()));
  LOGD("data size %zu reply size %zu", data.dataSize(),
       reply == nullptr ? 0 : reply->dataSize());
  if (reply) {
    CHECK_POST(tmpData.appendFrom(reply, 0, reply->dataSize()));
  }
  CHECK_POST(interceptor->transact(POST_TRANSACT, tmpData, &tmpReply));
  int32_t postType;
  CHECK_POST(tmpReply.readInt32(&postType));
  LOGD("post transact type %d", postType);
  if (postType == OVERRIDE_REPLY) {
    int32_t override_result = OK;
    uint64_t encoded_size = 0;
    CHECK_POST(tmpReply.readInt32(&override_result));
    CHECK_POST(tmpReply.readUint64(&encoded_size));
    if (encoded_size != tmpReply.dataAvail() ||
        encoded_size > kMaxInterceptParcelBytes ||
        (reply == nullptr && encoded_size != 0)) {
      LOGE("invalid post-transaction reply size: %llu",
           static_cast<unsigned long long>(encoded_size));
      return true;
    }
    result = override_result;
    if (reply) {
      reply->freeData();
      CHECK_POST(reply->appendFrom(&tmpReply, tmpReply.dataPosition(),
                                   static_cast<size_t>(encoded_size)));
      LOGD("reply size=%zu requested=%llu", reply->dataSize(),
           static_cast<unsigned long long>(encoded_size));
    }
  } else if (postType != SKIP && postType != CONTINUE) {
    LOGE("invalid post-transaction response type: %d", postType);
    return true;
  }
#undef CHECK_POST
#undef CHECK
  return true;
}

bool initialize_hooks() {
  const std::lock_guard<std::mutex> initialization_guard{
      gHookInitializationMutex};
  if (gHooksInitialized.load(std::memory_order_acquire)) {
    gHookPaused.store(false, std::memory_order_release);
    return true;
  }
  if (!g_adaptive.initialize()) {
    LOGE("Binder ABI validation failed; refusing to install hooks");
    return false;
  }

  auto maps = lsplt::MapInfo::Scan();
  dev_t binder_dev = 0;
  ino_t binder_ino = 0;
  bool binder_found = false;

  for (auto &m : maps) {
    if (m.path.ends_with("/libbinder.so")) {
      binder_dev = m.dev;
      binder_ino = m.inode;
      binder_found = true;
      LOGD("Found libbinder.so: dev=%lu, ino=%lu", m.dev, m.inode);
      break;
    }
  }

  if (!binder_found) {
    LOGE("libbinder.so not found");
    return false;
  }

  gBinderInterceptor = sp<BinderInterceptor>::make();
  gBinderStub = sp<BinderStub>::make();
  if (gBinderInterceptor == nullptr || gBinderStub == nullptr ||
      !lsplt::RegisterHook(binder_dev, binder_ino, "ioctl", (void *)new_ioctl,
                           (void **)&old_ioctl)) {
    LOGE("Failed to register the libbinder ioctl hook");
    return false;
  }

  if (!lsplt::CommitHook()) {
    LOGE("Failed to commit the libbinder ioctl hook");
    return false;
  }
  if (old_ioctl == nullptr) {
    LOGW("libbinder ioctl hook has no original function; using raw syscall fallback");
  }
  gHooksInitialized.store(true, std::memory_order_release);
  gHookPaused.store(false, std::memory_order_release);
  LOGI("libbinder ioctl hook installed successfully");
  return true;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]] bool
entry(void *activation_context) {
  LOGI("native Binder interceptor injected");
  cleverestricky::kernel_identity::configure(static_cast<const char *>(activation_context));
  const bool binder_ready = initialize_hooks();
  if (binder_ready && !cleverestricky::kernel_identity::install_hooks_if_enabled()) {
    LOGW("kernel identity hook requested but no uname import could be hooked; Binder core remains active");
  }
  return binder_ready;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]] bool
resume(void *activation_context) {
  LOGI("resuming parked native Binder hook");
  cleverestricky::kernel_identity::configure(static_cast<const char *>(activation_context));
  const bool binder_ready = initialize_hooks();
  if (binder_ready && !cleverestricky::kernel_identity::install_hooks_if_enabled()) {
    LOGW("kernel identity hook requested but no uname import could be hooked; Binder core remains active");
  }
  return binder_ready;
}
