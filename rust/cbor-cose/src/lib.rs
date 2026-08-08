//! Memory-safe parsing and process-local helpers for the native Binder hook.
//!
//! The Android-facing entry point remains C++. Rust is limited to bounded Binder
//! stream parsing used by the Binder ioctl hook.

pub mod binder_parser;
pub mod ffi;
