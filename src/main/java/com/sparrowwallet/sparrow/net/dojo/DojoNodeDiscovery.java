package com.sparrowwallet.sparrow.net.dojo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.DiscoveredServersChangedEvent;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.net.HttpClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Discovers the Electrum (Fulcrum) servers — and block explorers — running on Dojos listed in the
 * public dojobay.pw directory.
 *
 * <p>Each directory entry carries a {@code signed} block: a Bitcoin signed message wrapping the
 * Dojo's pairing payload and embedding the operator's BIP47 payment code. Discovery verifies that
 * block against the payment code the directory advertises for the node
 * ({@link SignedMessageVerifier#verify(String, String, String)}), which proves the listing belongs
 * to its claimed operator (paynym). For every entry that verifies, the Dojo's {@code indexer_url}
 * (a Fulcrum instance speaking the Electrum protocol) is taken directly from the directory JSON and
 * offered as a public Electrum server, labelled with the operator's paynym.
 *
 * <p>The only network I/O is fetching the directory itself (an onion service, routed over the Tor
 * proxy via {@link HttpClientService}); verification is pure local crypto, so there is no per-Dojo
 * probing. Note that {@code indexer_url} is <em>not</em> part of the signed message — the signature
 * proves the operator identity, not the indexer endpoint, which is trusted to the directory.
 */
public class DojoNodeDiscovery {
    private static final Logger log = LoggerFactory.getLogger(DojoNodeDiscovery.class);

    public static final String DIRECTORY_URL = "http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/data/dojos.json";

    public static class DiscoveredNode {
        private final Server indexerServer;
        private final Server explorerServer;
        private final String name;
        private final boolean verified;

        public DiscoveredNode(Server indexerServer, Server explorerServer, String name, boolean verified) {
            this.indexerServer = indexerServer;
            this.explorerServer = explorerServer;
            this.name = name;
            this.verified = verified;
        }

        public Server getIndexerServer() {
            return indexerServer;
        }

        public Server getExplorerServer() {
            return explorerServer;
        }

        public String getName() {
            return name;
        }

        /**
         * True if the Dojo's {@code signed} block cryptographically verifies against its embedded
         * BIP47 payment code and matches the payment code the directory advertises for this node.
         */
        public boolean isVerified() {
            return verified;
        }
    }

    public static class DiscoveryResult {
        private final List<DiscoveredNode> nodes;
        private final int attempted;
        private final int unverified;

        public DiscoveryResult(List<DiscoveredNode> nodes, int attempted, int unverified) {
            this.nodes = nodes;
            this.attempted = attempted;
            this.unverified = unverified;
        }

        public List<DiscoveredNode> getNodes() {
            return nodes;
        }

        public int getAttempted() {
            return attempted;
        }

        /**
         * Number of candidates whose signed block failed verification (bad/missing signature, or the
         * signed identity did not match the advertised payment code). These are never surfaced.
         */
        public int getUnverified() {
            return unverified;
        }
    }

    private record Candidate(String name, String pairingUrl, String signedBlock, String paymentCode, String indexerUrl, String explorerUrl) { }

    /**
     * Runs discovery on a daemon thread, persists each verified Dojo's Electrum server and block
     * explorer to {@link Config}, and posts a {@link DiscoveredServersChangedEvent} if anything
     * changed. Never throws — failures are logged. Safe to call from the UI thread.
     */
    public static void discoverInBackground() {
        Thread thread = new Thread(() -> {
            try {
                DiscoveryResult result = discover(AppServices.getHttpClientService());
                boolean changed = false;
                for(DiscoveredNode node : result.getNodes()) {
                    if(node.isVerified()) {
                        changed |= Config.get().addDiscoveredServer(node.getIndexerServer());
                        if(node.getExplorerServer() != null) {
                            changed |= Config.get().addDiscoveredExplorer(node.getExplorerServer());
                        }
                    }
                }
                if(changed) {
                    EventManager.get().post(new DiscoveredServersChangedEvent());
                }
                log.info("Dojo discovery: " + result.getNodes().size() + " verified, " + result.getUnverified()
                        + " unverified of " + result.getAttempted() + " candidates");
            } catch(Exception e) {
                log.warn("Background Dojo discovery failed: " + e.getMessage());
            }
        }, "dojo-discovery-bg");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Fetches the directory and, for each entry matching the current network that carries an
     * {@code indexer_url}, verifies its signed block against the advertised BIP47 payment code. Every
     * entry that verifies yields an Electrum server (and, where present, a block explorer) built from
     * the directory's own urls. Unverified entries are counted, not surfaced.
     *
     * <p>Performs blocking network I/O (the directory fetch) over Tor — must be called off the UI
     * thread.
     */
    public static DiscoveryResult discover(HttpClientService http) throws Exception {
        String directoryJson = http.requestJson(DIRECTORY_URL, String.class, null);
        JsonObject directory = JsonParser.parseString(directoryJson).getAsJsonObject();
        JsonArray nodes = directory.getAsJsonArray("nodes");

        List<Candidate> candidates = new ArrayList<>();
        if(nodes != null) {
            for(JsonElement element : nodes) {
                try {
                    JsonObject node = element.getAsJsonObject();
                    if(!matchesNetwork(optString(node, "network"))) {
                        continue;
                    }

                    String indexerUrl = optString(node, "indexer_url");
                    if(indexerUrl == null || indexerUrl.isEmpty()) {
                        continue;
                    }

                    JsonObject payload = node.getAsJsonObject("payload");
                    JsonObject pairing = payload == null ? null : payload.getAsJsonObject("pairing");
                    String pairingUrl = pairing == null ? null : optString(pairing, "url");
                    if(pairingUrl == null || pairingUrl.isEmpty()) {
                        continue;
                    }

                    candidates.add(new Candidate(displayName(node), pairingUrl, optString(node, "signed"),
                            optString(node, "paymentCode"), indexerUrl, explorerUrl(payload)));
                } catch(Exception e) {
                    log.warn("Skipping malformed dojo directory entry: " + e.getMessage());
                }
            }
        }

        // Verify each listing's signature and payment-code binding (pure local crypto — no network),
        // then take the indexer url straight from the directory for every entry that verifies.
        List<DiscoveredNode> discovered = new ArrayList<>();
        int unverified = 0;
        Set<String> seenHosts = new HashSet<>();
        for(Candidate candidate : candidates) {
            if(!SignedMessageVerifier.verify(candidate.signedBlock(), candidate.pairingUrl(), candidate.paymentCode())) {
                unverified++;
                log.info("Skipping unverified dojo directory entry: " + candidate.name());
                continue;
            }

            //The signature covers the operator's pairing payload, not the indexer url, which comes from the directory alone.
            //Say so in the label unless the signed message happens to name the indexer host too.
            String signedMessage = signedMessage(candidate.signedBlock());
            String indexerHost = new Server(candidate.indexerUrl(), candidate.name()).getHost();
            boolean indexerSigned = signedMessage != null && indexerHost != null && signedMessage.contains(indexerHost);
            Server indexerServer = new Server(candidate.indexerUrl(), indexerSigned ? candidate.name() : candidate.name() + " (unverified endpoint)");
            if(seenHosts.add(indexerServer.getHost())) {
                //Only offer an explorer url that is part of the signed message
                String explorerUrl = candidate.explorerUrl() != null && signedMessage != null && signedMessage.contains(candidate.explorerUrl()) ? candidate.explorerUrl() : null;
                Server explorerServer = explorerUrl == null ? null : new Server(explorerUrl, candidate.name());
                discovered.add(new DiscoveredNode(indexerServer, explorerServer, candidate.name(), true));
            }
        }

        return new DiscoveryResult(discovered, candidates.size(), unverified);
    }

    private static String signedMessage(String signedBlock) {
        try {
            return signedBlock == null ? null : SignedMessageVerifier.parse(signedBlock).message();
        } catch(Exception e) {
            return null;
        }
    }

    /**
     * Reads the block-explorer url from a node's signed {@code payload.explorer} object, skipping the
     * {@code explorer.null} placeholder type. Returns null when no usable explorer is present.
     */
    private static String explorerUrl(JsonObject payload) {
        JsonObject explorer = payload == null ? null : payload.getAsJsonObject("explorer");
        if(explorer == null) {
            return null;
        }
        String url = optString(explorer, "url");
        if(url == null || url.isEmpty()) {
            return null;
        }
        String type = optString(explorer, "type");
        if(type != null && type.toLowerCase(Locale.ROOT).contains("null")) {
            return null;
        }
        return url;
    }

    /**
     * Builds the dropdown label for a node as {@code name (paynym)} — e.g. {@code jordan
     * (+linkinparkrulz)}. Falls back to whichever of {@code name}/{@code paynym} is present alone, or
     * to the node {@code id} when both are absent.
     */
    private static String displayName(JsonObject node) {
        String name = optString(node, "name");
        String paynym = optString(node, "paynym");
        boolean hasName = name != null && !name.isEmpty();
        boolean hasPaynym = paynym != null && !paynym.isEmpty();
        if(hasName && hasPaynym) {
            return name + " (" + paynym + ")";
        }
        if(hasName) {
            return name;
        }
        if(hasPaynym) {
            return paynym;
        }
        return optString(node, "id");
    }

    /**
     * Matches a Dojo's network field ("mainnet"/"testnet") against the wallet's current network.
     * All test networks (testnet3, testnet4, signet) are treated as non-mainnet.
     */
    static boolean matchesNetwork(String network) {
        if(network == null) {
            return false;
        }
        boolean nodeMainnet = "mainnet".equalsIgnoreCase(network.trim());
        boolean walletMainnet = Network.get() == Network.MAINNET;
        return nodeMainnet == walletMainnet;
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull()) ? null : el.getAsString();
    }
}
