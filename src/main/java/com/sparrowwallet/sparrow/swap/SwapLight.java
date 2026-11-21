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
        FAILED
    }

    public static State state(boolean enabled, boolean running, String status) {
        if(!enabled) {
            return State.HIDDEN;
        }
        if(running) {
            return State.OK;
        }
        String text = status == null ? "" : status;
        if(text.startsWith("Could not start")) {
            return State.FAILED;
        }
        if(text.startsWith("Starting") || text.startsWith("Taking a fresh") || text.contains("Tor")) {
            return State.STARTING;
        }
        return State.HIDDEN;
    }
}
