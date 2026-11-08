package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.time.Instant;

/**
 * Where a swap stands against its Bitcoin timelocks, as the engine reports it. eigenwallet emits a
 * {@code TimelockChange} event whenever the chain moves a swap's timelocks, carrying how many blocks
 * are left: until the swap can be cancelled, or until the maker could punish a refund that is
 * not published in time.
 */
public record SwapTimelock(Phase phase, int blocksLeft, Instant seen) {
    public enum Phase {
        /** The cancel timelock has not expired yet: the swap cannot be cancelled */
        BEFORE_CANCEL,
        /** The cancel timelock has expired: the swap can be cancelled and refunded until the punish timelock */
        CANCEL_OPEN,
        /** A partial refund is out; the rest, the maker's deposit, can be claimed once this timelock expires */
        WAITING_REMAINING_REFUND,
        /** The deposit's timelock has expired */
        REMAINING_REFUND,
        /** The punish timelock has expired: the maker can take the BTC */
        PUNISH
    }

    /** Bitcoin's target block time; every time shown from a block count is an estimate built on it */
    public static final int MINUTES_PER_BLOCK = 10;
    /** The mainnet cancel timelock, in blocks (about 4 hours) */
    public static final int CANCEL_BLOCKS = 24;
    /** The mainnet punish timelock, in blocks, counted from the cancel confirming (about 24 hours) */
    public static final int PUNISH_BLOCKS = 144;

    /** The swap id of a TimelockChange event payload, or null */
    public static String swapId(JsonObject payload) {
        JsonObject event = event(payload);
        return event != null && event.has("swap_id") && !event.get("swap_id").isJsonNull() ? event.get("swap_id").getAsString() : null;
    }

    /**
     * Reads a TimelockChange payload, {@code {"channelName":"TimelockChange","event":{"swap_id":..,
     * "timelock":{"type":"None","content":{"blocks_left":13}}}}}. Null when the swap has no timelock
     * state (the engine sends a null timelock once a swap is over) or the type is not one we know.
     */
    public static SwapTimelock read(JsonObject payload, Instant now) {
        JsonObject event = event(payload);
        if(event == null || !event.has("timelock") || !event.get("timelock").isJsonObject()) {
            return null;
        }
        JsonObject timelock = event.getAsJsonObject("timelock");
        String type = timelock.has("type") ? timelock.get("type").getAsString() : "";
        int blocksLeft = 0;
        JsonElement content = timelock.get("content");
        if(content != null && content.isJsonObject() && content.getAsJsonObject().has("blocks_left")) {
            blocksLeft = Math.max(0, content.getAsJsonObject().get("blocks_left").getAsInt());
        }
        return switch(type) {
            case "None" -> new SwapTimelock(Phase.BEFORE_CANCEL, blocksLeft, now);
            case "Cancel" -> new SwapTimelock(Phase.CANCEL_OPEN, blocksLeft, now);
            case "WaitingForRemainingRefund" -> new SwapTimelock(Phase.WAITING_REMAINING_REFUND, blocksLeft, now);
            case "RemainingRefund" -> new SwapTimelock(Phase.REMAINING_REFUND, 0, now);
            case "Punish" -> new SwapTimelock(Phase.PUNISH, 0, now);
            default -> null;
        };
    }

    /** True once the swap can be cancelled */
    public boolean cancelOpen() {
        return phase != Phase.BEFORE_CANCEL;
    }

    /** About how long until the next timelock expires; an estimate, a block is about ten minutes */
    public Duration remaining() {
        return Duration.ofMinutes((long)blocksLeft * MINUTES_PER_BLOCK);
    }

    private static JsonObject event(JsonObject payload) {
        if(payload == null) {
            return null;
        }
        //The notification payload is the TauriEvent: {"channelName":"TimelockChange","event":{...}}
        if(payload.has("channelName") && payload.has("event") && payload.get("event").isJsonObject()) {
            return payload.getAsJsonObject("event");
        }
        return payload;
    }
}
