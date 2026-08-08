#pragma once

#include <vector>
#include <sys/types.h>

namespace android {

template<class TYPE>
class Vector : public std::vector<TYPE> {
public:
    using std::vector<TYPE>::vector;

    inline ssize_t add(const TYPE& item) {
        this->push_back(item);
        return static_cast<ssize_t>(this->size() - 1);
    }

    inline bool isEmpty() const {
        return this->empty();
    }

    inline void removeAt(size_t index) {
        this->erase(this->begin() + index);
    }
};

} // namespace android
