#include <utils/RefBase.h>
#include <binder/IPCThreadState.h>
#include <binder/Parcel.h>
#include <binder/IBinder.h>
#include <binder/Binder.h>
#include <utils/StrongPointer.h>
#include <binder/Common.h>
#include <binder/IServiceManager.h>
#include <sys/ioctl.h>
#include "kernel/binder.h"

#include <utility>
#include <map>
#include <shared_mutex>
#include <vector>
#include <queue>
#include <set>
#include <string>
// #include <android/log.h> // logging.hpp should cover this
#include <sys/system_properties.h> // For PROP_VALUE_MAX

#include "logging.hpp"
#include "lsplt.hpp"
#include "elf_util.h"

using namespace SandHook;
using namespace android;

// Define Target Properties
static std::set<std::string> g_target_properties = {
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
static const String16 PROPERTY_SERVICE_INTERFACE_TOKEN = String16("android.os.IPropertyServiceHider");


// Implement Hook Function
int new_system_property_get(const char* name, char* value) {
    // LOGD("Property get: %s", name); // Too verbose for every property get
    if (g_target_properties.count(name)) {
        LOGI("Targeted property access: %s", name);
        if (gBinderInterceptor != nullptr && gBinderInterceptor->gPropertyServiceBinder != nullptr) {
            Parcel data_parcel, reply_parcel;
            status_t status;

            // Write interface token
            status = data_parcel.writeInterfaceToken(PROPERTY_SERVICE_INTERFACE_TOKEN);
            if (status != OK) {
                LOGE("Failed to write interface token for property %s: %d", name, status);
                return original_system_property_get(name, value);
            }

            // Write property name
            status = data_parcel.writeCString(name);
            if (status != OK) {
                LOGE("Failed to write property name %s to parcel: %d", name, status);
                return original_system_property_get(name, value);
            }
            
            LOGD("Transacting with property service for %s", name);
            status = gBinderInterceptor->gPropertyServiceBinder->transact(
                GET_SPOOFED_PROPERTY_TRANSACTION_CODE, data_parcel, &reply_parcel, 0);

            if (status != OK) {
                LOGE("Transaction failed for property %s: %d", name, status);
                return original_system_property_get(name, value);
            }

            int32_t exception_code = reply_parcel.readExceptionCode();
            if (exception_code != 0) {
                LOGE("Property service threw exception for %s: %d", name, exception_code);
                return original_system_property_get(name, value);
            }

            // Read nullable string value
            const char* spoofed_value_cstr = reply_parcel.readCString();

            if (spoofed_value_cstr != nullptr) {
                LOGI("Received spoofed value for %s: '%s'", name, spoofed_value_cstr);
                strncpy(value, spoofed_value_cstr, PROP_VALUE_MAX - 1);
                value[PROP_VALUE_MAX - 1] = '\0'; // Ensure null termination
                return strlen(value);
            } else {
                LOGD("Property service returned null for %s, using original.", name);
            }
        } else {
            LOGW("Property service binder not available for %s.", name);
        }
    }
    return original_system_property_get(name, value);
}

class BinderInterceptor : public BBinder {
public: // Made public for access from new_system_property_get
    sp<IBinder> gPropertyServiceBinder = nullptr;
private:
    enum {
        REGISTER_INTERCEPTOR = 1,
        UNREGISTER_INTERCEPTOR = 2,
        REGISTER_PROPERTY_SERVICE = 3 // New transaction code
    };
    enum {
        PRE_TRANSACT = 1,
        POST_TRANSACT
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
    };
    using RwLock = std::shared_mutex;
    using WriteGuard = std::unique_lock<RwLock>;
    using ReadGuard = std::shared_lock<RwLock>;
    RwLock lock;
    std::map<wp<IBinder>, InterceptItem> items{};
// public: // gPropertyServiceBinder moved up
    status_t onTransact(uint32_t code, const android::Parcel &data, android::Parcel *reply,
                        uint32_t flags) override;

    bool handleIntercept(sp<BBinder> target, uint32_t code, const Parcel &data, Parcel *reply,
                         uint32_t flags, status_t &result);

    bool needIntercept(const wp<BBinder>& target);
};

sp<BinderInterceptor> gBinderInterceptor = nullptr; // Definition moved here from below BinderStub

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

// FIXME: when use ioctl hooking, some already blocked ioctl calls will not be hooked
int (*old_ioctl)(int fd, int request, ...) = nullptr;
int new_ioctl(int fd, int request, ...) {
    va_list list;
    va_start(list, request);
    auto arg = va_arg(list, void*);
    va_end(list);
    auto result = old_ioctl(fd, request, arg);
    // TODO: check fd
    if (result >= 0 && request == BINDER_WRITE_READ) {
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
            }
            // TODO: send callback to old interceptor
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
