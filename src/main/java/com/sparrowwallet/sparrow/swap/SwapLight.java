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
        UNFINISHED_DOWN,
        /**
         * Swaps recorded under an earlier identity are unfinished. The engine only works on its own identity's swaps, so nothing completes or refunds
         * these even while it runs: the identity they belong to has to be put back in charge (see SwapIdentity).
         */
        OLDER_IDENTITY_UNFINISHED
    }

    public static State state(boolean enabled, boolean running, String status) {
        return state(enabled, running, status, 0);
    }

    public static State state(boolean enabled, boolean running, String status, int unfinished) {
        return state(enabled, running, status, unfinished, 0);
    }

    /**
     * @param unfinished      how many swaps are known to be unfinished, or a negative number when that is not known (swap data is there but the
     *                        list of unfinished swaps is not). While the engine is not running they hold BTC that nothing is working to complete
     *                        or refund, which is shown whether swaps are on or off, unless the engine is just starting. Not known counts as some.
     * @param olderUnfinished how many of them were recorded under an earlier identity: nothing works on those even while the engine runs
     */
    public static State state(boolean enabled, boolean running, String status, int unfinished, int olderUnfinished) {
        if(olderUnfinished > 0 && running) {
            return State.OLDER_IDENTITY_UNFINISHED;
        }
        if(running && enabled) {
            return State.OK;
        }
        String text = status == null ? "" : status;
        if(unfinished != 0 && !running) {
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
