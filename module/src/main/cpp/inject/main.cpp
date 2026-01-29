#include <sys/ptrace.h>
#include <unistd.h>
#include <sys/uio.h>
#include <sys/auxv.h>
#include <elf.h>
#include <link.h>
#include <vector>
#include <string>
#include <sys/mman.h>
#include <sys/wait.h>
#include <cstdlib>
#include <cstdio>
#include <dlfcn.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/dlext.h>
#include <fcntl.h>
#include <csignal>
#include <sys/system_properties.h>
#include <string>
#include <cinttypes>

#include "lsplt.hpp"

#include "logging.hpp"
#include "utils.hpp"

using namespace std::string_literals;

// zygote inject

bool inject_library(int pid, const char *lib_path, const char* entry_name) {
    LOGI("injecting %s and calling %s in %d", lib_path, entry_name, pid);
    struct user_regs_struct regs{}, backup{};
    std::vector<lsplt::MapInfo> map;

    if (ptrace(PTRACE_ATTACH, pid, 0, 0) == -1) {
        PLOGE("PTRACE_ATTACH failed");
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
        if (!get_regs(pid, regs)) {
            LOGE("get_regs failed");
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }
        // The linker has been initialized now, we can do dlopen
        LOGD("Successfully got registers for pid %d", pid);
        // backup registers
        memcpy(&backup, &regs, sizeof(regs));
        
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
            if (!set_sockcreate_con("u:object_r:system_file:s0")) {
                 PLOGW("set_sockcreate_con failed (non-fatal)");
            }
            UniqueFd local_socket = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
            if (local_socket == -1) {
                PLOGE("Failed to create local_socket");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            if (setfilecon(lib_path, "u:object_r:system_file:s0") == -1) {
                PLOGW("setfilecon for lib_path %s failed (non-fatal)", lib_path);
            }
            UniqueFd local_lib_fd = open(lib_path, O_RDONLY | O_CLOEXEC);
            if (local_lib_fd == -1) {
                PLOGE("Failed to open library %s", lib_path);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            auto socket_addr = find_func_addr(local_map, map, "libc.so", "socket");
            auto bind_addr = find_func_addr(local_map, map, "libc.so", "bind");
            auto recvmsg_addr = find_func_addr(local_map, map, "libc.so", "recvmsg");
            auto errno_location_addr = find_func_addr(local_map, map, "libc.so", "__errno"); // Corrected name

            if (!socket_addr || !bind_addr || !recvmsg_addr) {
                LOGE("Failed to find socket/bind/recvmsg address in libc.so");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            
            auto get_remote_errno_val = [&]() -> int {
                if (!errno_location_addr) {
                    LOGE("Cannot get remote errno: __errno address not found.");
                    return -1; // Indicate error
                }
                args.clear();
                uintptr_t remote_errno_ptr = remote_call(pid, regs, (uintptr_t) errno_location_addr, 0, args);
                if (remote_errno_ptr == 0 || remote_errno_ptr == (uintptr_t)-1) {
                     LOGE("remote_call to __errno failed or returned null.");
                     return -1;
                }
                int err_val = 0;
                if (!read_proc(pid, remote_errno_ptr, &err_val, sizeof(err_val))) {
                    LOGE("read_proc for remote errno failed.");
                    return -1;
                }
                return err_val;
            };

            args.clear();
            args.push_back(AF_UNIX);
            args.push_back(SOCK_DGRAM | SOCK_CLOEXEC);
            args.push_back(0);
            int remote_fd = (int) remote_call(pid, regs, (uintptr_t) socket_addr, 0, args);
            if (remote_fd == -1) {
                errno = get_remote_errno_val();
                PLOGE("remote socket creation failed");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            auto close_remote_fd_func = [&](int fd_to_close) {
                args.clear();
                args.push_back(fd_to_close);
                if (remote_call(pid, regs, (uintptr_t) close_addr, 0, args) != 0) {
                    // Log error, but proceed with detach as primary cleanup
                    LOGW("remote_call to close fd %d failed (errno may be from remote)", fd_to_close);
                }
            };

            auto magic_name = generateMagic(16);
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
            if (remote_call(pid, regs, (uintptr_t) bind_addr, 0, args) == (uintptr_t) -1) {
                errno = get_remote_errno_val();
                PLOGE("remote bind failed");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            char cmsg_buffer[CMSG_SPACE(sizeof(int))] = {0};
            uintptr_t remote_cmsg_buffer_ptr = push_memory(pid, regs, &cmsg_buffer, sizeof(cmsg_buffer));
            if (remote_cmsg_buffer_ptr == 0) {
                LOGE("Failed to push cmsg_buffer to remote process");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            
            struct msghdr msg_hdr{};
            msg_hdr.msg_control = (void*) remote_cmsg_buffer_ptr;
            msg_hdr.msg_controllen = sizeof(cmsg_buffer);
            uintptr_t remote_msghdr_ptr = push_memory(pid, regs, &msg_hdr, sizeof(msg_hdr));
            if (remote_msghdr_ptr == 0) {
                LOGE("Failed to push msghdr to remote process");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            args.clear();
            args.push_back(remote_fd);
            args.push_back(remote_msghdr_ptr);
            args.push_back(MSG_WAITALL);
            if (!remote_pre_call(pid, regs, (uintptr_t) recvmsg_addr, 0, args)) {
                LOGE("remote_pre_call for recvmsg failed");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            // Prepare local msghdr for sendmsg
            struct msghdr local_msg_hdr{};
            struct cmsghdr *local_cmsg;
            char local_cmsg_buffer[CMSG_SPACE(sizeof(int))];
            local_msg_hdr.msg_control = local_cmsg_buffer;
            local_msg_hdr.msg_controllen = sizeof(local_cmsg_buffer);
            local_msg_hdr.msg_name = &sun_addr; // Use the same address struct (local copy)
            local_msg_hdr.msg_namelen = sock_len;

            local_cmsg = CMSG_FIRSTHDR(&local_msg_hdr);
            local_cmsg->cmsg_len = CMSG_LEN(sizeof(int));
            local_cmsg->cmsg_level = SOL_SOCKET;
            local_cmsg->cmsg_type = SCM_RIGHTS; // We are sending rights (the FD)
            *(int *) CMSG_DATA(local_cmsg) = local_lib_fd;

            if (sendmsg(local_socket, &local_msg_hdr, 0) == -1) {
                PLOGE("sendmsg to remote failed");
                // Attempt to cancel the remote recvmsg by stopping and detaching
                // This is tricky; the remote process is already in the syscall.
                // For now, just close and detach.
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0); // Detach after error
                return false;
            }

            if (remote_post_call(pid, regs, 0) == (uintptr_t)-1) {
                errno = get_remote_errno_val();
                PLOGE("remote_post_call for recvmsg failed");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            if (read_proc(pid, remote_cmsg_buffer_ptr, &cmsg_buffer, sizeof(cmsg_buffer)) != sizeof(cmsg_buffer)) {
                LOGE("Failed to read cmsg_buffer from remote process");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }
            
            // Re-construct msghdr with the local cmsg_buffer to interpret the received FD
            struct msghdr received_hdr_validation{};
            received_hdr_validation.msg_control = cmsg_buffer; // Use the buffer read from remote
            received_hdr_validation.msg_controllen = sizeof(cmsg_buffer);

            cmsghdr *received_cmsg = CMSG_FIRSTHDR(&received_hdr_validation);
            if (received_cmsg == nullptr || received_cmsg->cmsg_len != CMSG_LEN(sizeof(int)) ||
                received_cmsg->cmsg_level != SOL_SOCKET || received_cmsg->cmsg_type != SCM_RIGHTS) {
                LOGE("Invalid cmsg received from remote process");
                close_remote_fd_func(remote_fd);
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            lib_fd = *(int*) CMSG_DATA(received_cmsg);
            LOGD("Received remote lib_fd: %d", lib_fd);
            close_remote_fd_func(remote_fd); // Close the socket FD on remote side
        } // End of FD passing scope

        // call android_dlopen_ext
        {
            auto dlopen_addr = find_func_addr(local_map, map, "libdl.so", "android_dlopen_ext");
            if (dlopen_addr == nullptr) {
                 LOGE("Failed to find android_dlopen_ext address");
                 ptrace(PTRACE_DETACH, pid, 0, 0);
                 return false;
            }
            android_dlextinfo extinfo{};
            extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
            extinfo.library_fd = lib_fd;
            uintptr_t remote_extinfo_ptr = push_memory(pid, regs, &extinfo, sizeof(extinfo));
            if (remote_extinfo_ptr == 0) {
                LOGE("Failed to push android_dlextinfo to remote process");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            str_remote_path = push_string(pid, regs, lib_path);
            if (str_remote_path == 0) {
                LOGE("Failed to push lib_path string to remote process");
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            args.clear();
            args.push_back(str_remote_path);
            args.push_back(RTLD_NOW);
            args.push_back(remote_extinfo_ptr);
            remote_handle = remote_call(pid, regs, (uintptr_t) dlopen_addr,
                                        (uintptr_t) libc_return_addr, args);
            LOGD("android_dlopen_ext remote_handle: %p", (void *) remote_handle);

            if (remote_handle == 0) {
                LOGE("android_dlopen_ext returned null handle.");
                auto dlerror_addr = find_func_addr(local_map, map, "libdl.so", "dlerror");
                if (dlerror_addr != nullptr) {
                    args.clear();
                    uintptr_t dlerror_str_ptr = remote_call(pid, regs, (uintptr_t) dlerror_addr,
                                                        (uintptr_t) libc_return_addr, args);
                    if (dlerror_str_ptr != 0) {
                        auto strlen_addr = find_func_addr(local_map, map, "libc.so", "strlen");
                        if (strlen_addr != nullptr) {
                            args.clear();
                            args.push_back(dlerror_str_ptr);
                            size_t dlerror_len = remote_call(pid, regs, (uintptr_t) strlen_addr,
                                                          (uintptr_t) libc_return_addr, args);
                            if (dlerror_len > 0 && dlerror_len < 1024) { // Sanity check length
                                std::string err_msg(dlerror_len, '\0');
                                if (read_proc(pid, dlerror_str_ptr, err_msg.data(), dlerror_len)) {
                                    LOGE("dlerror: %s", err_msg.c_str());
                                } else { LOGE("Failed to read dlerror message from remote"); }
                            } else if (dlerror_len > 0) { LOGE("dlerror message too long or invalid length: %zu", dlerror_len); }
                        } else { LOGE("Could not find strlen to get dlerror message length"); }
                    } else { LOGE("dlerror returned null string pointer"); }
                } else { LOGE("Could not find dlerror address"); }
                // Close the lib_fd on remote side as dlopen failed
                args.clear();
                args.push_back(lib_fd);
                remote_call(pid, regs, (uintptr_t) close_addr, 0, args); // Best effort close
                ptrace(PTRACE_DETACH, pid, 0, 0);
                return false;
            }

            // Close the passed library FD in the remote process as dlopen now has its own
            args.clear();
            args.push_back(lib_fd);
            if (remote_call(pid, regs, (uintptr_t) close_addr, 0, args) != 0) {
                LOGW("Failed to close remote lib_fd %d after successful dlopen (non-fatal)", lib_fd);
                // Not returning false here as dlopen succeeded.
            }
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
            injector_entry_remote = remote_call(pid, regs, (uintptr_t) dlsym_addr,
                                               (uintptr_t) libc_return_addr, args);
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
            // First argument to entry function is typically the path to the loaded library itself or a handle
            // The problem description implies entry(void* handle)
            // In our case, remote_handle (the result of dlopen) is more appropriate than lib_path string.
            args.push_back(remote_handle); // Pass the remote library handle to the entry function
            if (remote_call(pid, regs, injector_entry_remote, (uintptr_t) libc_return_addr, args) == (uintptr_t)-1) {
                 LOGE("remote_call to injector_entry %s failed", entry_name);
                 // Even if entry fails, try to restore and detach
            } else {
                 LOGI("Successfully called remote entry function %s", entry_name);
            }
        }

        LOGD("Restoring original context for pid %d", pid);
        if (!set_regs(pid, backup)) {
            LOGE("set_regs failed during restoration");
            // Critical error, attempt to detach anyway
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }
        if (ptrace(PTRACE_DETACH, pid, 0, WSTOPSIG(status) == SIGSTOP ? 0 : WSTOPSIG(status)) == -1) {
            PLOGE("PTRACE_DETACH failed");
            // Process is already detached or other error, not much to do here
            return false;
        }
        LOGI("Injection successful for pid %d, library %s, entry %s", pid, lib_path, entry_name);
        return true;
    } else {
        LOGE("Process %d stopped by unexpected signal or event: %s", pid, parse_status(status).c_str());
        // No PTRACE_ATTACH was successful or it was already handled.
        // If waitpid indicated an exit, no detach is needed/possible.
        if (WIFSTOPPED(status)) { // Only detach if it was stopped, not exited/signaled to death
             ptrace(PTRACE_DETACH, pid, 0, 0);
        }
    }
    return false;
}

int main(int argc, char **argv) {
#ifndef NDEBUG
    logging::setPrintEnabled(true);
#endif
    if (argc < 4) {
        LOGF("Usage: %s <pid> <library_path> <entry_function_name>", argv[0]);
        return 1;
    }
    auto pid_val = strtol(argv[1], nullptr, 10);
    if (pid_val <= 0) {
        LOGF("Invalid PID: %s", argv[1]);
        return 1;
    }
    char real_lib_path[PATH_MAX];
    if (realpath(argv[2], real_lib_path) == nullptr) {
        PLOGF("Invalid library path: %s", argv[2]);
        return 1;
    }
    // argv[3] is entry_name, used directly

    // Attempt to set a higher scheduling priority for the injector
    if (nice(-20) == -1) { // Highest priority
        PLOGW("Failed to increase injector priority (nice -20)");
    }


    bool result = inject_library(pid_val, real_lib_path, argv[3]);
    if (result) {
        LOGI("Injection process completed successfully.");
    } else {
        LOGE("Injection process failed.");
    }
    return result ? 0 : 1; // Return 0 on success, 1 on failure
}
