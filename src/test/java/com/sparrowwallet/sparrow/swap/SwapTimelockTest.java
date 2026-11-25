package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SwapTimelockTest {
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private static final String ID = "12e8facc-8dc4-4184-bfa9-38ee87333dc2";

    private static JsonObject change(String timelock) {
        return JsonParser.parseString("{\"channelName\":\"TimelockChange\",\"event\":{\"swap_id\":\"" + ID + "\",\"timelock\":" + timelock + "}}").getAsJsonObject();
    }

    @Test
    void readsTheSwapIdAndBlocksLeftBeforeCancel() {
        JsonObject payload = change("{\"type\":\"None\",\"content\":{\"blocks_left\":13}}");
        assertEquals(ID, SwapTimelock.swapId(payload));
        SwapTimelock timelock = SwapTimelock.read(payload, T0);
        assertEquals(SwapTimelock.Phase.BEFORE_CANCEL, timelock.phase());
        assertEquals(13, timelock.blocksLeft());
        assertFalse(timelock.cancelOpen());
        assertEquals(Duration.ofMinutes(130), timelock.remaining());
        assertEquals(T0, timelock.seen());
    }

    @Test
    void readsEveryPhase() {
        SwapTimelock open = SwapTimelock.read(change("{\"type\":\"Cancel\",\"content\":{\"blocks_left\":100}}"), T0);
        assertEquals(SwapTimelock.Phase.CANCEL_OPEN, open.phase());
        assertEquals(100, open.blocksLeft());
        assertTrue(open.cancelOpen());

        SwapTimelock waiting = SwapTimelock.read(change("{\"type\":\"WaitingForRemainingRefund\",\"content\":{\"blocks_left\":2}}"), T0);
        assertEquals(SwapTimelock.Phase.WAITING_REMAINING_REFUND, waiting.phase());
        assertEquals(2, waiting.blocksLeft());

        assertEquals(SwapTimelock.Phase.REMAINING_REFUND, SwapTimelock.read(change("{\"type\":\"RemainingRefund\"}"), T0).phase());
        SwapTimelock punish = SwapTimelock.read(change("{\"type\":\"Punish\"}"), T0);
        assertEquals(SwapTimelock.Phase.PUNISH, punish.phase());
        assertEquals(0, punish.blocksLeft());
    }

    @Test
    void aNullOrUnknownTimelockMeansNothingToShow() {
        //The engine sends a null timelock once a swap is over
        assertNull(SwapTimelock.read(change("null"), T0));
        assertNull(SwapTimelock.read(change("{\"type\":\"SomethingNew\"}"), T0));
        assertNull(SwapTimelock.read(null, T0));
        assertNull(SwapTimelock.swapId(null));
    }

    @Test
    void theMainnetTimelocksAreAboutFourAndTwentyFourHours() {
        assertEquals("about 5 hours", SwapCopy.cancelAfter());
        assertEquals("about 24 hours", SwapCopy.refundWindow());
    }
}
