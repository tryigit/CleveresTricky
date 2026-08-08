#include "utils.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <climits>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <sys/wait.h>

#include "logging.hpp"

ssize_t write_proc(pid_t pid, uintptr_t remote_addr, const void *buffer,
                   size_t length) {
    if (pid <= 0 || remote_addr == 0 || buffer == nullptr || length == 0 ||
        length > static_cast<size_t>(SSIZE_MAX)) {
        errno = EINVAL;
        return -1;
    }

    iovec local{
        .iov_base = const_cast<void *>(buffer),
        .iov_len = length,
    };
    iovec remote{
        .iov_base = reinterpret_cast<void *>(remote_addr),
        .iov_len = length,
    };
    const ssize_t written = process_vm_writev(pid, &local, 1, &remote, 1, 0);
    if (written < 0) {
        PLOGE("process_vm_writev");
    } else if (static_cast<size_t>(written) != length) {
        LOGW("Partial remote write: %zd of %zu bytes", written, length);
    }
    return written;
}

ssize_t read_proc(pid_t pid, uintptr_t remote_addr, void *buffer,
                  size_t length) {
    if (pid <= 0 || remote_addr == 0 || buffer == nullptr || length == 0 ||
        length > static_cast<size_t>(SSIZE_MAX)) {
        errno = EINVAL;
        return -1;
    }

    iovec local{
        .iov_base = buffer,
        .iov_len = length,
    };
    iovec remote{
        .iov_base = reinterpret_cast<void *>(remote_addr),
        .iov_len = length,
    };
    const ssize_t read_count = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    if (read_count < 0) {
        PLOGE("process_vm_readv");
    } else if (static_cast<size_t>(read_count) != length) {
        LOGW("Partial remote read: %zd of %zu bytes", read_count, length);
    }
    return read_count;
}

bool get_regs(pid_t pid, InjectorRegisters &registers) {
#if defined(__x86_64__)
    if (ptrace(PTRACE_GETREGS, pid, nullptr, &registers) == -1) {
        PLOGE("PTRACE_GETREGS");
        return false;
    }
#elif defined(__aarch64__)
    iovec registers_iov{
        .iov_base = &registers,
        .iov_len = sizeof(registers),
    };
    if (ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &registers_iov) == -1 ||
        registers_iov.iov_len != sizeof(registers)) {
        PLOGE("PTRACE_GETREGSET");
        return false;
    }
#endif
    return true;
}

bool set_regs(pid_t pid, InjectorRegisters &registers) {
#if defined(__x86_64__)
    if (ptrace(PTRACE_SETREGS, pid, nullptr, &registers) == -1) {
        PLOGE("PTRACE_SETREGS");
        return false;
    }
#elif defined(__aarch64__)
    iovec registers_iov{
        .iov_base = &registers,
        .iov_len = sizeof(registers),
    };
    if (ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &registers_iov) == -1) {
        PLOGE("PTRACE_SETREGSET");
        return false;
    }
#endif
    return true;
}

void *find_module_base(const std::vector<lsplt::MapInfo> &mappings,
                       std::string_view suffix) {
    for (const auto &mapping : mappings) {
        if (mapping.offset == 0 && mapping.path.ends_with(suffix)) {
            return reinterpret_cast<void *>(mapping.start);
        }
    }
    return nullptr;
}

void *find_module_return_addr(const std::vector<lsplt::MapInfo> &mappings,
                              std::string_view suffix) {
    for (const auto &mapping : mappings) {
        if ((mapping.perms & PROT_EXEC) == 0 &&
            mapping.path.ends_with(suffix)) {
            return reinterpret_cast<void *>(mapping.start);
        }
    }
    return nullptr;
}

void *find_func_addr(const std::vector<lsplt::MapInfo> &local_mappings,
                     const std::vector<lsplt::MapInfo> &remote_mappings,
                     std::string_view module, std::string_view function) {
    const std::string module_name(module);
    const std::string function_name(function);
    void *library = dlopen(module_name.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) {
        LOGE("Could not open %s: %s", module_name.c_str(), dlerror());
        return nullptr;
    }

    void *symbol = dlsym(library, function_name.c_str());
    if (symbol == nullptr) {
        LOGE("Could not resolve %s in %s: %s", function_name.c_str(),
             module_name.c_str(), dlerror());
        dlclose(library);
        return nullptr;
    }

    const uintptr_t symbol_address = reinterpret_cast<uintptr_t>(symbol);
    const bool symbol_in_module =
        std::any_of(local_mappings.begin(), local_mappings.end(),
                    [&](const auto &mapping) {
                        return mapping.start <= symbol_address &&
                               symbol_address < mapping.end &&
                               mapping.path.ends_with(module);
                    });
    const uintptr_t local_base =
        reinterpret_cast<uintptr_t>(find_module_base(local_mappings, module));
    const uintptr_t remote_base =
        reinterpret_cast<uintptr_t>(find_module_base(remote_mappings, module));
    if (!symbol_in_module || local_base == 0 || remote_base == 0 ||
        symbol_address < local_base) {
        LOGE("Resolved %s outside the expected %s mapping",
             function_name.c_str(), module_name.c_str());
        dlclose(library);
        return nullptr;
    }

    const uintptr_t offset = symbol_address - local_base;
    constexpr uintptr_t kMaximumLibraryOffset = 512U * 1024U * 1024U;
    if (offset > kMaximumLibraryOffset || remote_base > UINTPTR_MAX - offset) {
        LOGE("Resolved %s with an invalid mapping offset", function_name.c_str());
        dlclose(library);
        return nullptr;
    }

    const uintptr_t remote_symbol = remote_base + offset;
    const bool remote_symbol_in_module =
        std::any_of(remote_mappings.begin(), remote_mappings.end(),
                    [&](const auto &mapping) {
                        return mapping.start <= remote_symbol &&
                               remote_symbol < mapping.end &&
                               mapping.path.ends_with(module);
                    });
    dlclose(library);
    if (!remote_symbol_in_module) {
        LOGE("Remote address for %s is outside %s", function_name.c_str(),
             module_name.c_str());
        return nullptr;
    }
    return reinterpret_cast<void *>(remote_symbol);
}

void align_stack(InjectorRegisters &registers, uintptr_t reserve) {
    registers.REG_SP = (registers.REG_SP - reserve) & ~uintptr_t{0x0f};
}

uintptr_t push_memory(pid_t pid, InjectorRegisters &registers,
                      const void *local_address, size_t length) {
    constexpr size_t kMaximumRemoteAllocation = 64U * 1024U;
    if (local_address == nullptr || length == 0 ||
        length > kMaximumRemoteAllocation || registers.REG_SP < length + 16U) {
        return 0;
    }

    const uintptr_t original_stack = registers.REG_SP;
    registers.REG_SP -= length;
    align_stack(registers);
    const uintptr_t remote_address = registers.REG_SP;
    if (write_proc(pid, remote_address, local_address, length) !=
        static_cast<ssize_t>(length)) {
        registers.REG_SP = original_stack;
        return 0;
    }
    return remote_address;
}

uintptr_t push_string(pid_t pid, InjectorRegisters &registers,
                      const char *value) {
    if (value == nullptr) return 0;
    constexpr size_t kMaximumRemoteString = 4096;
    const size_t string_length = strnlen(value, kMaximumRemoteString);
    if (string_length == kMaximumRemoteString) return 0;
    return push_memory(pid, registers, value, string_length + 1);
}

bool remote_pre_call(pid_t pid, InjectorRegisters &registers,
                     uintptr_t function_address, uintptr_t return_address,
                     const std::vector<uintptr_t> &arguments) {
    constexpr size_t kMaximumArguments = 32;
    if (pid <= 0 || function_address == 0 ||
        arguments.size() > kMaximumArguments) {
        return false;
    }

    align_stack(registers);
#if defined(__x86_64__)
    if (!arguments.empty()) registers.rdi = arguments[0];
    if (arguments.size() >= 2) registers.rsi = arguments[1];
    if (arguments.size() >= 3) registers.rdx = arguments[2];
    if (arguments.size() >= 4) registers.rcx = arguments[3];
    if (arguments.size() >= 5) registers.r8 = arguments[4];
    if (arguments.size() >= 6) registers.r9 = arguments[5];
    if (arguments.size() > 6) {
        const size_t stack_bytes =
            (arguments.size() - 6) * sizeof(uintptr_t);
        if (registers.REG_SP < stack_bytes + sizeof(uintptr_t)) return false;
        align_stack(registers, stack_bytes);
        if (write_proc(pid, registers.REG_SP, arguments.data() + 6,
                       stack_bytes) != static_cast<ssize_t>(stack_bytes)) {
            return false;
        }
    }
    if (registers.REG_SP < sizeof(uintptr_t)) return false;
    registers.REG_SP -= sizeof(uintptr_t);
    if (write_proc(pid, registers.REG_SP, &return_address,
                   sizeof(return_address)) !=
        static_cast<ssize_t>(sizeof(return_address))) {
        return false;
    }
    registers.REG_IP = function_address;
#elif defined(__aarch64__)
    for (size_t index = 0; index < arguments.size() && index < 8; ++index) {
        registers.regs[index] = arguments[index];
    }
    if (arguments.size() > 8) {
        const size_t stack_bytes =
            (arguments.size() - 8) * sizeof(uintptr_t);
        if (registers.REG_SP < stack_bytes + 16U) return false;
        align_stack(registers, stack_bytes);
        if (write_proc(pid, registers.REG_SP, arguments.data() + 8,
                       stack_bytes) != static_cast<ssize_t>(stack_bytes)) {
            return false;
        }
    }
    registers.regs[30] = return_address;
    registers.REG_IP = function_address;
#endif

    if (!set_regs(pid, registers)) return false;
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) == -1) {
        PLOGE("PTRACE_CONT");
        return false;
    }
    return true;
}

bool remote_post_call(pid_t pid, InjectorRegisters &registers,
                      uintptr_t return_address, uintptr_t &result) {
    int status = 0;
    if (!wait_for_trace(pid, &status, __WALL) || !get_regs(pid, registers)) {
        return false;
    }
    if (!WIFSTOPPED(status) || WSTOPSIG(status) != SIGSEGV ||
        static_cast<uintptr_t>(registers.REG_IP) != return_address) {
        LOGE("Remote call stopped unexpectedly: %s at %p",
             parse_status(status).c_str(),
             reinterpret_cast<void *>(registers.REG_IP));
        return false;
    }
    result = registers.REG_RET;
    return true;
}

bool remote_call(pid_t pid, InjectorRegisters &registers,
                 uintptr_t function_address, uintptr_t return_address,
                 const std::vector<uintptr_t> &arguments, uintptr_t &result) {
    return remote_pre_call(pid, registers, function_address, return_address,
                           arguments) &&
           remote_post_call(pid, registers, return_address, result);
}

bool wait_for_trace(pid_t pid, int *status, int flags) {
    if (status == nullptr) return false;
    while (true) {
        const pid_t result = waitpid(pid, status, flags);
        if (result == -1 && errno == EINTR) continue;
        if (result == -1) {
            PLOGE("waitpid for %d", pid);
            return false;
        }
        if (result != pid || !WIFSTOPPED(*status)) {
            LOGE("Tracee %d did not stop: %s", pid,
                 parse_status(*status).c_str());
            return false;
        }
        return true;
    }
}

std::string parse_status(int status) {
    char description[128] = {};
    if (WIFEXITED(status)) {
        snprintf(description, sizeof(description), "exited with %d",
                 WEXITSTATUS(status));
    } else if (WIFSIGNALED(status)) {
        const int signal = WTERMSIG(status);
        snprintf(description, sizeof(description), "terminated by %s (%d)",
                 signal_name(signal), signal);
    } else if (WIFSTOPPED(status)) {
        const int signal = WSTOPSIG(status);
        snprintf(description, sizeof(description), "stopped by %s (%d)",
                 signal_name(signal), signal);
    } else {
        snprintf(description, sizeof(description), "unknown status 0x%x",
                 status);
    }
    return description;
}

const char *signal_name(int signal) {
    if (signal <= 0 || signal >= NSIG) return "unknown signal";
    const char *name = strsignal(signal);
    return name == nullptr ? "unknown signal" : name;
}

std::vector<std::string> get_cmdline(pid_t pid) {
    if (pid <= 0) return {};

    char path[64] = {};
    const int path_length =
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    if (path_length <= 0 ||
        static_cast<size_t>(path_length) >= sizeof(path)) {
        return {};
    }

    UniqueFd fd(open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (!fd) return {};

    constexpr size_t kMaximumCmdlineBytes = 4096;
    std::array<char, kMaximumCmdlineBytes> buffer{};
    ssize_t count = 0;
    do {
        count = read(fd.get(), buffer.data(), buffer.size());
    } while (count < 0 && errno == EINTR);
    if (count <= 0 || static_cast<size_t>(count) == buffer.size()) return {};

    std::vector<std::string> arguments;
    size_t begin = 0;
    const size_t length = static_cast<size_t>(count);
    while (begin < length && arguments.size() < 64) {
        const void *terminator =
            memchr(buffer.data() + begin, '\0', length - begin);
        if (terminator == nullptr) return {};
        const size_t end =
            static_cast<const char *>(terminator) - buffer.data();
        arguments.emplace_back(buffer.data() + begin, end - begin);
        begin = end + 1;
    }
    if (begin != length || arguments.empty() || arguments.front().empty()) {
        return {};
    }
    return arguments;
}

std::string generate_magic(size_t length) {
    constexpr char kAlphabet[] =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    if (length == 0 || length > 64) return {};

    std::string value(length, '\0');
    for (char &character : value) {
        character = kAlphabet[arc4random_uniform(sizeof(kAlphabet) - 1)];
    }
    return value;
}

namespace {
bool write_attribute(const char *path, const char *value, size_t length) {
    UniqueFd fd(open(path, O_WRONLY | O_CLOEXEC));
    if (!fd) return false;

    size_t written = 0;
    while (written < length) {
        const ssize_t count =
            write(fd.get(), value + written, length - written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        written += static_cast<size_t>(count);
    }
    return true;
}
}  // namespace

bool set_sockcreate_con(const char *context) {
    if (context == nullptr) return false;
    constexpr size_t kMaximumSelinuxContext = 255;
    const size_t context_length = strnlen(context, kMaximumSelinuxContext + 1);
    if (context_length > kMaximumSelinuxContext) return false;
    const size_t attribute_length = context_length + 1;

    if (write_attribute("/proc/thread-self/attr/sockcreate", context,
                        attribute_length)) {
        return true;
    }

    char fallback_path[64] = {};
    const int path_length = snprintf(fallback_path, sizeof(fallback_path),
                                     "/proc/%d/attr/sockcreate", gettid());
    if (path_length <= 0 ||
        static_cast<size_t>(path_length) >= sizeof(fallback_path)) {
        return false;
    }
    if (!write_attribute(fallback_path, context, attribute_length)) {
        PLOGE("Could not set socket SELinux context");
        return false;
    }
    return true;
}
