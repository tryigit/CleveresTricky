#include <android/dlext.h>
#include <climits>
#include <cinttypes>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <string>
#include <string_view>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

#include "lsplt.hpp"

#include "logging.hpp"
#include "utils.hpp"

using namespace std::string_literals;

namespace {
thread_local pid_t g_traced_pid = -1;
thread_local InjectorRegisters *g_original_registers = nullptr;

bool is_remote_int_error(uintptr_t result) {
    return result == UINTPTR_MAX ||
           result == static_cast<uintptr_t>(UINT32_MAX);
}

template <typename Request>
long guarded_ptrace(Request request, pid_t pid, uintptr_t address,
                    uintptr_t data) {
    if (request == PTRACE_DETACH && pid == g_traced_pid &&
        g_original_registers != nullptr) {
        if (!set_regs(pid, *g_original_registers)) {
            LOGE("Refusing to detach from pid %d without restoring registers", pid);
            return -1;
        }
        g_original_registers = nullptr;
        g_traced_pid = -1;
    }
    return ::ptrace(request, pid, reinterpret_cast<void *>(address),
                    reinterpret_cast<void *>(data));
}
}  // namespace

// Every detach in this translation unit restores the tracee's original
// register state first. This keeps error paths from resuming a corrupted stack.
#define ptrace(request, pid, address, data)                                    \
    guarded_ptrace((request), (pid), static_cast<uintptr_t>(address),          \
                   static_cast<uintptr_t>(data))

bool inject_library(int pid, const char *lib_path, const char* entry_name) {
    LOGI("injecting %s and calling %s in pid %d (target process)", lib_path, entry_name, pid);
    InjectorRegisters regs{}, backup{};
    std::vector<lsplt::MapInfo> map;

    if (ptrace(PTRACE_ATTACH, pid, 0, 0) == -1) {
        if (errno == EPERM) {
            LOGE("PTRACE_ATTACH was denied for pid %d; another tracer or the "
                 "device security policy may be blocking injection", pid);
        } else {
            PLOGE("PTRACE_ATTACH failed for pid %d", pid);
        }
        return false; // Cannot proceed if attach fails
    }
    LOGD("PTRACE_ATTACH successful for pid %d", pid);

    int status;
    // Use waitpid directly for more control and error checking
    if (waitpid(pid, &status, __WALL) == -1) {
        PLOGE("waitpid failed after PTRACE_ATTACH");
        ptrace(PTRACE_DETACH, pid, 0, 0); // Attempt to detach
        return false;
    }

    if (WIFSTOPPED(status) && WSTOPSIG(status) == SIGSTOP) {
        LOGD("Process %d stopped by SIGSTOP as expected.", pid);
        const auto cmdline = get_cmdline(pid);
        if (cmdline.empty()) {
            LOGE("Could not verify the target process for pid %d", pid);
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }
        const std::string &argv0 = cmdline.front();
        const size_t basename_pos = argv0.find_last_of('/');
        const std::string_view process_name(
            argv0.data() + (basename_pos == std::string::npos ? 0
                                                               : basename_pos + 1),
            argv0.size() - (basename_pos == std::string::npos ? 0
                                                               : basename_pos + 1));
        if (process_name != "keystore2" && process_name != "com.android.phone") {
            LOGE("Refusing to inject unsupported target process '%.*s'",
                 static_cast<int>(process_name.size()), process_name.data());
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }
        if (!get_regs(pid, regs)) {
            LOGE("get_regs failed");
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }
        // The linker has been initialized now, we can do dlopen
        LOGD("Successfully got registers for pid %d", pid);
        // backup registers
        memcpy(&backup, &regs, sizeof(regs));
        g_traced_pid = pid;
        g_original_registers = &backup;

        map = lsplt::MapInfo::Scan(std::to_string(pid));
        if (map.empty()) {
            LOGE("Failed to scan maps for pid %d", pid);
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }

        auto local_map = lsplt::MapInfo::Scan();
        if (local_map.empty()) {
            LOGE("Failed to scan local maps");
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }

        auto libc_return_addr = find_module_return_addr(map, "libc.so");
        LOGD("libc return addr %p", libc_return_addr);
        // libc_return_addr can be 0 if not found, remote_call handles this by not setting lr

        std::vector<uintptr_t> args;
        uintptr_t str_remote_path, remote_handle, injector_entry_remote;

        auto close_addr = find_func_addr(local_map, map, "libc.so", "close");
        if (!close_addr) {
            LOGE("Failed to find close address in libc.so");
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }

        int lib_fd = -1;

        // Scoped block for FD passing
        {
            // SELinux context setting - best effort, log if fails
            // Read target process SELinux context and set as socket creation context
            // so the local socket has a label compatible with the target for FD passing.
            {
                constexpr size_t SELINUX_CONTEXT_MAX_SIZE = 256;
                char target_con[SELINUX_CONTEXT_MAX_SIZE] = {};
                char path_buf[64];
                snprintf(path_buf, sizeof(path_buf), "/proc/%d/attr/current", pid);
                int con_fd = open(path_buf, O_RDONLY | O_CLOEXEC);
                if (con_fd >= 0) {
                    ssize_t n = read(con_fd, target_con, sizeof(target_con) - 1);
                    close(con_fd);
                    if (n > 0) {
                        target_con[n] = '\0';
                        // Strip trailing newline if present
                        if (target_con[n - 1] == '\n') target_con[n - 1] = '\0';
                        if (target_con[0] != '\0' && !set_sockcreate_con(target_con)) {
                            LOGW("Failed to set socket creation context to '%s' (non-fatal)", target_con);
                        } else if (target_con[0] != '\0') {
                            LOGD("Set socket creation context to '%s'", target_con);
                        }
                    }
                } else {
                    LOGW("Could not read SELinux context for pid %d (non-fatal)", pid);
                }
            }
            UniqueFd local_socket(
                socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0));
            // Reset socket creation context so subsequent sockets are not affected
            if (!set_sockcreate_con("")) {
                LOGW("Failed to reset socket creation context (non-fatal)");
            }
            if (local_socket == -1) {
                PLOGE("Failed to create local_socket");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            UniqueFd local_lib_fd(open(lib_path, O_RDONLY | O_CLOEXEC));
            if (local_lib_fd == -1) {
                PLOGE("Failed to open library %s", lib_path);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            auto socket_addr = find_func_addr(local_map, map, "libc.so", "socket");
            auto bind_addr = find_func_addr(local_map, map, "libc.so", "bind");
            auto recvmsg_addr = find_func_addr(local_map, map, "libc.so", "recvmsg");
            auto mmap_addr = find_func_addr(local_map, map, "libc.so", "mmap");
            auto munmap_addr = find_func_addr(local_map, map, "libc.so", "munmap");
            auto errno_location_addr =
                find_func_addr(local_map, map, "libc.so", "__errno");

            if (!socket_addr || !bind_addr || !recvmsg_addr || !mmap_addr || !munmap_addr) {
                LOGE("Failed to find socket/bind/recvmsg/mmap/munmap address in libc.so");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            auto get_remote_errno_val = [&]() -> int {
                if (!errno_location_addr) {
                    LOGE("Cannot get remote errno: __errno address not found.");
                    return -1;
                }
                args.clear();
                uintptr_t remote_errno_ptr = 0;
                if (!remote_call(pid, regs,
                                 reinterpret_cast<uintptr_t>(errno_location_addr),
                                 0, args, remote_errno_ptr) ||
                    remote_errno_ptr == 0 ||
                    remote_errno_ptr == static_cast<uintptr_t>(-1)) {
                     LOGE("remote_call to __errno failed or returned null.");
                     return -1;
                }
                int err_val = 0;
                if (read_proc(pid, remote_errno_ptr, &err_val,
                              sizeof(err_val)) !=
                    static_cast<ssize_t>(sizeof(err_val))) {
                    LOGE("read_proc for remote errno failed.");
                    return -1;
                }
                return err_val;
            };

            args.clear();
            args.push_back(AF_UNIX);
            args.push_back(SOCK_DGRAM | SOCK_CLOEXEC);
            args.push_back(0);
            uintptr_t remote_socket_result = 0;
            if (!remote_call(pid, regs,
                             reinterpret_cast<uintptr_t>(socket_addr), 0, args,
                             remote_socket_result)) {
                LOGE("remote socket call failed");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            if (is_remote_int_error(remote_socket_result)) {
                errno = get_remote_errno_val();
                PLOGE("remote socket creation failed");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            if (remote_socket_result > static_cast<uintptr_t>(INT_MAX)) {
                LOGE("remote socket returned an invalid descriptor");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            const int remote_fd = static_cast<int>(remote_socket_result);

            auto close_remote_fd_func = [&](int fd_to_close) {
                args.clear();
                args.push_back(static_cast<uintptr_t>(fd_to_close));
                uintptr_t close_result = 0;
                if (!remote_call(pid, regs,
                                 reinterpret_cast<uintptr_t>(close_addr), 0,
                                 args, close_result) || close_result != 0) {
                    LOGW("Could not close remote fd %d", fd_to_close);
                }
            };

            const auto magic_name = generate_magic(kMainMagicLength);
            if (magic_name.size() != kMainMagicLength) {
                LOGE("Could not generate the abstract socket name");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            struct sockaddr_un sun_addr{};
            sun_addr.sun_family = AF_UNIX;
            // Abstract namespace: sun_path[0] is null byte
            memcpy(sun_addr.sun_path + 1, magic_name.c_str(), magic_name.size());
            socklen_t sock_len = sizeof(sun_addr.sun_family) + 1 + magic_name.size();

            uintptr_t remote_sockaddr_ptr = push_memory(pid, regs, &sun_addr, sock_len);
            if (remote_sockaddr_ptr == 0) {
                LOGE("Failed to push sockaddr_un to remote process");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            args.clear();
            args.push_back(remote_fd);
            args.push_back(remote_sockaddr_ptr);
            args.push_back(sock_len);
            uintptr_t bind_result = 0;
            if (!remote_call(pid, regs, reinterpret_cast<uintptr_t>(bind_addr),
                             0, args, bind_result)) {
                LOGE("remote bind call failed");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            if (is_remote_int_error(bind_result)) {
                errno = get_remote_errno_val();
                PLOGE("remote bind failed");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            // Allocate the control buffer in the remote process via mmap rather
            // than consuming the tracee's stack. SCM_RIGHTS needs only a small
            // ancillary buffer; the hard cap also bounds subsequent reads.
            constexpr size_t CMSG_BUF_SIZE = 4096;
            args.clear();
            args.push_back(0);                                    // addr   = NULL
            args.push_back(CMSG_BUF_SIZE);                        // length
            args.push_back(PROT_READ | PROT_WRITE);               // prot
            args.push_back(MAP_PRIVATE | MAP_ANONYMOUS);          // flags
            args.push_back(static_cast<uintptr_t>(-1));           // fd     = -1
            args.push_back(0);                                    // offset
            uintptr_t remote_cmsg_buffer_ptr = 0;
            if (!remote_call(pid, regs, reinterpret_cast<uintptr_t>(mmap_addr),
                             0, args, remote_cmsg_buffer_ptr) ||
                remote_cmsg_buffer_ptr == 0 ||
                remote_cmsg_buffer_ptr == static_cast<uintptr_t>(-1)) {
                LOGE("remote mmap for cmsg buffer failed (returned %p)",
                     (void*) remote_cmsg_buffer_ptr);
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            LOGD("remote cmsg buffer mmap'd at %p (%zu bytes)",
                 (void*) remote_cmsg_buffer_ptr, CMSG_BUF_SIZE);

            // Helper to release the mmap'd buffer on all exit paths.
            auto munmap_remote_cmsg = [&]() {
                args.clear();
                args.push_back(remote_cmsg_buffer_ptr);
                args.push_back(CMSG_BUF_SIZE);
                uintptr_t munmap_result = 0;
                if (!remote_call(pid, regs,
                                 reinterpret_cast<uintptr_t>(munmap_addr), 0,
                                 args, munmap_result) || munmap_result != 0) {
                    LOGW("Could not release the remote control buffer");
                }
            };

            // recvmsg requires at least one iov entry with data for SCM_RIGHTS
            char remote_iov_dummy = 0;
            uintptr_t remote_iov_data_ptr = push_memory(pid, regs, &remote_iov_dummy, sizeof(remote_iov_dummy));
            if (remote_iov_data_ptr == 0) {
                LOGE("Failed to push iov dummy data to remote process");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            struct iovec remote_iov{};
            remote_iov.iov_base = (void*) remote_iov_data_ptr;
            remote_iov.iov_len = sizeof(remote_iov_dummy);
            uintptr_t remote_iov_ptr = push_memory(pid, regs, &remote_iov, sizeof(remote_iov));
            if (remote_iov_ptr == 0) {
                LOGE("Failed to push iovec to remote process");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            struct msghdr msg_hdr{};
            msg_hdr.msg_iov = (struct iovec*) remote_iov_ptr;
            msg_hdr.msg_iovlen = 1;
            msg_hdr.msg_control = (void*) remote_cmsg_buffer_ptr;
            msg_hdr.msg_controllen = CMSG_BUF_SIZE;
            uintptr_t remote_msghdr_ptr = push_memory(pid, regs, &msg_hdr, sizeof(msg_hdr));
            if (remote_msghdr_ptr == 0) {
                LOGE("Failed to push msghdr to remote process");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            // Prepare local msghdr for sendmsg
            struct msghdr local_msg_hdr{};
            struct cmsghdr *local_cmsg;
            char local_cmsg_buffer[CMSG_SPACE(sizeof(int))]{};
            // sendmsg requires at least one iov entry with data for SCM_RIGHTS
            char local_iov_dummy = 0;
            struct iovec local_iov = { &local_iov_dummy, sizeof(local_iov_dummy) };
            local_msg_hdr.msg_iov = &local_iov;
            local_msg_hdr.msg_iovlen = 1;
            local_msg_hdr.msg_control = local_cmsg_buffer;
            local_msg_hdr.msg_controllen = sizeof(local_cmsg_buffer);
            local_msg_hdr.msg_name = &sun_addr; // Use the same address struct (local copy)
            local_msg_hdr.msg_namelen = sock_len;

            local_cmsg = CMSG_FIRSTHDR(&local_msg_hdr);
            if (local_cmsg == nullptr) {
                LOGE("Could not construct the local SCM_RIGHTS message");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            local_cmsg->cmsg_len = CMSG_LEN(sizeof(int));
            local_cmsg->cmsg_level = SOL_SOCKET;
            local_cmsg->cmsg_type = SCM_RIGHTS; // We are sending rights (the FD)
            *(int *) CMSG_DATA(local_cmsg) = local_lib_fd;

            if (sendmsg(local_socket, &local_msg_hdr, 0) == -1) {
                PLOGE("sendmsg to remote failed");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            // The datagram is now queued. Start recvmsg only after sendmsg has
            // succeeded so a local send failure can never leave the tracee
            // running inside a blocking syscall.
            args.clear();
            args.push_back(remote_fd);
            args.push_back(remote_msghdr_ptr);
            args.push_back(0);
            if (!remote_pre_call(pid, regs, (uintptr_t) recvmsg_addr, 0, args)) {
                LOGE("remote_pre_call for recvmsg failed");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            LOGD("recvmsg RPC armed: remote_fd=%d msghdr=%p", remote_fd,
                 (void*) remote_msghdr_ptr);

            uintptr_t recvmsg_result = 0;
            if (!remote_post_call(pid, regs, 0, recvmsg_result)) {
                LOGE("remote recvmsg call did not complete");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            LOGD("recvmsg RPC returned %" PRIdPTR, (intptr_t) recvmsg_result);
            if (recvmsg_result != sizeof(local_iov_dummy)) {
                errno = get_remote_errno_val();
                PLOGE("remote recvmsg returned an unexpected byte count");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            struct msghdr remote_msg_hdr_after_recv{};
            if (read_proc(pid, remote_msghdr_ptr,
                          &remote_msg_hdr_after_recv,
                          sizeof(remote_msg_hdr_after_recv)) ==
                static_cast<ssize_t>(sizeof(remote_msg_hdr_after_recv))) {
                LOGD(
                    "recvmsg remote msghdr: controllen=%zu flags=0x%x iovlen=%zu",
                    remote_msg_hdr_after_recv.msg_controllen,
                    remote_msg_hdr_after_recv.msg_flags,
                    remote_msg_hdr_after_recv.msg_iovlen
                );
            } else {
                LOGE("Failed to read remote msghdr after recvmsg");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            if ((remote_msg_hdr_after_recv.msg_flags & MSG_CTRUNC) != 0) {
                LOGE("recvmsg MSG_CTRUNC: control data was truncated (controllen=%zu, buffer=%zu). "
                     "Kernel ancillary data exceeded CMSG_BUF_SIZE.",
                     remote_msg_hdr_after_recv.msg_controllen, CMSG_BUF_SIZE);
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            if (remote_msg_hdr_after_recv.msg_control !=
                    reinterpret_cast<void *>(remote_cmsg_buffer_ptr) ||
                remote_msg_hdr_after_recv.msg_iov !=
                    reinterpret_cast<iovec *>(remote_iov_ptr) ||
                remote_msg_hdr_after_recv.msg_iovlen != 1) {
                LOGE("recvmsg returned inconsistent message pointers");
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            // Determine how many control bytes the kernel actually wrote.
            size_t safe_controllen = remote_msg_hdr_after_recv.msg_controllen;
            // Reject if controllen exceeds the buffer size; this indicates corruption or a kernel bug.
            if (safe_controllen < CMSG_LEN(sizeof(int)) ||
                safe_controllen > CMSG_BUF_SIZE) {
                LOGE("controllen %zu exceeds buffer size %zu, aborting injection", safe_controllen, CMSG_BUF_SIZE);
                munmap_remote_cmsg();
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            // Read only the used portion of the control buffer back from the
            // remote process instead of the full CMSG_BUF_SIZE.
            std::vector<char> cmsg_buffer(safe_controllen, 0);
            if (safe_controllen > 0) {
                if (read_proc(pid, remote_cmsg_buffer_ptr, cmsg_buffer.data(),
                              safe_controllen) !=
                    static_cast<ssize_t>(safe_controllen)) {
                    LOGE("Failed to read cmsg_buffer from remote process");
                    munmap_remote_cmsg();
                    close_remote_fd_func(remote_fd);
                    ptrace(PTRACE_DETACH, pid, 0, 0);
                    return false;
                }
            }

            // The mmap'd buffer is no longer needed; free it now.
            munmap_remote_cmsg();

            // Re-construct msghdr with the local cmsg_buffer to interpret the received FD.
            // Iterate through cmsg entries because SCM_RIGHTS may not be first
            // (kernel can prepend SCM_CREDENTIALS / SCM_SECURITY).
            struct msghdr received_hdr_validation{};
            received_hdr_validation.msg_control = cmsg_buffer.data();
            received_hdr_validation.msg_controllen = safe_controllen;

            int received_fd = -1;
            for (cmsghdr *received_cmsg = CMSG_FIRSTHDR(&received_hdr_validation);
                 received_cmsg != nullptr;
                 received_cmsg = CMSG_NXTHDR(&received_hdr_validation, received_cmsg)) {
                if (received_cmsg->cmsg_len == 0) {
                    LOGW("Received cmsg_len is 0, breaking to prevent infinite loop");
                    break;
                }
                LOGD(
                    "recvmsg cmsg details: len=%zu level=%d type=%d",
                    (size_t) received_cmsg->cmsg_len,
                    received_cmsg->cmsg_level,
                    received_cmsg->cmsg_type
                );
                if (received_cmsg->cmsg_level == SOL_SOCKET &&
                    received_cmsg->cmsg_type == SCM_RIGHTS &&
                    received_cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
                    received_fd = *(int*) CMSG_DATA(received_cmsg);
                    break;
                }
            }
            if (received_fd < 0) {
                LOGE("SCM_RIGHTS not found in cmsg entries from remote process (recvmsg_ret=%" PRIdPTR ")", (intptr_t) recvmsg_result);
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            lib_fd = received_fd;
            LOGD("Received remote lib_fd: %d", lib_fd);
            close_remote_fd_func(remote_fd);
        }

        auto close_remote_library_fd = [&]() {
            if (lib_fd < 0) return;
            args.clear();
            args.push_back(static_cast<uintptr_t>(lib_fd));
            uintptr_t close_result = 0;
            if (!remote_call(pid, regs,
                             reinterpret_cast<uintptr_t>(close_addr), 0, args,
                             close_result) || close_result != 0) {
                LOGW("Failed to close remote library fd %d", lib_fd);
            }
            lib_fd = -1;
        };

        // call android_dlopen_ext
        {
            auto dlopen_addr = find_func_addr(local_map, map, "libdl.so", "android_dlopen_ext");
            if (dlopen_addr == nullptr) {
                 LOGE("Failed to find android_dlopen_ext address");
                 close_remote_library_fd();
                 ptrace(PTRACE_DETACH, pid, 0, 0);
                 return false;
            }
            android_dlextinfo extinfo{};
            extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
            extinfo.library_fd = lib_fd;
            uintptr_t remote_extinfo_ptr = push_memory(pid, regs, &extinfo, sizeof(extinfo));
            if (remote_extinfo_ptr == 0) {
                LOGE("Failed to push android_dlextinfo to remote process");
                close_remote_library_fd();
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            str_remote_path = push_string(pid, regs, lib_path);
            if (str_remote_path == 0) {
                LOGE("Failed to push lib_path string to remote process");
                close_remote_library_fd();
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            args.clear();
            args.push_back(str_remote_path);
            args.push_back(RTLD_NOW);
            args.push_back(remote_extinfo_ptr);
            if (!remote_call(pid, regs,
                             reinterpret_cast<uintptr_t>(dlopen_addr),
                             reinterpret_cast<uintptr_t>(libc_return_addr),
                             args, remote_handle)) {
                LOGE("android_dlopen_ext remote call failed");
                close_remote_library_fd();
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            LOGD("android_dlopen_ext remote_handle: %p", (void *) remote_handle);

            if (remote_handle == 0) {
                LOGE("android_dlopen_ext returned null handle.");
                auto dlerror_addr = find_func_addr(local_map, map, "libdl.so", "dlerror");
                if (dlerror_addr != nullptr) {
                    args.clear();
                    uintptr_t dlerror_str_ptr = 0;
                    if (remote_call(
                            pid, regs,
                            reinterpret_cast<uintptr_t>(dlerror_addr),
                            reinterpret_cast<uintptr_t>(libc_return_addr), args,
                            dlerror_str_ptr) && dlerror_str_ptr != 0) {
                        auto strlen_addr = find_func_addr(local_map, map, "libc.so", "strlen");
                        if (strlen_addr != nullptr) {
                            args.clear();
                            args.push_back(dlerror_str_ptr);
                            uintptr_t dlerror_length_result = 0;
                            if (!remote_call(
                                    pid, regs,
                                    reinterpret_cast<uintptr_t>(strlen_addr),
                                    reinterpret_cast<uintptr_t>(libc_return_addr),
                                    args, dlerror_length_result)) {
                                LOGE("Remote strlen call failed");
                            } else if (dlerror_length_result > 0 &&
                                       dlerror_length_result < 1024) {
                                const size_t dlerror_length =
                                    static_cast<size_t>(dlerror_length_result);
                                std::string err_msg(dlerror_length, '\0');
                                if (read_proc(pid, dlerror_str_ptr,
                                              err_msg.data(), dlerror_length) ==
                                    static_cast<ssize_t>(dlerror_length)) {
                                    LOGE("dlerror: %s", err_msg.c_str());
                                } else { LOGE("Failed to read dlerror message from remote"); }
                            } else if (dlerror_length_result > 0) {
                                LOGE("dlerror message too long or invalid length: %" PRIuPTR,
                                     dlerror_length_result);
                            }
                        } else { LOGE("Could not find strlen to get dlerror message length"); }
                    } else { LOGE("dlerror returned null string pointer"); }
                } else { LOGE("Could not find dlerror address"); }
                // Close the lib_fd on remote side as dlopen failed
                close_remote_library_fd();
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            // Close the passed library FD in the remote process as dlopen now has its own
            close_remote_library_fd();
        }

        // call dlsym(handle, entry_name)
        {
            auto dlsym_addr = find_func_addr(local_map, map, "libdl.so", "dlsym");
            if (dlsym_addr == nullptr) {
                LOGE("Failed to find dlsym address");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            uintptr_t remote_entry_name_str = push_string(pid, regs, entry_name);
            if (remote_entry_name_str == 0) {
                LOGE("Failed to push entry_name string to remote process");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            args.clear();
            args.push_back(remote_handle);
            args.push_back(remote_entry_name_str);
            if (!remote_call(pid, regs,
                             reinterpret_cast<uintptr_t>(dlsym_addr),
                             reinterpret_cast<uintptr_t>(libc_return_addr),
                             args, injector_entry_remote)) {
                LOGE("Remote dlsym call failed");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            LOGD("dlsym remote injector_entry: %p", (void *) injector_entry_remote);
            if (injector_entry_remote == 0) {
                LOGE("dlsym returned null for entry_name '%s'", entry_name);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
        }

        // call injector_entry(remote_handle)
        {
            LOGI("Calling remote entry function %s at %p with handle %p", entry_name, (void*)injector_entry_remote, (void*)remote_handle);
            args.clear();
            args.push_back(remote_handle);
            uintptr_t entry_result = 0;
            if (!remote_call(pid, regs, injector_entry_remote,
                             reinterpret_cast<uintptr_t>(libc_return_addr),
                             args, entry_result) || entry_result != 1) {
                LOGE("Remote entry function %s rejected initialization", entry_name);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            LOGI("Successfully called remote entry function %s", entry_name);
        }

        LOGD("Restoring original context for pid %d", pid);
        if (!set_regs(pid, backup)) {
            LOGE("set_regs failed during restoration");
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }
        g_original_registers = nullptr;
        g_traced_pid = -1;
        if (ptrace(PTRACE_DETACH, pid, 0, WSTOPSIG(status) == SIGSTOP ? 0 : WSTOPSIG(status)) == -1) {
            PLOGE("PTRACE_DETACH failed");
            return false;
        }
        LOGI("Injection successful for pid %d, library %s, entry %s", pid, lib_path, entry_name);
        return true;
    } else {
        LOGE("Process %d stopped by unexpected signal or event: %s", pid, parse_status(status).c_str());
        // No PTRACE_ATTACH was successful or it was already handled.
        // If waitpid indicated an exit, no detach is needed/possible.
        if (WIFSTOPPED(status)) {
            ptrace(PTRACE_DETACH, pid, 0, 0);
        }
    }
    return false;
}

int main(int argc, char **argv) {
#ifndef NDEBUG
    logging::setPrintEnabled(true);
#endif
    if (argc != 4) {
        LOGF("Usage: %s <pid> <library_path> <entry_function_name>", argv[0]);
        return 1;
    }
    errno = 0;
    char *pid_end = nullptr;
    const long pid_val = strtol(argv[1], &pid_end, 10);
    if (errno != 0 || pid_end == argv[1] || *pid_end != '\0' ||
        pid_val <= 0 || pid_val > INT_MAX || pid_val == getpid()) {
        LOGF("Invalid PID: %s", argv[1]);
        return 1;
    }
    char real_lib_path[PATH_MAX];
    if (realpath(argv[2], real_lib_path) == nullptr) {
        PLOGF("Invalid library path: %s", argv[2]);
        return 1;
    }
    struct stat library_stat {};
    if (stat(real_lib_path, &library_stat) != 0 ||
        !S_ISREG(library_stat.st_mode) || library_stat.st_uid != 0 ||
        (library_stat.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        LOGF("Refusing an unsafe injection library: %s", real_lib_path);
        return 1;
    }
    if (strcmp(argv[3], "entry") != 0) {
        LOGF("Unsupported entry function: %s", argv[3]);
        return 1;
    }

    errno = 0;
    if (nice(-20) == -1 && errno != 0) {
        PLOGW("Failed to increase injector priority (nice -20)");
    }


    bool result = inject_library(pid_val, real_lib_path, argv[3]);
    if (result) {
        LOGI("Injection process completed successfully.");
    } else {
        LOGE("Injection process failed.");
    }
    return result ? 0 : 1;
}
