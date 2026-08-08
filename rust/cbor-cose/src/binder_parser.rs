use crate::ffi::{validate_mut_slice_args, validate_slice_args};
use std::mem;

const MAX_TRANSACTIONS_PER_CALL: usize = 1_024;

#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
pub struct RustOffsetCacheView {
    pub target_ptr_offset: usize,
    pub cookie_offset: usize,
    pub code_offset: usize,
    pub flags_offset: usize,
    pub sender_pid_offset: usize,
    pub sender_euid_offset: usize,
    pub data_size_offset: usize,
    pub data_ptr_offset: usize,
    pub transaction_data_size: usize,
    pub transaction_data_secctx_size: usize,
    pub valid: u8,
}

#[derive(Clone, Copy, Debug, Default)]
#[repr(C)]
pub struct RustParsedTransaction {
    pub target_ptr: usize,
    pub cookie: usize,
    pub code: u32,
    pub flags: u32,
    pub sender_pid: i32,
    pub sender_euid: u32,
    pub data_size: u64,
    pub data_buffer: usize,
    pub cmd: u32,
    pub raw_ptr: usize,
    pub raw_size: usize,
    pub valid: u8,
}

fn safe_read<T: Copy>(buffer: &[u8], offset: usize) -> Option<T> {
    let end = offset.checked_add(mem::size_of::<T>())?;
    if end > buffer.len() {
        return None;
    }

    let mut value = mem::MaybeUninit::<T>::uninit();
    // SAFETY: Both ranges are valid for `size_of::<T>()` bytes and do not overlap.
    unsafe {
        std::ptr::copy_nonoverlapping(
            buffer.as_ptr().add(offset),
            value.as_mut_ptr().cast::<u8>(),
            mem::size_of::<T>(),
        );
        Some(value.assume_init())
    }
}

#[inline]
fn ioctl_size(command: u32) -> usize {
    ((command >> IOC_SIZE_SHIFT) & 0x3fff) as usize
}

const IOC_NUMBER_BITS: u32 = 8;
const IOC_TYPE_BITS: u32 = 8;
const IOC_SIZE_BITS: u32 = 14;
const IOC_DIRECTION_BITS: u32 = 2;
const IOC_NUMBER_SHIFT: u32 = 0;
const IOC_TYPE_SHIFT: u32 = IOC_NUMBER_SHIFT + IOC_NUMBER_BITS;
const IOC_SIZE_SHIFT: u32 = IOC_TYPE_SHIFT + IOC_TYPE_BITS;
const IOC_DIRECTION_SHIFT: u32 = IOC_SIZE_SHIFT + IOC_SIZE_BITS;
const IOC_READ: u32 = 2;
const BINDER_TYPE: u32 = b'r' as u32;
const TRANSACTION_NUMBER: u32 = 2;

const fn ioctl_direction(command: u32) -> u32 {
    (command >> IOC_DIRECTION_SHIFT) & ((1 << IOC_DIRECTION_BITS) - 1)
}

const fn ioctl_type(command: u32) -> u32 {
    (command >> IOC_TYPE_SHIFT) & ((1 << IOC_TYPE_BITS) - 1)
}

const fn ioctl_number(command: u32) -> u32 {
    (command >> IOC_NUMBER_SHIFT) & ((1 << IOC_NUMBER_BITS) - 1)
}

fn is_transaction_command(command: u32) -> bool {
    ioctl_direction(command) == IOC_READ
        && ioctl_type(command) == BINDER_TYPE
        && ioctl_number(command) == TRANSACTION_NUMBER
}

/// Parse a Binder driver response stream into a caller-owned output array.
///
/// # Safety
/// All pointers must be valid, correctly aligned, non-overlapping for mutable
/// access, and live for the duration of this call. The C++ caller supplies the
/// ABI offsets only after validating them against live Binder traffic.
#[no_mangle]
pub unsafe extern "C" fn rust_parse_binder_stream(
    buffer_pointer: *const u8,
    consumed: usize,
    buffer_size: usize,
    cache_pointer: *const RustOffsetCacheView,
    output_pointer: *mut RustParsedTransaction,
    output_capacity: usize,
    output_count_pointer: *mut usize,
) -> bool {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // SAFETY: Forwarded from the function contract and bounded above.
        let count_slice = match unsafe { validate_mut_slice_args(output_count_pointer, 1) } {
            Some(value) => value,
            None => return false,
        };
        count_slice[0] = 0;

        if output_capacity > MAX_TRANSACTIONS_PER_CALL || consumed == 0 || consumed > buffer_size {
            return false;
        }

        // SAFETY: Forwarded from the function contract and bounded above.
        let cache_slice = match unsafe { validate_slice_args(cache_pointer, 1) } {
            Some(value) => value,
            _ => return false,
        };
        let cache = &cache_slice[0];
        if cache.valid == 0 {
            return false;
        }
        if cache.transaction_data_size == 0 && cache.transaction_data_secctx_size == 0 {
            return false;
        }

        // SAFETY: Forwarded from the function contract and bounded by `buffer_size`.
        let buffer = match unsafe { validate_slice_args(buffer_pointer, buffer_size) } {
            Some(value) => value,
            None => return false,
        };
        // SAFETY: Forwarded from the function contract and bounded above.
        let output = match unsafe { validate_mut_slice_args(output_pointer, output_capacity) } {
            Some(value) => value,
            None => return false,
        };

        let mut position = 0usize;
        let mut remaining = consumed;
        while remaining >= mem::size_of::<u32>() {
            let command = match safe_read::<u32>(buffer, position) {
                Some(value) => value,
                None => return false,
            };
            position += mem::size_of::<u32>();
            remaining -= mem::size_of::<u32>();

            let payload_size = ioctl_size(command);
            if payload_size > remaining {
                return false;
            }

            let known_transaction_size = payload_size == cache.transaction_data_size
                || payload_size == cache.transaction_data_secctx_size;
            if is_transaction_command(command) && !known_transaction_size {
                return false;
            }
            if is_transaction_command(command) && known_transaction_size {
                if count_slice[0] >= output.len() {
                    return false;
                }
                let transaction = &buffer[position..position + payload_size];
                let parsed = (
                    safe_read::<usize>(transaction, cache.target_ptr_offset),
                    safe_read::<usize>(transaction, cache.cookie_offset),
                    safe_read::<u32>(transaction, cache.code_offset),
                    safe_read::<u32>(transaction, cache.flags_offset),
                    safe_read::<i32>(transaction, cache.sender_pid_offset),
                    safe_read::<u32>(transaction, cache.sender_euid_offset),
                    safe_read::<u64>(transaction, cache.data_size_offset),
                    safe_read::<usize>(transaction, cache.data_ptr_offset),
                );

                if let (
                    Some(target_ptr),
                    Some(cookie),
                    Some(code),
                    Some(flags),
                    Some(sender_pid),
                    Some(sender_euid),
                    Some(data_size),
                    Some(data_buffer),
                ) = parsed
                {
                    output[count_slice[0]] = RustParsedTransaction {
                        target_ptr,
                        cookie,
                        code,
                        flags,
                        sender_pid,
                        sender_euid,
                        data_size,
                        data_buffer,
                        cmd: command,
                        raw_ptr: buffer_pointer as usize + position,
                        raw_size: payload_size,
                        valid: 1,
                    };
                    count_slice[0] += 1;
                }
            }

            position += payload_size;
            remaining -= payload_size;
        }

        remaining == 0 && count_slice[0] > 0
    }))
    .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_at<T: Copy>(buffer: &mut [u8], offset: usize, value: T) {
        let bytes = unsafe {
            std::slice::from_raw_parts((&value as *const T).cast::<u8>(), mem::size_of::<T>())
        };
        buffer[offset..offset + bytes.len()].copy_from_slice(bytes);
    }

    #[test]
    fn parses_a_bounded_transaction() {
        let payload_size = 64usize;
        let command = (IOC_READ << IOC_DIRECTION_SHIFT)
            | (payload_size as u32) << IOC_SIZE_SHIFT
            | (BINDER_TYPE << IOC_TYPE_SHIFT)
            | TRANSACTION_NUMBER;
        let mut input = vec![0u8; mem::size_of::<u32>() + payload_size];
        write_at(&mut input, 0, command);
        write_at(&mut input, 4, 0x1234usize);
        write_at(&mut input, 12, 0x5678usize);
        write_at(&mut input, 20, 42u32);
        write_at(&mut input, 24, 1u32);
        write_at(&mut input, 28, 123i32);
        write_at(&mut input, 32, 10_000u32);
        write_at(&mut input, 40, 16u64);
        write_at(&mut input, 48, 0x9abcusize);

        let cache = RustOffsetCacheView {
            target_ptr_offset: 0,
            cookie_offset: 8,
            code_offset: 16,
            flags_offset: 20,
            sender_pid_offset: 24,
            sender_euid_offset: 28,
            data_size_offset: 36,
            data_ptr_offset: 44,
            transaction_data_size: payload_size,
            transaction_data_secctx_size: 0,
            valid: 1,
        };
        let mut output = [RustParsedTransaction::default(); 1];
        let mut count = 0usize;

        let parsed = unsafe {
            rust_parse_binder_stream(
                input.as_ptr(),
                input.len(),
                input.len(),
                &cache,
                output.as_mut_ptr(),
                output.len(),
                &mut count,
            )
        };

        assert!(parsed);
        assert_eq!(count, 1);
        assert_eq!(output[0].code, 42);
        assert_eq!(output[0].data_buffer, 0x9abc);
    }

    #[test]
    fn rejects_an_unbounded_output_capacity() {
        let mut count = 99usize;
        let parsed = unsafe {
            rust_parse_binder_stream(
                std::ptr::null(),
                1,
                1,
                std::ptr::null(),
                std::ptr::null_mut(),
                MAX_TRANSACTIONS_PER_CALL + 1,
                &mut count,
            )
        };
        assert!(!parsed);
    }
}
