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
    void aSwapEngineLibraryCanOnlyBeChosenWhenAshigaruIsStartedNotInTheConfigFile() {
        java.io.File fromConfig = new java.io.File("/tmp/evil.so");
        assertNull(SwapService.engineOverride(fromConfig, null, null), "the config file alone changes nothing");
        assertNull(SwapService.engineOverride(null, "  ", ""));
        assertEquals(new java.io.File("/opt/engine/libashigaru_swap.so"), SwapService.engineOverride(fromConfig, "/opt/engine/libashigaru_swap.so", null));
        assertEquals(new java.io.File("/opt/env/lib.so"), SwapService.engineOverride(null, null, "/opt/env/lib.so"));
        assertEquals(new java.io.File("/opt/prop/lib.so"), SwapService.engineOverride(null, "/opt/prop/lib.so", "/opt/env/lib.so"), "the property wins");
    }

    @Test
    void theEngineIsNotRestartedForANewTorProxyWhileASwapIsBeingSetUp() {
        assertFalse(SwapService.restartAllowed(true));
        assertTrue(SwapService.restartAllowed(false));
        //No host, no setup; a host's setup lasts only until its keys are wiped (checked through BadbankSwapHost.isSetupOver)
        assertFalse(SwapService.isSettingUp(null));
    }

    @Test
    void unfinishedSwapsAreKeptOnDiskSoTheyAreKnownWhileTheEngineIsOff(@org.junit.jupiter.api.io.TempDir java.io.File swapsDir) {
        UnfinishedSwaps list = new UnfinishedSwaps(swapsDir);
        assertEquals(0, list.count());
        list.add("a");
        list.add("b");
        list.add("a");
        assertEquals(2, new UnfinishedSwaps(swapsDir).count(), "a new run knows them before any engine starts");
        list.remove("a");
        assertEquals(java.util.Set.of("b"), new UnfinishedSwaps(swapsDir).ids());
        list.replaceAll(java.util.List.of("c", "d"));
        assertEquals(java.util.Set.of("c", "d"), new UnfinishedSwaps(swapsDir).ids());
        list.replaceAll(java.util.List.of());
        assertEquals(0, new UnfinishedSwaps(swapsDir).count());
        assertEquals(0, new UnfinishedSwaps(null).count());
    }

    @Test
    void theEnginesListSaysWhichSwapsAreUnfinished() {
        com.google.gson.JsonArray list = com.google.gson.JsonParser.parseString(
                "[{\"swapId\":\"a\",\"complete\":true},{\"swapId\":\"b\",\"complete\":false},{\"swapId\":\"c\"},{\"complete\":false}]").getAsJsonArray();
        assertEquals(java.util.List.of("b", "c"), SwapService.notFinishedIds(list));
    }

    @Test
    void aStartThatFailsBeforeTheEngineIsTouchedLeavesARunningEngineAlone() {
        assertTrue(SwapService.keepEngineAfterFailedStart(false, true), "a fresh identity could not be taken while the engine ran");
        assertFalse(SwapService.keepEngineAfterFailedStart(true, true), "the engine was reached: it may be half up, so it is stopped");
        assertTrue(SwapService.keepEngineAfterFailedStart(false, true), "a restart for Tor: running is taken before the restart clears it");
        assertFalse(SwapService.keepEngineAfterFailedStart(false, false), "no engine was running");
    }

    @Test
    void theProxyInSettingsIsNotCalledTorUntilItIsShownToBe() {
        com.google.common.net.HostAndPort proxy = com.google.common.net.HostAndPort.fromParts("127.0.0.1", 9050);
        assertEquals("Starting through Tor…", SwapService.startingStatus(false, new TorRoute.Route(proxy, true)));
        assertTrue(SwapService.startingStatus(false, new TorRoute.Route(proxy, false)).startsWith("Starting through the proxy in Settings"));
        assertTrue(SwapLight.state(true, false, SwapService.startingStatus(false, new TorRoute.Route(proxy, false))) == SwapLight.State.STARTING);
        //No route at all: not Tor, and no network is touched to find that out
        assertFalse(TorRoute.isTor(null));
        assertTrue(TorRoute.isTor(new TorRoute.Route(proxy, true)), "Ashigaru's own Tor needs no proof");
    }

    @Test
    void aSettingsProxyIsOnlyUsedWhenTheUserMarkedItAsTorAndItAnswersThroughTor() {
        assertTrue(TorRoute.allowed(true, false, false), "Ashigaru's own Tor");
        assertFalse(TorRoute.allowed(false, false, true), "answering through Tor is not enough: a split-routing proxy does");
        assertFalse(TorRoute.allowed(false, true, false), "marked, but misconfigured");
        assertTrue(TorRoute.allowed(false, true, true));
    }

    @Test
    void throughASettingsProxyOnlyOnionOrLocalHostsAreUsed() {
        com.google.common.net.HostAndPort proxy = com.google.common.net.HostAndPort.fromParts("127.0.0.1", 9050);
        TorRoute.Route settings = new TorRoute.Route(proxy, false);
        TorRoute.Route own = new TorRoute.Route(proxy, true);
        String onion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion";
        java.util.List<String> onionNodes = java.util.List.of(onion + ":18081");
        assertNull(SwapService.clearnetOverSettingsProxy(settings, java.util.List.of("tcp://" + onion + ":50001"), onionNodes));
        assertNull(SwapService.clearnetOverSettingsProxy(settings, java.util.List.of("ssl://192.168.1.20:50002"), java.util.List.of("10.0.0.5:18081")), "the user's own network");
        assertNotNull(SwapService.clearnetOverSettingsProxy(settings, java.util.List.of("ssl://electrum.example.org:50002"), onionNodes));
        assertNotNull(SwapService.clearnetOverSettingsProxy(settings, java.util.List.of("tcp://" + onion + ":50001"), java.util.List.of("node.example.org:18081")));
        assertNotNull(SwapService.clearnetOverSettingsProxy(settings, java.util.List.of("tcp://8.8.8.8:50001"), onionNodes));
        assertNull(SwapService.clearnetOverSettingsProxy(own, java.util.List.of("ssl://electrum.example.org:50002"), java.util.List.of("node.example.org:18081")),
                "Ashigaru's own Tor reaches the clearnet through Tor");
        assertTrue(SwapService.onionOrLocal("localhost") && SwapService.onionOrLocal("::1") && SwapService.onionOrLocal("fd00::1"));
        assertFalse(SwapService.onionOrLocal("2001:4860::8888"));
    }

    @Test
    void aFailedProxyRecheckDoesNotCutOffASwapBeingSetUp() {
        assertFalse(SwapService.mayStopForProxy(true));
        assertTrue(SwapService.mayStopForProxy(false));
    }

    @Test
    void aProxysProofOfTorExpires() {
        java.time.Instant t0 = java.time.Instant.parse("2026-11-23T00:00:00Z");
        assertTrue(TorRoute.stillProven(t0, t0.plusSeconds(60)));
        assertFalse(TorRoute.stillProven(t0, t0.plus(java.time.Duration.ofMinutes(TorRoute.PROOF_MINUTES))));
        assertFalse(TorRoute.stillProven(null, t0));
    }

    @Test
    void theOneEarlierIdentityHoldingUnfinishedSwapsIsFound() {
        com.google.gson.JsonArray one = com.google.gson.JsonParser.parseString("[{\"complete\":true},"
                + "{\"complete\":false,\"past\":true,\"dataDir\":\"/s/swaps/aaaaaaaaaaaaaaaa\"},{\"past\":true,\"dataDir\":\"/s/swaps/aaaaaaaaaaaaaaaa\"},"
                + "{\"complete\":true,\"past\":true,\"dataDir\":\"/s/swaps/bbbbbbbbbbbbbbbb\"}]").getAsJsonArray();
        assertEquals("aaaaaaaaaaaaaaaa", SwapService.soleUnfinishedFolder(one));
        assertFalse(SwapService.hasUnfinishedOfThisIdentity(one));

        com.google.gson.JsonArray two = com.google.gson.JsonParser.parseString("[{\"complete\":false,\"past\":true,\"dataDir\":\"/s/swaps/aaaaaaaaaaaaaaaa\"},"
                + "{\"complete\":false,\"past\":true,\"dataDir\":\"/s/swaps/bbbbbbbbbbbbbbbb\"}]").getAsJsonArray();
        assertNull(SwapService.soleUnfinishedFolder(two), "no single identity can take both");
        assertNull(SwapService.soleUnfinishedFolder(com.google.gson.JsonParser.parseString("[{\"complete\":false,\"past\":true}]").getAsJsonArray()),
                "an engine too old to name folders");
        assertNull(SwapService.soleUnfinishedFolder(new com.google.gson.JsonArray()));
        assertTrue(SwapService.hasUnfinishedOfThisIdentity(com.google.gson.JsonParser.parseString("[{\"complete\":false}]").getAsJsonArray()));
    }

    @Test
    void aFailedRestartIsShownAsNotRunning() {
        String status = SwapService.restartFailedStatus("The proxy in Settings could not be shown to be Tor.");
        assertEquals(SwapLight.State.FAILED, SwapLight.state(true, false, status), "red, not On");
        assertEquals(SwapLight.State.UNFINISHED_DOWN, SwapLight.state(true, false, status, 1), "and with a swap unfinished, it says so");
    }

    @Test
    void anEngineKeptByAFailedRestartIsShutDownWhenTorGoes() {
        assertTrue(SwapService.shutDownKeptEngine(true, true));
        assertFalse(SwapService.shutDownKeptEngine(true, false), "Tor still there: kept until the next start replaces it");
        assertFalse(SwapService.shutDownKeptEngine(false, true));
    }

    @Test
    void aFailedStartAfterSwapsWereTurnedOffOrPausedSaysSo() {
        //The failure path asks the same question as the success path, with offline mode counted as off
        assertEquals(SwapService.Arrival.SHUT_DOWN_OFF, SwapService.arrival(false, false));
        assertEquals(SwapService.Arrival.SHUT_DOWN_PAUSED, SwapService.arrival(true, true));
        assertEquals(SwapService.Arrival.KEEP, SwapService.arrival(true, false));
    }

    @Test
    void swapsDoNotStartInOfflineMode() {
        assertTrue(SwapService.blockedOffline(com.sparrowwallet.sparrow.Mode.OFFLINE));
        assertFalse(SwapService.blockedOffline(com.sparrowwallet.sparrow.Mode.ONLINE));
        assertFalse(SwapService.blockedOffline(null), "no mode chosen yet is not offline");
        //What the status says is not taken for a start or a failure by the light
        assertEquals(SwapLight.State.HIDDEN, SwapLight.state(true, false, SwapService.OFFLINE));
    }

    @Test
    void unfinishedSwapsOfEarlierIdentitiesAreCountedApart() {
        com.google.gson.JsonArray list = com.google.gson.JsonParser.parseString(
                "[{\"complete\":false},{\"complete\":false,\"past\":true},{\"past\":true},{\"complete\":true,\"past\":true}]").getAsJsonArray();
        assertEquals(2, SwapService.olderIdentityUnfinished(list));
    }

    @Test
    void seedsAreOnlyRetiredFolderByFolderWhenTheirSwapsWereReadAndAreFinished() {
        com.google.gson.JsonArray list = com.google.gson.JsonParser.parseString("["
                + "{\"complete\":true,\"dataDir\":\"/s/swaps/aaaaaaaaaaaaaaaa\"},{\"complete\":true,\"past\":true,\"dataDir\":\"/s/swaps/aaaaaaaaaaaaaaaa\"},"
                + "{\"complete\":true,\"past\":true,\"dataDir\":\"/s/swaps/bbbbbbbbbbbbbbbb\"},{\"complete\":false,\"past\":true,\"dataDir\":\"/s/swaps/bbbbbbbbbbbbbbbb\"},"
                + "{\"past\":true,\"dataDir\":\"/s/swaps/cccccccccccccccc\"},"
                + "{\"complete\":true,\"past\":true}]").getAsJsonArray();
        //b has an unfinished swap, c one with no complete field, and the last entry names no folder (an older engine)
        assertEquals(java.util.Set.of("aaaaaaaaaaaaaaaa"), SwapService.finishedFolders(list));
        //A folder whose database could not be read has no entries, so it is never named
        assertEquals(java.util.Set.of(), SwapService.finishedFolders(new com.google.gson.JsonArray()));
    }

    @Test
    void theLogFileNeverGetsAnXmrAddressOrALockTxid() {
        String xmr = "4q2Dkq6ozw8yQ3t9g3QygMp3xMYfnw6f2mr16unD8uzXkyQBS7As25AhCje1UdemiPgTYu21LugRY98q6ZvjEKvdgq2mtVY";
        String txid = "0ad8c9aa5d7e11b4f0c2a9e3b6d85f4c1a7e90b23d4c5f6a7b8c9d0e1f2a3b4c";
        String line = SwapService.forLogFile("{\"btc_lock_txid\":\"" + txid + "\"} destination " + xmr);
        assertTrue(line.startsWith("Swap engine: "));
        assertFalse(line.contains(txid) || line.contains(xmr.substring(0, 30)), line);
        assertTrue(line.contains("[txid]") && line.contains("[xmr address]"), line);
    }

    @Test
    void makerPeersAreShortenedInTheSwapLog() {
        assertEquals("\u2026xyz123", SwapService.shortPeer("12D3KooWMakerabcdefghijklmnopqrstuvwxyz123".substring(0, 33) + "xyz123"));
        assertEquals("short", SwapService.shortPeer("short"));
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
