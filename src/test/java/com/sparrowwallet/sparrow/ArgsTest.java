package com.sparrowwallet.sparrow;

import com.beust.jcommander.JCommander;
import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArgsTest {
    private static Args parse(List<String> argv) {
        Args args = new Args();
        JCommander.newBuilder().addObject(args).acceptUnknownOptions(true).build().parse(argv.toArray(new String[0]));
        return args;
    }

    @Test
    void networkSurvivesARestartRoundTrip() {
        for(Network network : Network.values()) {
            Args original = new Args();
            original.network = network;

            Args reparsed = parse(original.toParams());
            assertEquals(network, reparsed.network, "toParams() must produce an argument that parses back to " + network);
        }
    }

    @Test
    void omitsNetworkWhenNotSet() {
        Args args = new Args();
        args.dir = "/some/dir";
        assertEquals(List.of("-d", "/some/dir"), args.toParams());
    }
}
