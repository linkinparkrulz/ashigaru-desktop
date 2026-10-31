package com.sparrowwallet.sparrow.swap;

import com.google.common.net.HostAndPort;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.drongo.Utils;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Java side of the embedded eigenwallet swap engine. Mainnet only. The engine reaches makers,
 * Electrum and the Monero node through Ashigaru's Tor and nothing else, so it cannot be started
 * without the Tor SOCKS proxy.
 *
 * <p>Calls block: {@link #start} waits for the engine to come up, so callers run them off the
 * JavaFX thread. Requests from the engine are handed to the {@link SwapHost} on a worker thread and
 * answered when the host's future completes.
 */
public class SwapEngine {
    private static final Logger log = LoggerFactory.getLogger(SwapEngine.class);
    private static final Gson GSON = new Gson();
    //JNA finds libashigaru_swap.so / .dylib / ashigaru_swap.dll under <os>-<arch>/ in the resources
    private static final String BUNDLED_NAME = "ashigaru_swap";

    private final SwapEngineLibrary library;
    private final ExecutorService requests = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SwapEngine-request");
        thread.setDaemon(true);
        return thread;
    });
    //Held for as long as the engine lives: native code keeps calling it, and a collected JNA
    //callback crashes the JVM
    private SwapEngineLibrary.RequestCallback callback;
    private volatile SwapHost host;

    private SwapEngine(SwapEngineLibrary library) {
        this.library = library;
    }

    /** Loads the library bundled in the app's resources, which only swap builds include */
    public static SwapEngine loadBundled() throws SwapEngineException {
        try {
            return new SwapEngine(Native.load(BUNDLED_NAME, SwapEngineLibrary.class));
        } catch(UnsatisfiedLinkError e) {
            throw new SwapEngineException("This build does not include the swap engine", e);
        }
    }

    public static SwapEngine load(File libraryFile) throws SwapEngineException {
        try {
            return new SwapEngine(Native.load(libraryFile.getAbsolutePath(), SwapEngineLibrary.class));
        } catch(UnsatisfiedLinkError e) {
            throw new SwapEngineException("Could not load the swap engine from " + libraryFile, e);
        }
    }

    /**
     * Starts the engine, replacing one already running. With a host and swap settings it can swap;
     * without, it only fetches quotes.
     *
     * @param electrumUrls e.g. tcp://host.onion:50001, reached through Tor
     * @param moneroNodes  onion host:port of each Monero node, reached through Tor over plain HTTP: the
     *                     first is the engine's node, the others witnesses that must agree with it
     * @param pastDataDirs earlier identities' data directories, read for the list of past swaps
     */
    public void start(File dataDir, HostAndPort torProxy, byte[] seed, SwapHost host, List<String> electrumUrls,
                      List<String> moneroNodes, List<File> pastDataDirs) throws SwapEngineException {
        if(seed.length != 32) {
            throw new IllegalArgumentException("Swap seed must be 32 bytes");
        }

        if(host != null) {
            this.host = host;
            callback = this::onRequest;
            call(() -> library.as_set_callback(callback));
        }

        JsonObject config = new JsonObject();
        config.addProperty("dataDir", dataDir.getAbsolutePath());
        config.addProperty("socksHost", torProxy.getHost());
        config.addProperty("socksPort", torProxy.getPort());
        config.addProperty("seedHex", Utils.bytesToHex(seed));
        JsonArray past = new JsonArray();
        pastDataDirs.forEach(dir -> past.add(dir.getAbsolutePath()));
        config.add("pastDataDirs", past);
        if(host != null && electrumUrls != null && !electrumUrls.isEmpty() && moneroNodes != null && !moneroNodes.isEmpty()) {
            JsonObject swap = new JsonObject();
            JsonArray urls = new JsonArray();
            electrumUrls.forEach(urls::add);
            swap.add("electrumUrls", urls);
            JsonArray nodes = new JsonArray();
            moneroNodes.forEach(nodes::add);
            swap.add("moneroNodes", nodes);
            config.add("swap", swap);
        }
        call(() -> library.as_init(GSON.toJson(config)));
    }

    /** Stops the engine. Unfinished swaps resume when an engine with the same data directory starts. */
    public void shutdown() throws SwapEngineException {
        call(library::as_shutdown);
    }

    private void onRequest(long requestId, Pointer kindPointer, Pointer payloadPointer) {
        //Copy now: the strings are only valid during this call
        String kind = kindPointer.getString(0, StandardCharsets.UTF_8.name());
        String payloadJson = payloadPointer.getString(0, StandardCharsets.UTF_8.name());
        requests.execute(() -> dispatch(requestId, kind, payloadJson));
    }

    private void dispatch(long requestId, String kind, String payloadJson) {
        SwapHost current = host;
        JsonElement parsed = JsonParser.parseString(payloadJson);
        JsonObject payload = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        if(requestId == 0) {
            if(current != null) {
                current.onNotification(kind, payload);
            }
            return;
        }

        if(current == null) {
            answer(requestId, null, "No swap host");
            return;
        }
        current.handle(kind, payload).whenComplete((result, error) -> {
            if(error != null) {
                log.info("Declined swap engine request " + kind + ": " + error.getMessage());
            }
            answer(requestId, result, error == null ? null : String.valueOf(error.getMessage()));
        });
    }

    private void answer(long requestId, JsonElement result, String error) {
        JsonObject answer = new JsonObject();
        if(error != null) {
            answer.addProperty("error", error);
        } else {
            answer.add("ok", result);
        }
        try {
            call(() -> library.as_respond(requestId, GSON.toJson(answer)));
        } catch(SwapEngineException e) {
            //Usually a request that already timed out on the engine side
            log.debug("Swap engine did not take the answer to request " + requestId, e);
        }
    }

    /** Quotes from the makers discovered so far. Discovery keeps running, so the list grows over time. */
    public List<Quote> listQuotes() throws SwapEngineException {
        JsonElement result = call(library::as_list_quotes);
        List<Quote> quotes = new ArrayList<>();
        for(JsonElement element : result.getAsJsonArray()) {
            JsonObject entry = element.getAsJsonObject();
            JsonObject quote = entry.getAsJsonObject("quote");
            quotes.add(new Quote(
                    entry.get("peer_id").getAsString(),
                    entry.get("multiaddr").getAsString(),
                    entry.has("version") && !entry.get("version").isJsonNull() ? entry.get("version").getAsString() : null,
                    quote.get("price").getAsLong(),
                    quote.get("min_quantity").getAsLong(),
                    quote.get("max_quantity").getAsLong(),
                    quote.get("refund_policy").toString()));
        }
        return quotes;
    }

    /** Starts a swap and returns its id. It then runs in the background, reporting to the host. */
    public String startSwap(Quote maker, long btcSats, long txLockFeeSats, String xmrAddress, String refundAddress)
            throws SwapEngineException {
        JsonObject request = new JsonObject();
        request.addProperty("peerId", maker.peerId());
        request.addProperty("multiaddr", maker.multiaddr());
        request.addProperty("btcSats", btcSats);
        request.addProperty("txLockFeeSats", txLockFeeSats);
        request.addProperty("xmrAddress", xmrAddress);
        request.addProperty("refundAddress", refundAddress);
        return call(() -> library.as_start_swap(GSON.toJson(request))).getAsString();
    }

    /** Resumes every unfinished swap, e.g. after a restart. */
    public JsonArray resumeAll() throws SwapEngineException {
        return call(library::as_resume_all).getAsJsonArray();
    }

    public JsonArray listSwaps() throws SwapEngineException {
        return call(library::as_list_swaps).getAsJsonArray();
    }

    /** Asks every Monero node for its height through Tor: {node, ok, height | error} each, main node first */
    public JsonArray checkNodes() throws SwapEngineException {
        return call(library::as_check_nodes).getAsJsonArray();
    }

    /** Takes the BTC back after the cancel timelock. Blocks until the refund is published. */
    public String cancelAndRefund(String swapId) throws SwapEngineException {
        return call(() -> library.as_cancel_refund(swapId)).getAsString();
    }

    private JsonElement call(Supplier<Pointer> nativeCall) throws SwapEngineException {
        Pointer response = nativeCall.get();
        if(response == null) {
            throw new SwapEngineException("The swap engine returned nothing");
        }

        String json;
        try {
            json = response.getString(0, StandardCharsets.UTF_8.name());
        } finally {
            library.as_free_string(response);
        }

        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        if(body.has("error")) {
            throw new SwapEngineException(body.get("error").getAsString());
        }
        return body.get("ok");
    }

    /**
     * A maker's offer. Amounts are in satoshis; {@code priceSats} is the price of one XMR.
     * {@code refundPolicy} is the engine's JSON: FullRefund, or PartialRefund with the anti-spam
     * deposit ratio the maker may withhold.
     */
    public record Quote(String peerId, String multiaddr, String version, long priceSats, long minSats, long maxSats,
                        String refundPolicy) {
        /**
         * Makers older than this cannot complete a swap with eigenwallet 4.16: 4.13 changed the
         * cross-curve proof. eigenwallet's own GUI marks them "Incompatible software — will not work".
         */
        public static final String MIN_MAKER_VERSION = "4.13.0";

        /**
         * Offers this much cheaper than the market are left out. No honest maker sells XMR below the
         * market; such offers draw takers in and then refuse the swap, and none is on eigenwallet's
         * liquidity list.
         */
        public static final double MAX_BELOW_MARKET_PERCENT = 1.0;

        /** Share of the BTC the maker may keep as an anti-spam deposit if the swap is refunded: 0 for a full refund */
        public double refundRatio() {
            try {
                com.google.gson.JsonObject policy = com.google.gson.JsonParser.parseString(refundPolicy).getAsJsonObject();
                if(!"PartialRefund".equals(policy.get("type").getAsString())) {
                    return 0;
                }
                return policy.getAsJsonObject("content").get("anti_spam_deposit_ratio").getAsDouble();
            } catch(RuntimeException e) {
                //Unknown or unreadable terms rank last for refunds
                return 1;
            }
        }

        /** True when the market price is known and this offer undercuts it by more than {@link #MAX_BELOW_MARKET_PERCENT} */
        public boolean isBelowMarket(Double marketBtcPerXmr) {
            return marketBtcPerXmr != null && XmrPrice.offerVersusMarket(priceSats, marketBtcPerXmr) > MAX_BELOW_MARKET_PERCENT;
        }

        /** Where the maker is, as eigenwallet's liquidity list shows it: host:port, or the start of its onion */
        public String provider() {
            String[] parts = multiaddr == null ? new String[0] : multiaddr.split("/");
            String host = null;
            String port = null;
            for(int i = 1; i + 1 < parts.length; i++) {
                switch(parts[i]) {
                    case "ip4", "dns", "dns4", "dns6", "dnsaddr" -> host = parts[i + 1];
                    case "ip6" -> host = "[" + parts[i + 1] + "]";
                    case "tcp" -> port = parts[i + 1];
                    case "onion3" -> {
                        String onion = parts[i + 1].split(":")[0];
                        return (onion.length() > 10 ? onion.substring(0, 10) + "…" : onion) + ".onion";
                    }
                    default -> {}
                }
            }
            if(host != null) {
                return port == null ? host : host + ":" + port;
            }
            return peerId.length() > 8 ? "…" + peerId.substring(peerId.length() - 8) : peerId;
        }

        /** True when the maker reports a version known not to work. An unknown version is given a chance, as in eigenwallet. */
        public boolean isIncompatible() {
            return version != null && !version.isBlank() && compareVersions(version, MIN_MAKER_VERSION) < 0;
        }

        /** Compares semantic versions; a pre-release (4.13.0-rc.1) comes before its release */
        static int compareVersions(String a, String b) {
            String[] partsA = a.trim().replaceFirst("^v", "").split("-", 2);
            String[] partsB = b.trim().replaceFirst("^v", "").split("-", 2);
            String[] numbersA = partsA[0].split("\\.");
            String[] numbersB = partsB[0].split("\\.");
            for(int i = 0; i < Math.max(numbersA.length, numbersB.length); i++) {
                int compared = Long.compare(number(numbersA, i), number(numbersB, i));
                if(compared != 0) {
                    return compared;
                }
            }
            if(partsA.length != partsB.length) {
                return partsA.length > partsB.length ? -1 : 1;
            }
            return partsA.length == 2 ? partsA[1].compareTo(partsB[1]) : 0;
        }

        private static long number(String[] numbers, int index) {
            try {
                return index < numbers.length ? Long.parseLong(numbers[index].replaceAll("\\D.*", "")) : 0;
            } catch(NumberFormatException e) {
                return 0;
            }
        }
    }
}
