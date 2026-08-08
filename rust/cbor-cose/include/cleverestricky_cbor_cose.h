/* C ABI for the Rust helpers used by the native Binder interceptor. */
#ifndef CLEVERESTRICKY_CBOR_COSE_H
#define CLEVERESTRICKY_CBOR_COSE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    size_t target_ptr_offset;
    size_t cookie_offset;
    size_t code_offset;
    size_t flags_offset;
    size_t sender_pid_offset;
    size_t sender_euid_offset;
    size_t data_size_offset;
    size_t data_ptr_offset;
    size_t transaction_data_size;
    size_t transaction_data_secctx_size;
    uint8_t valid;
} RustOffsetCacheView;

typedef struct {
    uintptr_t target_ptr;
    uintptr_t cookie;
    uint32_t code;
    uint32_t flags;
    int32_t sender_pid;
    uint32_t sender_euid;
    uint64_t data_size;
    uintptr_t data_buffer;
    uint32_t cmd;
    uintptr_t raw_ptr;
    size_t raw_size;
    uint8_t valid;
} RustParsedTransaction;

bool rust_parse_binder_stream(const uint8_t *buffer,
                              size_t consumed,
                              size_t buffer_size,
                              const RustOffsetCacheView *cache,
                              RustParsedTransaction *transactions,
                              size_t transaction_capacity,
                              size_t *transaction_count);

#ifdef __cplusplus
}
#endif

#endif
