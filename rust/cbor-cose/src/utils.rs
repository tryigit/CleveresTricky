use std::fs;
use std::ptr;

/// Interrupt threads blocked in ioctl calls.
///
/// This function iterates through candidate signals (SIGWINCH, SIGURG) to find one
/// that is safe to use (default or ignored handler), installs a temporary handler
/// without SA_RESTART, and sends the signal to any thread currently blocked in `ioctl`.
/// This causes the `ioctl` to return with `EINTR`, allowing our hook (or the kernel)
/// to resume control.
pub fn kick_already_blocked_ioctls() {
    let pid = unsafe { libc::getpid() };

    // 1. Identify all threads currently blocked in ioctl
    // Note: We need to check this *before* iterating signals, as signals are tried one by one.
    let mut target_threads = get_threads_in_ioctl(pid);
    if target_threads.is_empty() {
        return;
    }

    // Candidate signals to try. We use a slice to iterate easily.
    let signals = [libc::SIGWINCH, libc::SIGURG];

    for &sig in &signals {
        if target_threads.is_empty() {
            break;
        }

        // Check current handler for this signal.
        // We only want to use a signal if the application hasn't set a custom handler,
        // so we don't disrupt its logic.
        let mut old_sa: libc::sigaction = unsafe { std::mem::zeroed() };
        if unsafe { libc::sigaction(sig, ptr::null(), &mut old_sa) } != 0 {
            continue;
        }

        // Only proceed if the signal is default or ignored.
        // sa_sigaction is a usize in libc crate for Android/Linux.
        // SIG_DFL is 0, SIG_IGN is 1.
        if old_sa.sa_sigaction != libc::SIG_DFL && old_sa.sa_sigaction != libc::SIG_IGN {
            continue;
        }

        // Install dummy handler without SA_RESTART.
        // SA_RESTART would cause the syscall to automatically restart, defeating our purpose.
        let mut sa: libc::sigaction = unsafe { std::mem::zeroed() };
        // Fix for clippy::function_casts_as_integer
        sa.sa_sigaction = handler as *const () as usize;
        sa.sa_flags = 0;
        unsafe { libc::sigemptyset(&mut sa.sa_mask) };

        if unsafe { libc::sigaction(sig, &sa, &mut old_sa) } != 0 {
            continue;
        }

        // Filter threads: only kick those that do NOT block this signal.
        let mut remaining_threads = Vec::new();
        let mut kicked_any = false;

        for &tid in &target_threads {
            // Check if signal is blocked by the thread.
            // Signals are 1-indexed. Bit 0 corresponds to signal 1.
            // So mask bit (sig - 1) corresponds to signal `sig`.
            let blocked_mask = get_thread_blocked_signals(pid, tid).unwrap_or(0);
            let is_blocked = (blocked_mask & (1u64 << (sig - 1))) != 0;

            if !is_blocked {
                // Signal is NOT blocked, send it.
                unsafe {
                    libc::syscall(libc::SYS_tgkill, pid, tid, sig);
                }
                kicked_any = true;
                // Thread is kicked. We don't add it to remaining_threads.
            } else {
                // Signal is blocked, keep this thread for the next signal candidate.
                remaining_threads.push(tid);
            }
        }

        // Update target list for next iteration
        target_threads = remaining_threads;

        // Wait a bit to ensure signal delivery if we sent anything.
        if kicked_any {
            unsafe { libc::usleep(1000) };
        }

        // Restore original handler
        unsafe { libc::sigaction(sig, &old_sa, ptr::null_mut()) };
    }
}

extern "C" fn handler(_sig: libc::c_int) {}

use std::os::unix::ffi::OsStrExt;

/// Get a list of TIDs for the given PID that are currently blocked in the `ioctl` syscall.
fn get_threads_in_ioctl(pid: libc::pid_t) -> Vec<libc::pid_t> {
    let mut threads = Vec::new();
    let my_tid = unsafe { libc::gettid() };

    // Read /proc/{pid}/task directory to find all threads
    use std::fmt::Write;
    let mut path_buf = String::with_capacity(128);
    let _ = write!(path_buf, "/proc/{}/task", pid);

    if let Ok(entries) = fs::read_dir(&path_buf) {
        path_buf.push('/');
        let base_len = path_buf.len();

        for entry in entries.flatten() {
            let file_name = entry.file_name();
            let bytes = file_name.as_bytes();
            if bytes.starts_with(b".") {
                continue;
            }
            // Optimization: avoid string allocation from `OsStr`
            if let Ok(file_name_str) = std::str::from_utf8(bytes) {
                if let Ok(tid) = file_name_str.parse::<libc::pid_t>() {
                    // Don't kick ourselves
                    if tid == my_tid {
                        continue;
                    }

                    // Check which syscall the thread is executing
                    path_buf.push_str(file_name_str);
                    path_buf.push_str("/syscall");
                    // Read into a reusable stack buffer if possible, to avoid string allocation.
                    // The "syscall" file content is very small (e.g., "16 0x0...").
                    let mut buf = [0u8; 64];
                    if let Ok(mut file) = fs::File::open(&path_buf) {
                        use std::io::Read;
                        if let Ok(bytes_read) = file.read(&mut buf) {
                            if let Ok(content_str) = std::str::from_utf8(&buf[..bytes_read]) {
                                if let Some(syscall_nr_str) = content_str.split_whitespace().next()
                                {
                                    if let Ok(nr) = syscall_nr_str.parse::<i64>() {
                                        // SYS_ioctl constant varies by platform.
                                        // We cast to i64 to ensure comparison works safely.
                                        #[allow(clippy::unnecessary_cast)]
                                        if nr == libc::SYS_ioctl as i64 {
                                            threads.push(tid);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    path_buf.truncate(base_len);
                }
            }
        }
    }
    threads
}

/// Read the `SigBlk` mask from `/proc/{pid}/task/{tid}/status`.
/// Returns the mask as a u64, or None if reading/parsing fails.
fn get_thread_blocked_signals(pid: libc::pid_t, tid: libc::pid_t) -> Option<u64> {
    use std::fmt::Write;
    // We avoid allocating a string format for every status read by using a thread-local static buffer if possible, or just doing it inline but minimizing overhead.
    // Given this isn't a tight loop, format is okay, but `String::with_capacity` is slightly better.
    let mut status_path = String::with_capacity(64);
    let _ = write!(status_path, "/proc/{}/task/{}/status", pid, tid);
    let mut buf = [0u8; 1024]; // Stack-allocated buffer to avoid heap allocation
    if let Ok(mut file) = fs::File::open(&status_path) {
        use std::io::Read;
        if let Ok(bytes_read) = file.read(&mut buf) {
            if let Ok(content_str) = std::str::from_utf8(&buf[..bytes_read]) {
                for line in content_str.lines() {
                    if line.starts_with("SigBlk:") {
                        // Format is "SigBlk:\t0000000000000000"
                        if let Some(hex_str) = line.split_whitespace().nth(1) {
                            return u64::from_str_radix(hex_str, 16).ok();
                        }
                    }
                }
            }
        }
    }
    None
}
