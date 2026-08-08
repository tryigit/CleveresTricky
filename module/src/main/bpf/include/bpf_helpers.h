#ifndef __BPF_HELPERS_H
#define __BPF_HELPERS_H

/* helper macro to place programs, maps, license in
 * different sections in elf_bpf file. Section names
 * are interpreted by elf_bpf loader
 */
#define SEC(NAME) __attribute__((section(NAME), used))

/* helper functions called from eBPF programs support mapping to
 * BPF constants, e.g.
 * const int BPF_FUNC_map_lookup_elem = 1;
 * const int BPF_FUNC_map_update_elem = 2;
 * const int BPF_FUNC_map_delete_elem = 3;
 * ...
 */

static void *(*bpf_map_lookup_elem)(void *map, void *key) =
    (void *) 1;
static int (*bpf_map_update_elem)(void *map, void *key, void *value,
                                  unsigned long long flags) =
    (void *) 2;
static int (*bpf_map_delete_elem)(void *map, void *key) =
    (void *) 3;
static int (*bpf_probe_read)(void *dst, int size, void *src) =
    (void *) 4;
static unsigned long long (*bpf_ktime_get_ns)(void) =
    (void *) 5;
static int (*bpf_trace_printk)(const char *fmt, int fmt_size, ...) =
    (void *) 6;
static unsigned long long (*bpf_get_prandom_u32)(void) =
    (void *) 7;
static unsigned long long (*bpf_get_smp_processor_id)(void) =
    (void *) 8;
static unsigned long long (*bpf_get_current_pid_tgid)(void) =
    (void *) 14;
static unsigned long long (*bpf_get_current_uid_gid)(void) =
    (void *) 15;
static int (*bpf_get_current_comm)(void *buf, int buf_size) =
    (void *) 16;
static int (*bpf_perf_event_read)(void *map, unsigned long long flags) =
    (void *) 22;
static int (*bpf_clone_redirect)(void *skb, int ifindex, int flags) =
    (void *) 13;
static int (*bpf_redirect)(int ifindex, int flags) =
    (void *) 23;

/* bpf_perf_event_output: helper 25, streams data to user space via perf ring */
static int (*bpf_perf_event_output)(void *ctx, void *map,
                                    unsigned long long flags,
                                    void *data, unsigned long long size) =
    (void *) 25;

/* bpf_probe_read_user: helper 112 (kernel >= 5.5); reads from user-space.
 * On kernels < 5.5 both user and kernel reads use the same bpf_probe_read. */
#ifndef bpf_probe_read_user
# if defined(bpf_probe_read_kernel)
   /* kernel >= 5.5 already has distinct helpers; declare user variant */
static int (*bpf_probe_read_user)(void *dst, int size, const void *unsafe_ptr) =
    (void *) 112;
# else
   /* kernel < 5.5: unqualified helper works for both user and kernel space */
#  define bpf_probe_read_user bpf_probe_read
# endif
#endif

/* PT_REGS helpers provide argument accessors for kprobes */
#ifndef PT_REGS_PARM2
# if defined(__TARGET_ARCH_arm64) || defined(__aarch64__)
#  define PT_REGS_PARM1(x) ((x)->regs[0])
#  define PT_REGS_PARM2(x) ((x)->regs[1])
#  define PT_REGS_PARM3(x) ((x)->regs[2])
# elif defined(__TARGET_ARCH_x86) || defined(__x86_64__)
#  define PT_REGS_PARM1(x) ((x)->di)
#  define PT_REGS_PARM2(x) ((x)->si)
#  define PT_REGS_PARM3(x) ((x)->dx)
# else
#  define PT_REGS_PARM1(x) 0
#  define PT_REGS_PARM2(x) 0
#  define PT_REGS_PARM3(x) 0
# endif
#endif

/* llvm built-in functions */
unsigned long long load_byte(void *skb,
                             unsigned long long off) asm("llvm.bpf.load.byte");
unsigned long long load_half(void *skb,
                             unsigned long long off) asm("llvm.bpf.load.half");
unsigned long long load_word(void *skb,
                             unsigned long long off) asm("llvm.bpf.load.word");

#endif
