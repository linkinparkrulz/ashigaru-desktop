package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.io.Server;
import com.google.common.net.HostAndPort;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum PublicElectrumServer {

    FOUNDATION_DEVICES("Foundation TOR (mainnet)", "tcp://mocmguuik7rws4bclpcoz2ldfzesjolatrzggaxfl37hjpreap777yqd.onion:50001", Network.MAINNET),
    SETH_FOR_PRIVACY("SethForPrivacy TOR (mainnet)", "ssl://iuo6acfdicxhrovyqrekefh4rg2b7vgmzeeohc5cbwegawwhqpdxkgad.onion:50002", Network.MAINNET),
    KITTY("Kitty TOR (mainnet)", "tcp://kittycp2gatrqhlwpmbczk5rblw62enrpo2rzwtkfrrr27hq435d4vid.onion:50001", Network.MAINNET),
    GREY_PW("Grey-pw TOR (mainnet)", "tcp://fulcrum3li3ab37a5d4ew6yd2n4aepkebchddbpfn4ozvx57jcts2tqd.onion:50001", Network.MAINNET),
    TESTNET4_MEMPOOL_SPACE("Mempool Space CLEARNET (testnet4)", "ssl://mempool.space:40002", Network.TESTNET4),
    TEST4_ARANGUREN_CLEARNET("Aranguren CLEARNET (testnet4)", "tcp://testnet.aranguren.org:52001", Network.TESTNET4),
    TESTNET4_MEMPOOL_ONION("Mempool Space TOR (testnet4)", "ssl://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion:40002", Network.TESTNET4);

    PublicElectrumServer(String name, String url, Network network) {
        this.server = new Server(url, name);
        this.network = network;
    }

    public static final List<Network> SUPPORTED_NETWORKS = List.of(Network.MAINNET, Network.TESTNET, Network.SIGNET, Network.TESTNET4);

    private final Server server;
    private final Network network;

    public Server getServer() {
        return server;
    }

    public String getUrl() {
        return server.getUrl();
    }

    public Network getNetwork() {
        return network;
    }

    public static List<PublicElectrumServer> getServers() {
        return Arrays.stream(values()).filter(server -> server.network == Network.get()).collect(Collectors.toList());
    }

    public static boolean supportedNetwork() {
        return SUPPORTED_NETWORKS.contains(Network.get());
    }

    public static PublicElectrumServer fromServer(Server server) {
        for(PublicElectrumServer publicServer : values()) {
            if(publicServer.getServer().equals(server)) {
                return publicServer;
            }
        }

        return null;
    }

    public static boolean isPublicServer(HostAndPort hostAndPort) {
        for(PublicElectrumServer publicServer : values()) {
            if(publicServer.getServer().getHostAndPort().equals(hostAndPort)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return server.getAlias();
    }
}
