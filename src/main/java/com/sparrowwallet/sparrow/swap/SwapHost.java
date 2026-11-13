package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * Ashigaru's side of a running swap engine: answers the engine's requests for Badbank funds,
 * signatures, fee rates and approvals, and receives its progress events. A request that fails or is
 * never completed is a refusal, so an implementation only completes what it is sure of.
 */
public interface SwapHost {
    /** kinds: wallet_info, fund_lock, sign, max_giveable, fee_rate, approve */
    CompletableFuture<JsonElement> handle(String kind, JsonObject payload);

    /** kind is "event" (a swap progress event) or "swap_stopped" */
    void onNotification(String kind, JsonObject payload);
}
