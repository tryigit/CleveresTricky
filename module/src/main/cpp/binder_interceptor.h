#ifndef BINDER_INTERCEPTOR_H
#define BINDER_INTERCEPTOR_H

#include <utils/RefBase.h>
#include <binder/Parcel.h>
#include <binder/IBinder.h>
#include <binder/Binder.h>
#include <utils/StrongPointer.h>
#include <map>
#include <shared_mutex>

// Using android namespace for convenience if many types from it are used
using namespace android;

class BinderInterceptor : public BBinder {
public:
    sp<IBinder> gPropertyServiceBinder = nullptr;

    bool handleIntercept(sp<BBinder> target, uint32_t code, const Parcel &data, Parcel *reply,
                         uint32_t flags, status_t &result);
    bool needIntercept(const wp<BBinder>& target);

    status_t onTransact(uint32_t code, const android::Parcel &data, android::Parcel *reply,
                        uint32_t flags) override;

private:
    enum {
        REGISTER_INTERCEPTOR = 1,
        UNREGISTER_INTERCEPTOR = 2,
        REGISTER_PROPERTY_SERVICE = 3
    };
    enum { // These were likely intended to be scoped to functions or private
        PRE_TRANSACT = 1,
        POST_TRANSACT,
        INTERCEPTOR_REPLACED = 3
    };
    enum { // These were likely intended to be scoped to functions or private
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
};

#endif // BINDER_INTERCEPTOR_H
