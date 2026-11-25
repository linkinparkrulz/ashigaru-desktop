//! Hands eigenwallet's swap events and approval requests to Ashigaru (patch 0002).

use std::time::Duration;

use serde_json::{Value, json};
use swap::cli::api::tauri_bindings::EmbedderSink;

use crate::bridge;

pub struct AshigaruSink;

#[async_trait::async_trait]
impl EmbedderSink for AshigaruSink {
    fn emit(&self, event: &str, payload: Value) {
        bridge::notify("event", &json!({ "event": event, "payload": payload }));
    }

    async fn approve(&self, request: Value, timeout_secs: u64) -> Option<Value> {
        // An error, a timeout or a malformed answer is a rejection
        bridge::ask("approve", request, Duration::from_secs(timeout_secs))
            .await
            .inspect_err(|e| tracing::info!(error = %e, "Approval not given"))
            .ok()
    }
}
