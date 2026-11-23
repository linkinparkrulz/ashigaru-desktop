package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.net.PublicElectrumServer;
import com.sparrowwallet.sparrow.net.ServerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The first run must not take Ashigaru online, or start Tor, when the user had already chosen offline mode */
class FirstRunDefaultsTest {
    @Test
    void offlineModeIsKeptAndNothingConnects() {
        for(Network network : new Network[] {Network.MAINNET, Network.TESTNET4}) {
            AshigaruTerminal.FirstRunDefaults defaults = AshigaruTerminal.firstRunDefaults(network, Mode.OFFLINE);
            assertEquals(Mode.OFFLINE, defaults.mode(), network.getName());
            assertFalse(defaults.connect(), network.getName() + ": no connection, so no Tor either");
            assertEquals(ServerType.PUBLIC_ELECTRUM_SERVER, defaults.serverType(), "the server is set up for when the user goes online");
        }
    }

    @Test
    void otherwiseTheFirstRunGoesOnlineWithTheNetworksServer() {
        for(Mode mode : new Mode[] {null, Mode.ONLINE}) {
            AshigaruTerminal.FirstRunDefaults mainnet = AshigaruTerminal.firstRunDefaults(Network.MAINNET, mode);
            assertEquals(Mode.ONLINE, mainnet.mode());
            assertTrue(mainnet.connect());
            assertEquals(PublicElectrumServer.FOUNDATION_DEVICES, mainnet.server());
        }
        assertEquals(PublicElectrumServer.TEST4_ARANGUREN_CLEARNET, AshigaruTerminal.firstRunDefaults(Network.TESTNET4, null).server());
        assertNull(AshigaruTerminal.firstRunDefaults(Network.SIGNET, null), "no defaults for other networks, as before");
    }
}
