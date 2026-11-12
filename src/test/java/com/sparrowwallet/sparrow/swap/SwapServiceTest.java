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
