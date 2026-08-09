//! Rust owned injector orchestration.
//!
//! The injector executable is entirely Rust. It owns argument parsing, logging,
//! symbol resolution, process tracing, architecture register operations,
//! ancillary parsing, resource cleanup, and every injection state transition.

#[cfg(target_os = "android")]
use std::panic::catch_unwind;

#[cfg(any(target_os = "android", test))]
mod abi;
#[cfg(target_os = "android")]
mod engine;
#[cfg(any(target_os = "android", test))]
mod health;
#[cfg(target_os = "android")]
mod logging;
#[cfg(target_os = "android")]
mod process_memory;
#[cfg(target_os = "android")]
mod ptrace_session;
#[cfg(target_os = "android")]
mod symbol_resolver;

#[cfg(target_os = "android")]
pub fn run_cli() -> i32 {
    let arguments: Vec<std::ffi::OsString> = std::env::args_os().collect();
    health::record(&arguments, health::NativeRuntimeState::Starting);
    match catch_unwind(std::panic::AssertUnwindSafe(|| engine::run(&arguments))) {
        Ok(code) => {
            let state = if code == 0 {
                health::NativeRuntimeState::Active
            } else {
                health::NativeRuntimeState::Failed
            };
            health::record(&arguments, state);
            code
        }
        Err(_) => {
            health::record(&arguments, health::NativeRuntimeState::Failed);
            1
        }
    }
}
