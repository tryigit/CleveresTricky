#include <utils/RefBase.h>
#include <binder/IPCThreadState.h>
#include <binder/Parcel.h>
#include <binder/IBinder.h>
#include <binder/Binder.h>
#include <utils/StrongPointer.h>
#include <binder/Common.h>
#include <binder/IServiceManager.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include "kernel/binder.h"

#include <utility>
#include <map>
#include <mutex>
#include <shared_mutex>
#include <vector>
#include <queue>
#include <string>
#include <string_view>
#include <array>
#include <algorithm>
// #include <android/log.h> // logging.hpp should cover this
#include <sys/system_properties.h> // For PROP_VALUE_MAX

#include "logging.hpp"
#include "lsplt.hpp"
#include "elf_util.h"
#include "binder_interceptor.h"
// #include <utils/String8.h> // Removed as per subtask instructions

using namespace SandHook;
using namespace android;

// Define Target Properties
// Optimization: Use std::array<std::string_view> instead of std::set<std::string>
// to avoid std::string construction (heap allocation) on every property access check.
static constexpr std::array<std::string_view, 8> g_target_properties = {
    "ro.boot.verifiedbootstate",
    "ro.boot.flash.locked",
    "ro.boot.veritymode",
    "ro.boot.vbmeta.device_state",
    "ro.boot.warranty_bit",
    "ro.secure",
    "ro.debuggable",
    "ro.oem_unlock_supported"
};

// Declare Original Function Pointer
int (*original_system_property_get)(const char* name, char* value);

// Forward declaration for gBinderInterceptor
class BinderInterceptor;
extern sp<BinderInterceptor> gBinderInterceptor;

// Define transaction code for fetching spoofed property
static const uint32_t GET_SPOOFED_PROPERTY_TRANSACTION_CODE = IBinder::FIRST_CALL_TRANSACTION + 0;
// Define interface token for the property service
static const char* PROPERTY_SERVICE_INTERFACE_TOKEN = "android.os.IPropertyServiceHider";

// Helper functions for manual Parcel manipulation to avoid ABI issues with String16
void writeString16_manual(Parcel& p, const char* str) {
    size_t len = str ? strlen(str) : 0;
    std::vector<char16_t> u16;
    u16.reserve(len + 1);
    for (size_t i = 0; i < len; ++i) {
        u16.push_back(static_cast<char16_t>(str[i]));
    }
    u16.push_back(0); // null terminator

    p.writeInt32(len); // write length (count of char16_t excluding null)

    size_t dataSize = u16.size() * sizeof(char16_t);
    size_t paddedSize = (dataSize + 3) & ~3;
    size_t padding = paddedSize - dataSize;

    p.write(u16.data(), dataSize);
    if (padding > 0) {
        uint8_t pad[3] = {0};
        p.write(pad, padding);
    }
}

bool readString16_manual(const Parcel& p, std::string& out_str) {
    int32_t len = p.readInt32();
    if (len < 0) return false; // null string
    if (len > 4096) return false; // Sanity check

    size_t byteLen = (len + 1) * sizeof(char16_t);
    size_t paddedSize = (byteLen + 3) & ~3;
    size_t padding = paddedSize - byteLen;

    std::vector<char16_t> buf(len + 1);
    if (p.read(buf.data(), byteLen) != 0) return false;

    if (padding > 0) {
        p.setDataPosition(p.dataPosition() + padding);
    }

    out_str.clear();
    out_str.reserve(len);
    for (int32_t i = 0; i < len; ++i) {
         if (buf[i]) out_str += (char)buf[i];
    }
    return true;
}

void writeInterfaceToken_manual(Parcel& p, const char* interface_name) {
    // Write StrictMode policy (0 for now)
    p.writeInt32(0);
    writeString16_manual(p, interface_name);
}


// Implement Hook Function
int new_system_property_get(const char* name, char* value) {
    bool found = false;
    if (name != nullptr) {
        std::string_view name_sv(name);
        for (const auto& prop : g_target_properties) {
            if (prop == name_sv) {
                found = true;
                break;
            }
        }
    }

    if (found) {
        LOGI("Targeted property access: %s", name);
        if (gBinderInterceptor != nullptr && gBinderInterceptor->gPropertyServiceBinder != nullptr) {
            Parcel data_parcel, reply_parcel;
            
            // Manual construction of the parcel
            writeInterfaceToken_manual(data_parcel, PROPERTY_SERVICE_INTERFACE_TOKEN);
            writeString16_manual(data_parcel, name);

            // LOGD("Transacting with property service for %s", name);
            status_t status = gBinderInterceptor->gPropertyServiceBinder->transact(
                GET_SPOOFED_PROPERTY_TRANSACTION_CODE, data_parcel, &reply_parcel, 0);

            if (status != OK) {
                LOGE("Transaction failed for property %s: %d", name, status);
                return original_system_property_get(name, value);
            }

            int32_t exception_code = reply_parcel.readInt32(); // readExceptionCode usually just reads the first int
            if (exception_code != 0) {
                LOGE("Property service threw exception for %s: %d", name, exception_code);
                return original_system_property_get(name, value);
            }

            std::string spoofed_value;
            if (readString16_manual(reply_parcel, spoofed_value)) {
                LOGI("Received spoofed value for %s: '%s'", name, spoofed_value.c_str());
                strncpy(value, spoofed_value.c_str(), PROP_VALUE_MAX - 1);
                value[PROP_VALUE_MAX - 1] = '\0';
                return strlen(value);
            } else {
                // LOGD("Property service returned null or failed to read for %s", name);
            }
        } else {
            // LOGW("Property service binder not available for %s.", name);
        }
    }
    return original_system_property_get(name, value);
}

// Definition of gBinderInterceptor is now after BinderStub,
// ensure BinderInterceptor class is known via header.
sp<BinderInterceptor> gBinderInterceptor = nullptr;

struct thread_transaction_info {
    uint32_t code;
    wp<BBinder> target;
};

thread_local std::queue<thread_transaction_info> ttis;

class BinderStub : public BBinder {
    status_t onTransact(uint32_t code, const android::Parcel &data, android::Parcel *reply, uint32_t flags) override {
        LOGD("BinderStub %d", code);
        if (!ttis.empty()) {
            auto tti = ttis.front();
            ttis.pop();
            if (tti.target == nullptr && tti.code == 0xdeadbeef && reply) {
                LOGD("backdoor requested!");
                reply->writeStrongBinder(gBinderInterceptor);
                return OK;
            } else if (tti.target != nullptr) {
                LOGD("intercepting");
                auto p = tti.target.promote();
                if (p) {
                    LOGD("calling interceptor");
                    status_t result;
                    if (!gBinderInterceptor->handleIntercept(p, tti.code, data, reply, flags,
                                                             result)) {
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

static std::shared_mutex g_binder_fd_lock;
static std::map<int, bool> g_binder_fds;

static bool is_binder_fd(int fd) {
    {
        std::shared_lock<std::shared_mutex> lock(g_binder_fd_lock);
        auto it = g_binder_fds.find(fd);
        if (it != g_binder_fds.end()) {
            return it->second;
        }
    }

    char path[256];
    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fd);
    ssize_t len = readlink(proc_path, path, sizeof(path) - 1);

    bool is_binder = false;
    if (len > 0) {
        std::string_view sv(path, static_cast<size_t>(len));
        if (sv.find("binder") != std::string_view::npos) {
            is_binder = true;
        }
    }

    if (is_binder) {
        std::unique_lock<std::shared_mutex> lock(g_binder_fd_lock);
        g_binder_fds[fd] = true;
    }

    return is_binder;
}

// FIXME: when use ioctl hooking, some already blocked ioctl calls will not be hooked
int (*old_ioctl)(int fd, int request, ...) = nullptr;
int new_ioctl(int fd, int request, ...) {
    va_list list;
    va_start(list, request);
    auto arg = va_arg(list, void*);
    va_end(list);
    auto result = old_ioctl(fd, request, arg);

    if (result >= 0 && request == BINDER_WRITE_READ) {
        // Check if the FD is a binder device.
        // Note: We use a cache in is_binder_fd which never invalidates.
        // This is generally safe because if an FD is reused for a non-binder file,
        // old_ioctl above would likely have failed (returning < 0) for BINDER_WRITE_READ,
        // causing us to skip this block anyway.
        if (!is_binder_fd(fd)) {
            return result;
        }

        auto &bwr = *(struct binder_write_read*) arg;
        LOGD("read buffer %p size %zu consumed %zu", bwr.read_buffer, bwr.read_size,
             bwr.read_consumed);
        if (bwr.read_buffer != 0 && bwr.read_size != 0 && bwr.read_consumed > sizeof(int32_t)) {
            auto ptr = bwr.read_buffer;
            auto consumed = bwr.read_consumed;
            while (consumed > 0) {
                consumed -= sizeof(uint32_t);
                if (consumed < 0) {
                    LOGE("consumed < 0");
                    break;
                }
                auto cmd = *(uint32_t *) ptr;
                ptr += sizeof(uint32_t);
                auto sz = _IOC_SIZE(cmd);
                LOGD("ioctl cmd %d sz %d", cmd, sz);
                consumed -= sz;
                if (consumed < 0) {
                    LOGE("consumed < 0");
                    break;
                }
                if (cmd == BR_TRANSACTION_SEC_CTX || cmd == BR_TRANSACTION) {
                    binder_transaction_data_secctx *tr_secctx = nullptr;
                    binder_transaction_data *tr = nullptr;
                    if (cmd == BR_TRANSACTION_SEC_CTX) {
                        LOGD("cmd is BR_TRANSACTION_SEC_CTX");
                        tr_secctx = (binder_transaction_data_secctx *) ptr;
                        tr = &tr_secctx->transaction_data;
                    } else {
                        LOGD("cmd is BR_TRANSACTION");
                        tr = (binder_transaction_data *) ptr;
                    }

                    if (tr != nullptr) {
                        auto wt = tr->target.ptr;
                        if (wt != 0) {
                            bool need_intercept = false;
                            thread_transaction_info tti{};
                            if (tr->code == 0xdeadbeef && tr->sender_euid == 0) {
                                tti.code = 0xdeadbeef;
                                tti.target = nullptr;
                                need_intercept = true;
                            } else if (reinterpret_cast<RefBase::weakref_type *>(wt)->attemptIncStrong(
                                    nullptr)) {
                                auto b = (BBinder *) tr->cookie;
                                auto wb = wp<BBinder>::fromExisting(b);
                                if (gBinderInterceptor->needIntercept(wb)) {
                                    tti.code = tr->code;
                                    tti.target = wb;
                                    need_intercept = true;
                                    LOGD("intercept code=%d target=%p", tr->code, b);
                                }
                                b->decStrong(nullptr);
                            }
                            if (need_intercept) {
                                LOGD("add intercept item!");
                                tr->target.ptr = (uintptr_t) gBinderStub->getWeakRefs();
                                tr->cookie = (uintptr_t) gBinderStub.get();
                                tr->code = 0xdeadbeef;
                                ttis.push(tti);
                            }
                        }
                    } else {
                        LOGE("no transaction data found!");
                    }
                }
                ptr += sz;
            }
        }
    }
    return result;
}

bool BinderInterceptor::needIntercept(const wp<BBinder> &target) {
    ReadGuard g{lock};
    return items.find(target) != items.end();
}

status_t
BinderInterceptor::onTransact(uint32_t code, const android::Parcel &data, android::Parcel *reply,
                              uint32_t flags) {
    if (code == REGISTER_INTERCEPTOR) {
        sp<IBinder> target, interceptor;
        if (data.readStrongBinder(&target) != OK) {
            return BAD_VALUE;
        }
        if (!target->localBinder()) {
            return BAD_VALUE;
        }
        if (data.readStrongBinder(&interceptor) != OK) {
            return BAD_VALUE;
        }
        {
            WriteGuard wg{lock};
            wp<IBinder> t = target;
            auto it = items.lower_bound(t);
            if (it == items.end() || it->first != t) {
                it = items.emplace_hint(it, t, InterceptItem{});
                it->second.target = t;
            } else if (it->second.interceptor != nullptr && it->second.interceptor != interceptor) {
                Parcel data, reply;
                it->second.interceptor->transact(INTERCEPTOR_REPLACED, data, &reply, IBinder::FLAG_ONEWAY);
            }
            it->second.interceptor = interceptor;
            return OK;
        }
    } else if (code == UNREGISTER_INTERCEPTOR) {
        sp<IBinder> target, interceptor;
        if (data.readStrongBinder(&target) != OK) {
            return BAD_VALUE;
        }
        if (!target->localBinder()) {
            return BAD_VALUE;
        }
        if (data.readStrongBinder(&interceptor) != OK) {
            return BAD_VALUE;
        }
        {
            WriteGuard wg{lock};
            wp<IBinder> t = target;
            auto it = items.find(t);
            if (it != items.end()) {
                if (it->second.interceptor != interceptor) {
                    return BAD_VALUE;
                }
                items.erase(it);
                return OK;
            }
            return BAD_VALUE;
        }
    } else if (code == REGISTER_PROPERTY_SERVICE) {
        LOGI("Registering property service binder");
        sp<IBinder> property_service;
        if (data.readStrongBinder(&property_service) != OK) {
            LOGE("Failed to read property service binder from parcel");
            return BAD_VALUE;
        }
        if (property_service == nullptr) {
            LOGE("Received null property service binder");
            return BAD_VALUE;
        }
        this->gPropertyServiceBinder = property_service;
        LOGI("Property service binder registered successfully");
        if (reply) { // Send a success reply
            reply->writeInt32(0); // No error
        }
        return OK;
    }
    return UNKNOWN_TRANSACTION;
}

bool
BinderInterceptor::handleIntercept(sp<BBinder> target, uint32_t code, const Parcel &data, Parcel *reply,
                                   uint32_t flags, status_t &result) {
#define CHECK(expr) ({ auto __result = (expr); if (__result != OK) { LOGE(#expr " = %d", __result); return false; } })
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
    LOGD("intercept on binder %p code %d flags %d (reply=%s)", target.get(), code, flags,
         reply ? "true" : "false");
    Parcel tmpData, tmpReply, realData;
    CHECK(tmpData.writeStrongBinder(target));
    CHECK(tmpData.writeUint32(code));
    CHECK(tmpData.writeUint32(flags));
    CHECK(tmpData.writeInt32(IPCThreadState::self()->getCallingUid()));
    CHECK(tmpData.writeInt32(IPCThreadState::self()->getCallingPid()));
    CHECK(tmpData.writeUint64(data.dataSize()));
    CHECK(tmpData.appendFrom(&data, 0, data.dataSize()));
    CHECK(interceptor->transact(PRE_TRANSACT, tmpData, &tmpReply));
    int32_t preType;
    CHECK(tmpReply.readInt32(&preType));
    LOGD("pre transact type %d", preType);
    if (preType == SKIP) {
        return false;
    } else if (preType == OVERRIDE_REPLY) {
        result = tmpReply.readInt32();
        if (reply) {
            size_t sz = tmpReply.readUint64();
            CHECK(reply->appendFrom(&tmpReply, tmpReply.dataPosition(), sz));
        }
        return true;
    } else if (preType == OVERRIDE_DATA) {
        size_t sz = tmpReply.readUint64();
        CHECK(realData.appendFrom(&tmpReply, tmpReply.dataPosition(), sz));
    } else {
        CHECK(realData.appendFrom(&data, 0, data.dataSize()));
    }
    result = target->transact(code, realData, reply, flags);

    tmpData.freeData();
    tmpReply.freeData();

    CHECK(tmpData.writeStrongBinder(target));
    CHECK(tmpData.writeUint32(code));
    CHECK(tmpData.writeUint32(flags));
    CHECK(tmpData.writeInt32(IPCThreadState::self()->getCallingUid()));
    CHECK(tmpData.writeInt32(IPCThreadState::self()->getCallingPid()));
    CHECK(tmpData.writeInt32(result));
    CHECK(tmpData.writeUint64(data.dataSize()));
    CHECK(tmpData.appendFrom(&data, 0, data.dataSize()));
    CHECK(tmpData.writeUint64(reply == nullptr ? 0 : reply->dataSize()));
    LOGD("data size %zu reply size %zu", data.dataSize(), reply == nullptr ? 0 : reply->dataSize());
    if (reply) {
        CHECK(tmpData.appendFrom(reply, 0, reply->dataSize()));
    }
    CHECK(interceptor->transact(POST_TRANSACT, tmpData, &tmpReply));
    int32_t postType;
    CHECK(tmpReply.readInt32(&postType));
    LOGD("post transact type %d", postType);
    if (postType == OVERRIDE_REPLY) {
        result = tmpReply.readInt32();
        if (reply) {
            size_t sz = tmpReply.readUint64();
            reply->freeData();
            CHECK(reply->appendFrom(&tmpReply, tmpReply.dataPosition(), sz));
            LOGD("reply size=%zu sz=%zu", reply->dataSize(), sz);
        }
    }
    return true;
}

bool hookBinder() {
    auto maps = lsplt::MapInfo::Scan();
    dev_t dev;
    ino_t ino;
    bool found = false;
    for (auto &m: maps) {
        if (m.path.ends_with("/libbinder.so")) {
            dev = m.dev;
            ino = m.inode;
            found = true;
            break;
        }
    }
    if (!found) {
        LOGE("libbinder not found!");
        return false;
    }
    gBinderInterceptor = sp<BinderInterceptor>::make();
    gBinderStub = sp<BinderStub>::make();
    lsplt::RegisterHook(dev, ino, "ioctl", (void *) new_ioctl, (void **) &old_ioctl);
    if (!lsplt::CommitHook()) {
        LOGE("hook failed!");
        return false;
    }
    LOGI("hook success!");
    return true;
}

bool initialize_hooks() {
    auto maps = lsplt::MapInfo::Scan();
    dev_t binder_dev;
    ino_t binder_ino;
    bool binder_found = false;
    dev_t libc_dev;
    ino_t libc_ino;
    bool libc_found = false;

    for (auto &m: maps) {
        if (m.path.ends_with("/libbinder.so")) {
            binder_dev = m.dev;
            binder_ino = m.inode;
            binder_found = true;
            LOGD("Found libbinder.so: dev=%lu, ino=%lu", m.dev, m.inode);
        }
        if (m.path.ends_with("/libc.so")) {
            libc_dev = m.dev;
            libc_ino = m.inode;
            libc_found = true;
            LOGD("Found libc.so: dev=%lu, ino=%lu, path=%s", m.dev, m.inode, m.path.c_str());
        }
        if (binder_found && libc_found) {
            break;
        }
    }

    if (!binder_found) {
        LOGE("libbinder.so not found!");
        // return false; // Should not return early, try to hook libc if possible
    } else {
        gBinderInterceptor = sp<BinderInterceptor>::make();
        gBinderStub = sp<BinderStub>::make();
        lsplt::RegisterHook(binder_dev, binder_ino, "ioctl", (void *) new_ioctl, (void **) &old_ioctl);
        LOGI("Registered ioctl hook for libbinder.so");
    }

    if (!libc_found) {
        LOGE("libc.so not found!");
        // return false; // Should not return early if libbinder hook was set
    } else {
        lsplt::RegisterHook(libc_dev, libc_ino, "__system_property_get", (void *) new_system_property_get, (void **) &original_system_property_get);
        LOGI("Registered __system_property_get hook for libc.so");
    }
    
    if (!binder_found && !libc_found) {
        LOGE("Neither libbinder.so nor libc.so found! Cannot apply hooks.");
        return false;
    }

    if (!lsplt::CommitHook()) {
        LOGE("hook failed!");
        return false;
    }
    LOGI("hook success!");
    return true;
}

// Ensure gBinderInterceptor is initialized in initialize_hooks if not already.
// It is initialized: gBinderInterceptor = sp<BinderInterceptor>::make();

extern "C" [[gnu::visibility("default")]] [[gnu::used]] bool entry(void *handle) {
    LOGI("injected, my handle %p", handle);
    return initialize_hooks();
}
