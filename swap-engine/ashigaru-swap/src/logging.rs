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
use tracing::span::{Attributes, Id};
use tracing::{Event, Level, Subscriber};
use tracing_subscriber::layer::{Context, Layer, SubscriberExt};
use tracing_subscriber::registry::LookupSpan;

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

/// Lines that matter only when digging into a problem: wallet2's own log, the coming and going of
/// maker connections, dozens of which time out over Tor in normal use, and the fee floor note the
/// Bitcoin wallet repeats for every estimate while fees are low, and rendezvous retries
pub fn quiet(target: &str, message: &str) -> bool {
    target.starts_with("monero_cpp")
        || [
            "Connected to peer",
            "Lost connection to peer",
            "Outgoing connection error to peer",
            "Recommended fee rate is below the absolute minimum relay fee",
            "Failed to discover peers at rendezvous node",
        ]
            .iter()
            .any(|prefix| message.starts_with(prefix))
}

pub fn wanted(level: &Level, target: &str) -> bool {
    // In tracing, more verbose levels compare greater: keep INFO, WARN and ERROR
    *level <= Level::INFO && TARGETS.iter().any(|prefix| target.starts_with(prefix))
}

struct Forward;

/// A span's fields as text. eigenwallet names the maker in spans (`swap_setup{peer=..}`), not in
/// the events inside them, so they are kept to say which maker an error came from.
struct SpanFields(String);

impl<S: Subscriber + for<'a> LookupSpan<'a>> Layer<S> for Forward {
    fn on_new_span(&self, attrs: &Attributes<'_>, id: &Id, ctx: Context<'_, S>) {
        record_span_fields(attrs, id, &ctx);
    }

    fn on_event(&self, event: &Event<'_>, ctx: Context<'_, S>) {
        let metadata = event.metadata();
        if !wanted(metadata.level(), metadata.target()) {
            return;
        }
        let message = compose(event, &ctx);
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

/// Keeps a new span's fields, for `compose` to add to the events inside it
fn record_span_fields<S: Subscriber + for<'a> LookupSpan<'a>>(attrs: &Attributes<'_>, id: &Id, ctx: &Context<'_, S>) {
    let mut fields = Message::default();
    attrs.record(&mut fields);
    if let Some(span) = ctx.span(id) {
        span.extensions_mut().insert(SpanFields(fields.text()));
    }
}

/// The event's message and fields, then the fields of the spans it happened in
fn compose<S: Subscriber + for<'a> LookupSpan<'a>>(event: &Event<'_>, ctx: &Context<'_, S>) -> Message {
    let mut message = Message::default();
    event.record(&mut message);
    if let Some(scope) = ctx.event_scope(event) {
        for span in scope.from_root() {
            if let Some(SpanFields(fields)) = span.extensions().get::<SpanFields>()
                && !fields.is_empty()
            {
                let _ = write!(message.fields, " {fields}");
            }
        }
    }
    message
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
    fn span_fields_name_the_maker() {
        use std::sync::{Arc, Mutex};
        use tracing_subscriber::layer::SubscriberExt;

        // Does what Forward does, but collects the lines instead of sending them to Ashigaru:
        // bridge's own tests listen on the callback at the same time
        struct Capture(Arc<Mutex<Vec<String>>>);
        impl<S: Subscriber + for<'a> LookupSpan<'a>> Layer<S> for Capture {
            fn on_new_span(&self, attrs: &Attributes<'_>, id: &Id, ctx: Context<'_, S>) {
                record_span_fields(attrs, id, &ctx);
            }

            fn on_event(&self, event: &Event<'_>, ctx: Context<'_, S>) {
                self.0.lock().unwrap().push(compose(event, &ctx).text());
            }
        }

        let lines = Arc::new(Mutex::new(Vec::new()));
        let subscriber = tracing_subscriber::registry().with(Capture(lines.clone()));
        tracing::subscriber::with_default(subscriber, || {
            let span = tracing::info_span!("swap_setup", peer = "12D3KooWmaker");
            let _entered = span.enter();
            tracing::warn!(error = "closed", "Swap setup failed");
        });
        assert_eq!(lines.lock().unwrap().as_slice(), ["Swap setup failed error=closed peer=12D3KooWmaker"]);
    }

    #[test]
    fn marks_routine_chatter_quiet() {
        assert!(quiet("monero_cpp", "Generating SSL certificate"));
        assert!(quiet("swap::cli::event_loop", "Outgoing connection error to peer"));
        assert!(quiet("swap::cli::event_loop", "Connected to peer"));
        assert!(quiet("swap_p2p::protocols::rendezvous", "Failed to discover peers at rendezvous node, scheduling retry"));
        assert!(quiet("bitcoin_wallet", "Recommended fee rate is below the absolute minimum relay fee. Falling back to: 1000 sats"));
        assert!(!quiet("swap::protocol::bob::swap", "Checking Monero node connectivity"));
    }
}
