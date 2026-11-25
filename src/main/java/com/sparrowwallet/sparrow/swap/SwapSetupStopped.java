package com.sparrowwallet.sparrow.swap;

/**
 * A swap that did not start because of what the person did, not because something failed: the wrong password, or
 * cancelling the password window. They have already been told (or chose it themselves), so the Swaps screen shows
 * no error banner of its own for it.
 */
public final class SwapSetupStopped extends RuntimeException {
    public enum Reason { WRONG_PASSWORD, CANCELLED }

    private final Reason reason;

    public SwapSetupStopped(Reason reason, Throwable cause) {
        super(reason == Reason.WRONG_PASSWORD ? "The wallet password was wrong" : "Cancelled", cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
