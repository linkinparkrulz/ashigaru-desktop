package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Where a swap is, in plain words. eigenwallet reports its progress as typed events (the GUI's
 * {@code TauriSwapProgressEvent}); this turns each into one of a fixed set of steps, with what is
 * happening, what comes next and how long the step normally takes.
 */
public final class SwapProgress {
    public enum Step {
        NODES("Checking the Monero nodes", "Making sure every Monero node can be reached through Tor.",
                "Next, the terms are agreed with the maker.", Duration.ofMinutes(2)),
        TERMS("Agreeing terms with the maker", "Exchanging the swap's keys and signatures with the maker over Tor.",
                "Next, Ashigaru asks you to sign the lock, then to approve the final terms.", Duration.ofMinutes(5)),
        APPROVAL("Waiting for you", "Ashigaru is asking you to sign the lock and approve the final terms.",
                "Nothing is locked until you approve.", Duration.ofMinutes(3)),
        LOCK_PUBLISH("Publishing your BTC lock", "Sending your signed lock transaction to the Bitcoin network.",
                "Next, the lock confirms on the Bitcoin chain.", Duration.ofMinutes(5)),
        LOCK_CONFIRM("Your BTC lock is confirming", "Waiting for the lock transaction to confirm.",
                "Once it does, the maker locks the XMR.", Duration.ofMinutes(60)),
        MAKER_LOCK("Waiting for the maker's XMR", "The maker is locking the XMR; checking it pays the agreed amount.",
                "If the maker never locks, the swap is cancelled and refunded after the timelock.", Duration.ofMinutes(30)),
        MAKER_CONFIRM("The maker's XMR is confirming", "Every Monero node must see the maker's lock fully confirmed.",
                "Then Ashigaru releases its side so the maker can take the BTC.", Duration.ofMinutes(40)),
        RELEASE("Releasing your side", "Handing the maker the key to the BTC, now that the XMR is safely locked.",
                "The maker takes the BTC, which reveals the key to the XMR.", Duration.ofMinutes(30)),
        CLAIM("Claiming your XMR", "Sending the XMR to your address.", "Done once it is published.", Duration.ofMinutes(30)),
        DONE("Done: XMR sent to your address", "The swap is complete.", "", null),
        REFUND_WAIT("Waiting to cancel", "The swap did not complete. It can be cancelled once the timelock expires, about 4 hours after the lock.",
                "Then the BTC is refunded to Badbank.", null),
        REFUNDING("Refunding to Badbank", "Cancelling the swap and refunding the BTC to Badbank.",
                "Done once the refund confirms.", Duration.ofMinutes(60)),
        REFUNDED("Refunded to Badbank", "The BTC came back to Badbank.", "", null),
        STOPPED("Stopped", "The swap stopped.", "", null),
        WORKING("Working", "The swap is progressing.", "", null);

        private final String title;
        private final String doing;
        private final String next;
        private final Duration usual;

        Step(String title, String doing, String next, Duration usual) {
            this.title = title;
            this.doing = doing;
            this.next = next;
            this.usual = usual;
        }

        public String getTitle() {
            return title;
        }

        public String getDoing() {
            return doing;
        }

        public String getNext() {
            return next;
        }

        /** How long this step normally takes, or null when it has no usual length */
        public Duration getUsual() {
            return usual;
        }
    }

    /** The happy path, in order, for the step list */
    public static final List<Step> MAIN_STEPS = List.of(Step.NODES, Step.TERMS, Step.APPROVAL, Step.LOCK_PUBLISH,
            Step.LOCK_CONFIRM, Step.MAKER_LOCK, Step.MAKER_CONFIRM, Step.RELEASE, Step.CLAIM, Step.DONE);

    /** A swap's latest step, what is known about it, and since when */
    public record Status(Step step, String detail, Instant since, boolean finished) {
        public Status withDetail(String newDetail) {
            return new Status(step, newDetail, since, finished);
        }

        /** True when the step has gone on well past its usual length */
        public boolean isSlow(Instant now) {
            return !finished && step.getUsual() != null && Duration.between(since, now).compareTo(step.getUsual()) > 0;
        }
    }

    private SwapProgress() {}

    /** The swap id of a SwapProgress event payload, or null */
    public static String swapId(JsonObject payload) {
        JsonObject wrapper = wrapper(payload);
        return wrapper != null && wrapper.has("swap_id") ? wrapper.get("swap_id").getAsString() : null;
    }

    /**
     * Reads a SwapProgress event payload, {@code {"channelName":"SwapProgress","event":{"swap_id":..,
     * "event":{"type":..,"content":..}}}}. Keeps {@code since} when the step has not changed.
     */
    public static Status read(JsonObject payload, Status previous, Instant now) {
        JsonObject wrapper = wrapper(payload);
        JsonObject event = wrapper == null || !wrapper.has("event") ? null : wrapper.getAsJsonObject("event");
        String type = event == null || !event.has("type") ? "" : event.get("type").getAsString();
        JsonObject content = event != null && event.has("content") && event.get("content").isJsonObject() ? event.getAsJsonObject("content") : new JsonObject();

        Step step;
        String detail = null;
        boolean finished = false;
        switch(type) {
            case "Resuming", "CheckingMoneroNodeConnectivity" -> step = Step.NODES;
            case "ReceivedQuote", "SwapSetupInflight", "RetrievingMoneroBlockheight" -> step = Step.TERMS;
            case "BtcLockPublishInflight" -> step = Step.LOCK_PUBLISH;
            case "BtcLockTxInMempool" -> {
                step = Step.LOCK_CONFIRM;
                detail = confirmations(content, "btc_lock_confirmations", null) + " · " + txid(content, "btc_lock_txid");
            }
            case "VerifyingXmrLockTx" -> {
                step = Step.MAKER_LOCK;
                detail = "Maker's lock " + txid(content, "xmr_lock_txid") + " seen, checking it on every node";
            }
            case "XmrLockTxInMempool", "WaitingForXmrConfirmationsBeforeRedeem" -> {
                step = type.equals("XmrLockTxInMempool") ? Step.MAKER_CONFIRM : Step.CLAIM;
                detail = confirmations(content, "xmr_lock_tx_confirmations", "xmr_lock_tx_target_confirmations");
            }
            case "InflightEncSig", "EncryptedSignatureSent" -> step = Step.RELEASE;
            case "ConstructingMoneroRedeem", "PublishingMoneroRedeem", "XmrRedeemPublished" -> step = Step.CLAIM;
            case "XmrRedeemed" -> {
                step = Step.DONE;
                finished = true;
                detail = "XMR transaction " + firstTxid(content, "xmr_redeem_txids");
            }
            case "WaitingForCancelTimelockExpiration", "CancelTimelockExpired" -> step = Step.REFUND_WAIT;
            case "BtcCancelPublished", "BtcCancelled", "BtcRefundPublished", "BtcEarlyRefundPublished",
                 "BtcPartialRefundPublished", "WaitingForEarnestDepositTimelockExpiration", "BtcWithholdPublished",
                 "BtcMercyPublished" -> step = Step.REFUNDING;
            case "BtcRefunded", "BtcEarlyRefunded" -> {
                step = Step.REFUNDED;
                finished = true;
                detail = "Refund " + txid(content, content.has("btc_refund_txid") ? "btc_refund_txid" : "btc_early_refund_txid");
            }
            case "BtcPartiallyRefunded", "BtcWithheld", "BtcAmnestyReceived", "BtcMercyConfirmed" -> {
                step = Step.REFUNDED;
                finished = type.equals("BtcAmnestyReceived") || type.equals("BtcMercyConfirmed");
                detail = refundDetail(type, content);
            }
            default -> {
                step = previous != null ? previous.step() : Step.WORKING;
                detail = type.isEmpty() ? null : type;
            }
        }

        Instant since = previous != null && previous.step() == step ? previous.since() : now;
        return new Status(step, detail, since, finished);
    }

    /** The status of a swap that stopped, from its swap_stopped notification */
    public static Status stopped(JsonObject payload, Status previous, Instant now) {
        if(payload.has("error")) {
            return new Status(Step.STOPPED, stopReason(payload.get("error").getAsString(), previous), now, true);
        }
        if(previous != null && previous.finished()) {
            return previous;
        }
        String state = payload.has("state") ? payload.get("state").getAsString() : "";
        return new Status(Step.STOPPED, state.isEmpty() ? "The swap stopped." : "Stopped at: " + state, now, true);
    }

    /** The engine's error, with what it means for the user's coin */
    static String stopReason(String error, Status previous) {
        boolean beforeLock = previous == null || previous.step().ordinal() <= Step.APPROVAL.ordinal();
        String consequence = beforeLock
                ? "Nothing was locked: the coin is still in Badbank and can be swapped again."
                : "Your BTC is locked. It is refunded to Badbank after the timelock; reopen Ashigaru with swaps on to let it continue.";
        return error + "\n\n" + consequence;
    }

    private static JsonObject wrapper(JsonObject payload) {
        if(payload == null) {
            return null;
        }
        //The notification payload is the TauriEvent: {"channelName":"SwapProgress","event":{...}}
        if(payload.has("channelName") && payload.has("event") && payload.get("event").isJsonObject()) {
            return payload.getAsJsonObject("event");
        }
        return payload;
    }

    private static String confirmations(JsonObject content, String field, String targetField) {
        JsonElement value = content.get(field);
        String current = value == null || value.isJsonNull() ? "0" : value.getAsString();
        if(targetField != null && content.has(targetField)) {
            return current + " of " + content.get(targetField).getAsString() + " confirmations";
        }
        return current + " confirmation" + ("1".equals(current) ? "" : "s");
    }

    private static String txid(JsonObject content, String field) {
        JsonElement value = content.get(field);
        if(value == null || value.isJsonNull()) {
            return "";
        }
        String txid = value.isJsonPrimitive() ? value.getAsString() : value.toString();
        return txid.length() > 16 ? txid.substring(0, 8) + "…" + txid.substring(txid.length() - 8) : txid;
    }

    private static String firstTxid(JsonObject content, String field) {
        JsonElement value = content.get(field);
        if(value instanceof JsonArray array && !array.isEmpty()) {
            JsonObject holder = new JsonObject();
            holder.add("txid", array.get(0));
            return txid(holder, "txid");
        }
        return "";
    }

    private static String refundDetail(String type, JsonObject content) {
        long lock = content.has("btc_lock_amount") ? content.get("btc_lock_amount").getAsLong() : 0;
        long amnesty = content.has("btc_amnesty_amount") ? content.get("btc_amnesty_amount").getAsLong() : 0;
        String amounts = String.format(Locale.ROOT, "%s BTC refunded", BadbankSwapHost.btc(lock - amnesty));
        return switch(type) {
            case "BtcPartiallyRefunded" -> amounts + "; the maker holds " + BadbankSwapHost.btc(amnesty) + " BTC as its deposit for now";
            case "BtcWithheld" -> amounts + "; the maker kept its " + BadbankSwapHost.btc(amnesty) + " BTC deposit";
            default -> "All of the deposit came back too";
        };
    }
}
