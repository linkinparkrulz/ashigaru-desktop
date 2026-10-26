package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuoteTest {
    private static SwapEngine.Quote quote(String version) {
        return new SwapEngine.Quote("peer", "/onion3/x:1", version, 680_000, 10_000, 1_000_000, "{}");
    }

    @Test
    void makersBeforeTheFloorAreIncompatible() {
        assertTrue(quote("4.12.3").isIncompatible());
        assertTrue(quote("3.2.0").isIncompatible());
        assertTrue(quote("4.13.0-rc.1").isIncompatible());
        assertFalse(quote("4.13.0").isIncompatible());
        assertFalse(quote("4.16.1").isIncompatible());
        assertFalse(quote("5.0.0").isIncompatible());
    }

    @Test
    void anUnknownVersionGetsAChance() {
        assertFalse(quote(null).isIncompatible());
        assertFalse(quote("").isIncompatible());
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(SwapEngine.Quote.compareVersions("4.9.0", "4.13.0") < 0);
        assertTrue(SwapEngine.Quote.compareVersions("4.13.0", "4.13.0-rc.2") > 0);
        assertEquals(0, SwapEngine.Quote.compareVersions("v4.13.0", "4.13.0"));
    }
}
