package com.sparrowwallet.sparrow.net;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
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

    @Test
    void theProofOfWorkAnswerSaysWhatTorHasAndWhatAshigaruAskedFor() {
        assertEquals("CompiledProofOfWorkHash=0 (set); Ashigaru asks for 0 (interpreted) on this system",
                Tor.describeProofOfWork(List.of("0 (set)"), false));
        assertEquals("CompiledProofOfWorkHash=auto (default); Ashigaru asks for Tor's default on this system",
                Tor.describeProofOfWork(List.of("auto (default)"), true));
        assertTrue(Tor.describeProofOfWork(List.of(), true).contains("no answer"));
    }

    @Test
    void onlyADaemonSignedToRunCompiledCodeIsAskedToCompileItsProofOfWork() {
        //The entitlements travel inside the daemon's signature, so the bytes carry them or do not
        assertTrue(Tor.carriesJitEntitlement("...com.apple.security.cs.allow-jit...".getBytes(StandardCharsets.ISO_8859_1)));
        assertFalse(Tor.carriesJitEntitlement("a hardened runtime signature with no entitlements".getBytes(StandardCharsets.ISO_8859_1)));
        assertFalse(Tor.carriesJitEntitlement(null));
    }
}
