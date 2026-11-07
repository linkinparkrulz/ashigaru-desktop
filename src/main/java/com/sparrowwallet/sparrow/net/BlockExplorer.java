package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.io.Server;

public enum BlockExplorer {
    MEMPOOL_SPACE("https://mempool.space"),
    BLOCKSTREAM_INFO("https://blockstream.info"),
    NONE("http://none");

    private final Server server;

    BlockExplorer(String url) {
        this.server = new Server(url);
    }

    public Server getServer() {
        return server;
    }

    /**
     * The path segment block explorers put before /tx/ for a network, or empty for mainnet. This is not
     * {@link Network#getName()}: that is "testnet3" for testnet3 and "testnet" for testnet4, whereas explorers
     * use "testnet" for testnet3 and "testnet4" for testnet4.
     */
    public static String getNetworkPath(Network network) {
        return switch(network) {
            case MAINNET -> "";
            case TESTNET -> "testnet";
            case TESTNET4, RESTART -> "testnet4";
            case SIGNET -> "signet";
            case REGTEST -> "regtest";
        };
    }
}
