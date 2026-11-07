package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppRestartTest {
    @Test
    void relaunchKeepsFolderAndLogLevelButDropsTheNetworkByDefault() {
        Args current = new Args();
        current.dir = "/data/ashigaru";
        current.network = Network.MAINNET;
        current.level = Level.DEBUG;

        //Dropping -n lets the network saved in Settings decide; an explicit one would override it
        assertEquals(List.of("-d", "/data/ashigaru", "-l", "DEBUG"), AppRestart.relaunchParams(current, null));
    }

    @Test
    void relaunchCanForceANetwork() {
        Args current = new Args();
        assertEquals(List.of("-n", "testnet4"), AppRestart.relaunchParams(current, Network.TESTNET4));
    }

    @Test
    void relaunchDoesNotCarryOverTerminalMode() {
        Args current = new Args();
        current.terminal = true;
        assertEquals(List.of(), AppRestart.relaunchParams(current, null));
    }
}
