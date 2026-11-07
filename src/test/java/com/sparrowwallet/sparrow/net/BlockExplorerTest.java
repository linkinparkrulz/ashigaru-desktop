package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockExplorerTest {
    @Test
    void networkPathMatchesWhatExplorersUse() {
        assertEquals("", BlockExplorer.getNetworkPath(Network.MAINNET));
        assertEquals("testnet", BlockExplorer.getNetworkPath(Network.TESTNET));
        assertEquals("testnet4", BlockExplorer.getNetworkPath(Network.TESTNET4));
        assertEquals("testnet4", BlockExplorer.getNetworkPath(Network.RESTART));
        assertEquals("signet", BlockExplorer.getNetworkPath(Network.SIGNET));
    }
}
