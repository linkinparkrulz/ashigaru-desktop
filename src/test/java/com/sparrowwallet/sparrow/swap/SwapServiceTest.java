package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class SwapServiceTest {
    private static SwapService newService() throws Exception {
        //Not get(): that registers the singleton for Tor events
        Constructor<SwapService> constructor = SwapService.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Wallet badbankOf(Wallet master) {
        Wallet badbank = new Wallet();
        badbank.setMasterWallet(master);
        return badbank;
    }

    private static BlockTransactionHashIndex coin() {
        return new BlockTransactionHashIndex(Sha256Hash.ZERO_HASH, 900_000, new Date(), 0L, 2, 374_120);
    }

    @Test
    void lockingAWalletForgetsItsChosenCoin() throws Exception {
        SwapService service = newService();
        Wallet master = new Wallet();
        service.chooseCoin("FTF2", badbankOf(master), coin());
        assertNotNull(service.coinProperty().get());

        service.walletUnloaded(new Wallet());
        assertNotNull(service.coinProperty().get(), "another wallet locking keeps the coin");

        service.walletUnloaded(master);
        assertNull(service.coinProperty().get());
    }

    private static com.google.gson.JsonObject timelockEvent(String timelock) {
        return com.google.gson.JsonParser.parseString("{\"event\":\"TimelockChange\",\"payload\":{\"channelName\":\"TimelockChange\","
                + "\"event\":{\"swap_id\":\"abc\",\"timelock\":" + timelock + "}}}").getAsJsonObject();
    }

    @Test
    void timelockEventsAreKeptPerSwapAndTellTheScreens() throws Exception {
        SwapService service = newService();
        java.util.concurrent.atomic.AtomicInteger notified = new java.util.concurrent.atomic.AtomicInteger();
        service.addSwapListener(notified::incrementAndGet);
        assertNull(service.getTimelock("abc"));

        service.notification("event", timelockEvent("{\"type\":\"None\",\"content\":{\"blocks_left\":13}}"));
        assertEquals(SwapTimelock.Phase.BEFORE_CANCEL, service.getTimelock("abc").phase());
        assertEquals(13, service.getTimelock("abc").blocksLeft());
        assertEquals(1, notified.get());
        assertNull(service.getTimelock("other"));

        //The chain moves on
        service.notification("event", timelockEvent("{\"type\":\"Cancel\",\"content\":{\"blocks_left\":140}}"));
        assertEquals(SwapTimelock.Phase.CANCEL_OPEN, service.getTimelock("abc").phase());

        //A null timelock, sent once a swap is over, clears it
        service.notification("event", timelockEvent("null"));
        assertNull(service.getTimelock("abc"));
        assertEquals(3, notified.get());
    }

    @Test
    void anEngineThatFinishesStartingAfterSwapsWereTurnedOffIsShutDown() {
        //Turning swaps off during the start used to leave the new engine running, dialling makers, while Settings said Off
        assertEquals(SwapService.Arrival.SHUT_DOWN_OFF, SwapService.arrival(false, false));
        assertEquals(SwapService.Arrival.SHUT_DOWN_OFF, SwapService.arrival(false, true), "off wins over paused");
        assertEquals(SwapService.Arrival.SHUT_DOWN_PAUSED, SwapService.arrival(true, true));
        assertEquals(SwapService.Arrival.KEEP, SwapService.arrival(true, false));
    }

    @Test
    void aStopEventOfAnotherSwapDoesNotTakeTheSetupsRefusalOrKeys() throws Exception {
        java.util.Set<String> before = java.util.Set.of("old-swap");
        assertTrue(SwapService.isOfThisSetup(true, before, "new-swap"));
        assertFalse(SwapService.isOfThisSetup(true, before, "old-swap"), "an old swap stopping says nothing about this setup");
        assertFalse(SwapService.isOfThisSetup(false, before, "new-swap"), "no setup, nothing to release");

        //Through the service: another swap stopping leaves the refusal waiting for the swap that was refused
        SwapService service = newService();
        java.lang.reflect.Field refusal = SwapService.class.getDeclaredField("pendingRefusal");
        refusal.setAccessible(true);
        refusal.set(service, "The maker changed the refund policy");
        service.notification("swap_stopped", com.google.gson.JsonParser.parseString("{\"swapId\":\"old-swap\",\"state\":\"btc is refunded\"}").getAsJsonObject());
        assertEquals("The maker changed the refund policy", refusal.get(service));
    }

    @Test
    void theSwapAmountIsCheckedAgainstTheLimitsAtTheMomentItIsStarted() {
        SwapEngine.Quote maker = new SwapEngine.Quote("12D3KooWMaker", "/ip4/10.0.0.1/tcp/9939", "5.0.4", 33_000_000, 100_000, 5_000_000, "{\"type\":\"FullRefund\"}");
        assertNull(SwapService.amountProblem(maker, 4_998_760, 10_000_000));
        //The fee rate rose between drawing the offers and pressing Review offer, and the amount fell below the maker's minimum
        assertTrue(SwapService.amountProblem(maker, 99_000, 10_000_000).contains("takes 0.00100000 to 0.05000000 BTC"));
        assertTrue(SwapService.amountProblem(maker, 5_000_001, 10_000_000).contains("takes"));
        assertTrue(SwapService.amountProblem(maker, 4_998_760, 1_000_000).contains("Swaps are limited to 0.01000000 BTC"));
    }

    @Test
    void aSwapRecordWithoutTheCompleteFieldCountsAsUnfinishedAndDoesNotCrash() {
        com.google.gson.JsonObject bare = com.google.gson.JsonParser.parseString("{\"swapId\":\"abc\"}").getAsJsonObject();
        assertFalse(SwapService.isComplete(bare));
        assertFalse(SwapService.isRunning(bare));
        assertFalse(SwapService.isComplete(com.google.gson.JsonParser.parseString("{\"complete\":null}").getAsJsonObject()));
        assertTrue(SwapService.isComplete(com.google.gson.JsonParser.parseString("{\"complete\":true,\"running\":false}").getAsJsonObject()));
        assertTrue(SwapService.isRunning(com.google.gson.JsonParser.parseString("{\"running\":true}").getAsJsonObject()));
    }

    @Test
    void seedsAreOnlyRetiredWhenEverySwapOfEveryIdentityIsFinished() {
        com.google.gson.JsonArray done = com.google.gson.JsonParser.parseString("[{\"complete\":true},{\"complete\":true,\"past\":true}]").getAsJsonArray();
        assertTrue(SwapService.allFinished(done));
        assertTrue(SwapService.allFinished(new com.google.gson.JsonArray()));
        assertFalse(SwapService.allFinished(com.google.gson.JsonParser.parseString("[{\"complete\":true},{\"complete\":false,\"past\":true}]").getAsJsonArray()));
        assertFalse(SwapService.allFinished(com.google.gson.JsonParser.parseString("[{\"swapId\":\"no complete field\"}]").getAsJsonArray()));
    }

    @Test
    void addressesInTheSwapLogAreShortened() {
        String xmr = "4q2Dkq6ozw8yQ3t9g3QygMp3xMYfnw6f2mr16unD8uzXkyQBS7As25AhCje1UdemiPgTYu21LugRY98q6ZvjEKvdgq2mtVY";
        assertEquals("4q2D\u2026mtVY", SwapService.shortAddress(xmr));
        assertFalse(SwapService.shortAddress(xmr).contains(xmr.substring(10, 30)));
        assertEquals("short", SwapService.shortAddress("short"));
        assertNull(SwapService.shortAddress(null));
    }

    @Test
    void walletRequestsAreRefusedWhenNoSwapIsBeingSetUp() throws Exception {
        SwapService service = newService();
        assertFalse(service.isSwapActive());
        assertTrue(service.handle("fund_lock", new com.google.gson.JsonObject()).isCompletedExceptionally());
        assertTrue(service.handle("sign", new com.google.gson.JsonObject()).isCompletedExceptionally());
    }

    @org.junit.jupiter.api.Test
    void onlyThisIdentitysSwapsMeanItWasUsed() {
        com.google.gson.JsonArray swaps = new com.google.gson.JsonArray();
        org.junit.jupiter.api.Assertions.assertFalse(SwapService.usedByThisIdentity(swaps));

        //A finished swap from an earlier identity, listed so it stays in Your swaps
        com.google.gson.JsonObject past = new com.google.gson.JsonObject();
        past.addProperty("swapId", "282210d2-18f1-4f90-a4fa-1eccbd9c2466");
        past.addProperty("past", true);
        swaps.add(past);
        org.junit.jupiter.api.Assertions.assertFalse(SwapService.usedByThisIdentity(swaps));

        com.google.gson.JsonObject current = new com.google.gson.JsonObject();
        current.addProperty("swapId", "98a303f2-39aa-43c9-aec3-2f8b2303103c");
        current.addProperty("past", false);
        swaps.add(current);
        org.junit.jupiter.api.Assertions.assertTrue(SwapService.usedByThisIdentity(swaps));
    }

    @Test
    void aSwapIsNotStartedWhenTheBitcoinServerCannotBeReached() {
        //From the field: the engine could not reach the tester's own server, and the swap stopped after the user had approved
        String message = SwapService.notStartedMessage(java.util.List.of("Bitcoin server (Electrum): general SOCKS server failure", "abc.onion:18081: No answer"));
        assertTrue(message.startsWith("Not started: the swap engine cannot reach your Bitcoin server"), message);
        assertTrue(message.contains("Settings → Server"));
        assertTrue(message.contains("general SOCKS server failure"));

        String monero = SwapService.notStartedMessage(java.util.List.of("abc.onion:18081: No answer"));
        assertTrue(monero.startsWith("Not started: a Monero node did not answer"), monero);
        assertTrue(SwapService.notStartedMessage(java.util.List.of("a: x", "b: y")).startsWith("Not started: 2 Monero nodes"));
    }

    @Test
    void theMessageNamesTheServerTheEngineAsked() {
        //From the field: the user had switched servers, Ashigaru was connected, and the engine still asked the old one
        String message = SwapService.notStartedMessage(java.util.List.of("Bitcoin server (Electrum): timed out"), "tcp://192.168.1.20:50001");
        assertTrue(message.startsWith("Not started: the swap engine cannot reach your Bitcoin server (tcp://192.168.1.20:50001)."), message);
    }

    @Test
    void theEngineFollowsAChangeOfBitcoinServer() {
        assertTrue(SwapService.serverChanged("tcp://192.168.1.20:50001", "tcp://abcdef.onion:50001"));
        assertFalse(SwapService.serverChanged("tcp://abcdef.onion:50001", "tcp://abcdef.onion:50001"));
        //No server configured is not a server to move to
        assertFalse(SwapService.serverChanged("tcp://abcdef.onion:50001", null));
    }

    @Test
    void torStoppingRightAfterTheEngineStartsIsPutDownToTheEngine() {
        long started = 1_000_000;
        assertTrue(SwapService.diedSoonAfterStart(started, started + 6_000, false));
        assertFalse(SwapService.diedSoonAfterStart(started, started + SwapService.TOR_LOSS_WINDOW_MILLIS, false));
        //Still starting: there is no start time yet, and it counts
        assertTrue(SwapService.diedSoonAfterStart(0, started, true));
        assertFalse(SwapService.diedSoonAfterStart(0, started, false));
    }
}
