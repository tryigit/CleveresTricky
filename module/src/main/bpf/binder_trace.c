/* SPDX-License-Identifier: GPL-2.0 */
/*
 * binder_trace.c: CO-RE-compatible eBPF binder tracing program
 *
 * Design goals:
 *  1. CO-RE (Compile Once – Run Everywhere): every kernel struct access goes
 *     through BPF_CORE_READ() so the BPF loader patches field offsets against
 *     the running kernel's BTF at load time.  This prevents Kernel Panic on
 *     devices where vendor kernels (HyperOS, OneUI, Exynos GKI, etc.) have
 *     shifted binder_write_read / binder_transaction_data fields.
 *
 *  2. Vendor-kernel safety: bpf_probe_read_kernel is aliased to bpf_probe_read
 *     on older kernels (< 5.5) via vmlinux_compat.h so the same .o works on
 *     both GKI and non-GKI builds.
 *
 *  3. Structured event reporting: a perf-event ring buffer (BPF_MAP_TYPE_PERF_EVENT_ARRAY)
 *     is used to stream parsed binder_transaction_data to user space without
 *     kernel-panic risk from direct pointer arithmetic.
 */

#include <linux/bpf.h>
#include <linux/types.h>
#include "include/bpf_helpers.h"
#include "include/vmlinux_compat.h"

/* -------------------------------------------------------------------------
 * Maps
 * ---------------------------------------------------------------------- */

/* Per-PID ioctl count (unchanged from original) */
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __type(key, __u32);   /* PID */
    __type(value, __u64); /* count of intercepted binder ioctls */
    __uint(max_entries, 1024);
} ioctl_counts SEC(".maps");

/* Perf-event ring buffer streams binder_event records to user space.
 * Using a perf array avoids the need to share data through hash maps,
 * reducing contention and preventing map-full drops. */
struct {
    __uint(type, BPF_MAP_TYPE_PERF_EVENT_ARRAY);
    __uint(key_size, sizeof(__u32));
    __uint(value_size, sizeof(__u32));
    __uint(max_entries, 256);
} binder_events SEC(".maps");

/* -------------------------------------------------------------------------
 * Event structure sent to user space via the perf buffer
 * ---------------------------------------------------------------------- */
struct binder_event {
    __u32 pid;
    __u32 tgid;
    __u32 code;      /* transaction code */
    __u32 flags;
    __u64 data_size;
    char  comm[16];  /* process name */
};

/* -------------------------------------------------------------------------
 * kprobe/binder_ioctl fires on every binder ioctl call
 *
 * We count ioctls per PID (as before) and, when the call is a
 * BINDER_WRITE_READ ioctl (cmd == 0xc0306201 on 64-bit), we read the
 * binder_write_read struct from user space and attempt to parse the first
 * transaction in the write buffer.  All struct accesses use BPF_CORE_READ
 * so they are safe across kernel versions.
 * ---------------------------------------------------------------------- */
SEC("kprobe/binder_ioctl")
int trace_binder_ioctl(struct pt_regs *ctx) {
    __u64 pidtgid = bpf_get_current_pid_tgid();
    __u32 pid     = (__u32)(pidtgid >> 32);
    __u32 tgid    = (__u32)(pidtgid & 0xFFFFFFFF);
    __u64 *val, initial_val = 1;

    /* Update per-PID ioctl counter */
    val = bpf_map_lookup_elem(&ioctl_counts, &pid);
    if (val) {
        __sync_fetch_and_add(val, 1);
    } else {
        bpf_map_update_elem(&ioctl_counts, &pid, &initial_val, BPF_ANY);
    }

    /*
     * Read the second argument (unsigned long cmd) from the pt_regs.
     * PT_REGS_PARM2 expands to the correct register on arm64/x86_64.
     * We check for BINDER_WRITE_READ (0xc0306201 on 64-bit arm).
     */
    unsigned long cmd = (unsigned long)PT_REGS_PARM2(ctx);
#define BINDER_WRITE_READ_CMD 0xc0306201UL
    if (cmd != BINDER_WRITE_READ_CMD) {
        return 0;
    }

    /*
     * Read the third argument, a pointer to a user-space binder_write_read.
     * Use bpf_probe_read_user (kernel >= 5.5) to safely copy from user space.
     * Fall back to bpf_probe_read on older kernels via the vmlinux_compat alias.
     */
    struct binder_write_read_core bwr;
    void *bwr_ptr = (void *)PT_REGS_PARM3(ctx);
    if (!bwr_ptr) return 0;

    /*
     * Read binder_write_read from USER space; the ioctl argument is a
     * pointer into the calling process's address space, not kernel memory.
     * Use bpf_probe_read_user (kernel >= 5.5) or the unqualified
     * bpf_probe_read on older kernels (aliased via bpf_helpers.h).
     */
    if (bpf_probe_read_user(&bwr, sizeof(bwr), bwr_ptr) != 0) {
        return 0;
    }

    /* Only process if there is data in the write buffer */
    __u64 write_sz = BPF_CORE_READ(&bwr, write_size);
    if (write_sz < sizeof(__u32) + sizeof(struct binder_transaction_data_core)) {
        return 0;
    }

    /*
     * Read the first command word from the write buffer.
     * BC_TRANSACTION == 0x40406300 (32-bit) on most architectures.
     */
    binder_uintptr_co_t write_buf = BPF_CORE_READ(&bwr, write_buffer);
    if (!write_buf) return 0;

    __u32 bc_cmd = 0;
    if (bpf_probe_read_user(&bc_cmd, sizeof(bc_cmd), (void *)write_buf) != 0) {
        return 0;
    }

#define BC_TRANSACTION_CMD 0x40406300U
    if (bc_cmd != BC_TRANSACTION_CMD) {
        return 0;
    }

    /* Read the binder_transaction_data that follows the command word.
     * write_buf is a user-space address, so use bpf_probe_read_user. */
    struct binder_transaction_data_core txd;
    if (bpf_probe_read_user(&txd, sizeof(txd),
                            (void *)(write_buf + sizeof(__u32))) != 0) {
        return 0;
    }

    /* Populate the event using CO-RE field reads */
    struct binder_event evt = {};
    evt.pid       = pid;
    evt.tgid      = tgid;
    evt.code      = BPF_CORE_READ(&txd, code);
    evt.flags     = BPF_CORE_READ(&txd, flags);
    evt.data_size = BPF_CORE_READ(&txd, data_size);
    bpf_get_current_comm(evt.comm, sizeof(evt.comm));

    /* Submit to the perf ring buffer for the user-space daemon. */
    bpf_perf_event_output(ctx, &binder_events, BPF_F_CURRENT_CPU,
                          &evt, sizeof(evt));

    return 0;
}

char _license[] SEC("license") = "GPL";
