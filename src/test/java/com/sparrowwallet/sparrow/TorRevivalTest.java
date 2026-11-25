package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AD-29: Tor that stopped by itself is only started again when the user has not chosen offline mode */
class TorRevivalTest {
    @Test
    void offlineModeIsNeverReversedByAnAutomaticRestart() {
        assertFalse(AppServices.mayReviveTor(Mode.OFFLINE, true, true));
        assertTrue(AppServices.mayReviveTor(Mode.ONLINE, true, true));
        assertTrue(AppServices.mayReviveTor(null, true, true), "no mode chosen yet is not offline");
        assertFalse(AppServices.mayReviveTor(Mode.ONLINE, false, true), "external Tor is not Ashigaru's to restart");
        assertFalse(AppServices.mayReviveTor(Mode.ONLINE, true, false), "stopped on purpose (Tor.getDefault() cleared)");
    }
}
