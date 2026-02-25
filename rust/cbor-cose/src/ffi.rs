//! C FFI bridge for the Zygisk → C++ entry → Rust core pipeline.
//!
//! This module exposes the Rust CBOR/COSE core functions as `extern "C"` symbols
//! that the C++ `binder_interceptor` can call directly. The C++ code remains the
//! entry point (loaded via Zygisk injection), but delegates encoding and
//! cryptographic operations to the memory-safe Rust implementation.
//!
//! # Safety
//!
//! All functions in this module use `unsafe` only at the FFI boundary to convert
//! between C pointers and Rust slices. The inner logic is entirely safe Rust.

use std::borrow::Cow;
use std::panic;
use std::ptr;

use crate::cbor::{self, CborValue};
use crate::cose;

/// Validate pointer and length for slice creation.
///
/// Ensures that:
/// 1. `ptr` is not null (unless `len` is 0, though we prefer valid pointers).
/// 2. `ptr` is properly aligned for `T`.
/// 3. `len` * `size_of::<T>()` does not overflow `isize::MAX`.
///
/// Returns `None` if validation fails, or `Some(slice)` if successful.
unsafe fn validate_slice_args<'a, T>(ptr: *const T, len: usize) -> Option<&'a [T]> {
    if len == 0 {
        return Some(&[]);
    }
    if ptr.is_null() {
        return None;
    }
    #[allow(clippy::manual_is_multiple_of)]
    if (ptr as usize) % std::mem::align_of::<T>() != 0 {
        return None;
    }
    // Check for overflow
    let size_of_t = std::mem::size_of::<T>();
    if size_of_t > 0 {
        let size = len.checked_mul(size_of_t)?;
        if size > isize::MAX as usize {
            return None;
        }
    }
    Some(std::slice::from_raw_parts(ptr, len))
}

/// Result buffer returned to C/C++ callers.
/// The caller must free the buffer with `rust_free_buffer`.
#[repr(C)]
pub struct RustBuffer {
    pub data: *mut u8,
    pub len: usize,
}

impl RustBuffer {
    fn from_vec(v: Vec<u8>) -> Self {
        let mut boxed = v.into_boxed_slice();
        let data = boxed.as_mut_ptr();
        let len = boxed.len();
        std::mem::forget(boxed);
        RustBuffer { data, len }
    }

    fn empty() -> Self {
        RustBuffer {
            data: ptr::null_mut(),
            len: 0,
        }
    }
}

/// Free a buffer previously returned by a Rust FFI function.
///
/// # Safety
/// `buf.data` must have been allocated by Rust (returned from a `rust_*` function)
/// and must not have been freed already.
#[no_mangle]
pub unsafe extern "C" fn rust_free_buffer(buf: RustBuffer) {
    if !buf.data.is_null() && buf.len > 0 {
        let _ = panic::catch_unwind(panic::AssertUnwindSafe(|| unsafe {
            let _ = Box::from_raw(std::ptr::slice_from_raw_parts_mut(buf.data, buf.len));
        }));
    }
}

/// Encode a CBOR unsigned integer.
///
/// # Safety
/// No pointer arguments; always safe.
#[no_mangle]
pub extern "C" fn rust_cbor_encode_unsigned(value: u64) -> RustBuffer {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        RustBuffer::from_vec(cbor::encode(&CborValue::UnsignedInt(value)))
    }))
    .unwrap_or_else(|_| RustBuffer::empty())
}

/// Encode a CBOR signed integer (handles both positive and negative).
///
/// # Safety
/// No pointer arguments; always safe.
#[no_mangle]
pub extern "C" fn rust_cbor_encode_int(value: i64) -> RustBuffer {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        RustBuffer::from_vec(cbor::encode(&CborValue::from_int(value)))
    }))
    .unwrap_or_else(|_| RustBuffer::empty())
}

/// Encode a CBOR byte string.
///
/// # Safety
/// `data` must point to `len` valid bytes, or be null if `len` is 0.
#[no_mangle]
pub unsafe extern "C" fn rust_cbor_encode_bytes(data: *const u8, len: usize) -> RustBuffer {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let bytes = unsafe { validate_slice_args(data, len) }
            .unwrap_or(&[]);
        RustBuffer::from_vec(cbor::encode(&CborValue::ByteString(Cow::Borrowed(bytes))))
    }))
    .unwrap_or_else(|_| RustBuffer::empty())
}

/// Encode a CBOR text string.
///
/// # Safety
/// `data` must point to `len` valid UTF-8 bytes, or be null if `len` is 0.
#[no_mangle]
pub unsafe extern "C" fn rust_cbor_encode_text(data: *const u8, len: usize) -> RustBuffer {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let s = match unsafe { validate_slice_args(data, len) } {
            Some(bytes) => String::from_utf8_lossy(bytes),
            None => Cow::Borrowed(""),
        };
        RustBuffer::from_vec(cbor::encode(&CborValue::TextString(s)))
    }))
    .unwrap_or_else(|_| RustBuffer::empty())
}

/// Generate a COSE_Mac0 MACed public key for RKP.
///
/// # Arguments
/// * `x_ptr`/`x_len` - EC P-256 public key X coordinate.
/// * `y_ptr`/`y_len` - EC P-256 public key Y coordinate.
/// * `hmac_key_ptr`/`hmac_key_len` - HMAC-SHA256 key bytes.
///
/// # Safety
/// All pointers must be valid for their stated lengths, or null if length is 0.
#[no_mangle]
pub unsafe extern "C" fn rust_generate_maced_public_key(
    x_ptr: *const u8,
    x_len: usize,
    y_ptr: *const u8,
    y_len: usize,
    hmac_key_ptr: *const u8,
    hmac_key_len: usize,
) -> RustBuffer {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let x = match unsafe { validate_slice_args(x_ptr, x_len) } {
            Some(s) => s,
            None => return RustBuffer::empty(),
        };
        let y = match unsafe { validate_slice_args(y_ptr, y_len) } {
            Some(s) => s,
            None => return RustBuffer::empty(),
        };
        let hmac_key = match unsafe { validate_slice_args(hmac_key_ptr, hmac_key_len) } {
            Some(s) => s,
            None => return RustBuffer::empty(),
        };

        match cose::generate_maced_public_key(x, y, hmac_key) {
            Ok(buf) => RustBuffer::from_vec(buf),
            Err(_) => RustBuffer::empty(),
        }
    }))
    .unwrap_or_else(|_| RustBuffer::empty())
}

/// Create a DeviceInfo CBOR map for RKP certificate requests.
///
/// All string arguments are UTF-8 byte pointers. Pass null/0 for defaults.
///
/// # Safety
/// String pointers must be valid for their lengths, or null if length is 0.
#[no_mangle]
pub unsafe extern "C" fn rust_create_device_info(
    brand_ptr: *const u8,
    brand_len: usize,
    manufacturer_ptr: *const u8,
    manufacturer_len: usize,
    product_ptr: *const u8,
    product_len: usize,
    model_ptr: *const u8,
    model_len: usize,
    device_ptr: *const u8,
    device_len: usize,
) -> RustBuffer {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let to_str = |ptr: *const u8, len: usize| -> Option<Cow<str>> {
            let bytes = unsafe { validate_slice_args(ptr, len) }?;
            if bytes.is_empty() {
                return None;
            }
            Some(String::from_utf8_lossy(bytes))
        };

        let brand = to_str(brand_ptr, brand_len);
        let manufacturer = to_str(manufacturer_ptr, manufacturer_len);
        let product = to_str(product_ptr, product_len);
        let model = to_str(model_ptr, model_len);
        let device = to_str(device_ptr, device_len);

        let result = cose::create_device_info_cbor(
            brand.as_deref(),
            manufacturer.as_deref(),
            product.as_deref(),
            model.as_deref(),
            device.as_deref(),
        );

        RustBuffer::from_vec(result)
    }))
    .unwrap_or_else(|_| RustBuffer::empty())
}

/// Create a certificate request response for RKP.
///
/// # Arguments
/// * `keys_data`/`keys_offsets` - Concatenated MACed key bytes and per-key offsets.
/// * `challenge_ptr`/`challenge_len` - Server challenge bytes.
/// * `device_info_ptr`/`device_info_len` - CBOR-encoded DeviceInfo.
///
/// # Safety
/// All pointers must be valid for their stated lengths.
#[no_mangle]
pub unsafe extern "C" fn rust_create_certificate_request(
    keys_data_ptr: *const u8,
    keys_data_len: usize,
    keys_offsets_ptr: *const usize,
    keys_count: usize,
    challenge_ptr: *const u8,
    challenge_len: usize,
    device_info_ptr: *const u8,
    device_info_len: usize,
) -> RustBuffer {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        // Parse concatenated keys using offsets
        let mut maced_keys: Vec<Vec<u8>> = Vec::new();

        // Validate keys_data and offsets
        let keys_data_opt = unsafe { validate_slice_args(keys_data_ptr, keys_data_len) };

        // Ensure keys_count + 1 doesn't overflow for offsets slice
        let offsets_opt = if keys_count.checked_add(1).is_some() {
            unsafe { validate_slice_args(keys_offsets_ptr, keys_count + 1) }
        } else {
            None
        };

        if let (Some(keys_data), Some(offsets)) = (keys_data_opt, offsets_opt) {
            for i in 0..keys_count {
                let start = offsets[i];
                let end = offsets[i + 1];
                // Check bounds within keys_data
                if start <= end && end <= keys_data.len() {
                    maced_keys.push(keys_data[start..end].to_vec());
                }
            }
        }

        let challenge = unsafe { validate_slice_args(challenge_ptr, challenge_len) }.unwrap_or(&[]);

        let device_info =
            unsafe { validate_slice_args(device_info_ptr, device_info_len) }.unwrap_or(&[]);

        let result = cose::create_certificate_request_response(&maced_keys, challenge, device_info);
        RustBuffer::from_vec(result)
    }))
    .unwrap_or_else(|_| RustBuffer::empty())
}

/// Generate a spoofed Boot Certificate Chain (BCC).
///
/// Returns a RustBuffer containing the CBOR-encoded BCC array.
/// The caller must free the buffer with `rust_free_buffer`.
#[no_mangle]
pub extern "C" fn rust_generate_spoofed_bcc() -> RustBuffer {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let bcc = crate::bcc::generate_spoofed_bcc();
        RustBuffer::from_vec(bcc)
    }))
    .unwrap_or_else(|_| RustBuffer::empty())
}

// ---- Fingerprint Cache FFI ----

/// Inject fingerprint data into the in-memory cache.
///
/// Parses the provided text (newline-separated fingerprint lines) and
/// stores the results in the thread-safe cache. Returns the number of
/// fingerprints parsed, or 0 on error.
///
/// # Safety
/// `data_ptr` must point to `data_len` valid UTF-8 bytes, or be null.
#[no_mangle]
pub unsafe extern "C" fn rust_fp_inject(data_ptr: *const u8, data_len: usize) -> usize {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let bytes = match unsafe { validate_slice_args(data_ptr, data_len) } {
            Some(b) => b,
            None => return 0,
        };
        let text = match std::str::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => return 0,
        };
        crate::fingerprint::inject_fingerprints(text)
    }))
    .unwrap_or(0)
}

/// Fetch fingerprints from a URL into the cache.
///
/// Pass null/0 for `url_ptr`/`url_len` to use the default URL.
/// Returns the number of fingerprints fetched, or 0 on error.
///
/// # Safety
/// `url_ptr` must point to `url_len` valid UTF-8 bytes, or be null.
#[no_mangle]
pub unsafe extern "C" fn rust_fp_fetch(url_ptr: *const u8, url_len: usize) -> usize {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let url = unsafe { validate_slice_args(url_ptr, url_len) }
            .and_then(|bytes| std::str::from_utf8(bytes).ok());
        crate::fingerprint::fetch_fingerprints(url).unwrap_or(0)
    }))
    .unwrap_or(0)
}

/// Look up a cached fingerprint by device codename.
///
/// Returns the fingerprint string as a RustBuffer, or an empty buffer if
/// not found. The caller must free the result with `rust_free_buffer`.
///
/// # Safety
/// `device_ptr` must point to `device_len` valid UTF-8 bytes.
#[no_mangle]
pub unsafe extern "C" fn rust_fp_get(device_ptr: *const u8, device_len: usize) -> RustBuffer {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let bytes = match unsafe { validate_slice_args(device_ptr, device_len) } {
            Some(b) => b,
            None => return RustBuffer::empty(),
        };
        let device = match std::str::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => return RustBuffer::empty(),
        };
        match crate::fingerprint::get_fingerprint(device) {
            Some(fp) => RustBuffer::from_vec(fp.into_bytes()),
            None => RustBuffer::empty(),
        }
    }))
    .unwrap_or_else(|_| RustBuffer::empty())
}

/// Get the number of fingerprints in the cache.
#[no_mangle]
pub extern "C" fn rust_fp_count() -> usize {
    panic::catch_unwind(panic::AssertUnwindSafe(|| {
        crate::fingerprint::cache_count()
    }))
    .unwrap_or(0)
}

/// Clear the fingerprint cache.
#[no_mangle]
pub extern "C" fn rust_fp_clear() {
    let _ = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        crate::fingerprint::clear_cache();
    }));
}

#[cfg(test)]
#[no_mangle]
pub extern "C" fn rust_test_panic() -> u32 {
    std::panic::catch_unwind(panic::AssertUnwindSafe(|| {
        panic!("Oops");
    }))
    .unwrap_or(42)
}

#[cfg(test)]
mod panic_tests {
    use super::*;

    #[test]
    fn test_ffi_catch_unwind_works() {
        // This validates that our catch_unwind pattern correctly handles panics
        // and returns the fallback value (42) instead of crashing.
        let result = rust_test_panic();
        assert_eq!(result, 42);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ffi_encode_unsigned() {
        let buf = rust_cbor_encode_unsigned(42);
        assert!(!buf.data.is_null());
        assert!(buf.len > 0);
        let bytes = unsafe { std::slice::from_raw_parts(buf.data, buf.len) };
        // 42 = 0x18 0x2a
        assert_eq!(bytes, &[0x18, 0x2a]);
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_ffi_encode_int_negative() {
        let buf = rust_cbor_encode_int(-1);
        let bytes = unsafe { std::slice::from_raw_parts(buf.data, buf.len) };
        assert_eq!(bytes, &[0x20]); // -1
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_ffi_encode_bytes() {
        let data = [0x01, 0x02, 0x03];
        let buf = unsafe { rust_cbor_encode_bytes(data.as_ptr(), data.len()) };
        let bytes = unsafe { std::slice::from_raw_parts(buf.data, buf.len) };
        assert_eq!(bytes, &[0x43, 0x01, 0x02, 0x03]);
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_ffi_encode_bytes_null() {
        let buf = unsafe { rust_cbor_encode_bytes(ptr::null(), 0) };
        let bytes = unsafe { std::slice::from_raw_parts(buf.data, buf.len) };
        assert_eq!(bytes, &[0x40]); // empty byte string
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_ffi_encode_text() {
        let text = b"hello";
        let buf = unsafe { rust_cbor_encode_text(text.as_ptr(), text.len()) };
        let bytes = unsafe { std::slice::from_raw_parts(buf.data, buf.len) };
        assert_eq!(bytes, &[0x65, b'h', b'e', b'l', b'l', b'o']);
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_ffi_generate_maced_public_key() {
        let x = [0x01u8; 32];
        let y = [0x02u8; 32];
        let hmac_key = [0x00u8; 32];
        let buf = unsafe {
            rust_generate_maced_public_key(
                x.as_ptr(),
                x.len(),
                y.as_ptr(),
                y.len(),
                hmac_key.as_ptr(),
                hmac_key.len(),
            )
        };
        assert!(!buf.data.is_null());
        assert!(buf.len > 0);
        let bytes = unsafe { std::slice::from_raw_parts(buf.data, buf.len) };
        assert_eq!(bytes[0], 0x84); // COSE_Mac0 array
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_ffi_generate_maced_public_key_null_x() {
        let y = [0x02u8; 32];
        let hmac_key = [0x00u8; 32];
        let buf = unsafe {
            rust_generate_maced_public_key(
                ptr::null(),
                0,
                y.as_ptr(),
                y.len(),
                hmac_key.as_ptr(),
                hmac_key.len(),
            )
        };
        assert!(buf.data.is_null());
        assert_eq!(buf.len, 0);
    }

    #[test]
    fn test_ffi_create_device_info() {
        let brand = b"google";
        let mfg = b"Google";
        let product = b"husky";
        let model = b"Pixel 8 Pro";
        let device = b"husky";
        let buf = unsafe {
            rust_create_device_info(
                brand.as_ptr(),
                brand.len(),
                mfg.as_ptr(),
                mfg.len(),
                product.as_ptr(),
                product.len(),
                model.as_ptr(),
                model.len(),
                device.as_ptr(),
                device.len(),
            )
        };
        assert!(!buf.data.is_null());
        let bytes = unsafe { std::slice::from_raw_parts(buf.data, buf.len) };
        assert_eq!(bytes[0], 0xAB); // map of 11 items
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_ffi_create_device_info_defaults() {
        let buf = unsafe {
            rust_create_device_info(
                ptr::null(),
                0,
                ptr::null(),
                0,
                ptr::null(),
                0,
                ptr::null(),
                0,
                ptr::null(),
                0,
            )
        };
        assert!(!buf.data.is_null());
        let bytes = unsafe { std::slice::from_raw_parts(buf.data, buf.len) };
        assert_eq!(bytes[0], 0xAB);
        let content = String::from_utf8_lossy(bytes);
        assert!(content.contains("google"));
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_ffi_generate_spoofed_bcc() {
        let buf = rust_generate_spoofed_bcc();
        assert!(!buf.data.is_null());
        assert!(buf.len > 0);
        let bytes = unsafe { std::slice::from_raw_parts(buf.data, buf.len) };
        // Should be a CBOR array (0x80..0x9F)
        assert_eq!(bytes[0] & 0xE0, 0x80);
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_ffi_free_empty_buffer() {
        let buf = RustBuffer::empty();
        // Should not crash
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    fn test_validate_slice_args_null() {
        unsafe {
            assert!(validate_slice_args::<u8>(ptr::null(), 1).is_none());
        }
    }

    #[test]
    fn test_validate_slice_args_len_0() {
        unsafe {
            // Allowed to pass null if len is 0 (returns empty slice)
            assert!(validate_slice_args::<u8>(ptr::null(), 0).is_some());
            assert_eq!(validate_slice_args::<u8>(ptr::null(), 0).unwrap().len(), 0);
        }
    }

    #[test]
    fn test_validate_slice_args_alignment() {
        let val: usize = 0;
        let ptr = &val as *const usize as *const u8;
        // Misaligned pointer: ptr + 1
        unsafe {
            let misaligned = ptr.add(1) as *const usize;
            assert!(validate_slice_args::<usize>(misaligned, 1).is_none());
        }
    }

    #[test]
    fn test_validate_slice_args_overflow() {
        let ptr = &0u8 as *const u8;
        unsafe {
            // usize::MAX length (total size overflow)
            assert!(validate_slice_args::<u8>(ptr, usize::MAX).is_none());
            // isize::MAX + 1 (slice limit exceeded)
            assert!(validate_slice_args::<u8>(ptr, isize::MAX as usize + 1).is_none());
        }
    }

    // ---- Fingerprint FFI tests ----
    use serial_test::serial;

    const SAMPLE_FP: &[u8] = b"google/husky/husky:15/AP41.250105.002/12731906:user/release-keys\n\
          google/shiba/shiba:15/AP41.250105.002/12731906:user/release-keys\n";

    #[test]
    #[serial]
    fn test_ffi_fp_inject_and_get() {
        rust_fp_clear();
        let count = unsafe { rust_fp_inject(SAMPLE_FP.as_ptr(), SAMPLE_FP.len()) };
        assert_eq!(count, 2);
        assert_eq!(rust_fp_count(), 2);

        let device = b"husky";
        let buf = unsafe { rust_fp_get(device.as_ptr(), device.len()) };
        assert!(!buf.data.is_null());
        let fp =
            unsafe { std::str::from_utf8(std::slice::from_raw_parts(buf.data, buf.len)).unwrap() };
        assert!(fp.contains("husky"));
        unsafe { rust_free_buffer(buf) };
    }

    #[test]
    #[serial]
    fn test_ffi_fp_get_missing() {
        rust_fp_clear();
        let device = b"nonexistent";
        let buf = unsafe { rust_fp_get(device.as_ptr(), device.len()) };
        assert!(buf.data.is_null());
        assert_eq!(buf.len, 0);
    }

    #[test]
    #[serial]
    fn test_ffi_fp_inject_null() {
        let count = unsafe { rust_fp_inject(ptr::null(), 0) };
        assert_eq!(count, 0);
    }

    #[test]
    #[serial]
    fn test_ffi_fp_clear() {
        unsafe { rust_fp_inject(SAMPLE_FP.as_ptr(), SAMPLE_FP.len()) };
        assert!(rust_fp_count() > 0);
        rust_fp_clear();
        assert_eq!(rust_fp_count(), 0);
    }
}
