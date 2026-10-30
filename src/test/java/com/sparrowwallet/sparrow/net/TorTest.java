package com.sparrowwallet.sparrow.net;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TorTest {
    private static final File DATA = new File("/home/jordan/.ashigaru/tor/work/data");

    @Test
    void aTorOnOurDataDirectoryIsFound() {
        //The hung daemon's command line, as ps showed it
        List<String> hung = List.of("--quiet", "--torrc-file", "-", "--DataDirectory", "/home/jordan/.ashigaru/tor/work/data",
                "--CacheDirectory", "/home/jordan/.ashigaru/tor/cache", "--__OwningControllerProcess", "1114543");
        assertTrue(Tor.holdsDataDir(hung, DATA));
    }

    @Test
    void otherProcessesAreLeftAlone() {
        //Another Tor, e.g. a testnet profile or the system's own
        assertFalse(Tor.holdsDataDir(List.of("--DataDirectory", "/home/jordan/.ashigaru/testnet4/tor/work/data"), DATA));
        assertFalse(Tor.holdsDataDir(List.of("-f", "/etc/tor/torrc"), DATA));
        assertFalse(Tor.holdsDataDir(List.of("--DataDirectory"), DATA));
        assertFalse(Tor.holdsDataDir(List.of(), DATA));
    }
}
