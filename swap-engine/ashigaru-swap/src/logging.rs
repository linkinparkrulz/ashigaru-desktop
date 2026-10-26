//! Forwards the engine's own log to Ashigaru.
//!
//! eigenwallet reports what it is doing, and above all why a swap stopped, through `tracing`. With
//! no subscriber installed all of that is lost, which leaves a stalled swap unexplained. This layer
//! passes the useful part - INFO and above from the swap's own crates - to Ashigaru as `log`
//! notifications, where it lands in the swap log and Ashigaru's log file. Routine chatter, marked
//! `quiet`, goes to the log file only.

use std::fmt::Write as _;
use std::sync::Once;

use serde_json::json;
use tracing::field::{Field, Visit};
use tracing::{Event, Level, Subscriber};
use tracing_subscriber::layer::{Context, Layer, SubscriberExt};

use crate::bridge;

/// The crates whose messages are worth showing: the swap protocol, its wallets and our own
const TARGETS: &[&str] = &["swap", "monero", "bitcoin_wallet", "electrum", "ashigaru_swap"];

pub fn install() {
    static INSTALL: Once = Once::new();
    INSTALL.call_once(|| {
        let subscriber = tracing_subscriber::registry().with(Forward);
        // Fails only when the host process already installed one; then its log is used instead
        let _ = tracing::subscriber::set_global_default(subscriber);
    });
}

/// Lines that matter only when digging into a problem: wallet2's own log, and the coming and going
/// of maker connections, dozens of which time out over Tor in normal use
pub fn quiet(target: &str, message: &str) -> bool {
    target.starts_with("monero_cpp")
        || ["Connected to peer", "Lost connection to peer", "Outgoing connection error to peer"]
            .iter()
            .any(|prefix| message.starts_with(prefix))
}

pub fn wanted(level: &Level, target: &str) -> bool {
    // In tracing, more verbose levels compare greater: keep INFO, WARN and ERROR
    *level <= Level::INFO && TARGETS.iter().any(|prefix| target.starts_with(prefix))
}

struct Forward;

impl<S: Subscriber> Layer<S> for Forward {
    fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
        let metadata = event.metadata();
        if !wanted(metadata.level(), metadata.target()) {
            return;
        }
        let mut message = Message::default();
        event.record(&mut message);
        bridge::notify(
            "log",
            &json!({
                "level": metadata.level().as_str(),
                "target": metadata.target(),
                "message": message.text(),
                "quiet": quiet(metadata.target(), &message.message),
            }),
        );
    }
}

/// The event's message followed by its other fields as `name=value`
#[derive(Default)]
struct Message {
    message: String,
    fields: String,
}

impl Message {
    fn text(&self) -> String {
        if self.fields.is_empty() {
            self.message.clone()
        } else if self.message.is_empty() {
            self.fields.trim_start().to_string()
        } else {
            format!("{}{}", self.message, self.fields)
        }
    }
}

impl Visit for Message {
    fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
        if field.name() == "message" {
            let _ = write!(self.message, "{value:?}");
        } else {
            let _ = write!(self.fields, " {}={:?}", field.name(), value);
        }
    }

    fn record_str(&mut self, field: &Field, value: &str) {
        if field.name() == "message" {
            self.message.push_str(value);
        } else {
            let _ = write!(self.fields, " {}={}", field.name(), value);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_swap_info_and_above_only() {
        assert!(wanted(&Level::INFO, "swap::protocol::bob::swap"));
        assert!(wanted(&Level::ERROR, "ashigaru_swap::engine"));
        assert!(wanted(&Level::WARN, "monero_wallet::wallets"));
        assert!(!wanted(&Level::DEBUG, "swap::protocol::bob::swap"));
        assert!(!wanted(&Level::INFO, "libp2p_swarm"));
        assert!(!wanted(&Level::INFO, "hyper::client"));
    }

    #[test]
    fn marks_routine_chatter_quiet() {
        assert!(quiet("monero_cpp", "Generating SSL certificate"));
        assert!(quiet("swap::cli::event_loop", "Outgoing connection error to peer"));
        assert!(quiet("swap::cli::event_loop", "Connected to peer"));
        assert!(!quiet("swap::protocol::bob::swap", "Checking Monero node connectivity"));
    }
}
