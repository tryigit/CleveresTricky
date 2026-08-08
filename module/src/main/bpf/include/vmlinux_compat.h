/* SPDX-License-Identifier: GPL-2.0 */
/*
 * vmlinux_compat.h: CO-RE-compatible kernel struct definitions for binder eBPF
 *
 * Problem:
 *   Different kernel versions (GKI 5.10, 5.15, 6.1, 6.6 and vendor forks used
 *   by HyperOS / OneUI) may shift fields inside binder_write_read and
 *   binder_transaction_data.  A BPF program compiled against a fixed struct
 *   layout will read garbage or panic on a device with a different layout.
 *
 * Solution: CO-RE (Compile Once, Run Everywhere):
 *   Mark every struct with __attribute__((preserve_access_index)).  The clang
 *   BPF backend then emits BTF relocation records for every field access.  The
 *   kernel's BPF loader (or libbpf at load time) fixes up each access to the
 *   actual offset found in the running kernel's BTF, regardless of what the
 *   struct looked like when the .o was compiled.
 *
 *   Use BPF_CORE_READ() / BPF_CORE_READ_INTO() from bpf_core_read.h instead
 *   of direct member dereferences wherever kernel memory is accessed.
 *
 * Note:
 *   If the kernel was built without CONFIG_DEBUG_INFO_BTF the loader will
 *   silently fall back to the compiled-in offsets (same behaviour as before
 *   CO-RE), so adding CO-RE annotations is always a safe improvement.
 */

#ifndef VMLINUX_COMPAT_H
#define VMLINUX_COMPAT_H

#include <linux/types.h>

/*
 * BPF_CORE_READ / BPF_CORE_READ_INTO
 * ------------------------------------
 * When compiling with a modern clang that targets BPF and includes
 * <bpf/bpf_core_read.h> these macros are already defined.  The minimal
 * definitions below are provided as a compile-time fallback so the file
 * remains self-contained when built in environments that do not ship
 * bpf_core_read.h (e.g. simple unit-test builds on the host).
 */
#ifndef BPF_CORE_READ
/*
 * __builtin_preserve_access_index instructs clang to emit a BTF relocation
 * record for the field access.  When the kernel loader applies relocations the
 * offset is patched to match the running kernel's actual struct layout.
 */
#define BPF_CORE_READ(src, a) \
    __builtin_preserve_access_index(({ typeof((src)->a) __val; \
        bpf_probe_read_kernel(&__val, sizeof(__val), &(src)->a); __val; }))

#define BPF_CORE_READ_INTO(dst, src, a) \
    bpf_probe_read_kernel((dst), sizeof((src)->a), \
                          __builtin_preserve_access_index(&(src)->a))
#endif /* BPF_CORE_READ */

/*
 * bpf_probe_read_kernel
 * ---------------------
 * Kernels < 5.5 only have bpf_probe_read().  Alias to it so the eBPF
 * program compiles on both old and new kernels.
 */
#if defined(__BPF_TRACING__) || defined(__KERNEL__)
#  ifndef bpf_probe_read_kernel
#    define bpf_probe_read_kernel bpf_probe_read
#  endif
#endif

/* -----------------------------------------------------------------------
 * CO-RE-annotated kernel struct definitions
 *
 * The __attribute__((preserve_access_index)) on each struct tells the BPF
 * backend to emit relocation entries for every field access so the loader
 * can patch offsets at load time against the running kernel's BTF.
 *
 * Fields are taken from the AOSP/mainline kernel headers; OEM additions are
 * handled transparently by the CO-RE relocation mechanism.
 * ---------------------------------------------------------------------- */

/*
 * binder_uintptr_t matches the kernel typedef (binder_uintptr_t).
 * On 32-bit kernels this is __u32; on 64-bit kernels it is __u64.
 * We use __u64 here; the CO-RE relocation will widen/narrow as needed.
 */
typedef __u64 binder_uintptr_co_t;

/*
 * struct binder_transaction_data (CO-RE annotated)
 */
struct binder_transaction_data_core {
    union {
        __u32 handle;           /* target descriptor of command transaction */
        binder_uintptr_co_t ptr;/* target descriptor of return transaction */
    } target;
    binder_uintptr_co_t cookie; /* target object cookie */
    __u32               code;   /* transaction command */
    __u32               flags;
    __s32               sender_pid;
    __u32               sender_euid;
    __u64               data_size;
    __u64               offsets_size;
    union {
        struct {
            binder_uintptr_co_t buffer;
            binder_uintptr_co_t offsets;
        } ptr;
        __u8 buf[8];
    } data;
} __attribute__((preserve_access_index));

/*
 * struct binder_write_read (CO-RE annotated)
 */
struct binder_write_read_core {
    __u64               write_size;
    __u64               write_consumed;
    binder_uintptr_co_t write_buffer;
    __u64               read_size;
    __u64               read_consumed;
    binder_uintptr_co_t read_buffer;
} __attribute__((preserve_access_index));

/*
 * struct task_struct (minimal, CO-RE annotated) is used to read pid/tgid
 */
struct task_struct_core {
    volatile long state;
    int           pid;
    int           tgid;
    char          comm[16];
} __attribute__((preserve_access_index));

#endif /* VMLINUX_COMPAT_H */
