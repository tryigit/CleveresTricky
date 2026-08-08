#ifndef BINDER_INTERCEPTOR_H
#define BINDER_INTERCEPTOR_H

#include <utils/RefBase.h>
#include <binder/Parcel.h>
#include <binder/IBinder.h>
#include <binder/Binder.h>
#include <utils/StrongPointer.h>
#include <map>
#include <unordered_map>
#include <shared_mutex>
#include <cstdint>
#include <cstddef>
#include <mutex>
#include <atomic>
#include <string>

using namespace android;

// =============================================================================
// Adaptive Binder Interceptor Architecture
//
// This header defines a Binder interception framework that validates the
// platform UAPI before touching driver-owned buffers. It resolves layouts
// through two production strategies:
//
//   1. Live UAPI validation (dummy PING_TRANSACTION)
//   2. Validated stable-UAPI fallback (Android 12 - 16)
//
// A bounded Rust parser processes the Binder read buffer, and all memory
// accesses go through checked accessors.
// =============================================================================

// ---------------------------------------------------------------------------
// OffsetCache holds dynamically discovered struct offsets.
// Populated once during module init; read-only afterward.
// ---------------------------------------------------------------------------
struct OffsetCache {
    // binder_transaction_data field offsets (bytes from struct start)
    size_t target_ptr_offset  = 0;   // offset of target.ptr union member
    size_t cookie_offset      = 0;   // offset of cookie field
    size_t code_offset        = 0;   // offset of code field
    size_t flags_offset       = 0;   // offset of flags field
    size_t sender_pid_offset  = 0;   // offset of sender_pid
    size_t sender_euid_offset = 0;   // offset of sender_euid
    size_t data_size_offset   = 0;   // offset of data_size
    size_t data_ptr_offset    = 0;   // offset of data.ptr.buffer

    // Total struct sizes (discovered dynamically, never hardcoded)
    size_t transaction_data_size         = 0;
    size_t transaction_data_secctx_size  = 0;

    // binder_write_read field offsets
    size_t bwr_write_size_offset    = 0;
    size_t bwr_write_consumed_offset = 0;
    size_t bwr_write_buffer_offset  = 0;
    size_t bwr_read_size_offset     = 0;
    size_t bwr_read_consumed_offset = 0;
    size_t bwr_read_buffer_offset   = 0;
    size_t bwr_total_size           = 0;

    // Validation & state
    bool   valid         = false;
    bool   btf_source    = false;    // true if offsets came from BTF
    bool   heuristic_source = false; // true if offsets came from heuristic
    bool   fallback_mode = false;    // true if using static fallback

    int    android_api_level = 0;
    std::string kernel_version;

    // Singleton accessor
    static OffsetCache& instance();

    // Sanity-check the discovered offsets
    bool validateOffsets() const;
};

// ---------------------------------------------------------------------------
// BtfProvider reads BTF (BPF Type Format) data from the running kernel
// to discover struct field offsets without any compile-time assumptions.
// Available on kernel 5.4+ with CONFIG_DEBUG_INFO_BTF=y.
// ---------------------------------------------------------------------------
class BtfProvider {
public:
    // Returns true if BTF data is available on this kernel
    static bool isAvailable();

    // Query the byte offset of a named field within a named struct.
    // Returns -1 on failure.
    static int queryStructLayout(const char *struct_name, const char *field_name);

    // Query the total size of a named struct. Returns 0 on failure.
    static size_t queryStructSize(const char *struct_name);

    // Populate the OffsetCache from BTF data. Returns true on success.
    static bool populateCache(OffsetCache &cache);

private:
    static bool readBtf(const char *path, const char *struct_name,
                        const char *field_name, int &out_offset);
};

// ---------------------------------------------------------------------------
// RuntimeOffsetDiscovery sends a PING_TRANSACTION through the binder
// driver and observes memory patterns to discover struct field positions.
// ---------------------------------------------------------------------------
class RuntimeOffsetDiscovery {
public:
    // Run the heuristic offset discovery. Returns true if offsets were found.
    static bool discoverOffsets(OffsetCache &cache);

    // Probe a specific struct by sending a controlled transaction and
    // analyzing the returned bytes for known marker patterns.
    static bool probeOffsets(OffsetCache &cache);

private:
    // Send a PING_TRANSACTION to servicemanager and capture the raw response
    static bool sendPingProbe(uint8_t *out_buf, size_t buf_size, size_t &out_len);

    // Scan a captured buffer for known binder command patterns
    static bool analyzeProbeResult(const uint8_t *buf, size_t len,
                                   OffsetCache &cache);
};

// ---------------------------------------------------------------------------
// FallbackDatabase provides static offset maps for supported platform combinations.
// Indexed by (API level, kernel major, kernel minor).
// ---------------------------------------------------------------------------
struct FallbackOffsetEntry {
    int    api_level;
    int    kernel_major;
    int    kernel_minor;
    size_t transaction_data_size;
    size_t secctx_size;
    size_t target_ptr_offset;
    size_t cookie_offset;
    size_t code_offset;
    size_t flags_offset;
    size_t sender_pid_offset;
    size_t sender_euid_offset;
    size_t data_size_offset;
    size_t data_ptr_offset;
    size_t bwr_total_size;
};

class FallbackDatabase {
public:
    // Lookup the best matching entry for the current device.
    // Returns true and populates cache on success.
    static bool lookup(int api_level, int kernel_major, int kernel_minor,
                       OffsetCache &cache);

    // Get the full table (for testing / enumeration)
    static const FallbackOffsetEntry* getTable(size_t &out_count);

private:
    static const FallbackOffsetEntry s_entries[];
    static const size_t s_entry_count;
};

// ---------------------------------------------------------------------------
// BinderStreamParser combines a bounded Rust parser with checked C++ write-back.
// ---------------------------------------------------------------------------
class BinderStreamParser {
public:
    struct ParsedTransaction {
        uintptr_t target_ptr;
        uintptr_t cookie;
        uint32_t  code;
        uint32_t  flags;
        int32_t   sender_pid;
        uint32_t  sender_euid;
        uint64_t  data_size;
        uintptr_t data_buffer;
        uint32_t  cmd;           // BR_TRANSACTION or BR_TRANSACTION_SEC_CTX
        uintptr_t raw_ptr;      // pointer to start of transaction_data in buffer
        size_t    raw_size;     // size of transaction_data region
        bool      valid;
    };

    // Parse the binder read buffer and extract transactions.
    // Uses the OffsetCache for field positions. All reads are bounds-checked.
    static bool parse(uintptr_t buffer, size_t consumed, size_t buffer_size,
                      const OffsetCache &cache,
                      ParsedTransaction *out_txns, size_t max_txns,
                      size_t &out_txn_count);

    // Write back a modified transaction into the consumed buffer region.
    static bool writeBack(uintptr_t buffer_ptr, size_t consumed,
                          const ParsedTransaction &txn,
                          const OffsetCache &cache);

private:
    static bool safeWrite(uintptr_t base, size_t offset, const void *src,
                          size_t len, size_t buffer_end);
};

// ---------------------------------------------------------------------------
// AdaptiveBinderInterceptor is the top-level orchestrator. It initializes the offset
// discovery pipeline (live validation → stable-UAPI fallback) and wires up the
// bounded parser for all ioctl interception.
// ---------------------------------------------------------------------------
class AdaptiveBinderInterceptor {
public:
    // Initialize the adaptive system: detect versions, discover offsets.
    // Must be called once during module load before hooks are active.
    bool initialize();

    // Get the active OffsetCache (read-only after init)
    const OffsetCache& getCache() const { return OffsetCache::instance(); }

    // Detect Android API level from system properties
    static int detectApiLevel();

    // Parse kernel version string into (major, minor)
    static bool parseKernelVersion(int &major, int &minor);

private:
    bool initBtf(OffsetCache &cache);
    bool initHeuristic(OffsetCache &cache);
    bool initFallback(OffsetCache &cache);

};

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// BinderInterceptor is the Binder-level intercept handler.
// Manages registered interceptor items and dispatches pre/post transact.
// ---------------------------------------------------------------------------
struct WpIBinderHash {
    std::size_t operator()(const wp<IBinder>& ptr) const {
        return std::hash<IBinder*>()(ptr.unsafe_get());
    }
};

struct WpIBinderEqual {
    bool operator()(const wp<IBinder>& lhs, const wp<IBinder>& rhs) const {
        return lhs == rhs;
    }
};

class BinderInterceptor : public BBinder {
public:
    bool handleIntercept(sp<BBinder> target, uint32_t code, const Parcel &data,
                         Parcel *reply, uint32_t flags, status_t &result);
    bool shouldIntercept(const wp<BBinder>& target, uint32_t code);

    status_t onTransact(uint32_t code, const android::Parcel &data,
                        android::Parcel *reply, uint32_t flags) override;

private:
    enum {
        REGISTER_INTERCEPTOR = 1,
        UNREGISTER_INTERCEPTOR = 2
    };
    enum {
        PRE_TRANSACT = 1,
        POST_TRANSACT,
        INTERCEPTOR_REPLACED = 3
    };
    enum {
        SKIP = 1,
        CONTINUE,
        OVERRIDE_REPLY,
        OVERRIDE_DATA
    };

    struct InterceptItem {
        wp<IBinder> target{};
        sp<IBinder> interceptor;
        std::vector<uint32_t> filtered_codes;
    };

    using RwLock = std::shared_mutex;
    using WriteGuard = std::unique_lock<RwLock>;
    using ReadGuard = std::shared_lock<RwLock>;
    RwLock lock;
    std::unordered_map<wp<IBinder>, InterceptItem, WpIBinderHash, WpIBinderEqual> items{};
};

#endif // BINDER_INTERCEPTOR_H
