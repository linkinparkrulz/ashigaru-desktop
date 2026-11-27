package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The fee rate a swap is set up at: next block, as eigenwallet's taker estimates it, never below the default or minimum relay */
class SwapFeeRateTest {
    /** The window the engine accepts a maker's proposal in, in sat/kvB: half to three times our estimate (swap_fee_limits) */
    private static boolean engineAccepts(double ourSatPerVb, long proposedSatPerKvb) {
        long ours = (long)Math.ceil(ourSatPerVb) * 1000;
        long min = Math.max(1000, ours / 2 + ours % 2);
        return proposedSatPerKvb >= min && proposedSatPerKvb <= ours * 3;
    }

    @Test
    void theNextBlockRateIsUsedWhenItIsHigher() {
        assertEquals(6.2, BadbankSwapHost.swapFeeRate(Map.of(1, 6.2, 4, 1.0), 1.0, 1.0));
    }

    @Test
    void theDefaultIsTheFloorWithoutANextBlockRate() {
        assertEquals(3.0, BadbankSwapHost.swapFeeRate(Map.of(4, 3.0), 3.0, 1.0));
        assertEquals(3.0, BadbankSwapHost.swapFeeRate(null, 3.0, 1.0));
    }

    @Test
    void neverBelowTheMinimumRelayFee() {
        assertEquals(1.0, BadbankSwapHost.swapFeeRate(Map.of(1, 0.5), 0.5, 1.0));
        assertEquals(1.0, BadbankSwapHost.swapFeeRate(null, null, 1.0));
    }

    @Test
    void aMakersMarketRateIsNoLongerRefused() {
        //The rejection seen in testing: the 4-block estimate read 1 sat/vB, so 6224 sat/kvB fell outside 1000..=3000
        assertFalse(engineAccepts(BadbankSwapHost.swapFeeRate(Map.of(4, 1.0), 1.0, 1.0), 6224));
        //With the next-block rate the market is paying, the same proposal is inside the window
        assertTrue(engineAccepts(BadbankSwapHost.swapFeeRate(Map.of(1, 6.0, 4, 1.0), 1.0, 1.0), 6224));
        //and an absurd one is still refused
        assertFalse(engineAccepts(BadbankSwapHost.swapFeeRate(Map.of(1, 6.0), 1.0, 1.0), 60_000));
    }
}
