package com.sparrowwallet.sparrow.whirlpool;

import com.samourai.whirlpool.client.wallet.beans.WhirlpoolServer;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.io.NetworkSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhirlpoolServerResolverTest {
    @Test
    void mapsEveryWhirlpoolNetworkToAServer() {
        assertEquals(WhirlpoolServer.MAINNET, WhirlpoolServerResolver.builtInServer(Network.MAINNET));
        assertEquals(WhirlpoolServer.TESTNET, WhirlpoolServerResolver.builtInServer(Network.TESTNET));
        //Network.TESTNET.getName() is "testnet3" and TESTNET4's is "testnet": a lookup by name only works for one of them
        assertEquals(WhirlpoolServer.TESTNET, WhirlpoolServerResolver.builtInServer(Network.TESTNET4));
        for(Network network : Whirlpool.WHIRLPOOL_NETWORKS) {
            assertDoesNotThrow(() -> WhirlpoolServerResolver.builtInServer(network));
        }
    }

    @Test
    void rejectsNetworksWithoutWhirlpool() {
        assertThrows(IllegalArgumentException.class, () -> WhirlpoolServerResolver.builtInServer(Network.SIGNET));
        assertThrows(IllegalArgumentException.class, () -> WhirlpoolServerResolver.builtInServer(Network.REGTEST));
    }

    @Test
    void usesTheBuiltInServerWithoutAnOverride() {
        WhirlpoolServerResolver.ServerUrls onion = WhirlpoolServerResolver.resolve(WhirlpoolServer.TESTNET, null, true);
        assertEquals(WhirlpoolServer.TESTNET.getServerUrlOnion(), onion.serverUrl());
        assertEquals(WhirlpoolServer.TESTNET.getServerUrlOutputReg(), onion.serverUrlOutputReg());
        assertFalse(onion.overridden());

        WhirlpoolServerResolver.ServerUrls clear = WhirlpoolServerResolver.resolve(WhirlpoolServer.TESTNET, null, false);
        assertEquals(WhirlpoolServer.TESTNET.getServerUrlClear(), clear.serverUrl());
    }

    @Test
    void anOverrideIsUsedAsGivenWhetherOrNotTorIsInUse() {
        NetworkSettings.WhirlpoolEndpoint override = new NetworkSettings.WhirlpoolEndpoint("http://127.0.0.1:8080", null, null);

        for(boolean onion : new boolean[] {true, false}) {
            WhirlpoolServerResolver.ServerUrls urls = WhirlpoolServerResolver.resolve(WhirlpoolServer.TESTNET, override, onion);
            assertEquals("http://127.0.0.1:8080", urls.serverUrl());
            assertEquals("http://127.0.0.1:8080", urls.serverUrlOutputReg());
            assertTrue(urls.overridden());
        }
    }

    @Test
    void anOverrideCanSetADifferentOutputRegistrationUrl() {
        NetworkSettings.WhirlpoolEndpoint override = new NetworkSettings.WhirlpoolEndpoint("http://coordinator", "http://outputs", null);
        WhirlpoolServerResolver.ServerUrls urls = WhirlpoolServerResolver.resolve(WhirlpoolServer.MAINNET, override, true);
        assertEquals("http://coordinator", urls.serverUrl());
        assertEquals("http://outputs", urls.serverUrlOutputReg());
    }

    @Test
    void anEmptyOverrideFallsBackToTheBuiltInServer() {
        NetworkSettings.WhirlpoolEndpoint empty = new NetworkSettings.WhirlpoolEndpoint(" ", "http://out", "/k.pem");
        WhirlpoolServerResolver.ServerUrls urls = WhirlpoolServerResolver.resolve(WhirlpoolServer.MAINNET, empty, true);
        assertEquals(WhirlpoolServer.MAINNET.getServerUrlOnion(), urls.serverUrl());
        assertFalse(urls.overridden());
    }
}
