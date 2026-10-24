package com.sparrowwallet.sparrow.swap;

import com.google.common.net.HostAndPort;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.drongo.Utils;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Java side of the embedded eigenwallet swap engine. Mainnet only. The engine reaches makers through
 * Ashigaru's Tor and nothing else, so it cannot be started without the Tor SOCKS proxy.
 *
 * <p>Calls block: {@link #start} waits for the engine to come up, so callers run them off the
 * JavaFX thread.
 */
public class SwapEngine {
    private static final Gson GSON = new Gson();

    private final SwapEngineLibrary library;

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

    public void start(File dataDir, HostAndPort torProxy, byte[] seed) throws SwapEngineException {
        if(seed.length != 32) {
            throw new IllegalArgumentException("Swap seed must be 32 bytes");
        }

        JsonObject config = new JsonObject();
        config.addProperty("dataDir", dataDir.getAbsolutePath());
        config.addProperty("socksHost", torProxy.getHost());
        config.addProperty("socksPort", torProxy.getPort());
        config.addProperty("seedHex", Utils.bytesToHex(seed));
        call(() -> library.as_init(GSON.toJson(config)));
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
     * {@code refundPolicy} is kept as the engine's JSON until the swap screen interprets it.
     */
    public record Quote(String peerId, String multiaddr, String version, long priceSats, long minSats, long maxSats,
                        String refundPolicy) {}
}
