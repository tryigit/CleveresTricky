#include "utils/StrongPointer.h"
#include "utils/RefBase.h"
#include "utils/String16.h"

// Ensure these symbols are exported from libutils.so so libbinder.so can use them.
// Without this, -fvisibility=hidden causes undefined references in shared libraries.
#define EXPORT [[gnu::visibility("default")]]

namespace android {
    EXPORT void RefBase::incStrong(const void *id) const {

    }

    EXPORT void RefBase::incStrongRequireStrong(const void *id) const {

    }

    EXPORT void RefBase::decStrong(const void *id) const {

    }

    EXPORT void RefBase::forceIncStrong(const void *id) const {

    }

    EXPORT RefBase::weakref_type* RefBase::createWeak(const void* id) const {
        return nullptr;
    }

    EXPORT RefBase::weakref_type* RefBase::getWeakRefs() const {
        return nullptr;
    }

    EXPORT RefBase::RefBase(): mRefs(nullptr) {}
    EXPORT RefBase::~RefBase() {}

    EXPORT void RefBase::onFirstRef() {}
    EXPORT void RefBase::onLastStrongRef(const void* id) {}
    EXPORT bool RefBase::onIncStrongAttempted(uint32_t flags, const void* id) { return false; }

    EXPORT RefBase* RefBase::weakref_type::refBase() const { return nullptr; }

    EXPORT void RefBase::weakref_type::incWeak(const void* id) {}
    EXPORT void RefBase::weakref_type::incWeakRequireWeak(const void* id) {}
    EXPORT void RefBase::weakref_type::decWeak(const void* id) {}

    // acquires a strong reference if there is already one.
    EXPORT bool RefBase::weakref_type::attemptIncStrong(const void* id) { return false; }

    // acquires a weak reference if there is already one.
    // This is not always safe. see ProcessState.cpp and BpBinder.cpp
    // for proper use.
    EXPORT bool RefBase::weakref_type::attemptIncWeak(const void* id) { return false; }

    EXPORT void sp_report_race() {}

    EXPORT String16::String16() {}

    EXPORT String16::String16(const String16 &o) {}

    EXPORT String16::String16(String16 &&o) noexcept {}

    EXPORT String16::String16(const char *o) {}

    EXPORT String16::~String16() {}
}
