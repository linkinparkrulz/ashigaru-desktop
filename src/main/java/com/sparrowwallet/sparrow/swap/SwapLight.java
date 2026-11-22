package com.sparrowwallet.sparrow.swap;

/** What the top bar's swap light shows, from what the swap service already says about itself */
public final class SwapLight {
    private SwapLight() {}

    public enum State {
        /** Swaps are off, or this build cannot swap: no light */
        HIDDEN,
        /** The engine is running */
        OK,
        /** Starting, or waiting for Tor */
        STARTING,
        /** It could not start */
        FAILED,
        /** The engine is not running, off or down, while a swap is still unfinished: nothing can complete or refund it until the engine runs again */
        UNFINISHED_DOWN
    }

    public static State state(boolean enabled, boolean running, String status) {
        return state(enabled, running, status, 0);
    }

    /**
     * @param unfinished how many swaps are known to be unfinished. While the engine is not running they hold BTC that nothing is working to
     *                   complete or refund, which is shown whether swaps are on or off, unless the engine is just starting.
     */
    public static State state(boolean enabled, boolean running, String status, int unfinished) {
        if(running && enabled) {
            return State.OK;
        }
        String text = status == null ? "" : status;
        if(unfinished > 0 && !running) {
            boolean coming = enabled && (text.startsWith("Starting") || text.startsWith("Taking a fresh"));
            return coming ? State.STARTING : State.UNFINISHED_DOWN;
        }
        if(!enabled) {
            return State.HIDDEN;
        }
        if(running) {
            return State.OK;
        }
        if(text.startsWith("Could not start")) {
            return State.FAILED;
        }
        if(text.startsWith("Starting") || text.startsWith("Taking a fresh") || text.contains("Tor")) {
            return State.STARTING;
        }
        return State.HIDDEN;
    }
}
