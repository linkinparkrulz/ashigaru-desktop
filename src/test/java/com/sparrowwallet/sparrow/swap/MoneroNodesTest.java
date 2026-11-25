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

    private static final String OWN2 = "bbcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion:18081";
    private static final String RUCKNIUM = MoneroNodes.PUBLIC_NODES.get(2).address();

    @Test
    void ticksThatAreNotUsedAreCounted() {
        //A tester ticked Cake, plowsof and rucknium and added their own: only theirs runs
        assertEquals(3, MoneroNodes.ignoredPublic(List.of(CAKE, PLOWSOF, RUCKNIUM, OWN)));
        assertEquals(0, MoneroNodes.ignoredPublic(List.of(CAKE, PLOWSOF, RUCKNIUM)));
        assertEquals(0, MoneroNodes.ignoredPublic(List.of(OWN)));
        assertTrue(MoneroNodes.hasOwn(List.of(CAKE, OWN)));
        assertFalse(MoneroNodes.hasOwn(List.of(CAKE, PLOWSOF)));
    }

    @Test
    void theStatusSaysWhichNodesRun() {
        assertEquals("your own Monero node, trusted on its own", MoneroNodes.describe(List.of(OWN)));
        assertEquals("your own Monero node only; the 3 public nodes you ticked are not used",
                MoneroNodes.describe(List.of(CAKE, PLOWSOF, RUCKNIUM, OWN)));
        assertEquals("your own Monero node only; the 1 public node you ticked is not used", MoneroNodes.describe(List.of(CAKE, OWN)));
        assertEquals("2 of your own Monero nodes, which must agree before a swap releases your BTC", MoneroNodes.describe(List.of(OWN, OWN2)));
        assertEquals("2 of your own Monero nodes, which must agree before a swap releases your BTC; the 1 public node you ticked is not used",
                MoneroNodes.describe(List.of(CAKE, OWN, OWN2)));
        assertEquals("3 public Monero nodes, which must agree before a swap releases your BTC", MoneroNodes.describe(List.of(CAKE, PLOWSOF, RUCKNIUM)));
    }

    @Test
    void nothingIsSaidAboutASelectionThatCannotBeUsed() {
        assertNull(MoneroNodes.describe(List.of(CAKE)));
        assertNull(MoneroNodes.describe(List.of()));
    }

    @Test
    void theSettingsNoteAppearsOnlyWhenTicksAreIgnored() {
        assertEquals("Your own node is ticked, so only your own node is used. The 3 public nodes you ticked are ignored.",
                MoneroNodes.note(List.of(CAKE, PLOWSOF, RUCKNIUM, OWN)));
        assertEquals("Your own node is ticked, so only your own node is used. The 1 public node you ticked is ignored.",
                MoneroNodes.note(List.of(CAKE, OWN)));
        assertNull(MoneroNodes.note(List.of(OWN)));
        assertNull(MoneroNodes.note(List.of(CAKE, PLOWSOF)));
    }
}
