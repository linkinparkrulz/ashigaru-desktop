package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoneroNodesTest {
    private static final String CAKE = MoneroNodes.PUBLIC_NODES.get(0).address();
    private static final String PLOWSOF = MoneroNodes.PUBLIC_NODES.get(1).address();
    private static final String OWN = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion:18081";

    @Test
    void onePublicNodeIsNotEnough() {
        assertTrue(MoneroNodes.forEngine(List.of(CAKE)).isEmpty());
        assertNotNull(MoneroNodes.problem(List.of(CAKE)));
    }

    @Test
    void publicNodesAreUsedTogether() {
        assertEquals(List.of(CAKE, PLOWSOF), MoneroNodes.forEngine(List.of(CAKE, PLOWSOF)));
        assertNull(MoneroNodes.problem(List.of(CAKE, PLOWSOF)));
    }

    @Test
    void ownNodeIsTrustedAlone() {
        assertEquals(List.of(OWN), MoneroNodes.forEngine(List.of(CAKE, OWN)));
        assertEquals(List.of(OWN), MoneroNodes.forEngine(List.of(OWN)));
    }

    @Test
    void defaultsAreEnoughToSwap() {
        assertNull(MoneroNodes.problem(MoneroNodes.DEFAULT_SELECTION));
    }

    @Test
    void onlyOnionAddressesWithAPort() {
        assertTrue(MoneroNodes.isOnionHostAndPort(OWN));
        assertFalse(MoneroNodes.isOnionHostAndPort("node.example.com:18081"));
        assertFalse(MoneroNodes.isOnionHostAndPort("abc.onion"));
    }
}
