// =============================================================================
// Adaptive Binder Interceptor
//
// This module implements a fully dynamic, self-adapting Binder interception
// framework with runtime layout validation for supported Android releases.
//
// Core Design Principles:
//   1. Live UAPI Validation: A PING_TRANSACTION confirms that the driver and
//      packaged architecture-specific Binder header agree at runtime.
//   2. Memory-Safe Binder Stream Parser: Rust validates every command and
//      field before C++ performs a bounded write-back.
//   3. Stable-UAPI Fallback: If the live probe is unavailable during process
//      startup, compiler-calculated layouts are used on Android 12–16.
//   4. Bounds Checking and Safety: Every buffer access is bounds-checked.
//      Unrecognized layouts fail closed instead of guessing offsets.
// =============================================================================

#include "kernel/binder.h"
#include <binder/Binder.h>
#include <binder/Common.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <cstring>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <unistd.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdio>
#include <mutex>
#include <limits>
#include <queue>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include <sys/system_properties.h>

#include "binder_interceptor.h"
#include "cleverestricky_cbor_cose.h"
#include "logging.hpp"
#include "lsplt.hpp"


using namespace android;

// =============================================================================
// Section 1: OffsetCache Singleton
// =============================================================================
OffsetCache& OffsetCache::instance() {
  static OffsetCache cache;
  return cache;
}

bool OffsetCache::validateOffsets() const {
  // Sanity check: all critical offsets must be within reasonable bounds
  // and the struct sizes must be non-zero if we claim to be valid
  if (!valid) return false;

  // transaction_data must be at least 40 bytes on any arch
  if (transaction_data_size < 40 || transaction_data_size > 512) return false;
  if (transaction_data_secctx_size < transaction_data_size ||
      transaction_data_secctx_size > 512) {
    return false;
  }

  const auto fits = [](size_t offset, size_t width, size_t total) {
    return offset <= total && width <= total - offset;
  };

  if (!fits(target_ptr_offset, sizeof(binder_uintptr_t), transaction_data_size) ||
      !fits(cookie_offset, sizeof(binder_uintptr_t), transaction_data_size) ||
      !fits(code_offset, sizeof(uint32_t), transaction_data_size) ||
      !fits(flags_offset, sizeof(uint32_t), transaction_data_size) ||
      !fits(sender_pid_offset, sizeof(int32_t), transaction_data_size) ||
      !fits(sender_euid_offset, sizeof(uint32_t), transaction_data_size) ||
      !fits(data_size_offset, sizeof(binder_size_t), transaction_data_size) ||
      !fits(data_ptr_offset, sizeof(binder_uintptr_t), transaction_data_size)) {
    return false;
  }

  // cookie must come after target.ptr (ABI contract)
  if (cookie_offset <= target_ptr_offset) return false;

  // code comes after cookie
  if (code_offset <= cookie_offset) return false;

  // BWR size must be reasonable
  if (bwr_total_size < 24 || bwr_total_size > 256) return false;
  if (!fits(bwr_write_size_offset, sizeof(binder_size_t), bwr_total_size) ||
      !fits(bwr_write_consumed_offset, sizeof(binder_size_t), bwr_total_size) ||
      !fits(bwr_write_buffer_offset, sizeof(binder_uintptr_t), bwr_total_size) ||
      !fits(bwr_read_size_offset, sizeof(binder_size_t), bwr_total_size) ||
      !fits(bwr_read_consumed_offset, sizeof(binder_size_t), bwr_total_size) ||
      !fits(bwr_read_buffer_offset, sizeof(binder_uintptr_t), bwr_total_size)) {
    return false;
  }

  return true;
}

// =============================================================================
// Section 2: BTF Provider and Kernel Introspection (Kernel 5.4+)
// =============================================================================

bool BtfProvider::isAvailable() {
  return access("/sys/kernel/btf/vmlinux", R_OK) == 0;
}

namespace {

constexpr uint16_t kBtfMagic = 0xeb9f;
constexpr size_t kMaxBtfBytes = 64U * 1024U * 1024U;
constexpr size_t kMaxBtfTypes = 1U * 1024U * 1024U;
constexpr size_t kMaxBtfMembers = 2U * 1024U * 1024U;
constexpr size_t kMaxBtfNameBytes = 512U;

enum BtfKind : uint32_t {
  BTF_KIND_INT = 1,
  BTF_KIND_PTR = 2,
  BTF_KIND_ARRAY = 3,
  BTF_KIND_STRUCT = 4,
  BTF_KIND_UNION = 5,
  BTF_KIND_ENUM = 6,
  BTF_KIND_FWD = 7,
  BTF_KIND_TYPEDEF = 8,
  BTF_KIND_VOLATILE = 9,
  BTF_KIND_CONST = 10,
  BTF_KIND_RESTRICT = 11,
  BTF_KIND_FUNC = 12,
  BTF_KIND_FUNC_PROTO = 13,
  BTF_KIND_VAR = 14,
  BTF_KIND_DATASEC = 15,
  BTF_KIND_FLOAT = 16,
  BTF_KIND_DECL_TAG = 17,
  BTF_KIND_TYPE_TAG = 18,
  BTF_KIND_ENUM64 = 19,
};

#pragma pack(push, 1)
struct BtfHeader {
  uint16_t magic;
  uint8_t version;
  uint8_t flags;
  uint32_t hdr_len;
  uint32_t type_off;
  uint32_t type_len;
  uint32_t str_off;
  uint32_t str_len;
};

struct BtfTypeRaw {
  uint32_t name_off;
  uint32_t info;
  uint32_t size_or_type;
};

struct BtfMemberRaw {
  uint32_t name_off;
  uint32_t type;
  uint32_t offset;
};
#pragma pack(pop)

struct BtfMember {
  std::string name;
  uint32_t type = 0;
  uint32_t bit_offset = 0;
};

struct BtfType {
  std::string name;
  uint32_t kind = 0;
  uint32_t size_or_type = 0;
  std::vector<BtfMember> members;
};

template <typename T>
bool readPacked(const std::vector<uint8_t> &data, size_t offset, T &out) {
  if (offset > data.size() || sizeof(T) > data.size() - offset) return false;
  memcpy(&out, data.data() + offset, sizeof(T));
  return true;
}

bool checkedRegion(size_t base, uint32_t offset, uint32_t length,
                   size_t total, size_t &start, size_t &end) {
  if (offset > total - std::min(base, total)) return false;
  start = base + static_cast<size_t>(offset);
  if (start > total || static_cast<size_t>(length) > total - start) return false;
  end = start + static_cast<size_t>(length);
  return true;
}

class BtfIndex {
 public:
  bool load(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    std::vector<uint8_t> data;
    data.reserve(1024U * 1024U);
    std::array<uint8_t, 64U * 1024U> chunk{};
    while (true) {
      ssize_t count = read(fd, chunk.data(), chunk.size());
      if (count == 0) break;
      if (count < 0) {
        if (errno == EINTR) continue;
        close(fd);
        return false;
      }
      if (static_cast<size_t>(count) > kMaxBtfBytes - data.size()) {
        close(fd);
        LOGW("BTF: vmlinux exceeds the %zu-byte safety limit", kMaxBtfBytes);
        return false;
      }
      data.insert(data.end(), chunk.begin(), chunk.begin() + count);
    }
    close(fd);

    BtfHeader header{};
    if (!readPacked(data, 0, header) || header.magic != kBtfMagic ||
        header.version != 1 || header.hdr_len < sizeof(BtfHeader) ||
        header.hdr_len > data.size()) {
      return false;
    }

    size_t type_start = 0;
    size_t type_end = 0;
    size_t str_start = 0;
    size_t str_end = 0;
    if (!checkedRegion(header.hdr_len, header.type_off, header.type_len,
                       data.size(), type_start, type_end) ||
        !checkedRegion(header.hdr_len, header.str_off, header.str_len,
                       data.size(), str_start, str_end) ||
        str_start >= str_end || data[str_start] != '\0') {
      return false;
    }

    auto getString = [&](uint32_t string_offset, std::string &out) -> bool {
      if (static_cast<size_t>(string_offset) >= str_end - str_start) return false;
      const char *begin =
          reinterpret_cast<const char *>(data.data() + str_start + string_offset);
      const size_t remaining = str_end - (str_start + string_offset);
      const void *terminator = memchr(begin, '\0', remaining);
      if (terminator == nullptr) return false;
      const size_t length =
          static_cast<const char *>(terminator) - begin;
      if (length > kMaxBtfNameBytes) return false;
      out.assign(begin, length);
      return true;
    };

    types_.clear();
    types_.emplace_back();  // BTF type IDs start at one.
    size_t total_members = 0;

    size_t cursor = type_start;
    while (cursor < type_end) {
      if (types_.size() >= kMaxBtfTypes) return false;
      BtfTypeRaw raw{};
      if (!readPacked(data, cursor, raw)) return false;
      cursor += sizeof(raw);

      const uint32_t kind = (raw.info >> 24U) & 0x1fU;
      const uint32_t vlen = raw.info & 0xffffU;
      const bool kind_flag = (raw.info & 0x80000000U) != 0;
      BtfType parsed{};
      parsed.kind = kind;
      parsed.size_or_type = raw.size_or_type;
      if (!getString(raw.name_off, parsed.name)) return false;

      size_t extra_size = 0;
      switch (kind) {
        case BTF_KIND_INT:
        case BTF_KIND_VAR:
        case BTF_KIND_DECL_TAG:
          extra_size = 4;
          break;
        case BTF_KIND_ARRAY:
          extra_size = 12;
          break;
        case BTF_KIND_STRUCT:
        case BTF_KIND_UNION:
          if (vlen > (type_end - cursor) / sizeof(BtfMemberRaw)) return false;
          if (vlen > kMaxBtfMembers - total_members) return false;
          total_members += vlen;
          extra_size = static_cast<size_t>(vlen) * sizeof(BtfMemberRaw);
          parsed.members.reserve(vlen);
          for (uint32_t i = 0; i < vlen; ++i) {
            BtfMemberRaw member_raw{};
            if (!readPacked(data, cursor + i * sizeof(member_raw), member_raw)) {
              return false;
            }
            BtfMember member{};
            if (!getString(member_raw.name_off, member.name)) return false;
            member.type = member_raw.type;
            member.bit_offset =
                kind_flag ? member_raw.offset & 0x00ffffffU : member_raw.offset;
            parsed.members.push_back(std::move(member));
          }
          break;
        case BTF_KIND_ENUM:
        case BTF_KIND_FUNC_PROTO:
          if (vlen > (type_end - cursor) / 8U) return false;
          extra_size = static_cast<size_t>(vlen) * 8U;
          break;
        case BTF_KIND_DATASEC:
        case BTF_KIND_ENUM64:
          if (vlen > (type_end - cursor) / 12U) return false;
          extra_size = static_cast<size_t>(vlen) * 12U;
          break;
        case BTF_KIND_PTR:
        case BTF_KIND_FWD:
        case BTF_KIND_TYPEDEF:
        case BTF_KIND_VOLATILE:
        case BTF_KIND_CONST:
        case BTF_KIND_RESTRICT:
        case BTF_KIND_FUNC:
        case BTF_KIND_FLOAT:
        case BTF_KIND_TYPE_TAG:
          break;
        default:
          LOGW("BTF: unsupported type kind %u", kind);
          return false;
      }

      if (extra_size > type_end - cursor) return false;
      cursor += extra_size;
      types_.push_back(std::move(parsed));
    }
    return cursor == type_end && types_.size() > 1;
  }

  int fieldOffset(const char *struct_name, const char *field_path) const {
    if (struct_name == nullptr || field_path == nullptr ||
        *struct_name == '\0' || *field_path == '\0') {
      return -1;
    }
    uint32_t type_id = findStruct(struct_name);
    if (type_id == 0) return -1;

    uint64_t bit_offset = 0;
    std::string_view remaining(field_path);
    while (!remaining.empty()) {
      const size_t separator = remaining.find('.');
      const std::string_view component = remaining.substr(0, separator);
      if (component.empty()) return -1;

      type_id = resolveModifiers(type_id);
      if (type_id == 0 || type_id >= types_.size()) return -1;
      const BtfType &type = types_[type_id];
      if (type.kind != BTF_KIND_STRUCT && type.kind != BTF_KIND_UNION) return -1;

      const BtfMember *found = nullptr;
      for (const BtfMember &member : type.members) {
        if (member.name == component) {
          found = &member;
          break;
        }
      }
      if (found == nullptr) return -1;
      bit_offset += found->bit_offset;
      type_id = found->type;

      if (separator == std::string_view::npos) break;
      remaining.remove_prefix(separator + 1);
    }

    if ((bit_offset & 7U) != 0 || bit_offset / 8U > INT32_MAX) return -1;
    return static_cast<int>(bit_offset / 8U);
  }

  size_t structSize(const char *struct_name) const {
    const uint32_t type_id = resolveModifiers(findStruct(struct_name));
    if (type_id == 0 || type_id >= types_.size()) return 0;
    const BtfType &type = types_[type_id];
    if (type.kind != BTF_KIND_STRUCT && type.kind != BTF_KIND_UNION) return 0;
    return type.size_or_type;
  }

 private:
  uint32_t findStruct(std::string_view name) const {
    for (uint32_t id = 1; id < types_.size(); ++id) {
      if (types_[id].kind == BTF_KIND_STRUCT && types_[id].name == name) {
        return id;
      }
    }
    return 0;
  }

  uint32_t resolveModifiers(uint32_t type_id) const {
    for (size_t depth = 0; depth < 32 && type_id < types_.size(); ++depth) {
      const uint32_t kind = types_[type_id].kind;
      if (kind != BTF_KIND_TYPEDEF && kind != BTF_KIND_VOLATILE &&
          kind != BTF_KIND_CONST && kind != BTF_KIND_RESTRICT &&
          kind != BTF_KIND_TYPE_TAG) {
        return type_id;
      }
      type_id = types_[type_id].size_or_type;
    }
    return 0;
  }

  std::vector<BtfType> types_;
};

const BtfIndex *kernelBtfIndex() {
  static BtfIndex index;
  static bool loaded = false;
  static std::once_flag load_once;
  std::call_once(load_once, [] {
    loaded = index.load("/sys/kernel/btf/vmlinux");
    if (!loaded) LOGW("BTF: failed to parse /sys/kernel/btf/vmlinux");
  });
  return loaded ? &index : nullptr;
}

}  // namespace

bool BtfProvider::readBtf(const char *path, const char *struct_name,
                          const char *field_name, int &out_offset) {
  if (path == nullptr || strcmp(path, "/sys/kernel/btf/vmlinux") != 0) return false;
  const BtfIndex *index = kernelBtfIndex();
  if (index == nullptr) return false;
  out_offset = index->fieldOffset(struct_name, field_name);
  return out_offset >= 0;
}

int BtfProvider::queryStructLayout(const char *struct_name,
                                   const char *field_name) {
  int offset = -1;
  if (readBtf("/sys/kernel/btf/vmlinux", struct_name, field_name, offset)) {
    return offset;
  }
  return -1;
}

size_t BtfProvider::queryStructSize(const char *struct_name) {
  const BtfIndex *index = kernelBtfIndex();
  return index == nullptr ? 0 : index->structSize(struct_name);
}

bool BtfProvider::populateCache(OffsetCache &cache) {
  if (!isAvailable()) return false;

  LOGI("BTF available, attempting kernel introspection");

  size_t td_size = queryStructSize("binder_transaction_data");
  if (td_size == 0) {
    LOGW("BTF: could not determine binder_transaction_data size");
    return false;
  }

  int tp = queryStructLayout("binder_transaction_data", "target.ptr");
  int ck = queryStructLayout("binder_transaction_data", "cookie");
  int cd = queryStructLayout("binder_transaction_data", "code");
  int fl = queryStructLayout("binder_transaction_data", "flags");
  int sp = queryStructLayout("binder_transaction_data", "sender_pid");
  int se = queryStructLayout("binder_transaction_data", "sender_euid");
  int ds = queryStructLayout("binder_transaction_data", "data_size");
  int dp = queryStructLayout("binder_transaction_data", "data.ptr.buffer");

  size_t bwr_size = queryStructSize("binder_write_read");
  int bws = queryStructLayout("binder_write_read", "write_size");
  int bwc = queryStructLayout("binder_write_read", "write_consumed");
  int bwb = queryStructLayout("binder_write_read", "write_buffer");
  int brs = queryStructLayout("binder_write_read", "read_size");
  int brc = queryStructLayout("binder_write_read", "read_consumed");
  int brb = queryStructLayout("binder_write_read", "read_buffer");

  if (tp < 0 || ck < 0 || cd < 0 || fl < 0 || sp < 0 || se < 0 ||
      ds < 0 || dp < 0 || bwr_size == 0 || bws < 0 || bwc < 0 ||
      bwb < 0 || brs < 0 || brc < 0 || brb < 0) {
    LOGW("BTF: some field offsets could not be resolved");
    return false;
  }

  cache.target_ptr_offset  = static_cast<size_t>(tp);
  cache.cookie_offset      = static_cast<size_t>(ck);
  cache.code_offset        = static_cast<size_t>(cd);
  cache.flags_offset       = static_cast<size_t>(fl);
  cache.sender_pid_offset  = static_cast<size_t>(sp);
  cache.sender_euid_offset = static_cast<size_t>(se);
  cache.data_size_offset   = static_cast<size_t>(ds);
  cache.data_ptr_offset    = static_cast<size_t>(dp);
  cache.transaction_data_size = td_size;
  cache.bwr_write_size_offset = static_cast<size_t>(bws);
  cache.bwr_write_consumed_offset = static_cast<size_t>(bwc);
  cache.bwr_write_buffer_offset = static_cast<size_t>(bwb);
  cache.bwr_read_size_offset = static_cast<size_t>(brs);
  cache.bwr_read_consumed_offset = static_cast<size_t>(brc);
  cache.bwr_read_buffer_offset = static_cast<size_t>(brb);
  cache.bwr_total_size = bwr_size;

  size_t secctx_size = queryStructSize("binder_transaction_data_secctx");
  cache.transaction_data_secctx_size =
      secctx_size > 0 ? secctx_size : td_size + sizeof(uintptr_t);

  cache.valid = true;
  if (!cache.validateOffsets()) {
    cache.valid = false;
    LOGW("BTF: discovered layout failed validation");
    return false;
  }
  cache.btf_source = true;
  LOGI("BTF: offsets populated and validated successfully");
  return true;
}

// =============================================================================
// Section 3: Runtime Heuristic Offset Discovery
// =============================================================================

bool RuntimeOffsetDiscovery::sendPingProbe(uint8_t *out_buf, size_t buf_size,
                                           size_t &out_len) {
  // Open binder device and send a PING_TRANSACTION to servicemanager (handle 0)
  // to capture a real BR_REPLY in the read buffer. This gives us a live sample
  // of binder_transaction_data as the kernel sees it.
  int fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
  if (fd < 0) {
    fd = open("/dev/vndbinder", O_RDWR | O_CLOEXEC);
  }
  if (fd < 0) {
    LOGW("Heuristic: cannot open binder device");
    return false;
  }

  // Prepare a minimal BC_TRANSACTION for PING_TRANSACTION (code 1599098439)
  // targeting handle 0 (servicemanager)
  struct {
    uint32_t cmd;
    binder_transaction_data txn;
  } __attribute__((packed)) write_data{};

  write_data.cmd = BC_TRANSACTION;
  memset(&write_data.txn, 0, sizeof(write_data.txn));
  write_data.txn.target.handle = 0;
  write_data.txn.code = 1599098439; // PING_TRANSACTION
  write_data.txn.flags = 0;

  binder_write_read bwr{};
  bwr.write_size = sizeof(write_data);
  bwr.write_buffer = reinterpret_cast<binder_uintptr_t>(&write_data);
  bwr.read_size = buf_size;
  bwr.read_buffer = reinterpret_cast<binder_uintptr_t>(out_buf);

  // Use raw syscall to bypass any potentially installed ioctl hook.
  // This probe runs during initialization, before or after hooks may be active.
  int ret = static_cast<int>(syscall(SYS_ioctl, fd, BINDER_WRITE_READ, &bwr));
  close(fd);

  if (ret < 0) {
    LOGW("Heuristic: PING_TRANSACTION ioctl failed: %d", errno);
    return false;
  }

  if (bwr.read_consumed > buf_size) {
    LOGW("Heuristic: driver reported an oversized read buffer");
    return false;
  }
  out_len = static_cast<size_t>(bwr.read_consumed);
  return out_len > sizeof(uint32_t);
}

bool RuntimeOffsetDiscovery::analyzeProbeResult(const uint8_t *buf, size_t len,
                                                OffsetCache &cache) {
  // Walk the read buffer looking for BR_REPLY or BR_TRANSACTION_COMPLETE.
  // When we find BR_REPLY, we know the next bytes are binder_transaction_data.
  // Measure the actual size by looking at _IOC_SIZE of the command.
  size_t pos = 0;
  while (pos + sizeof(uint32_t) <= len) {
    uint32_t cmd;
    memcpy(&cmd, buf + pos, sizeof(uint32_t));
    pos += sizeof(uint32_t);

    auto payload_sz = _IOC_SIZE(cmd);
    if (pos + payload_sz > len) break;

    if (cmd == BR_REPLY || cmd == BR_TRANSACTION) {
      // Binder's userspace ABI is stable. The live reply validates that the
      // running driver agrees with the architecture-specific UAPI header; we
      // then use compiler-calculated offsets rather than guessing padding.
      if (payload_sz != sizeof(binder_transaction_data)) {
        LOGW("ABI validation: driver transaction size=%u, userspace size=%zu",
             payload_sz, sizeof(binder_transaction_data));
        return false;
      }

      cache.target_ptr_offset = offsetof(binder_transaction_data, target.ptr);
      cache.cookie_offset = offsetof(binder_transaction_data, cookie);
      cache.code_offset = offsetof(binder_transaction_data, code);
      cache.flags_offset = offsetof(binder_transaction_data, flags);
      cache.sender_pid_offset = offsetof(binder_transaction_data, sender_pid);
      cache.sender_euid_offset = offsetof(binder_transaction_data, sender_euid);
      cache.data_size_offset = offsetof(binder_transaction_data, data_size);
      cache.data_ptr_offset = offsetof(binder_transaction_data, data.ptr.buffer);
      cache.transaction_data_size = sizeof(binder_transaction_data);
      cache.transaction_data_secctx_size =
          sizeof(binder_transaction_data_secctx);
      cache.bwr_write_size_offset = offsetof(binder_write_read, write_size);
      cache.bwr_write_consumed_offset =
          offsetof(binder_write_read, write_consumed);
      cache.bwr_write_buffer_offset = offsetof(binder_write_read, write_buffer);
      cache.bwr_read_size_offset = offsetof(binder_write_read, read_size);
      cache.bwr_read_consumed_offset =
          offsetof(binder_write_read, read_consumed);
      cache.bwr_read_buffer_offset = offsetof(binder_write_read, read_buffer);
      cache.bwr_total_size = sizeof(binder_write_read);

      cache.valid = true;
      if (!cache.validateOffsets()) {
        cache.valid = false;
        return false;
      }
      cache.heuristic_source = true;
      LOGI("ABI validation: live Binder layout confirmed (%u bytes)", payload_sz);
      return true;
    }

    pos += payload_sz;
  }
  return false;
}

bool RuntimeOffsetDiscovery::discoverOffsets(OffsetCache &cache) {
  LOGI("Starting runtime heuristic offset discovery via PING_TRANSACTION");

  uint8_t probe_buf[4096];
  size_t probe_len = 0;

  if (!sendPingProbe(probe_buf, sizeof(probe_buf), probe_len)) {
    LOGW("Heuristic: probe failed, will try fallback");
    return false;
  }

  if (!analyzeProbeResult(probe_buf, probe_len, cache)) {
    LOGW("Heuristic: could not analyze probe result");
    return false;
  }

  return cache.valid;
}

bool RuntimeOffsetDiscovery::probeOffsets(OffsetCache &cache) {
  return discoverOffsets(cache);
}

// =============================================================================
// Section 4: Compile-time Binder UAPI fallback (supported Android 12–16)
// =============================================================================

// Binder's ioctl structures are a stable userspace ABI. These values are
// calculated by the compiler for each packaged 64-bit ABI; they are not
// hand-maintained offsets and therefore cannot silently drift with padding.
const FallbackOffsetEntry FallbackDatabase::s_entries[] = {
#define BINDER_UAPI_ENTRY(api)                                                \
  {api, 0, 0, sizeof(binder_transaction_data),                                \
   sizeof(binder_transaction_data_secctx),                                    \
   offsetof(binder_transaction_data, target.ptr),                             \
   offsetof(binder_transaction_data, cookie),                                 \
   offsetof(binder_transaction_data, code),                                   \
   offsetof(binder_transaction_data, flags),                                  \
   offsetof(binder_transaction_data, sender_pid),                             \
   offsetof(binder_transaction_data, sender_euid),                            \
   offsetof(binder_transaction_data, data_size),                              \
   offsetof(binder_transaction_data, data.ptr.buffer),                        \
   sizeof(binder_write_read)}
    BINDER_UAPI_ENTRY(31),
    BINDER_UAPI_ENTRY(32),
    BINDER_UAPI_ENTRY(33),
    BINDER_UAPI_ENTRY(34),
    BINDER_UAPI_ENTRY(35),
    BINDER_UAPI_ENTRY(36),
#undef BINDER_UAPI_ENTRY
};

const size_t FallbackDatabase::s_entry_count =
    sizeof(s_entries) / sizeof(s_entries[0]);

const FallbackOffsetEntry* FallbackDatabase::getTable(size_t &out_count) {
  out_count = s_entry_count;
  return s_entries;
}

bool FallbackDatabase::lookup(int api_level, int kernel_major, int kernel_minor,
                              OffsetCache &cache) {
  LOGI("FallbackDatabase: looking up API=%d kernel=%d.%d",
       api_level, kernel_major, kernel_minor);

  // Helper to populate cache from a table entry
  auto populateFromEntry = [](const FallbackOffsetEntry &e, OffsetCache &c) {
    c.transaction_data_size        = e.transaction_data_size;
    c.transaction_data_secctx_size = e.secctx_size;
    c.target_ptr_offset  = e.target_ptr_offset;
    c.cookie_offset      = e.cookie_offset;
    c.code_offset        = e.code_offset;
    c.flags_offset       = e.flags_offset;
    c.sender_pid_offset  = e.sender_pid_offset;
    c.sender_euid_offset = e.sender_euid_offset;
    c.data_size_offset   = e.data_size_offset;
    c.data_ptr_offset    = e.data_ptr_offset;
    c.bwr_write_size_offset = offsetof(binder_write_read, write_size);
    c.bwr_write_consumed_offset = offsetof(binder_write_read, write_consumed);
    c.bwr_write_buffer_offset = offsetof(binder_write_read, write_buffer);
    c.bwr_read_size_offset = offsetof(binder_write_read, read_size);
    c.bwr_read_consumed_offset = offsetof(binder_write_read, read_consumed);
    c.bwr_read_buffer_offset = offsetof(binder_write_read, read_buffer);
    c.bwr_total_size     = e.bwr_total_size;
    c.fallback_mode      = true;
    c.valid              = true;
  };

  // The module's minSdk is 31. Kernel versions do not change this userspace
  // ABI, so select by Android API only.
  for (size_t i = 0; i < s_entry_count; ++i) {
    if (s_entries[i].api_level == api_level) {
      populateFromEntry(s_entries[i], cache);
      if (!cache.validateOffsets()) {
        cache.valid = false;
        return false;
      }
      LOGI("FallbackDatabase: validated stable Binder UAPI for API=%d", api_level);
      return true;
    }
  }

  LOGE("FallbackDatabase: unsupported Android API %d", api_level);
  return false;
}

// =============================================================================
// Section 5: Bounds-Checked Rust Stream Parser
// =============================================================================

// Safe memory copy using pipe method (kernel validates pointer safely)
static bool safe_memcpy(void *dst, const void *src, size_t len) {
  if (len == 0) return true;
  if (dst == nullptr || src == nullptr) return false;

  int fd[2];
  if (pipe(fd) < 0) return false;

  constexpr size_t kChunkSize = 4096;
  const uint8_t *src_bytes = static_cast<const uint8_t *>(src);
  uint8_t *dst_bytes = static_cast<uint8_t *>(dst);
  size_t remaining = len;

  while (remaining > 0) {
    const size_t to_copy = std::min(remaining, kChunkSize);

    // The kernel returns EFAULT for an invalid source or destination instead
    // of delivering SIGSEGV to this process.
    size_t written = 0;
    while (written < to_copy) {
      const ssize_t count =
          write(fd[1], src_bytes + written, to_copy - written);
      if (count < 0 && errno == EINTR) continue;
      if (count <= 0) {
        close(fd[0]);
        close(fd[1]);
        return false;
      }
      written += static_cast<size_t>(count);
    }

    size_t copied = 0;
    while (copied < to_copy) {
      const ssize_t count = read(fd[0], dst_bytes + copied, to_copy - copied);
      if (count < 0 && errno == EINTR) continue;
      if (count <= 0) {
        close(fd[0]);
        close(fd[1]);
        return false;
      }
      copied += static_cast<size_t>(count);
    }

    src_bytes += to_copy;
    dst_bytes += to_copy;
    remaining -= to_copy;
  }

  close(fd[0]);
  close(fd[1]);

  return true;
}

bool BinderStreamParser::safeWrite(uintptr_t base, size_t offset,
                                   const void *src, size_t len,
                                   size_t buffer_end) {
  // Bounds check with overflow protection
  if (offset > buffer_end || len > buffer_end - offset) {
    LOGE("safeWrite: out-of-bounds write offset=%zu len=%zu buffer_end=%zu",
         offset, len, buffer_end);
    return false;
  }
  uintptr_t destination = 0;
  if (__builtin_add_overflow(base, offset, &destination)) return false;
  return safe_memcpy(reinterpret_cast<void *>(destination), src, len);
}

bool BinderStreamParser::parse(uintptr_t buffer, size_t consumed,
                               size_t buffer_size,
                               const OffsetCache &cache,
                               ParsedTransaction *out_txns, size_t max_txns,
                               size_t &out_txn_count) {
  out_txn_count = 0;

  constexpr size_t kMaxTransactionsPerCall = 1024;
  if (buffer == 0 || consumed == 0 || consumed > buffer_size || !cache.valid ||
      out_txns == nullptr || max_txns == 0 ||
      max_txns > kMaxTransactionsPerCall) {
    return false;
  }

  RustOffsetCacheView rust_cache{
      cache.target_ptr_offset,     cache.cookie_offset,
      cache.code_offset,           cache.flags_offset,
      cache.sender_pid_offset,     cache.sender_euid_offset,
      cache.data_size_offset,      cache.data_ptr_offset,
      cache.transaction_data_size, cache.transaction_data_secctx_size,
      1};

  std::vector<RustParsedTransaction> rust_txns(max_txns);
  size_t rust_count = 0;
  if (!rust_parse_binder_stream(reinterpret_cast<const uint8_t *>(buffer),
                                consumed, consumed, &rust_cache,
                                rust_txns.data(), max_txns, &rust_count) ||
      rust_count == 0 || rust_count > max_txns) {
    return false;
  }

  for (size_t i = 0; i < rust_count; ++i) {
    const auto &source = rust_txns[i];
    ParsedTransaction &transaction = out_txns[i];
    transaction.target_ptr = source.target_ptr;
    transaction.cookie = source.cookie;
    transaction.code = source.code;
    transaction.flags = source.flags;
    transaction.sender_pid = source.sender_pid;
    transaction.sender_euid = source.sender_euid;
    transaction.data_size = source.data_size;
    transaction.data_buffer = source.data_buffer;
    transaction.cmd = source.cmd;
    transaction.raw_ptr = source.raw_ptr;
    transaction.raw_size = source.raw_size;
    transaction.valid = source.valid != 0;
  }
  out_txn_count = rust_count;
  return true;
}

bool BinderStreamParser::writeBack(uintptr_t buffer_ptr, size_t consumed,
                                   const ParsedTransaction &txn,
                                   const OffsetCache &cache) {
  uintptr_t buffer_end = 0;
  uintptr_t transaction_end = 0;
  if (!txn.valid || buffer_ptr == 0 || txn.raw_ptr < buffer_ptr ||
      !cache.valid ||
      __builtin_add_overflow(buffer_ptr, consumed, &buffer_end) ||
      __builtin_add_overflow(txn.raw_ptr, txn.raw_size, &transaction_end) ||
      transaction_end > buffer_end) {
    return false;
  }

  uintptr_t field_base = txn.raw_ptr;
  size_t field_end = txn.raw_size;

  return safeWrite(field_base, cache.target_ptr_offset, &txn.target_ptr,
                   sizeof(uintptr_t), field_end) &&
         safeWrite(field_base, cache.cookie_offset, &txn.cookie,
                   sizeof(uintptr_t), field_end) &&
         safeWrite(field_base, cache.code_offset, &txn.code,
                   sizeof(uint32_t), field_end);
}

// =============================================================================
// Section 6: AdaptiveBinderInterceptor Orchestrator
// =============================================================================

int AdaptiveBinderInterceptor::detectApiLevel() {
  char sdk_str[PROP_VALUE_MAX] = {};
  __system_property_get("ro.build.version.sdk", sdk_str);
  int sdk_version = atoi(sdk_str);
  return sdk_version > 0 ? sdk_version : 0;
}

bool AdaptiveBinderInterceptor::parseKernelVersion(int &major, int &minor) {
  struct utsname uts{};
  if (uname(&uts) != 0) {
    LOGE("Failed to get kernel version via uname");
    return false;
  }

  if (sscanf(uts.release, "%d.%d", &major, &minor) < 2) {
    LOGE("Failed to parse kernel version from '%s'", uts.release);
    return false;
  }
  return true;
}

bool AdaptiveBinderInterceptor::initBtf(OffsetCache &cache) {
  int kmajor = 0, kminor = 0;
  if (!parseKernelVersion(kmajor, kminor)) return false;

  // BTF is typically available on kernel 5.4+ with CONFIG_DEBUG_INFO_BTF
  if (kmajor < 5 || (kmajor == 5 && kminor < 4)) {
    LOGI("Kernel %d.%d < 5.4, skipping BTF", kmajor, kminor);
    return false;
  }

  return BtfProvider::populateCache(cache);
}

bool AdaptiveBinderInterceptor::initHeuristic(OffsetCache &cache) {
  return RuntimeOffsetDiscovery::discoverOffsets(cache);
}

bool AdaptiveBinderInterceptor::initFallback(OffsetCache &cache) {
  int api_level = detectApiLevel();
  int kmajor = 0, kminor = 0;
  parseKernelVersion(kmajor, kminor);
  return FallbackDatabase::lookup(api_level, kmajor, kminor, cache);
}

bool AdaptiveBinderInterceptor::initialize() {
  OffsetCache &cache = OffsetCache::instance();

  // Detect system info
  cache.android_api_level = detectApiLevel();
  if (cache.android_api_level < 31 || cache.android_api_level > 36) {
    LOGE("AdaptiveBinderInterceptor: unsupported Android API %d",
         cache.android_api_level);
    return false;
  }
  int kmajor = 0, kminor = 0;
  if (parseKernelVersion(kmajor, kminor)) {
    char kver[64];
    snprintf(kver, sizeof(kver), "%d.%d", kmajor, kminor);
    cache.kernel_version = kver;
  }

  LOGI("AdaptiveBinderInterceptor: API=%d kernel=%s",
       cache.android_api_level, cache.kernel_version.c_str());

  // Prefer live validation of the stable userspace ABI. Kernel BTF is not
  // consulted here: parsing vmlinux in every injected service adds material
  // startup and memory cost without improving the stable Binder UAPI layout.
  if (initHeuristic(cache)) {
    LOGI("Strategy: live Binder UAPI validation succeeded");
    return true;
  }

  // The compiler-calculated fallback is restricted to packaged API/ABIs.
  if (initFallback(cache)) {
    LOGI("Strategy: safe_fallback database activated");
    return true;
  }

  LOGE("AdaptiveBinderInterceptor: ALL strategies failed! "
       "Using graceful degradation; interception disabled.");
  cache.valid = false;
  return false;
}

// Global adaptive interceptor instance
static AdaptiveBinderInterceptor g_adaptive;

// Private transaction used only to obtain the in-process control Binder. The
// driver-supplied sender EUID is checked before this code is rewritten.
static constexpr uint32_t kControlEndpointTransactionCode = 0xdeadbeefU;

// =============================================================================
// Section 8: Binder Interceptor Core (preserved API, adaptive internals)
// =============================================================================

sp<BinderInterceptor> gBinderInterceptor = nullptr;

struct thread_transaction_info {
  uint32_t code;
  wp<BBinder> target;
};

thread_local std::queue<thread_transaction_info> ttis;

class BinderStub : public BBinder {
  status_t onTransact(uint32_t code, const android::Parcel &data,
                      android::Parcel *reply, uint32_t flags) override {
    LOGD("BinderStub %d", code);
    if (!ttis.empty()) {
      auto tti = ttis.front();
      ttis.pop();
      if (tti.target == nullptr &&
          tti.code == kControlEndpointTransactionCode && reply) {
        LOGD("Native Binder control endpoint requested");
        reply->writeStrongBinder(gBinderInterceptor);
        return OK;
      } else if (tti.target != nullptr) {
        LOGD("intercepting");
        auto p = tti.target.promote();
        if (p) {
          LOGD("calling interceptor");
          status_t result;
          if (!gBinderInterceptor->handleIntercept(p, tti.code, data, reply,
                                                   flags, result)) {
            LOGD("calling orig");
            result = p->transact(tti.code, data, reply, flags);
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

// =============================================================================
// Section 9: Binder FD Caching (preserved with bounds safety)
// =============================================================================

static std::shared_mutex g_binder_fd_lock;
struct BinderFdCacheEntry {
  dev_t device = 0;
  ino_t inode = 0;
  bool is_binder = false;
};
static std::unordered_map<int, BinderFdCacheEntry> g_binder_fds;
static constexpr size_t kMaxBinderFdCacheEntries = 4096;

static bool is_binder_fd(int fd) {
  if (fd < 0) return false;
  struct stat descriptor_stat {};
  if (fstat(fd, &descriptor_stat) != 0) return false;

  {
    std::shared_lock<std::shared_mutex> lock(g_binder_fd_lock);
    auto it = g_binder_fds.find(fd);
    if (it != g_binder_fds.end() &&
        it->second.device == descriptor_stat.st_dev &&
        it->second.inode == descriptor_stat.st_ino) {
      return it->second.is_binder;
    }
  }

  char path[256];
  char proc_path[64];
  snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fd);
  ssize_t len = readlink(proc_path, path, sizeof(path) - 1);

  bool is_binder = false;
  if (len > 0 && static_cast<size_t>(len) < sizeof(path) - 1) {
    std::string_view sv(path, static_cast<size_t>(len));
    const size_t separator = sv.rfind('/');
    const std::string_view basename =
        separator == std::string_view::npos ? sv : sv.substr(separator + 1);
    is_binder = basename == "binder" || basename == "vndbinder" ||
                basename == "hwbinder";
  }

  {
    std::unique_lock<std::shared_mutex> lock(g_binder_fd_lock);
    if (g_binder_fds.size() >= kMaxBinderFdCacheEntries) {
      g_binder_fds.clear();
    }
    g_binder_fds[fd] =
        BinderFdCacheEntry{descriptor_stat.st_dev, descriptor_stat.st_ino,
                           is_binder};
  }

  return is_binder;
}

// =============================================================================
// Section 10: Hooked Functions (ioctl, close) and Adaptive Stream Parsing
// =============================================================================

int (*old_ioctl)(int fd, unsigned long request, ...) = nullptr;
int new_ioctl(int fd, unsigned long request, ...) {
  va_list list;
  va_start(list, request);
  auto arg = va_arg(list, void *);
  va_end(list);
  const int result =
      old_ioctl != nullptr
          ? old_ioctl(fd, request, arg)
          : static_cast<int>(syscall(SYS_ioctl, fd, request, arg));

  if (result >= 0 && request == BINDER_WRITE_READ) {
    // Safety: ensure arg is not null before any access
    if (arg == nullptr) {
      return result;
    }

    if (!is_binder_fd(fd)) {
      return result;
    }

    const OffsetCache &cache = OffsetCache::instance();
    if (!cache.valid) {
      // The adaptive system is not initialized, so parsing is disabled.
      return result;
    }

    // Safe accessor: read binder_write_read fields via dynamic offsets
    // instead of raw C-style struct cast
    binder_write_read bwr{};
    if (!safe_memcpy(&bwr, arg, sizeof(bwr))) {
      LOGE("new_ioctl: failed to safely read binder_write_read");
      return result;
    }

    if (bwr.read_buffer == 0 || bwr.read_size == 0) {
      return result;
    }

    LOGD("read buffer %p size %llu consumed %llu", (void *)bwr.read_buffer,
         (unsigned long long)bwr.read_size,
         (unsigned long long)bwr.read_consumed);

    // Validate consumed is within bounds
    constexpr binder_size_t kMaxBinderReadBytes = 8U * 1024U * 1024U;
    if (bwr.read_consumed <= sizeof(int32_t) ||
        bwr.read_consumed > bwr.read_size ||
        bwr.read_consumed > kMaxBinderReadBytes ||
        gBinderInterceptor == nullptr || gBinderStub == nullptr) {
      return result;
    }

    // Use the bounded Rust stream parser to extract transactions.
    static constexpr size_t MAX_TXNS = 16;
    BinderStreamParser::ParsedTransaction txns[MAX_TXNS];
    size_t txn_count = 0;

    if (!BinderStreamParser::parse(bwr.read_buffer, bwr.read_consumed,
                                   bwr.read_size, cache,
                                   txns, MAX_TXNS, txn_count)) {
      return result;
    }

    // Process each parsed transaction
    for (size_t i = 0; i < txn_count; ++i) {
      auto &txn = txns[i];
      if (!txn.valid) continue;

      auto wt = txn.target_ptr;
      if (wt == 0 || txn.cookie == 0) continue;

      bool need_intercept = false;
      thread_transaction_info tti{};

      if (txn.code == kControlEndpointTransactionCode &&
          txn.sender_euid == 0) {
        tti.code = kControlEndpointTransactionCode;
        tti.target = nullptr;
        need_intercept = true;
      } else if (reinterpret_cast<RefBase::weakref_type *>(wt)
                     ->attemptIncStrong(nullptr)) {
        auto b = (BBinder *)txn.cookie;
        auto wb = wp<BBinder>::fromExisting(b);
        if (gBinderInterceptor->shouldIntercept(wb, txn.code)) {
          tti.code = txn.code;
          tti.target = wb;
          need_intercept = true;
          LOGD("intercept code=%d target=%p", txn.code, b);
        }
        b->decStrong(nullptr);
      }

      if (need_intercept) {
        constexpr size_t kMaxQueuedTransactions = 64;
        if (ttis.size() >= kMaxQueuedTransactions) {
          LOGW("Binder interception queue is full; passing transaction through");
          continue;
        }
        LOGD("add intercept item!");
        txn.target_ptr = (uintptr_t)gBinderStub->getWeakRefs();
        txn.cookie = (uintptr_t)gBinderStub.get();
        txn.code = kControlEndpointTransactionCode;

        // Write the modified transaction back using bounds-checked writer
        if (BinderStreamParser::writeBack(bwr.read_buffer, bwr.read_consumed,
                                          txn, cache)) {
          ttis.push(tti);
        } else {
          LOGE("Failed to write intercepted Binder transaction");
        }
      }
    }
  }
  return result;
}

// =============================================================================
// Section 11: BinderInterceptor Methods (preserved from original)
// =============================================================================

bool BinderInterceptor::shouldIntercept(const wp<BBinder> &target, uint32_t code) {
  ReadGuard g{lock};
  auto it = items.find(target);
  if (it == items.end()) return false;
  const auto &codes = it->second.filtered_codes;
  return codes.empty() || std::find(codes.begin(), codes.end(), code) != codes.end();
}

status_t BinderInterceptor::onTransact(uint32_t code,
                                       const android::Parcel &data,
                                       android::Parcel *reply, uint32_t flags) {
  if (code == REGISTER_INTERCEPTOR) {
    if (reply == nullptr) return BAD_VALUE;
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
        static_cast<size_t>(code_count) >
            data.dataAvail() / sizeof(uint32_t)) {
      return BAD_VALUE;
    }
    if (code_count > 0) {
      codes.reserve(static_cast<size_t>(code_count));
      for (int32_t i = 0; i < code_count; ++i) {
        uint32_t filtered_code = 0;
        if (data.readUint32(&filtered_code) != OK || filtered_code == 0 ||
            filtered_code == kControlEndpointTransactionCode) {
          return BAD_VALUE;
        }
        codes.push_back(filtered_code);
      }
      if (data.dataAvail() != 0) return BAD_VALUE;
      std::sort(codes.begin(), codes.end());
      if (std::adjacent_find(codes.begin(), codes.end()) != codes.end()) {
        return BAD_VALUE;
      }
      LOGI("Interceptor registered for binder %p with %zu filtered codes",
           target.get(), codes.size());
    }
    sp<IBinder> replaced_interceptor;
    {
      WriteGuard wg{lock};
      wp<IBinder> t = target;
      auto [it, inserted] = items.try_emplace(t);
      if (inserted) {
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
    return reply->writeInt32(0);
  } else if (code == UNREGISTER_INTERCEPTOR) {
    if (reply == nullptr) return BAD_VALUE;
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
    if (data.dataAvail() != 0) return BAD_VALUE;
    {
      WriteGuard wg{lock};
      wp<IBinder> t = target;
      auto it = items.find(t);
      if (it != items.end()) {
        if (it->second.interceptor != interceptor) {
          return BAD_VALUE;
        }
        items.erase(it);
        return reply->writeInt32(0);
      }
      return BAD_VALUE;
    }
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
      LOGE("no intercept item found!");
      return false;
    }
    interceptor = it->second.interceptor;
  }
  if (interceptor == nullptr) return false;
  IPCThreadState *thread_state = IPCThreadState::self();
  if (thread_state == nullptr) return false;
  const uid_t calling_uid = thread_state->getCallingUid();
  const pid_t calling_pid = thread_state->getCallingPid();
  LOGD("intercept on binder %p code %d flags %d (reply=%s)", target.get(), code,
       flags, reply ? "true" : "false");
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

// =============================================================================
// =============================================================================
// Section 13: Hook Registration & Entry Point
// =============================================================================

bool initialize_hooks() {
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
    LOGE("libbinder ioctl hook has no original function");
    return false;
  }
  LOGI("libbinder ioctl hook installed successfully");
  return true;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]] bool
entry(void *handle) {
  LOGI("injected, my handle %p", handle);
  return initialize_hooks();
}
