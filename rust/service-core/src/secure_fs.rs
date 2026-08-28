// Additional GPLv3 section 7(b) attribution term for tryigit-owned material: see ../../NOTICE.
use std::ffi::{CStr, CString};
use std::fs::File;
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::FileExt;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

const MAX_COMPONENT_BYTES: usize = 255;
static TEMP_COUNTER: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
pub struct TrustedDir {
    fd: OwnedFd,
}

impl TrustedDir {
    pub fn open(path: &Path) -> io::Result<Self> {
        let path = CString::new(path.as_os_str().as_bytes())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "path contains NUL"))?;
        // SAFETY: `path` is a live NUL-terminated C string. No pointer is retained by `open`.
        // The returned descriptor is checked before ownership is transferred to `OwnedFd`.
        let raw = unsafe {
            libc::open(
                path.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            )
        };
        let fd = owned_fd(raw)?;
        require_directory(fd.as_raw_fd())?;
        Ok(Self { fd })
    }

    pub fn open_child(&self, name: &str) -> io::Result<Self> {
        let name = component(name)?;
        // SAFETY: `name` is a valid C string for a single path component. `self.fd` is an owned,
        // live directory descriptor for the entire call. The descriptor returned by `openat` is
        // independent and is transferred exactly once to `OwnedFd` after checking for errors.
        let raw = unsafe {
            libc::openat(
                self.fd.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            )
        };
        let fd = owned_fd(raw)?;
        require_directory(fd.as_raw_fd())?;
        Ok(Self { fd })
    }

    pub fn mkdir_child(&self, name: &str, mode: u32) -> io::Result<Self> {
        let mode = checked_mode(mode)?;
        match self.open_child(name) {
            Ok(child) => {
                child.chmod(mode)?;
                return Ok(child);
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }

        let name = component(name)?;
        // SAFETY: `name` is a valid single-component C string and `self.fd` remains a live
        // directory descriptor. `mkdirat` does not retain either argument.
        let result = unsafe { libc::mkdirat(self.fd.as_raw_fd(), name.as_ptr(), mode) };
        if result != 0 {
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::AlreadyExists {
                return Err(error);
            }
        }
        let child_name = name.to_str().map_err(|_| {
            io::Error::new(io::ErrorKind::InvalidInput, "component is not valid UTF-8")
        })?;
        let child = self.open_child(child_name)?;
        child.chmod(mode)?;
        self.sync()?;
        Ok(child)
    }

    pub fn read_bounded(&self, name: &str, max_bytes: usize) -> io::Result<Vec<u8>> {
        let (mut file, size) = self.open_file_bounded(name, max_bytes)?;
        let mut output = vec![0u8; size];
        if let Err(error) = file.read_exact(&mut output) {
            output.fill(0);
            return Err(error);
        }
        let mut trailing = [0u8; 1];
        match file.read(&mut trailing) {
            Ok(0) => Ok(output),
            Ok(_) => {
                output.fill(0);
                Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "file grew beyond validated size",
                ))
            }
            Err(error) => {
                output.fill(0);
                Err(error)
            }
        }
    }

    /// Read at most `max_bytes` from a regular file opened relative to this pinned directory.
    ///
    /// Unlike [`Self::read_bounded`], this intentionally accepts a larger source and returns its
    /// bounded prefix. The final component is opened once with `O_NOFOLLOW`; validation and reads
    /// therefore stay attached to the same descriptor even if the path is replaced concurrently.
    pub fn read_prefix_bounded(&self, name: &str, max_bytes: usize) -> io::Result<Vec<u8>> {
        if max_bytes == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "read limit must be non-zero",
            ));
        }
        let name = component(name)?;
        let fd = self.open_regular(&name, libc::O_RDONLY, 0)?;
        let mut file = File::from(fd).take(max_bytes as u64);
        let mut output = Vec::new();
        file.read_to_end(&mut output)?;
        Ok(output)
    }

    pub fn open_file_bounded(&self, name: &str, max_bytes: usize) -> io::Result<(File, usize)> {
        let name = component(name)?;
        let fd = self.open_regular(&name, libc::O_RDONLY, 0)?;
        let metadata = stat_fd(fd.as_raw_fd())?;
        let size = checked_regular_size(&metadata, max_bytes)?;
        Ok((File::from(fd), size))
    }

    pub fn create_new_file(&self, name: &str, mode: u32) -> io::Result<File> {
        let name = component(name)?;
        let mode = checked_mode(mode)?;
        let fd = self.open_regular(&name, libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL, mode)?;
        Ok(File::from(fd))
    }

    pub fn open_or_create_file(&self, name: &str, mode: u32) -> io::Result<File> {
        let name = component(name)?;
        let mode = checked_mode(mode)?;
        let fd = self.open_regular(&name, libc::O_RDWR | libc::O_CREAT, mode)?;
        Ok(File::from(fd))
    }

    pub fn chown(&self, owner: u32, group: u32) -> io::Result<()> {
        // SAFETY: `self.fd` is a live owned descriptor. `fchown` retains no pointer or FD.
        if unsafe { libc::fchown(self.fd.as_raw_fd(), owner, group) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub fn entry_names_bounded(&self, max_entries: usize) -> io::Result<Vec<String>> {
        if max_entries == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "entry limit must be non-zero",
            ));
        }
        let descriptor_path = format!("/proc/self/fd/{}", self.fd.as_raw_fd());
        let mut names = Vec::new();
        for (scanned, entry) in std::fs::read_dir(descriptor_path)?.enumerate() {
            if scanned == max_entries {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "directory entry count exceeds configured bound",
                ));
            }
            let name = entry?.file_name();
            if let Some(name) = name.to_str() {
                names.push(name.to_string());
            }
        }
        Ok(names)
    }

    pub fn file_size_bounded(&self, name: &str, max_bytes: usize) -> io::Result<usize> {
        let (_, size) = self.open_file_bounded(name, max_bytes)?;
        Ok(size)
    }

    pub fn append_bounded(&self, name: &str, data: &[u8], max_bytes: usize) -> io::Result<usize> {
        let name = component(name)?;
        let fd = self.open_regular(&name, libc::O_WRONLY | libc::O_APPEND, 0)?;
        lock_exclusive(fd.as_raw_fd())?;
        let metadata = stat_fd(fd.as_raw_fd())?;
        let current_size = checked_regular_size(&metadata, max_bytes)?;
        let final_size = current_size.checked_add(data.len()).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "staged file size overflow")
        })?;
        if final_size > max_bytes {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "staged file exceeds size limit",
            ));
        }
        let mut file = File::from(fd);
        file.write_all(data)?;
        file.flush()?;
        Ok(final_size)
    }

    pub fn read_range_bounded(
        &self,
        name: &str,
        offset: u64,
        length: usize,
        max_bytes: usize,
    ) -> io::Result<(Vec<u8>, usize)> {
        let name = component(name)?;
        let fd = self.open_regular(&name, libc::O_RDONLY, 0)?;
        let metadata = stat_fd(fd.as_raw_fd())?;
        let size = checked_regular_size(&metadata, max_bytes)?;
        let offset_usize = usize::try_from(offset).map_err(|_| {
            io::Error::new(io::ErrorKind::InvalidInput, "range offset overflows usize")
        })?;
        let end = offset_usize
            .checked_add(length)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "range length overflow"))?;
        if offset_usize > size || end > size {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "requested range exceeds staged file",
            ));
        }
        let file = File::from(fd);
        let mut output = vec![0u8; length];
        if let Err(error) = file.read_exact_at(&mut output, offset) {
            output.fill(0);
            return Err(error);
        }
        Ok((output, size))
    }

    pub fn unlink_file(&self, name: &str) -> io::Result<bool> {
        let name = component(name)?;
        // SAFETY: `name` is a valid single path component and `self.fd` is a live trusted directory
        // descriptor. `unlinkat` never follows a final symlink and retains neither argument.
        let result = unsafe { libc::unlinkat(self.fd.as_raw_fd(), name.as_ptr(), 0) };
        if result == 0 {
            return Ok(true);
        }
        let error = io::Error::last_os_error();
        if error.kind() == io::ErrorKind::NotFound {
            Ok(false)
        } else {
            Err(error)
        }
    }

    pub fn atomic_write(&self, name: &str, data: &[u8], mode: u32) -> io::Result<()> {
        self.atomic_replace_with(name, mode, |file| file.write_all(data))
    }

    pub fn atomic_write_from<R: Read>(
        &self,
        name: &str,
        source: &mut R,
        expected_bytes: usize,
        mode: u32,
        scratch: &mut [u8],
    ) -> io::Result<()> {
        self.atomic_write_from_confirmed(name, source, expected_bytes, mode, scratch, |_| Ok(()))
    }

    pub fn atomic_write_from_confirmed<R: Read, C>(
        &self,
        name: &str,
        source: &mut R,
        expected_bytes: usize,
        mode: u32,
        scratch: &mut [u8],
        confirm: C,
    ) -> io::Result<()>
    where
        C: FnOnce(&mut R) -> io::Result<()>,
    {
        if scratch.is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "stream scratch buffer is empty",
            ));
        }
        let (target, temporary_name) = self.prepare_atomic_replace(name, mode, |file| {
            let mut remaining = expected_bytes;
            while remaining != 0 {
                let limit = remaining.min(scratch.len());
                let count = loop {
                    match source.read(&mut scratch[..limit]) {
                        Ok(0) => {
                            scratch.fill(0);
                            return Err(io::Error::new(
                                io::ErrorKind::UnexpectedEof,
                                "stream ended before declared length",
                            ));
                        }
                        Ok(count) => break count,
                        Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                        Err(error) => {
                            scratch.fill(0);
                            return Err(error);
                        }
                    }
                };
                if let Err(error) = file.write_all(&scratch[..count]) {
                    scratch[..count].fill(0);
                    return Err(error);
                }
                scratch[..count].fill(0);
                remaining -= count;
            }
            Ok(())
        })?;

        if let Err(error) = confirm(source) {
            scratch.fill(0);
            self.unlink_component(&temporary_name);
            return Err(error);
        }
        scratch.fill(0);
        self.commit_temporary(&target, &temporary_name)
    }

    fn atomic_replace_with<F>(&self, name: &str, mode: u32, writer: F) -> io::Result<()>
    where
        F: FnOnce(&mut File) -> io::Result<()>,
    {
        let (target, temporary_name) = self.prepare_atomic_replace(name, mode, writer)?;
        self.commit_temporary(&target, &temporary_name)
    }

    fn prepare_atomic_replace<F>(
        &self,
        name: &str,
        mode: u32,
        writer: F,
    ) -> io::Result<(CString, CString)>
    where
        F: FnOnce(&mut File) -> io::Result<()>,
    {
        let target = component(name)?;
        let mode = checked_mode(mode)?;
        let (temporary_name, fd) = self.create_temporary(mode)?;
        let mut file = File::from(fd);
        let write_result = (|| -> io::Result<()> {
            writer(&mut file)?;
            file.flush()?;
            // SAFETY: the file owns this live descriptor and `fdatasync` does not retain it.
            if unsafe { libc::fdatasync(file.as_raw_fd()) } != 0 {
                return Err(io::Error::last_os_error());
            }
            // SAFETY: the file descriptor is live and exclusively owned by `file`; the validated
            // mode is a plain value and `fchmod` retains no references.
            if unsafe { libc::fchmod(file.as_raw_fd(), mode) } != 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        })();
        drop(file);
        if let Err(error) = write_result {
            self.unlink_component(&temporary_name);
            return Err(error);
        }
        Ok((target, temporary_name))
    }

    fn commit_temporary(&self, target: &CStr, temporary_name: &CStr) -> io::Result<()> {
        // SAFETY: both names are valid single-component C strings. The source and destination
        // directory descriptors are the same live trusted directory and no argument is retained.
        let renamed = unsafe {
            libc::renameat(
                self.fd.as_raw_fd(),
                temporary_name.as_ptr(),
                self.fd.as_raw_fd(),
                target.as_ptr(),
            )
        };
        if renamed != 0 {
            let error = io::Error::last_os_error();
            self.unlink_component(temporary_name);
            return Err(error);
        }
        self.sync()
    }

    pub fn sync(&self) -> io::Result<()> {
        // SAFETY: `self.fd` is an owned live directory descriptor and `fsync` retains no state.
        if unsafe { libc::fsync(self.fd.as_raw_fd()) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    fn chmod(&self, mode: u32) -> io::Result<()> {
        let mode = checked_mode(mode)?;
        // SAFETY: `self.fd` is live and owned by this object. `fchmod` retains no pointer or FD.
        if unsafe { libc::fchmod(self.fd.as_raw_fd(), mode) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    fn open_regular(&self, name: &CStr, flags: i32, mode: libc::mode_t) -> io::Result<OwnedFd> {
        // SAFETY: `name` is a valid single path component, `self.fd` is a live directory FD, and
        // the optional mode value is passed only with caller-selected flags. `openat` retains no
        // pointers. Ownership of a successful descriptor is transferred exactly once below.
        let raw = unsafe {
            libc::openat(
                self.fd.as_raw_fd(),
                name.as_ptr(),
                flags | libc::O_CLOEXEC | libc::O_NOFOLLOW,
                mode,
            )
        };
        let fd = owned_fd(raw)?;
        let metadata = stat_fd(fd.as_raw_fd())?;
        if metadata.st_mode & libc::S_IFMT != libc::S_IFREG {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "descriptor is not a regular file",
            ));
        }
        Ok(fd)
    }

    fn create_temporary(&self, mode: libc::mode_t) -> io::Result<(CString, OwnedFd)> {
        for _ in 0..32 {
            let sequence = TEMP_COUNTER.fetch_add(1, Ordering::Relaxed);
            let name = CString::new(format!(".ct.{}.{}.tmp", std::process::id(), sequence))
                .expect("generated temporary name has no NUL");
            match self.open_regular(&name, libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL, mode) {
                Ok(fd) => return Ok((name, fd)),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error),
            }
        }
        Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            "could not allocate temporary file",
        ))
    }

    fn unlink_component(&self, name: &CStr) {
        // SAFETY: `name` is a live C string for a single component and `self.fd` is a live trusted
        // directory descriptor. Failure is intentionally ignored during best-effort cleanup.
        let _ = unsafe { libc::unlinkat(self.fd.as_raw_fd(), name.as_ptr(), 0) };
    }
}

pub fn lock_exclusive_file(file: &File) -> io::Result<()> {
    let mut lock = libc::flock {
        l_type: libc::F_WRLCK as libc::c_short,
        l_whence: libc::SEEK_SET as libc::c_short,
        l_start: 0,
        l_len: 0,
        l_pid: 0,
    };
    loop {
        // SAFETY: `file` owns a live descriptor, `lock` is initialized for F_SETLKW, and fcntl
        // retains neither pointer after returning nor ownership of the descriptor.
        if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETLKW, &mut lock) } == 0 {
            return Ok(());
        }
        let error = io::Error::last_os_error();
        if error.kind() != io::ErrorKind::Interrupted {
            return Err(error);
        }
    }
}

pub fn chown_file(file: &File, owner: u32, group: u32) -> io::Result<()> {
    // SAFETY: `file` owns a live descriptor. `fchown` retains no pointer or descriptor.
    if unsafe { libc::fchown(file.as_raw_fd(), owner, group) } == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

fn owned_fd(raw: RawFd) -> io::Result<OwnedFd> {
    if raw < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `raw` is a newly returned successful descriptor and has not been wrapped or closed.
    // `OwnedFd` becomes its sole owner and will close it exactly once.
    Ok(unsafe { OwnedFd::from_raw_fd(raw) })
}

fn component(name: &str) -> io::Result<CString> {
    let bytes = name.as_bytes();
    if bytes.is_empty()
        || bytes.len() > MAX_COMPONENT_BYTES
        || name == "."
        || name == ".."
        || bytes.contains(&b'/')
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid path component",
        ));
    }
    CString::new(bytes)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "component contains NUL"))
}

fn checked_mode(mode: u32) -> io::Result<libc::mode_t> {
    if mode & !0o777 != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid file mode",
        ));
    }
    Ok(mode)
}

fn require_directory(fd: RawFd) -> io::Result<()> {
    let metadata = stat_fd(fd)?;
    if metadata.st_mode & libc::S_IFMT == libc::S_IFDIR {
        Ok(())
    } else {
        Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "descriptor is not a directory",
        ))
    }
}

fn stat_fd(fd: RawFd) -> io::Result<libc::stat> {
    let mut metadata = std::mem::MaybeUninit::<libc::stat>::uninit();
    // SAFETY: `metadata` points to properly aligned writable storage for one `libc::stat`; `fd` is
    // required by callers to be live for this call. `fstat` initializes the full object on success
    // and retains neither the pointer nor the descriptor.
    if unsafe { libc::fstat(fd, metadata.as_mut_ptr()) } != 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: successful `fstat` initialized the complete `libc::stat` value above.
    Ok(unsafe { metadata.assume_init() })
}

fn checked_regular_size(metadata: &libc::stat, max_bytes: usize) -> io::Result<usize> {
    if metadata.st_mode & libc::S_IFMT != libc::S_IFREG || metadata.st_size < 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "not a regular file",
        ));
    }
    let size = usize::try_from(metadata.st_size)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "file size overflows usize"))?;
    if size > max_bytes {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "file exceeds size limit",
        ));
    }
    Ok(size)
}

fn lock_exclusive(fd: RawFd) -> io::Result<()> {
    loop {
        // SAFETY: `fd` is a live descriptor owned by the caller for the duration of this call.
        // `flock` uses scalar arguments, retains no pointer, and the lock is released when the
        // descriptor closes.
        if unsafe { libc::flock(fd, libc::LOCK_EX) } == 0 {
            return Ok(());
        }
        let error = io::Error::last_os_error();
        if error.kind() != io::ErrorKind::Interrupted {
            return Err(error);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::os::unix::fs::symlink;
    use std::sync::Arc;
    use std::thread;

    struct TestDir {
        path: std::path::PathBuf,
    }

    impl TestDir {
        fn new() -> Self {
            static COUNTER: AtomicU64 = AtomicU64::new(1);
            let path = std::env::temp_dir().join(format!(
                "cleverestricky-service-core-{}-{}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self { path }
        }
    }

    impl Drop for TestDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.path);
        }
    }

    #[test]
    fn atomic_write_and_bounded_read_use_directory_capability() {
        let root = TestDir::new();
        let dir = TrustedDir::open(&root.path).unwrap();
        dir.atomic_write("state.bin", b"immutable", 0o600).unwrap();
        assert_eq!(dir.read_bounded("state.bin", 32).unwrap(), b"immutable");
        assert!(dir.read_bounded("state.bin", 4).is_err());
    }

    #[test]
    fn atomic_stream_write_is_bounded_partial_read_safe_and_cleans_truncation() {
        let root = TestDir::new();
        let dir = TrustedDir::open(&root.path).unwrap();
        let payload = vec![0x5a; 16 * 1024 + 7];
        let mut source = io::Cursor::new(payload.as_slice());
        let mut scratch = [0u8; 37];
        dir.atomic_write_from(
            "stream.bin",
            &mut source,
            payload.len(),
            0o600,
            &mut scratch,
        )
        .unwrap();
        assert_eq!(
            dir.read_bounded("stream.bin", payload.len()).unwrap(),
            payload
        );
        assert!(scratch.iter().all(|byte| *byte == 0));

        let mut short = io::Cursor::new(b"short".as_slice());
        assert!(dir
            .atomic_write_from("truncated.bin", &mut short, 10, 0o600, &mut scratch)
            .is_err());
        assert!(!root.path.join("truncated.bin").exists());
        assert!(scratch.iter().all(|byte| *byte == 0));
        let names: Vec<_> = fs::read_dir(&root.path)
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect();
        assert!(!names
            .iter()
            .any(|name| name.to_string_lossy().starts_with(".ct.")));
    }

    #[test]
    fn confirmation_failure_preserves_existing_destination_and_cleans_temporary() {
        let root = TestDir::new();
        let dir = TrustedDir::open(&root.path).unwrap();
        dir.atomic_write("state.bin", b"old", 0o600).unwrap();
        let mut source = io::Cursor::new(b"new!".as_slice());
        let mut scratch = [0u8; 2];
        let result = dir.atomic_write_from_confirmed(
            "state.bin",
            &mut source,
            3,
            0o600,
            &mut scratch,
            |reader| {
                let mut trailing = [0u8; 1];
                reader.read_exact(&mut trailing)?;
                if trailing[0] != 0xa5 {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "commit marker rejected",
                    ));
                }
                Ok(())
            },
        );
        assert!(result.is_err());
        assert_eq!(fs::read(root.path.join("state.bin")).unwrap(), b"old");
        assert!(scratch.iter().all(|byte| *byte == 0));
        let names: Vec<_> = fs::read_dir(&root.path)
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect();
        assert_eq!(names, vec![std::ffi::OsString::from("state.bin")]);
    }

    #[test]
    fn staging_primitives_are_descriptor_relative_and_bounded() {
        let root = TestDir::new();
        let dir = TrustedDir::open(&root.path).unwrap();
        drop(dir.create_new_file("stage", 0o600).unwrap());
        assert_eq!(dir.append_bounded("stage", b"abcdef", 8).unwrap(), 6);
        assert!(dir.append_bounded("stage", b"xyz", 8).is_err());
        assert_eq!(dir.file_size_bounded("stage", 8).unwrap(), 6);
        let (range, total) = dir.read_range_bounded("stage", 2, 3, 8).unwrap();
        assert_eq!(range, b"cde");
        assert_eq!(total, 6);
        let (mut file, size) = dir.open_file_bounded("stage", 8).unwrap();
        let mut streamed = Vec::new();
        file.read_to_end(&mut streamed).unwrap();
        assert_eq!(size, 6);
        assert_eq!(streamed, b"abcdef");
        assert!(dir.read_range_bounded("stage", 5, 2, 8).is_err());
        assert!(dir.unlink_file("stage").unwrap());
        assert!(!dir.unlink_file("stage").unwrap());
    }

    #[test]
    fn directory_enumeration_is_descriptor_relative_and_bounded() {
        let root = TestDir::new();
        let pinned = root.path.with_extension("pinned");
        fs::write(root.path.join("first"), b"one").unwrap();
        fs::write(root.path.join("third"), b"three").unwrap();
        let dir = TrustedDir::open(&root.path).unwrap();
        fs::rename(&root.path, &pinned).unwrap();
        fs::create_dir(&root.path).unwrap();
        fs::write(root.path.join("second"), b"two").unwrap();

        assert!(dir.entry_names_bounded(1).is_err());
        let mut names = dir.entry_names_bounded(2).unwrap();
        names.sort();
        assert_eq!(names, ["first", "third"]);
        assert!(dir.entry_names_bounded(0).is_err());
        drop(dir);
        fs::remove_dir_all(&root.path).unwrap();
        fs::rename(&pinned, &root.path).unwrap();
    }

    #[test]
    fn concurrent_staging_appends_cannot_cross_limit() {
        let root = TestDir::new();
        let dir = Arc::new(TrustedDir::open(&root.path).unwrap());
        drop(dir.create_new_file("stage", 0o600).unwrap());
        let mut workers = Vec::new();
        for value in 0u8..16 {
            let dir = Arc::clone(&dir);
            workers.push(thread::spawn(move || {
                let data = vec![value; 512];
                dir.append_bounded("stage", &data, 4096).is_ok()
            }));
        }
        let successes = workers
            .into_iter()
            .map(|worker| usize::from(worker.join().unwrap()))
            .sum::<usize>();
        assert_eq!(successes, 8);
        assert_eq!(dir.file_size_bounded("stage", 4096).unwrap(), 4096);
    }

    #[test]
    fn staging_primitives_reject_symlinks() {
        let root = TestDir::new();
        let outside = root.path.with_extension("staging-outside");
        fs::write(&outside, b"outside").unwrap();
        symlink(&outside, root.path.join("stage")).unwrap();
        let dir = TrustedDir::open(&root.path).unwrap();
        assert!(dir.append_bounded("stage", b"inside", 32).is_err());
        assert!(dir.file_size_bounded("stage", 32).is_err());
        assert!(dir.read_range_bounded("stage", 0, 1, 32).is_err());
        assert_eq!(fs::read(&outside).unwrap(), b"outside");
        assert!(dir.unlink_file("stage").unwrap());
        assert_eq!(fs::read(&outside).unwrap(), b"outside");
        let _ = fs::remove_file(outside);
    }

    #[test]
    fn nofollow_prevents_symlink_reads_and_atomic_replace_cannot_escape() {
        let root = TestDir::new();
        let outside = root.path.with_extension("outside");
        fs::write(&outside, b"outside").unwrap();
        symlink(&outside, root.path.join("escape")).unwrap();
        let dir = TrustedDir::open(&root.path).unwrap();
        assert!(dir.read_bounded("escape", 32).is_err());

        dir.atomic_write("escape", b"inside", 0o600).unwrap();
        assert_eq!(fs::read(&outside).unwrap(), b"outside");
        assert_eq!(fs::read(root.path.join("escape")).unwrap(), b"inside");
        let _ = fs::remove_file(outside);
    }

    #[test]
    fn prefix_read_is_bounded_and_rejects_replaced_symlink() {
        let root = TestDir::new();
        let outside = root.path.with_extension("prefix-outside");
        fs::write(root.path.join("source"), b"0123456789").unwrap();
        fs::write(&outside, b"outside-secret").unwrap();
        let dir = TrustedDir::open(&root.path).unwrap();

        assert_eq!(dir.read_prefix_bounded("source", 4).unwrap(), b"0123");
        assert!(dir.read_prefix_bounded("source", 0).is_err());

        fs::remove_file(root.path.join("source")).unwrap();
        symlink(&outside, root.path.join("source")).unwrap();
        assert!(dir.read_prefix_bounded("source", 64).is_err());
        assert_eq!(fs::read(&outside).unwrap(), b"outside-secret");
        let _ = fs::remove_file(outside);
    }

    #[test]
    fn symlink_child_directory_is_rejected() {
        let root = TestDir::new();
        let outside = root.path.with_extension("child-outside");
        fs::create_dir(&outside).unwrap();
        symlink(&outside, root.path.join("child")).unwrap();
        let dir = TrustedDir::open(&root.path).unwrap();
        assert!(dir.open_child("child").is_err());
        let _ = fs::remove_dir(outside);
    }

    #[test]
    fn arbitrary_paths_are_not_capabilities() {
        let root = TestDir::new();
        let dir = TrustedDir::open(&root.path).unwrap();
        for invalid in ["", ".", "..", "a/b", "/absolute", "nul\0byte"] {
            assert!(dir.atomic_write(invalid, b"x", 0o600).is_err());
        }
    }

    #[test]
    fn concurrent_replacement_is_atomic_and_leaves_no_temporary_files() {
        let root = TestDir::new();
        let dir = Arc::new(TrustedDir::open(&root.path).unwrap());
        let mut workers = Vec::new();
        for value in 0u8..8 {
            let dir = Arc::clone(&dir);
            workers.push(thread::spawn(move || {
                let data = vec![value; 4096];
                dir.atomic_write("state", &data, 0o600).unwrap();
            }));
        }
        for worker in workers {
            worker.join().unwrap();
        }
        let output = dir.read_bounded("state", 4096).unwrap();
        assert_eq!(output.len(), 4096);
        assert!(output.iter().all(|byte| *byte == output[0]));
        let names: Vec<_> = fs::read_dir(&root.path)
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect();
        assert_eq!(names, vec![std::ffi::OsString::from("state")]);
    }
}
