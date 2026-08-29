use crate::ffi::{validate_mut_slice_args, validate_slice_args};
use std::cell::Cell;
use std::ffi::{c_char, c_int, c_uint};
use std::mem;
use std::sync::RwLock;

const AT_NO_AUTOMOUNT: c_int = 0x800;
const AT_EMPTY_PATH: c_int = 0x1000;
const STATX_TYPE: c_uint = 0x001;
const STATX_INO: c_uint = 0x100;
const FILE_TYPE_MASK: u16 = 0o170_000;
const CHARACTER_DEVICE: u16 = 0o020_000;
const BINDER_FD_CACHE_ENTRIES: usize = 64;
const BINDER_FD_FAST_CACHE_ENTRIES: usize = 4;
const BINDER_FD_FAST_REVALIDATE_HITS: u8 = 31;
const PROC_FD_PREFIX: &[u8] = b"/proc/self/fd/";
const MAXIMUM_DESCRIPTOR_PATH_BYTES: usize = 64;
const MAXIMUM_DESCRIPTOR_TARGET_BYTES: usize = 256;

#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
struct StatxTimestamp {
    seconds: i64,
    nanoseconds: u32,
    reserved: i32,
}

#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
struct Statx {
    mask: u32,
    block_size: u32,
    attributes: u64,
    link_count: u32,
    owner: u32,
    group: u32,
    mode: u16,
    spare_zero: u16,
    inode: u64,
    size: u64,
    blocks: u64,
    attributes_mask: u64,
    access_time: StatxTimestamp,
    birth_time: StatxTimestamp,
    change_time: StatxTimestamp,
    modification_time: StatxTimestamp,
    device_type_major: u32,
    device_type_minor: u32,
    device_major: u32,
    device_minor: u32,
    mount_identifier: u64,
    direct_io_memory_alignment: u32,
    direct_io_offset_alignment: u32,
    spare: [u64; 12],
}

const _: [(); 16] = [(); mem::size_of::<StatxTimestamp>()];
const _: [(); 256] = [(); mem::size_of::<Statx>()];
const _: [(); 32] = [(); mem::offset_of!(Statx, inode)];
const _: [(); 136] = [(); mem::offset_of!(Statx, device_major)];

#[derive(Clone, Copy)]
struct BinderFdCacheEntry {
    descriptor: i32,
    device: u64,
    inode: u64,
    is_binder: bool,
}

impl BinderFdCacheEntry {
    const EMPTY: Self = Self {
        descriptor: -1,
        device: 0,
        inode: 0,
        is_binder: false,
    };
}

#[derive(Clone, Copy)]
struct BinderFdFastCacheEntry {
    descriptor: i32,
    exchange_token: usize,
    hits_remaining: u8,
}

impl BinderFdFastCacheEntry {
    const EMPTY: Self = Self {
        descriptor: -1,
        exchange_token: 0,
        hits_remaining: 0,
    };
}

static BINDER_FD_CACHE: RwLock<[BinderFdCacheEntry; BINDER_FD_CACHE_ENTRIES]> =
    RwLock::new([BinderFdCacheEntry::EMPTY; BINDER_FD_CACHE_ENTRIES]);
static EMPTY_PATH: [c_char; 1] = [0];

thread_local! {
    // libbinder can legitimately alternate a small number of BINDER_WRITE_READ
    // exchange structures on the same thread. Keeping a bounded set avoids
    // turning those alternations into repeated statx/RwLock work while every
    // entry still remains bound to both the descriptor and exchange pointer.
    static BINDER_FD_FAST_CACHE: Cell<[BinderFdFastCacheEntry; BINDER_FD_FAST_CACHE_ENTRIES]> =
        const { Cell::new([BinderFdFastCacheEntry::EMPTY; BINDER_FD_FAST_CACHE_ENTRIES]) };
    static BINDER_FD_FAST_CACHE_NEXT: Cell<usize> = const { Cell::new(0) };
}

extern "C" {
    fn statx(
        descriptor: c_int,
        path: *const c_char,
        flags: c_int,
        mask: c_uint,
        output: *mut Statx,
    ) -> c_int;
    fn readlink(path: *const c_char, output: *mut c_char, capacity: usize) -> isize;
}

pub fn is_binder_device_path(path: &[u8]) -> bool {
    if path.is_empty() || path.len() > 255 || path.contains(&0) {
        return false;
    }
    let basename = path.rsplit(|byte| *byte == b'/').next().unwrap_or(path);
    matches!(basename, b"binder" | b"vndbinder" | b"hwbinder")
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct DescriptorIdentity {
    device: u64,
    inode: u64,
    file_type: u16,
}

fn descriptor_identity(descriptor: i32) -> Option<DescriptorIdentity> {
    if descriptor < 0 {
        return None;
    }
    let mut metadata = Statx::default();
    if unsafe {
        statx(
            descriptor,
            EMPTY_PATH.as_ptr(),
            AT_EMPTY_PATH | AT_NO_AUTOMOUNT,
            STATX_TYPE | STATX_INO,
            &mut metadata,
        )
    } != 0
        || (metadata.mask & (STATX_TYPE | STATX_INO)) != (STATX_TYPE | STATX_INO)
    {
        return None;
    }
    let device = (u64::from(metadata.device_major) << 32) | u64::from(metadata.device_minor);
    Some(DescriptorIdentity {
        device,
        inode: metadata.inode,
        file_type: metadata.mode & FILE_TYPE_MASK,
    })
}

fn write_descriptor_path(descriptor: i32, output: &mut [u8]) -> Option<usize> {
    if descriptor < 0 || output.len() < PROC_FD_PREFIX.len() + 2 {
        return None;
    }
    output[..PROC_FD_PREFIX.len()].copy_from_slice(PROC_FD_PREFIX);

    let mut digits = [0u8; 10];
    let mut digit_start = digits.len();
    let mut value = descriptor as u32;
    loop {
        digit_start = digit_start.checked_sub(1)?;
        digits[digit_start] = b'0' + (value % 10) as u8;
        value /= 10;
        if value == 0 {
            break;
        }
    }
    let digit_count = digits.len() - digit_start;
    let path_length = PROC_FD_PREFIX.len().checked_add(digit_count)?;
    if path_length >= output.len() {
        return None;
    }
    output[PROC_FD_PREFIX.len()..path_length].copy_from_slice(&digits[digit_start..]);
    output[path_length] = 0;
    Some(path_length)
}

fn lookup_binder_fd_cache(
    cache: &[BinderFdCacheEntry; BINDER_FD_CACHE_ENTRIES],
    descriptor: i32,
    identity: DescriptorIdentity,
) -> Option<bool> {
    cache
        .iter()
        .find(|entry| {
            entry.descriptor == descriptor
                && entry.device == identity.device
                && entry.inode == identity.inode
        })
        .map(|entry| entry.is_binder)
}

fn remember_binder_fd_cache(
    cache: &mut [BinderFdCacheEntry; BINDER_FD_CACHE_ENTRIES],
    descriptor: i32,
    identity: DescriptorIdentity,
    is_binder: bool,
) {
    let replacement = cache
        .iter()
        .position(|entry| entry.descriptor == descriptor)
        .or_else(|| cache.iter().position(|entry| entry.descriptor < 0))
        .unwrap_or(descriptor as usize % BINDER_FD_CACHE_ENTRIES);
    cache[replacement] = BinderFdCacheEntry {
        descriptor,
        device: identity.device,
        inode: identity.inode,
        is_binder,
    };
}

fn take_fast_binder_fd_hit(descriptor: i32, exchange_token: usize) -> bool {
    BINDER_FD_FAST_CACHE.with(|cache| {
        let mut entries = cache.get();
        for entry in &mut entries {
            if entry.descriptor == descriptor
                && entry.exchange_token == exchange_token
                && entry.hits_remaining != 0
            {
                entry.hits_remaining -= 1;
                cache.set(entries);
                return true;
            }
        }
        false
    })
}

fn remember_fast_binder_fd(descriptor: i32, exchange_token: usize) {
    BINDER_FD_FAST_CACHE.with(|cache| {
        let mut entries = cache.get();
        if let Some(entry) = entries
            .iter_mut()
            .find(|entry| entry.descriptor == descriptor && entry.exchange_token == exchange_token)
        {
            entry.hits_remaining = BINDER_FD_FAST_REVALIDATE_HITS;
            cache.set(entries);
            return;
        }
        let slot = BINDER_FD_FAST_CACHE_NEXT.with(|next| {
            let slot = next.get() % BINDER_FD_FAST_CACHE_ENTRIES;
            next.set((slot + 1) % BINDER_FD_FAST_CACHE_ENTRIES);
            slot
        });
        entries[slot] = BinderFdFastCacheEntry {
            descriptor,
            exchange_token,
            hits_remaining: BINDER_FD_FAST_REVALIDATE_HITS,
        };
        cache.set(entries);
    });
}

/// Classifies the descriptor after a successful `BINDER_WRITE_READ` ioctl.
///
/// The successful Binder command is a required part of the trust decision: it
/// prevents a stale positive cache entry from admitting an ordinary reused FD.
/// Fast hits remain bound to both the FD and the successful exchange pointer;
/// a small per-thread set tolerates normal libbinder call-site alternation.
/// Device and inode identity are revalidated at a bounded interval for each
/// cached pair without adding a syscall to every Binder transaction.
pub fn is_binder_fd_after_successful_ioctl(descriptor: i32, exchange_token: usize) -> bool {
    if descriptor < 0 || exchange_token == 0 {
        return false;
    }
    if take_fast_binder_fd_hit(descriptor, exchange_token) {
        return true;
    }

    let Some(identity) = descriptor_identity(descriptor) else {
        return false;
    };
    // Binder endpoints are character devices. A regular file named `binder`
    // must never enter the interception path solely because its basename
    // resembles a Binder device.
    if identity.file_type != CHARACTER_DEVICE {
        return false;
    }
    if let Ok(cache) = BINDER_FD_CACHE.read() {
        if let Some(is_binder) = lookup_binder_fd_cache(&cache, descriptor, identity) {
            if is_binder {
                remember_fast_binder_fd(descriptor, exchange_token);
            }
            return is_binder;
        }
    }

    let mut proc_path = [0u8; MAXIMUM_DESCRIPTOR_PATH_BYTES];
    if write_descriptor_path(descriptor, &mut proc_path).is_none() {
        return false;
    }
    let mut target = [0u8; MAXIMUM_DESCRIPTOR_TARGET_BYTES];
    let target_length = unsafe {
        readlink(
            proc_path.as_ptr().cast(),
            target.as_mut_ptr().cast(),
            target.len(),
        )
    };
    if target_length <= 0 || target_length as usize >= target.len() {
        return false;
    }
    if descriptor_identity(descriptor) != Some(identity) {
        return false;
    }
    let is_binder = is_binder_device_path(&target[..target_length as usize]);
    if let Ok(mut cache) = BINDER_FD_CACHE.write() {
        remember_binder_fd_cache(&mut cache, descriptor, identity, is_binder);
    }
    if is_binder {
        remember_fast_binder_fd(descriptor, exchange_token);
    }
    is_binder
}

pub fn parse_android_api_level(value: &[u8]) -> Option<i32> {
    if value.is_empty() || value.len() > 3 || !value.iter().all(u8::is_ascii_digit) {
        return None;
    }
    let api = value.iter().try_fold(0i32, |current, digit| {
        current
            .checked_mul(10)?
            .checked_add(i32::from(digit - b'0'))
    })?;
    (31..=37).contains(&api).then_some(api)
}

pub fn parse_kernel_release(value: &[u8]) -> Option<(i32, i32)> {
    if value.is_empty() || value.len() > 255 {
        return None;
    }
    let separator = value.iter().position(|byte| *byte == b'.')?;
    let major = parse_decimal_component(&value[..separator])?;
    let remainder = &value[separator + 1..];
    let minor_length = remainder
        .iter()
        .position(|byte| !byte.is_ascii_digit())
        .unwrap_or(remainder.len());
    let minor = parse_decimal_component(&remainder[..minor_length])?;
    Some((major, minor))
}

fn parse_decimal_component(value: &[u8]) -> Option<i32> {
    if value.is_empty() || value.len() > 9 || !value.iter().all(u8::is_ascii_digit) {
        return None;
    }
    value.iter().try_fold(0i32, |current, digit| {
        current
            .checked_mul(10)?
            .checked_add(i32::from(digit - b'0'))
    })
}

#[no_mangle]
pub extern "C" fn rust_is_binder_fd_after_successful_ioctl(
    descriptor: i32,
    exchange_token: usize,
) -> bool {
    std::panic::catch_unwind(|| is_binder_fd_after_successful_ioctl(descriptor, exchange_token))
        .unwrap_or(false)
}

#[no_mangle]
/// Parses a bounded Android API level string.
///
/// # Safety
/// `value_pointer` must be readable for `length` bytes when `length` is not
/// zero. The memory must remain valid for the duration of the call.
pub unsafe extern "C" fn rust_parse_android_api_level(
    value_pointer: *const u8,
    length: usize,
) -> i32 {
    std::panic::catch_unwind(|| {
        let value = match unsafe { validate_slice_args(value_pointer, length) } {
            Some(value) => value,
            None => return 0,
        };
        parse_android_api_level(value).unwrap_or(0)
    })
    .unwrap_or(0)
}

#[no_mangle]
/// Parses the major and minor components of a bounded kernel release string.
///
/// # Safety
/// `value_pointer` must be readable for `length` bytes when `length` is not
/// zero. Both output pointers must each reference one writable `i32`. All
/// pointed to memory must remain valid for the duration of the call.
pub unsafe extern "C" fn rust_parse_kernel_release(
    value_pointer: *const u8,
    length: usize,
    major_pointer: *mut i32,
    minor_pointer: *mut i32,
) -> bool {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let value = match unsafe { validate_slice_args(value_pointer, length) } {
            Some(value) => value,
            None => return false,
        };
        let major = match unsafe { validate_mut_slice_args(major_pointer, 1) } {
            Some(value) => value,
            None => return false,
        };
        let minor = match unsafe { validate_mut_slice_args(minor_pointer, 1) } {
            Some(value) => value,
            None => return false,
        };
        let Some((parsed_major, parsed_minor)) = parse_kernel_release(value) else {
            return false;
        };
        major[0] = parsed_major;
        minor[0] = parsed_minor;
        true
    }))
    .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reset_fast_binder_cache() {
        BINDER_FD_FAST_CACHE.with(|cache| {
            cache.set([BinderFdFastCacheEntry::EMPTY; BINDER_FD_FAST_CACHE_ENTRIES]);
        });
        BINDER_FD_FAST_CACHE_NEXT.with(|next| next.set(0));
    }

    #[test]
    fn classifies_only_supported_binder_devices() {
        assert!(is_binder_device_path(b"/dev/binder"));
        assert!(is_binder_device_path(b"/dev/binderfs/hwbinder"));
        assert!(!is_binder_device_path(b"anon_inode:hwbinder"));
        assert!(!is_binder_device_path(b"/dev/binderfs/binder_logs"));
        assert!(!is_binder_device_path(b"/dev/notbinder"));
    }

    #[test]
    #[cfg(unix)]
    fn refuses_a_regular_file_named_binder() {
        use std::fs::{self, OpenOptions};
        #[cfg(unix)]
        use std::os::unix::io::AsRawFd;
        use std::time::{SystemTime, UNIX_EPOCH};

        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!(
            "cleverestricky-binder-fd-{}-{nonce}",
            std::process::id()
        ));
        fs::create_dir(&directory).unwrap();
        let path = directory.join("binder");
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .unwrap();

        assert!(!is_binder_fd_after_successful_ioctl(file.as_raw_fd(), 1));

        drop(file);
        fs::remove_file(path).unwrap();
        fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn descriptor_paths_are_bounded_and_null_terminated() {
        let mut output = [0xa5u8; MAXIMUM_DESCRIPTOR_PATH_BYTES];
        let length = write_descriptor_path(i32::MAX, &mut output).unwrap();
        assert_eq!(&output[..length], b"/proc/self/fd/2147483647");
        assert_eq!(output[length], 0);
        assert!(write_descriptor_path(-1, &mut output).is_none());
    }

    #[test]
    fn binder_fd_cache_keeps_colliding_descriptors_resident() {
        let mut cache = [BinderFdCacheEntry::EMPTY; BINDER_FD_CACHE_ENTRIES];
        let first = DescriptorIdentity {
            device: 1,
            inode: 100,
            file_type: CHARACTER_DEVICE,
        };
        let second = DescriptorIdentity {
            device: 2,
            inode: 200,
            file_type: CHARACTER_DEVICE,
        };
        let first_descriptor = 3;
        let second_descriptor = first_descriptor + BINDER_FD_CACHE_ENTRIES as i32;

        remember_binder_fd_cache(&mut cache, first_descriptor, first, true);
        remember_binder_fd_cache(&mut cache, second_descriptor, second, true);

        assert_eq!(
            lookup_binder_fd_cache(&cache, first_descriptor, first),
            Some(true)
        );
        assert_eq!(
            lookup_binder_fd_cache(&cache, second_descriptor, second),
            Some(true)
        );
    }

    #[test]
    fn binder_fd_cache_replaces_reused_descriptor_identity() {
        let mut cache = [BinderFdCacheEntry::EMPTY; BINDER_FD_CACHE_ENTRIES];
        let old_identity = DescriptorIdentity {
            device: 1,
            inode: 100,
            file_type: CHARACTER_DEVICE,
        };
        let new_identity = DescriptorIdentity {
            device: 1,
            inode: 101,
            file_type: CHARACTER_DEVICE,
        };

        remember_binder_fd_cache(&mut cache, 9, old_identity, true);
        remember_binder_fd_cache(&mut cache, 9, new_identity, false);

        assert_eq!(lookup_binder_fd_cache(&cache, 9, old_identity), None);
        assert_eq!(lookup_binder_fd_cache(&cache, 9, new_identity), Some(false));
    }

    #[test]
    fn fast_binder_cache_is_bound_to_the_fd_and_exchange_call_site() {
        reset_fast_binder_cache();
        remember_fast_binder_fd(123, 0x4567);
        assert!(!take_fast_binder_fd_hit(124, 0x4567));
        assert!(!take_fast_binder_fd_hit(123, 0x7654));
        for _ in 0..BINDER_FD_FAST_REVALIDATE_HITS {
            assert!(take_fast_binder_fd_hit(123, 0x4567));
        }
        assert!(!take_fast_binder_fd_hit(123, 0x4567));
    }

    #[test]
    fn fast_binder_cache_keeps_alternating_exchange_sites_hot() {
        reset_fast_binder_cache();
        remember_fast_binder_fd(123, 0x1000);
        remember_fast_binder_fd(123, 0x2000);

        for _ in 0..BINDER_FD_FAST_REVALIDATE_HITS {
            assert!(take_fast_binder_fd_hit(123, 0x1000));
            assert!(take_fast_binder_fd_hit(123, 0x2000));
        }
        assert!(!take_fast_binder_fd_hit(123, 0x1000));
        assert!(!take_fast_binder_fd_hit(123, 0x2000));
    }

    #[test]
    fn statx_layout_matches_the_linux_uapi() {
        assert_eq!(mem::size_of::<StatxTimestamp>(), 16);
        assert_eq!(mem::size_of::<Statx>(), 256);
        assert_eq!(mem::offset_of!(Statx, inode), 32);
        assert_eq!(mem::offset_of!(Statx, device_major), 136);
    }

    #[test]
    fn parses_supported_platform_versions() {
        assert_eq!(parse_android_api_level(b"31"), Some(31));
        assert_eq!(parse_android_api_level(b"36"), Some(36));
        assert_eq!(parse_android_api_level(b"37"), Some(37));
        assert_eq!(parse_android_api_level(b"38"), None);
        assert_eq!(parse_kernel_release(b"6.1.75-android14"), Some((6, 1)));
        assert_eq!(parse_kernel_release(b"invalid"), None);
    }
}
