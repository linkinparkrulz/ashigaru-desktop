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

    public static SwapEngine load(File libraryFile) throws SwapEngineException {
        try {
            return new SwapEngine(Native.load(libraryFile.getAbsolutePath(), SwapEngineLibrary.class));
        } catch(UnsatisfiedLinkError e) {
            throw new SwapEngineException("Could not load the swap engine from " + libraryFile, e);
        }
    }

    /**
     * Starts the engine. With a host and swap settings it can swap; without, it only fetches quotes.
     *
     * @param electrumUrls e.g. tcp://host.onion:50001, reached through Tor
     * @param moneroNode   host:port of a Monero node reached through Tor over plain HTTP - an onion node
     */
    public void start(File dataDir, HostAndPort torProxy, byte[] seed, SwapHost host, List<String> electrumUrls,
                      String moneroNode) throws SwapEngineException {
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
        if(host != null && electrumUrls != null && !electrumUrls.isEmpty() && moneroNode != null) {
            JsonObject swap = new JsonObject();
            JsonArray urls = new JsonArray();
            electrumUrls.forEach(urls::add);
            swap.add("electrumUrls", urls);
            swap.addProperty("moneroNode", moneroNode);
            config.add("swap", swap);
        }
        call(() -> library.as_init(GSON.toJson(config)));
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
                        String refundPolicy) {}
}
