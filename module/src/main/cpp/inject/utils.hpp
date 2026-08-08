#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/user.h>
#include <unistd.h>

#include "lsplt.hpp"

#define LOG_TAG "CleveresTricky"

#if defined(__x86_64__)
using InjectorRegisters = struct user_regs_struct;
#define REG_SP rsp
#define REG_IP rip
#define REG_RET rax
#elif defined(__aarch64__)
#include <asm/ptrace.h>
using InjectorRegisters = struct user_pt_regs;
#define REG_SP sp
#define REG_IP pc
#define REG_RET regs[0]
#else
#error "The injector supports only arm64-v8a and x86_64"
#endif

ssize_t write_proc(pid_t pid, uintptr_t remote_addr, const void *buffer,
                   size_t length);
ssize_t read_proc(pid_t pid, uintptr_t remote_addr, void *buffer,
                  size_t length);

bool get_regs(pid_t pid, InjectorRegisters &registers);
bool set_regs(pid_t pid, InjectorRegisters &registers);

void *find_module_base(const std::vector<lsplt::MapInfo> &mappings,
                       std::string_view suffix);
void *find_module_return_addr(const std::vector<lsplt::MapInfo> &mappings,
                              std::string_view suffix);
void *find_func_addr(const std::vector<lsplt::MapInfo> &local_mappings,
                     const std::vector<lsplt::MapInfo> &remote_mappings,
                     std::string_view module, std::string_view function);

void align_stack(InjectorRegisters &registers, uintptr_t reserve = 0);
uintptr_t push_memory(pid_t pid, InjectorRegisters &registers,
                      const void *local_address, size_t length);
uintptr_t push_string(pid_t pid, InjectorRegisters &registers,
                      const char *value);

bool remote_pre_call(pid_t pid, InjectorRegisters &registers,
                     uintptr_t function_address, uintptr_t return_address,
                     const std::vector<uintptr_t> &arguments);
bool remote_post_call(pid_t pid, InjectorRegisters &registers,
                      uintptr_t return_address, uintptr_t &result);
bool remote_call(pid_t pid, InjectorRegisters &registers,
                 uintptr_t function_address, uintptr_t return_address,
                 const std::vector<uintptr_t> &arguments, uintptr_t &result);

bool wait_for_trace(pid_t pid, int *status, int flags);
std::string parse_status(int status);
const char *signal_name(int signal);
std::vector<std::string> get_cmdline(pid_t pid);

constexpr size_t kMainMagicLength = 16;
std::string generate_magic(size_t length);

class UniqueFd {
public:
    UniqueFd() = default;
    explicit UniqueFd(int fd) noexcept : fd_(fd) {}
    ~UniqueFd() {
        reset();
    }

    UniqueFd(const UniqueFd &) = delete;
    UniqueFd &operator=(const UniqueFd &) = delete;

    UniqueFd(UniqueFd &&other) noexcept : fd_(other.release()) {}
    UniqueFd &operator=(UniqueFd &&other) noexcept {
        if (this != &other) reset(other.release());
        return *this;
    }

    [[nodiscard]] int get() const noexcept {
        return fd_;
    }
    explicit operator bool() const noexcept {
        return fd_ >= 0;
    }
    operator int() const noexcept {
        return fd_;
    }

    int release() noexcept {
        const int released = fd_;
        fd_ = -1;
        return released;
    }

    void reset(int replacement = -1) noexcept {
        if (fd_ >= 0) close(fd_);
        fd_ = replacement;
    }

private:
    int fd_ = -1;
};

bool set_sockcreate_con(const char *context);
