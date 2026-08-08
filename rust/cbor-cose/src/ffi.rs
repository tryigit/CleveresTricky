//! Shared pointer validation used by the native Binder parser ABI.

pub(crate) unsafe fn validate_slice_args<'a, T>(
    pointer: *const T,
    length: usize,
) -> Option<&'a [T]> {
    if length == 0 {
        return Some(&[]);
    }
    #[allow(clippy::manual_is_multiple_of)]
    if pointer.is_null() || (pointer as usize) % std::mem::align_of::<T>() != 0 {
        return None;
    }

    let byte_length = length.checked_mul(std::mem::size_of::<T>())?;
    if byte_length > isize::MAX as usize || (pointer as usize).checked_add(byte_length).is_none() {
        return None;
    }

    // SAFETY: The caller owns the remaining validity and lifetime requirements.
    Some(unsafe { std::slice::from_raw_parts(pointer, length) })
}

pub(crate) unsafe fn validate_mut_slice_args<'a, T>(
    pointer: *mut T,
    length: usize,
) -> Option<&'a mut [T]> {
    if length == 0 {
        return Some(&mut []);
    }
    #[allow(clippy::manual_is_multiple_of)]
    if pointer.is_null() || (pointer as usize) % std::mem::align_of::<T>() != 0 {
        return None;
    }

    let byte_length = length.checked_mul(std::mem::size_of::<T>())?;
    if byte_length > isize::MAX as usize || (pointer as usize).checked_add(byte_length).is_none() {
        return None;
    }

    // SAFETY: The caller owns the remaining validity, uniqueness, and lifetime requirements.
    Some(unsafe { std::slice::from_raw_parts_mut(pointer, length) })
}
