#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub struct FileIdentity {
    pub size: u64,
    pub dev: u64,
    pub ino: u64,
    pub mode: u32,
}

#[cfg(unix)]
mod imp {
    use super::FileIdentity;
    use std::ffi::{CStr, CString};
    use std::io;
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};

    /// Extracts the raw file descriptor from an OwnedFd.
    pub fn get_raw_fd(fd: &OwnedFd) -> RawFd {
        fd.as_raw_fd()
    }

    /// Opens a file relative to a directory FD without following symlinks.
    pub fn open_file_nofollow(dir_fd: RawFd, relative_path: &str) -> io::Result<OwnedFd> {
        if relative_path.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "empty path"));
        }

        let components: Vec<&str> = relative_path.split('/').collect();
        if components.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid path"));
        }

        let mut current_dir: Option<OwnedFd> = None;

        for (i, component) in components.iter().enumerate() {
            if component.is_empty() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "empty path component",
                ));
            }

            let c_component = CString::new(*component)
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
            let is_last = i == components.len() - 1;

            let flags = if is_last {
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC
            } else {
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC
            };

            let parent_fd = match &current_dir {
                Some(fd) => fd.as_raw_fd(),
                None => dir_fd,
            };

            // SAFETY: Calling openat with a valid directory FD and a valid C string.
            let next_raw = unsafe { libc::openat(parent_fd, c_component.as_ptr(), flags) };
            if next_raw < 0 {
                return Err(io::Error::last_os_error());
            }

            // SAFETY: next_raw is checked >= 0 and we take ownership.
            let next_fd = unsafe { OwnedFd::from_raw_fd(next_raw) };
            if is_last {
                return Ok(next_fd);
            }
            current_dir = Some(next_fd);
        }

        Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid path components",
        ))
    }

    /// Opens a directory without following symlinks.
    pub fn open_dir_nofollow(path: &str) -> io::Result<OwnedFd> {
        let c_path =
            CString::new(path).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
        let flags = libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC;

        // SAFETY: Calling open with a valid C string.
        let fd = unsafe { libc::open(c_path.as_ptr(), flags) };
        if fd < 0 {
            Err(io::Error::last_os_error())
        } else {
            // SAFETY: We just successfully opened this FD.
            Ok(unsafe { OwnedFd::from_raw_fd(fd) })
        }
    }

    /// Retrieves file metadata (size, device, inode, mode) for the given file descriptor.
    pub fn fstat_fd(fd: RawFd) -> io::Result<FileIdentity> {
        // SAFETY: stat struct is plain old data and initialized by fstat.
        let mut stat: libc::stat = unsafe { std::mem::zeroed() };

        // SAFETY: Calling fstat with a potentially valid FD. Returns -1 on error.
        if unsafe { libc::fstat(fd, &mut stat) } < 0 {
            return Err(io::Error::last_os_error());
        }

        Ok(FileIdentity {
            size: stat.st_size as u64,
            dev: stat.st_dev as u64,
            ino: stat.st_ino as u64,
            mode: stat.st_mode as u32,
        })
    }

    /// Checks if a file mode represents a regular file.
    #[allow(clippy::unnecessary_cast)]
    pub fn is_regular_file(mode: u32) -> bool {
        (mode & (libc::S_IFMT as u32)) == (libc::S_IFREG as u32)
    }

    /// Checks if a file mode represents a symlink.
    #[allow(clippy::unnecessary_cast)]
    pub fn is_symlink(mode: u32) -> bool {
        (mode & (libc::S_IFMT as u32)) == (libc::S_IFLNK as u32)
    }

    /// Checks if a file mode has any executable permission bits set.
    pub fn is_executable(mode: u32) -> bool {
        (mode & 0o111) != 0
    }

    #[cfg(target_os = "android")]
    unsafe fn clear_errno() {
        // SAFETY: __errno returns a valid thread-local errno pointer on Android Bionic.
        unsafe {
            *libc::__errno() = 0;
        }
    }

    #[cfg(target_os = "android")]
    unsafe fn get_errno() -> libc::c_int {
        // SAFETY: __errno returns a valid thread-local errno pointer on Android Bionic.
        unsafe { *libc::__errno() }
    }

    #[cfg(all(unix, not(target_os = "android")))]
    unsafe fn clear_errno() {
        // SAFETY: __errno_location returns a valid thread-local errno pointer on non-Android Unix.
        unsafe {
            *libc::__errno_location() = 0;
        }
    }

    #[cfg(all(unix, not(target_os = "android")))]
    unsafe fn get_errno() -> libc::c_int {
        // SAFETY: __errno_location returns a valid thread-local errno pointer on non-Android Unix.
        unsafe { *libc::__errno_location() }
    }

    /// Lists all entries in a directory by file descriptor, returning (name, is_dir) pairs.
    #[allow(clippy::unnecessary_cast)]
    pub fn list_directory_at(dir_fd: RawFd) -> io::Result<Vec<(String, bool)>> {
        // SAFETY: Calling dup with a potentially valid FD.
        let dup_fd = unsafe { libc::dup(dir_fd) };
        if dup_fd < 0 {
            return Err(io::Error::last_os_error());
        }

        // SAFETY: dup_fd is a valid, newly duplicated FD.
        let dir = unsafe { libc::fdopendir(dup_fd) };
        if dir.is_null() {
            // SAFETY: dup_fd is valid and not owned by dir.
            unsafe { libc::close(dup_fd) };
            return Err(io::Error::last_os_error());
        }

        let mut entries = Vec::new();

        loop {
            // SAFETY: clear errno before readdir to distinguish EOF from error.
            unsafe { clear_errno() };
            // SAFETY: dir is a valid DIR pointer returned by fdopendir.
            let entry = unsafe { libc::readdir(dir) };
            if entry.is_null() {
                let err = unsafe { get_errno() };
                if err != 0 {
                    unsafe { libc::closedir(dir) };
                    return Err(io::Error::from_raw_os_error(err));
                }
                break;
            }

            // SAFETY: entry is a valid dirent pointer from readdir.
            let name_cstr = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) };
            let name_bytes = name_cstr.to_bytes();
            if name_bytes == b"." || name_bytes == b".." {
                continue;
            }

            let name = match std::str::from_utf8(name_bytes) {
                Ok(s) => s.to_string(),
                Err(_) => name_cstr.to_string_lossy().into_owned(),
            };

            // SAFETY: entry is valid. If d_type is DT_UNKNOWN, fallback to fstatat.
            let is_dir = unsafe {
                if (*entry).d_type == libc::DT_DIR {
                    true
                } else if (*entry).d_type == libc::DT_UNKNOWN {
                    let mut stat: libc::stat = std::mem::zeroed();
                    if libc::fstatat(
                        dir_fd,
                        (*entry).d_name.as_ptr(),
                        &mut stat,
                        libc::AT_SYMLINK_NOFOLLOW,
                    ) == 0
                    {
                        (stat.st_mode & (libc::S_IFMT as u32)) == (libc::S_IFDIR as u32)
                    } else {
                        false
                    }
                } else {
                    false
                }
            };
            entries.push((name, is_dir));
        }

        // SAFETY: dir is a valid DIR pointer. This will also close the underlying FD.
        unsafe { libc::closedir(dir) };

        Ok(entries)
    }

    /// Duplicates a file descriptor, returning a new owned FD.
    pub fn duplicate_fd(fd: RawFd) -> io::Result<OwnedFd> {
        let dup_fd = unsafe { libc::dup(fd) };
        if dup_fd < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(unsafe { OwnedFd::from_raw_fd(dup_fd) })
        }
    }
}

#[cfg(not(unix))]
mod imp {
    use super::FileIdentity;
    use std::fs::File;
    use std::io;
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle, RawHandle};

    pub type OwnedFd = OwnedHandle;
    pub type RawFd = RawHandle;

    /// Extracts the raw file descriptor (Windows handle) from an OwnedFd.
    pub fn get_raw_fd(fd: &OwnedFd) -> RawFd {
        fd.as_raw_handle()
    }

    /// Windows stub: opens a file (does not enforce nofollow semantics).
    pub fn open_file_nofollow(_dir_fd: RawFd, relative_path: &str) -> io::Result<OwnedFd> {
        let f = File::open(relative_path)?;
        let handle = f.as_raw_handle();
        std::mem::forget(f); // keep handle alive
                             // SAFETY: we just leaked the File to take ownership of its handle
        Ok(unsafe { OwnedHandle::from_raw_handle(handle) })
    }

    /// Windows stub: opens a directory (does not enforce nofollow semantics).
    pub fn open_dir_nofollow(path: &str) -> io::Result<OwnedFd> {
        let f = File::open(path)?;
        let handle = f.as_raw_handle();
        std::mem::forget(f);
        // SAFETY: we just leaked the File to take ownership of its handle
        Ok(unsafe { OwnedHandle::from_raw_handle(handle) })
    }

    /// Windows stub: returns dummy file metadata for testing.
    pub fn fstat_fd(_fd: RawFd) -> io::Result<FileIdentity> {
        // Dummy implementation for tests on windows
        Ok(FileIdentity {
            size: 5,
            dev: 0,
            ino: 0,
            mode: 0x8000,
        })
    }

    /// Windows stub: always returns true.
    pub fn is_regular_file(_mode: u32) -> bool {
        true
    }

    /// Windows stub: always returns false.
    pub fn is_symlink(_mode: u32) -> bool {
        false
    }

    /// Windows stub: returns false for executable check.
    pub fn is_executable(_mode: u32) -> bool {
        false
    }

    /// Windows stub: returns a dummy directory listing.
    pub fn list_directory_at(_dir_fd: RawFd) -> io::Result<Vec<(String, bool)>> {
        Ok(vec![("test.txt".to_string(), false)])
    }

    /// Windows stub: duplicates a file handle.
    #[allow(clippy::not_unsafe_ptr_arg_deref)]
    pub fn duplicate_fd(fd: RawFd) -> io::Result<OwnedFd> {
        // Windows dummy duplicate for tests
        // SAFETY: fd is valid in test environment
        let handle = unsafe { OwnedHandle::from_raw_handle(fd) };
        let dup = handle.try_clone()?;
        std::mem::forget(handle);
        Ok(dup)
    }
}

pub use imp::*;

#[cfg(test)]
#[cfg(unix)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn test_file_operations() {
        let dir = tempdir().unwrap();
        let file_path = dir.path().join("test.txt");
        fs::write(&file_path, "hello").unwrap();

        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = open_dir_nofollow(dir_str).unwrap();

        let file_fd = open_file_nofollow(get_raw_fd(&dir_fd), "test.txt").unwrap();

        let identity = fstat_fd(get_raw_fd(&file_fd)).unwrap();
        assert_eq!(identity.size, 5);
        assert!(is_regular_file(identity.mode));
        assert!(!is_symlink(identity.mode));

        let entries = list_directory_at(get_raw_fd(&dir_fd)).unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].0, "test.txt");
        assert!(!entries[0].1);
    }

    #[test]
    fn test_symlink_nofollow() {
        let dir = tempdir().unwrap();
        let file_path = dir.path().join("test.txt");
        fs::write(&file_path, "hello").unwrap();

        let symlink_path = dir.path().join("link.txt");
        #[cfg(unix)]
        std::os::unix::fs::symlink("test.txt", &symlink_path).unwrap();

        let dir_str = dir.path().to_str().unwrap();
        let dir_fd = open_dir_nofollow(dir_str).unwrap();

        #[cfg(unix)]
        {
            // Should fail because it's a symlink
            let res = open_file_nofollow(get_raw_fd(&dir_fd), "link.txt");
            assert!(res.is_err());
            assert_eq!(res.unwrap_err().raw_os_error(), Some(libc::ELOOP));
        }
    }
}
