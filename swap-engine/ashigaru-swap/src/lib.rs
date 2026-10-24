//! C interface Ashigaru Desktop loads through JNA.
//!
//! Every call takes and returns UTF-8 JSON. A result is either `{"ok": <value>}` or
//! `{"error": "<message>"}`, and every returned string must be released with `as_free_string`.
//! Panics are caught at this boundary so they surface as errors instead of aborting the JVM.

mod engine;
mod socks;
mod wallet;

use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Mutex, OnceLock};

use anyhow::{Context, Result, anyhow};
use serde_json::{Value, json};

use crate::engine::{Engine, EngineConfig};

fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("ashigaru-swap")
            .build()
            .expect("tokio runtime to start")
    })
}

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

fn respond(result: std::thread::Result<Result<Value>>) -> *mut c_char {
    let body = match result {
        Ok(Ok(value)) => json!({ "ok": value }),
        Ok(Err(e)) => json!({ "error": format!("{e:#}") }),
        Err(_) => json!({ "error": "The swap engine hit an internal error" }),
    };
    CString::new(body.to_string())
        .expect("JSON never contains NUL")
        .into_raw()
}

fn read_str(ptr: *const c_char) -> Result<String> {
    if ptr.is_null() {
        return Err(anyhow!("Missing argument"));
    }
    // SAFETY: the caller passes a NUL-terminated string that outlives this call
    let s = unsafe { CStr::from_ptr(ptr) };
    Ok(s.to_str().context("Argument is not UTF-8")?.to_owned())
}

/// Starts the engine. Calling it again replaces a running engine.
#[unsafe(no_mangle)]
pub extern "C" fn as_init(config_json: *const c_char) -> *mut c_char {
    respond(catch_unwind(AssertUnwindSafe(|| {
        let config: EngineConfig =
            serde_json::from_str(&read_str(config_json)?).context("Bad engine config")?;
        let engine = runtime().block_on(Engine::start(config))?;
        *ENGINE.lock().map_err(|_| anyhow!("Engine lock poisoned"))? = Some(engine);
        Ok(Value::Null)
    })))
}

/// Quotes discovered so far, as eigenwallet's `QuoteWithAddress` list.
#[unsafe(no_mangle)]
pub extern "C" fn as_list_quotes() -> *mut c_char {
    respond(catch_unwind(AssertUnwindSafe(|| {
        let guard = ENGINE.lock().map_err(|_| anyhow!("Engine lock poisoned"))?;
        let engine = guard.as_ref().context("Swap engine is not started")?;
        Ok(serde_json::to_value(engine.quotes())?)
    })))
}

/// Releases a string returned by any `as_*` call.
#[unsafe(no_mangle)]
pub extern "C" fn as_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        // SAFETY: ptr came from CString::into_raw in `respond` and is freed exactly once
        drop(unsafe { CString::from_raw(ptr) });
    }
}
