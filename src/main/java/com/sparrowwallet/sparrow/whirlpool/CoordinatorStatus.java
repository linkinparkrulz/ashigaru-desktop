package com.sparrowwallet.sparrow.whirlpool;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Whether the Whirlpool coordinator can be reached, as far as Ashigaru already knows it. Nothing here asks the coordinator
 * anything: it only reads what mixing and start-up report, so a wallet that is not mixing says nothing and is not noticed.
 *
 * <ul>
 *   <li>A coin that stays in the CONNECTING step for {@link #STUCK_MS} is stuck: while the coordinator is down the client
 *       loops on that step. Any forward progress, or a finished round, shows it is reachable and clears every stuck coin
 *       (the connection is one for the whole client).</li>
 *   <li>Start-up that failed is unreachable until it succeeds, and it retries by itself.</li>
 *   <li>Otherwise Whirlpool being started means it reached the coordinator to do so.</li>
 * </ul>
 */
public final class CoordinatorStatus {
    /** A coin connecting for longer than this means the coordinator cannot be reached */
    public static final long STUCK_MS = 90_000;

    public enum State { UNKNOWN, CONNECTING, OK, UNREACHABLE }

    private static final CoordinatorStatus INSTANCE = new CoordinatorStatus(System::currentTimeMillis);

    /** The one the app uses: fed by Whirlpool's own events, read by the top bar */
    public static CoordinatorStatus get() {
        return INSTANCE;
    }

    private final LongSupplier clock;
    private final Map<Object, Long> connectingSince = new HashMap<>();
    private boolean reached;
    private boolean startupFailing;
    private boolean started;
    private boolean starting;

    public CoordinatorStatus(LongSupplier clock) {
        this.clock = clock;
    }

    /** A coin is trying to connect */
    public synchronized void connecting(Object utxo) {
        connectingSince.putIfAbsent(utxo, clock.getAsLong());
    }

    /** Forward progress, or a finished round: the coordinator answered, for every coin */
    public synchronized void progressed() {
        connectingSince.clear();
        startupFailing = false;
        reached = true;
    }

    /** A coin's mix failed: that says nothing about the coordinator, only that this coin is no longer tracked */
    public synchronized void failed(Object utxo) {
        connectingSince.remove(utxo);
    }

    public synchronized void startupSucceeded() {
        startupFailing = false;
        reached = true;
    }

    /** Start-up could not reach the coordinator; it tries again by itself */
    public synchronized void startupFailed() {
        startupFailing = true;
    }

    /** Whether any wallet has Whirlpool started or starting, as seen when the bar looks */
    public synchronized void whirlpool(boolean started, boolean starting) {
        this.started = started;
        this.starting = starting;
        if(!started && !starting) {
            reached = false;
            connectingSince.clear();
        }
    }

    public synchronized State state() {
        if(startupFailing) {
            return State.UNREACHABLE;
        }
        long now = clock.getAsLong();
        boolean stuck = connectingSince.values().stream().anyMatch(since -> now - since > STUCK_MS);
        if(stuck) {
            return State.UNREACHABLE;
        }
        if(!connectingSince.isEmpty() || starting && !started) {
            return State.CONNECTING;
        }
        return reached || started ? State.OK : State.UNKNOWN;
    }

    /** Why, in a sentence for a tooltip */
    public synchronized String detail() {
        return switch(state()) {
            case UNREACHABLE -> startupFailing ? "Ashigaru could not reach the Whirlpool coordinator when it started, and will try again."
                    : "A coin has been trying to reach the Whirlpool coordinator for over " + STUCK_MS / 1000 + " seconds.";
            case CONNECTING -> "Connecting to the Whirlpool coordinator through Tor…";
            case OK -> "The Whirlpool coordinator answered.";
            case UNKNOWN -> "Nothing is mixing, so Ashigaru has not tried the Whirlpool coordinator.";
        };
    }
}
