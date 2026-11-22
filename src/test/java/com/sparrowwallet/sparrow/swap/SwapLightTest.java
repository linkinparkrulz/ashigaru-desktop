package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.sparrow.swap.SwapLight.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The top bar's swap light, from the words the swap service already uses for itself */
class SwapLightTest {
    @Test
    void noLightWhenSwapsAreOffOrCannotRun() {
        assertEquals(State.HIDDEN, SwapLight.state(false, false, "Off."));
        assertEquals(State.HIDDEN, SwapLight.state(false, true, "On. Using your own server."));
    }

    @Test
    void runningIsGreen() {
        assertEquals(State.OK, SwapLight.state(true, true, "On. Using your own Monero node."));
    }

    @Test
    void startingAndWaitingForTorAreAmber() {
        assertEquals(State.STARTING, SwapLight.state(true, false, "Starting through Tor…"));
        assertEquals(State.STARTING, SwapLight.state(true, false, "Taking a fresh identity for the next swap…"));
        assertEquals(State.STARTING, SwapLight.state(true, false, "Waiting for Tor. Swaps only connect through Tor."));
        assertEquals(State.STARTING, SwapLight.state(true, false, SwapService.PAUSED_FOR_TOR));
    }

    @Test
    void aFailureToStartIsRed() {
        assertEquals(State.FAILED, SwapLight.state(true, false, "Could not start: the engine did not load"));
    }

    @Test
    void enabledButIdleShowsNothing() {
        assertEquals(State.HIDDEN, SwapLight.state(true, false, "Off."));
        assertEquals(State.HIDDEN, SwapLight.state(true, false, null));
    }

    @Test
    void anUnfinishedSwapWithNoRunningEngineIsRedWhetherSwapsAreOnOrOff() {
        //Swaps off: the swap still holds BTC and nothing is working on it
        assertEquals(State.UNFINISHED_DOWN, SwapLight.state(false, false, "Off.", 1));
        assertEquals(State.UNFINISHED_DOWN, SwapLight.state(true, false, "Off.", 2));
        assertEquals(State.UNFINISHED_DOWN, SwapLight.state(true, false, "Waiting for Tor. Swaps only connect through Tor.", 1));
        assertEquals(State.UNFINISHED_DOWN, SwapLight.state(true, false, "Could not start: the engine did not load", 1));
        //Starting is on its way, so amber; running is green; nothing unfinished changes nothing
        assertEquals(State.STARTING, SwapLight.state(true, false, "Starting through Tor\u2026", 1));
        assertEquals(State.OK, SwapLight.state(true, true, "On. Using 2 public Monero nodes.", 1));
        assertEquals(State.HIDDEN, SwapLight.state(false, false, "Off.", 0));
    }
}
