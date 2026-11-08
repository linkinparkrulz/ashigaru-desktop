package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An unfinished swap that holds the user's BTC and that Ashigaru is not running, so nobody will refund
 * it unless it is resumed or cancelled. Such a swap is shown before any new offer, with a way to its
 * recovery. A swap that stopped before anything was locked needs nothing, and a running swap looks after
 * itself.
 *
 * @param swapId the swap
 * @param title  the stage it stopped at, in the dashboard's words
 */
public record SwapAttention(String swapId, String title) {
    /** The swaps in the engine's list that need attention, newest first as listed */
    public static List<SwapAttention> of(JsonArray swaps, Instant now) {
        List<SwapAttention> needing = new ArrayList<>();
        for(JsonElement element : swaps) {
            JsonObject swap = element.getAsJsonObject();
            if(!swap.has("swapId") || flag(swap, "past") || flag(swap, "complete") || flag(swap, "running")) {
                continue;
            }
            String state = swap.has("state") ? swap.get("state").getAsString() : "";
            SwapProgress.Status status = SwapProgress.fromState(state, false, false, now);
            SwapProgress.Step reached = status.step() == SwapProgress.Step.STOPPED ? status.stoppedAfter() : status.step();
            if(reached == null || reached == SwapProgress.Step.WORKING || reached.ordinal() < SwapProgress.Step.LOCK_PUBLISH.ordinal()
                    || reached == SwapProgress.Step.DONE || reached == SwapProgress.Step.REFUNDED) {
                continue;
            }
            needing.add(new SwapAttention(swap.get("swapId").getAsString(), reached.getTitle()));
        }
        return List.copyOf(needing);
    }

    /** The headline: "1 swap needs attention" */
    public static String headline(List<SwapAttention> needing) {
        return needing.size() == 1 ? "1 swap needs attention" : needing.size() + " swaps need attention";
    }

    /** What to say about the first of them, and the others in a few words */
    public static String text(List<SwapAttention> needing) {
        SwapAttention first = needing.get(0);
        String id = first.swapId().substring(0, Math.min(8, first.swapId().length()));
        String more = needing.size() > 1 ? " and " + (needing.size() - 1) + " more" : "";
        return "Swap " + id + more + " is not running (stopped at “" + first.title() + "”). Your BTC is still locked in " + (needing.size() > 1 ? "them." : "it.");
    }

    private static boolean flag(JsonObject swap, String name) {
        return swap.has(name) && swap.get(name).getAsBoolean();
    }
}
