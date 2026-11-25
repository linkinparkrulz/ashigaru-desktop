package com.sparrowwallet.sparrow.whirlpool;

import com.sparrowwallet.sparrow.whirlpool.CoordinatorStatus.State;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The top bar's coordinator light: what Ashigaru can say about the Whirlpool coordinator without asking it anything */
class CoordinatorStatusTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final CoordinatorStatus status = new CoordinatorStatus(now::get);

    @Test
    void withNothingMixingNothingIsKnown() {
        assertEquals(State.UNKNOWN, status.state());
        assertTrue(status.detail().contains("Nothing is mixing"));
    }

    @Test
    void whirlpoolBeingStartedMeansItReachedTheCoordinator() {
        status.whirlpool(true, false);
        assertEquals(State.OK, status.state());
        status.whirlpool(false, true);
        assertEquals(State.CONNECTING, status.state());
        status.whirlpool(true, true);
        assertEquals(State.OK, status.state(), "started wins over a retry that is starting");
    }

    @Test
    void aCoinConnectingIsConnectingUntilItHasBeenStuckLongEnough() {
        status.whirlpool(true, false);
        status.connecting("coin-1");
        assertEquals(State.CONNECTING, status.state());
        now.addAndGet(CoordinatorStatus.STUCK_MS);
        assertEquals(State.CONNECTING, status.state(), "exactly the limit is not past it");
        now.addAndGet(1);
        assertEquals(State.UNREACHABLE, status.state());
        assertTrue(status.detail().contains("90 seconds"));
    }

    @Test
    void anyForwardProgressClearsEveryStuckCoin() {
        status.connecting("coin-1");
        status.connecting("coin-2");
        now.addAndGet(CoordinatorStatus.STUCK_MS + 1);
        assertEquals(State.UNREACHABLE, status.state());
        status.progressed();
        assertEquals(State.OK, status.state(), "one connection serves all coins, so it is back for all of them");
    }

    @Test
    void aFailedCoinStopsBeingTrackedButProvesNothing() {
        status.connecting("coin-1");
        now.addAndGet(CoordinatorStatus.STUCK_MS + 1);
        status.failed("coin-1");
        assertEquals(State.UNKNOWN, status.state());
    }

    @Test
    void aStartupThatFailedIsUnreachableUntilItSucceeds() {
        status.whirlpool(false, true);
        status.startupFailed();
        assertEquals(State.UNREACHABLE, status.state());
        assertTrue(status.detail().contains("try again"));
        status.startupSucceeded();
        status.whirlpool(true, false);
        assertEquals(State.OK, status.state());
    }

    @Test
    void whenWhirlpoolStopsWhatWasLearntIsForgotten() {
        status.whirlpool(true, false);
        status.connecting("coin-1");
        status.progressed();
        assertEquals(State.OK, status.state());
        status.whirlpool(false, false);
        assertEquals(State.UNKNOWN, status.state());
    }
}
