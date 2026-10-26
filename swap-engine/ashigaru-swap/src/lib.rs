//! C interface Ashigaru Desktop loads through JNA.
//!
//! Every call takes and returns UTF-8 JSON. A result is either `{"ok": <value>}` or
//! `{"error": "<message>"}`, and every returned string must be released with `as_free_string`.
//! Panics are caught at this boundary so they surface as errors instead of aborting the JVM.
//!
//! The engine calls back into Ashigaru through the single callback registered with
//! `as_set_callback`; Ashigaru answers each request with `as_respond` (see `bridge`).

mod bridge;
mod engine;
mod events;
mod forward;
mod logging;
mod socks;
mod wallet;

use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, OnceLock};

use anyhow::{Context, Result, anyhow};
use serde_json::{Value, json};

use crate::engine::{Engine, EngineConfig, StartSwap};

fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        logging::install();
        // eigenwallet's binaries do this first thing; without it the Monero RPC client's TLS setup
        // panics. Fails only when the host already installed one, which then serves as well.
        let _ = rustls::crypto::ring::default_provider().install_default();
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("ashigaru-swap")
            .build()
            .expect("tokio runtime to start")
    })
}

static ENGINE: Mutex<Option<Arc<Engine>>> = Mutex::new(None);

fn engine() -> Result<Arc<Engine>> {
    ENGINE
        .lock()
        .map_err(|_| anyhow!("Engine lock poisoned"))?
        .clone()
        .context("Swap engine is not started")
}

fn respond(result: std::thread::Result<Result<Value>>) -> *mut c_char {
    let body = match result {
        Ok(Ok(value)) => json!({ "ok": value }),
        Ok(Err(e)) => json!({ "error": format!("{e:#}") }),
        Err(panic) => json!({ "error": format!("The swap engine hit an internal error: {}", panic_message(&*panic)) }),
    };
    CString::new(body.to_string())
        .expect("JSON never contains NUL")
        .into_raw()
}

/// The text a panic was raised with, for the error Ashigaru shows
pub(crate) fn panic_message(panic: &(dyn std::any::Any + Send)) -> String {
    panic
        .downcast_ref::<&str>()
        .map(|s| s.to_string())
        .or_else(|| panic.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "no details".to_string())
}

fn call(f: impl FnOnce() -> Result<Value>) -> *mut c_char {
    respond(catch_unwind(AssertUnwindSafe(f)))
}

fn read_str(ptr: *const c_char) -> Result<String> {
    if ptr.is_null() {
        return Err(anyhow!("Missing argument"));
    }
    // SAFETY: the caller passes a NUL-terminated string that outlives this call
    let s = unsafe { CStr::from_ptr(ptr) };
    Ok(s.to_str().context("Argument is not UTF-8")?.to_owned())
}

/// Registers Ashigaru's request callback. Must be called before `as_init` when swapping.
#[unsafe(no_mangle)]
pub extern "C" fn as_set_callback(callback: bridge::RequestCallback) -> *mut c_char {
    call(|| {
        bridge::set_callback(callback);
        Ok(Value::Null)
    })
}

/// Ashigaru's answer to a request made through the callback: `{"ok": ...}` or `{"error": ...}`.
#[unsafe(no_mangle)]
pub extern "C" fn as_respond(request_id: u64, answer_json: *const c_char) -> *mut c_char {
    call(|| {
        bridge::respond(request_id, &read_str(answer_json)?)?;
        Ok(Value::Null)
    })
}

/// Starts the engine. Calling it again replaces a running engine: the old one is stopped first.
#[unsafe(no_mangle)]
pub extern "C" fn as_init(config_json: *const c_char) -> *mut c_char {
    call(|| {
        let config: EngineConfig =
            serde_json::from_str(&read_str(config_json)?).context("Bad engine config")?;
        stop_engine()?;
        let engine = runtime().block_on(Engine::start(config))?;
        *ENGINE.lock().map_err(|_| anyhow!("Engine lock poisoned"))? = Some(Arc::new(engine));
        Ok(Value::Null)
    })
}

/// Stops the engine, e.g. when swaps are turned off or before it takes a new identity. Unfinished
/// swaps stop where they are and resume from their database when an engine using it starts again.
#[unsafe(no_mangle)]
pub extern "C" fn as_shutdown() -> *mut c_char {
    call(|| {
        stop_engine()?;
        Ok(Value::Null)
    })
}

fn stop_engine() -> Result<()> {
    let old = ENGINE.lock().map_err(|_| anyhow!("Engine lock poisoned"))?.take();
    if let Some(old) = old {
        // Aborts its tasks once nothing else holds it; a call still running keeps it a moment longer
        drop(old);
    }
    Ok(())
}

/// Quotes discovered so far, as eigenwallet's `QuoteWithAddress` list.
#[unsafe(no_mangle)]
pub extern "C" fn as_list_quotes() -> *mut c_char {
    call(|| {
        let engine = engine()?;
        Ok(serde_json::to_value(runtime().block_on(engine.quotes()))?)
    })
}

/// Starts a swap; returns its id. The swap then runs in the background and reports through the
/// callback.
#[unsafe(no_mangle)]
pub extern "C" fn as_start_swap(request_json: *const c_char) -> *mut c_char {
    call(|| {
        let request: StartSwap =
            serde_json::from_str(&read_str(request_json)?).context("Bad swap request")?;
        let engine = engine()?;
        let swap_id = runtime().block_on(engine.start_swap(request))?;
        Ok(json!(swap_id))
    })
}

/// Resumes every unfinished swap; returns their ids.
#[unsafe(no_mangle)]
pub extern "C" fn as_resume_all() -> *mut c_char {
    call(|| {
        let engine = engine()?;
        Ok(json!(runtime().block_on(engine.resume_all())?))
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn as_list_swaps() -> *mut c_char {
    call(|| {
        let engine = engine()?;
        runtime().block_on(engine.list_swaps())
    })
}

/// Refunds a swap whose cancel timelock has expired. Blocks until the refund is published.
#[unsafe(no_mangle)]
pub extern "C" fn as_cancel_refund(swap_id: *const c_char) -> *mut c_char {
    call(|| {
        let swap_id = read_str(swap_id)?.parse().context("Bad swap id")?;
        let engine = engine()?;
        Ok(json!(runtime().block_on(engine.cancel_and_refund(swap_id))?))
    })
}

/// Asks every Monero node for its block height through Tor: `[{node, ok, height | error}]`. The
/// first node is the engine's own, the rest its witnesses.
#[unsafe(no_mangle)]
pub extern "C" fn as_check_nodes() -> *mut c_char {
    call(|| {
        let engine = engine()?;
        Ok(runtime().block_on(engine.check_nodes()))
    })
}

/// Releases a string returned by any `as_*` call.
#[unsafe(no_mangle)]
pub extern "C" fn as_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        // SAFETY: ptr came from CString::into_raw in `respond` and is freed exactly once
        drop(unsafe { CString::from_raw(ptr) });
    }
}
