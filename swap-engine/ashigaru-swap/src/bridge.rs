//! Calls from the engine into Ashigaru.
//!
//! Ashigaru registers one C callback. The engine calls it with a request id, a kind and a JSON
//! payload; Ashigaru handles it on its own thread and answers later through `as_respond` with the
//! same id. Nothing blocks on the Java side, and no memory crosses the boundary in either direction
//! beyond the call itself. A request that is not answered in time fails, and every caller treats a
//! failure as "no": no funding, no signature, no approval.

use std::collections::HashMap;
use std::ffi::{CString, c_char};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use serde_json::Value;
use tokio::sync::oneshot;

/// `request_id`, `kind`, `payload_json`. Both strings are only valid for the duration of the call.
pub type RequestCallback = extern "C" fn(u64, *const c_char, *const c_char);

struct Bridge {
    callback: Mutex<Option<RequestCallback>>,
    pending: Mutex<HashMap<u64, oneshot::Sender<Result<Value, String>>>>,
    next_id: AtomicU64,
}

fn bridge() -> &'static Bridge {
    static BRIDGE: OnceLock<Bridge> = OnceLock::new();
    BRIDGE.get_or_init(|| Bridge {
        callback: Mutex::new(None),
        pending: Mutex::new(HashMap::new()),
        next_id: AtomicU64::new(1),
    })
}

pub fn set_callback(callback: RequestCallback) {
    *bridge().callback.lock().expect("bridge lock") = Some(callback);
}

/// Fire-and-forget notification, e.g. a swap progress event.
pub fn notify(kind: &str, payload: &Value) {
    let Some(callback) = *bridge().callback.lock().expect("bridge lock") else {
        return;
    };
    let (Ok(kind), Ok(payload)) = (CString::new(kind), CString::new(payload.to_string())) else {
        return;
    };
    callback(0, kind.as_ptr(), payload.as_ptr());
}

/// Asks Ashigaru for something and waits for its answer.
pub async fn ask(kind: &str, payload: Value, timeout: Duration) -> Result<Value> {
    let callback = (*bridge().callback.lock().expect("bridge lock"))
        .context("Ashigaru has not registered its callback")?;
    let id = bridge().next_id.fetch_add(1, Ordering::Relaxed);
    let (sender, receiver) = oneshot::channel();
    bridge().pending.lock().expect("bridge lock").insert(id, sender);

    let kind_c = CString::new(kind)?;
    let payload_c = CString::new(payload.to_string())?;
    // The callback only queues the request on the Java side, but it still crosses into the JVM, so
    // keep it off the async worker threads.
    tokio::task::spawn_blocking(move || callback(id, kind_c.as_ptr(), payload_c.as_ptr()))
        .await
        .context("Callback into Ashigaru failed")?;

    let answer = tokio::time::timeout(timeout, receiver).await;
    bridge().pending.lock().expect("bridge lock").remove(&id);
    match answer {
        Ok(Ok(Ok(value))) => Ok(value),
        Ok(Ok(Err(message))) => Err(anyhow!("Ashigaru declined {kind}: {message}")),
        Ok(Err(_)) => bail!("Ashigaru dropped the {kind} request"),
        Err(_) => bail!("Ashigaru did not answer the {kind} request in time"),
    }
}

/// Delivers Ashigaru's answer: `{"ok": value}` or `{"error": message}`.
pub fn respond(id: u64, answer: &str) -> Result<()> {
    let body: Value = serde_json::from_str(answer).context("Answer is not JSON")?;
    let result = match (body.get("ok"), body.get("error")) {
        (_, Some(error)) => Err(error.as_str().unwrap_or("declined").to_string()),
        (Some(value), None) => Ok(value.clone()),
        (None, None) => Err("empty answer".to_string()),
    };
    let sender = bridge()
        .pending
        .lock()
        .expect("bridge lock")
        .remove(&id)
        .context("No such request, or it already timed out")?;
    let _ = sender.send(result);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CStr;
    use std::sync::mpsc;

    static REQUESTS: Mutex<Option<mpsc::Sender<(u64, String, String)>>> = Mutex::new(None);

    extern "C" fn record(id: u64, kind: *const c_char, payload: *const c_char) {
        // SAFETY: the bridge passes valid NUL-terminated strings for the duration of the call
        let (kind, payload) = unsafe {
            (
                CStr::from_ptr(kind).to_str().unwrap().to_owned(),
                CStr::from_ptr(payload).to_str().unwrap().to_owned(),
            )
        };
        if let Some(sender) = REQUESTS.lock().unwrap().as_ref() {
            sender.send((id, kind, payload)).unwrap();
        }
    }

    /// One test, so the process-wide callback is not shared between tests running in parallel
    #[tokio::test(flavor = "multi_thread")]
    async fn requests_are_answered_declined_or_time_out() {
        let (sender, receiver) = mpsc::channel();
        *REQUESTS.lock().unwrap() = Some(sender);
        set_callback(record);

        // Answered from another thread, as Ashigaru does
        let answerer = std::thread::spawn(move || {
            let (id, kind, payload) = receiver.recv().unwrap();
            assert_eq!(kind, "fee_rate");
            assert_eq!(payload, "{\"x\":1}");
            respond(id, r#"{"ok":{"satPerVb":7}}"#).unwrap();

            let (id, kind, _) = receiver.recv().unwrap();
            assert_eq!(kind, "sign");
            respond(id, r#"{"error":"user cancelled"}"#).unwrap();

            // The third request is never answered
            let (id, _, _) = receiver.recv().unwrap();
            id
        });

        let answer = ask("fee_rate", serde_json::json!({"x": 1}), Duration::from_secs(5)).await.unwrap();
        assert_eq!(answer["satPerVb"], 7);

        let declined = ask("sign", serde_json::json!({}), Duration::from_secs(5)).await;
        assert!(declined.unwrap_err().to_string().contains("user cancelled"));

        let unanswered = ask("approve", serde_json::json!({}), Duration::from_millis(200)).await;
        assert!(unanswered.unwrap_err().to_string().contains("in time"));

        // A late answer after the timeout is refused rather than delivered to anyone
        let late_id = answerer.join().unwrap();
        assert!(respond(late_id, r#"{"ok":true}"#).is_err());
    }
}
