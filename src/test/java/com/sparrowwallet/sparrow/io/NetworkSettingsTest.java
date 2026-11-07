package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkSettingsTest {
    @Test
    void defaultsToMainnetWhenNothingIsSet() {
        assertEquals(Network.MAINNET, NetworkSettings.resolveNetwork(null, null, null, List.of()));
        assertEquals(Network.MAINNET, NetworkSettings.resolveNetwork(null, null, null, null));
    }

    @Test
    void launchArgumentBeatsEverythingElse() {
        assertEquals(Network.MAINNET, NetworkSettings.resolveNetwork(Network.MAINNET, "testnet4", Network.TESTNET4, List.of(Network.SIGNET)));
        assertEquals(Network.TESTNET4, NetworkSettings.resolveNetwork(Network.TESTNET4, "mainnet", Network.MAINNET, List.of()));
    }

    @Test
    void environmentBeatsSavedChoiceAndFlagFiles() {
        assertEquals(Network.MAINNET, NetworkSettings.resolveNetwork(null, "mainnet", Network.TESTNET4, List.of(Network.TESTNET)));
    }

    @Test
    void savedChoiceBeatsLegacyFlagFiles() {
        assertEquals(Network.MAINNET, NetworkSettings.resolveNetwork(null, null, Network.MAINNET, List.of(Network.TESTNET4)));
        assertEquals(Network.TESTNET4, NetworkSettings.resolveNetwork(null, null, Network.TESTNET4, List.of(Network.SIGNET)));
    }

    @Test
    void legacyFlagFileStillWorksAndLastFlagWins() {
        assertEquals(Network.TESTNET4, NetworkSettings.resolveNetwork(null, null, null, List.of(Network.TESTNET4)));
        assertEquals(Network.SIGNET, NetworkSettings.resolveNetwork(null, null, null, List.of(Network.TESTNET, Network.TESTNET4, Network.SIGNET)));
    }

    @Test
    void unrecognisedEnvironmentValueIsIgnored() {
        assertEquals(Network.TESTNET4, NetworkSettings.resolveNetwork(null, "not-a-network", Network.TESTNET4, List.of()));
        assertEquals(Network.MAINNET, NetworkSettings.resolveNetwork(null, "  ", null, List.of()));
    }

    @Test
    void restartIsOnlyEverTestnet4() {
        assertEquals(Network.TESTNET4, NetworkSettings.resolveNetwork(Network.RESTART, null, null, List.of()));
        assertEquals(Network.TESTNET4, NetworkSettings.resolveNetwork(null, "restart", null, List.of()));

        NetworkSettings settings = new NetworkSettings();
        settings.setNetwork(Network.RESTART);
        assertEquals(Network.TESTNET4, settings.getNetwork());
    }

    @Test
    void roundTripsThroughTheFile(@TempDir File dir) {
        File file = new File(dir, NetworkSettings.FILENAME);

        NetworkSettings settings = new NetworkSettings();
        settings.setNetwork(Network.TESTNET4);
        settings.setWhirlpool(Network.TESTNET4, new NetworkSettings.WhirlpoolEndpoint("http://127.0.0.1:8080", " ", "/keys/test.pem"));
        settings.setWhirlpool(Network.MAINNET, new NetworkSettings.WhirlpoolEndpoint("http://abc.onion", "http://def.onion", null));
        settings.save(file);

        NetworkSettings loaded = NetworkSettings.load(file);
        assertEquals(Network.TESTNET4, loaded.getNetwork());

        NetworkSettings.WhirlpoolEndpoint testnet = loaded.getWhirlpool(Network.TESTNET4);
        assertEquals("http://127.0.0.1:8080", testnet.getCoordinatorUrl());
        assertEquals("http://127.0.0.1:8080", testnet.getOutputRegUrl(), "blank output registration URL defaults to the coordinator");
        assertNull(testnet.getOutputRegUrlOverride());
        assertEquals("/keys/test.pem", testnet.getBlindSignatureKeyPath());

        NetworkSettings.WhirlpoolEndpoint mainnet = loaded.getWhirlpool(Network.MAINNET);
        assertEquals("http://def.onion", mainnet.getOutputRegUrl());
        assertNull(mainnet.getBlindSignatureKeyPath());
    }

    @Test
    void testnetOverrideIsSharedByEveryTestnetName() {
        NetworkSettings settings = new NetworkSettings();
        settings.setWhirlpool(Network.TESTNET4, new NetworkSettings.WhirlpoolEndpoint("http://t", null, null));
        assertEquals("http://t", settings.getWhirlpool(Network.RESTART).getCoordinatorUrl());
        assertNull(settings.getWhirlpool(Network.MAINNET));
    }

    @Test
    void overrideWithoutACoordinatorUrlIsTreatedAsUnset() {
        NetworkSettings settings = new NetworkSettings();
        settings.setWhirlpool(Network.MAINNET, new NetworkSettings.WhirlpoolEndpoint(null, "http://out", "/k.pem"));
        assertNull(settings.getWhirlpool(Network.MAINNET));

        settings.setWhirlpool(Network.MAINNET, new NetworkSettings.WhirlpoolEndpoint("http://c", null, null));
        settings.setWhirlpool(Network.MAINNET, null);
        assertNull(settings.getWhirlpool(Network.MAINNET));
    }

    @Test
    void missingOrDamagedFileFallsBackToDefaults(@TempDir File dir) throws Exception {
        NetworkSettings missing = NetworkSettings.load(new File(dir, "absent.json"));
        assertNull(missing.getNetwork());
        assertNull(missing.getWhirlpool(Network.MAINNET));

        File damaged = new File(dir, NetworkSettings.FILENAME);
        Files.writeString(damaged.toPath(), "{ not json");
        NetworkSettings loaded = NetworkSettings.load(damaged);
        assertNull(loaded.getNetwork());

        Files.writeString(damaged.toPath(), "");
        assertNull(NetworkSettings.load(damaged).getNetwork());
    }

    @Test
    void unknownNetworkInFileIsIgnored(@TempDir File dir) throws Exception {
        File file = new File(dir, NetworkSettings.FILENAME);
        Files.writeString(file.toPath(), "{\"network\": \"DOGECOIN\"}");
        assertNull(NetworkSettings.load(file).getNetwork());
    }
}
