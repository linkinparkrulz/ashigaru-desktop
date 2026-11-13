package com.sparrowwallet.sparrow.swap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the screens show about one swap that the engine's own list does not carry: the amounts, where
 * the XMR goes and who the maker is. It is known at two moments, when the swap starts (the lock and
 * fee, the address, the maker) and when the maker's terms are approved (the XMR amount and any
 * deposit), so it is kept beside the swap database and survives a restart, which is when a swap
 * most needs its recovery screen to say what is at stake.
 *
 * @param piconero    the XMR to receive; 0 until the terms are known
 * @param amnestySats the deposit the maker may keep if the swap fails; 0 for none
 */
public record SwapDetails(long lockSats, long feeSats, long piconero, long amnestySats, boolean fullRefund, String xmrAddress, String maker) {
    /** The swap as it starts: the XMR amount and any deposit are not known until the terms are approved */
    public static SwapDetails started(long lockSats, long feeSats, String xmrAddress, String maker) {
        return new SwapDetails(lockSats, feeSats, 0, 0, false, xmrAddress, maker);
    }

    /** The same swap once the maker's terms are known */
    public SwapDetails withTerms(long piconero, long amnestySats, boolean fullRefund) {
        return new SwapDetails(lockSats, feeSats, piconero, amnestySats, fullRefund, xmrAddress, maker);
    }

    /** True when the maker may keep part of the BTC if the swap fails */
    public boolean hasDeposit() {
        return !fullRefund && amnestySats > 0;
    }

    /** The most that can come back if the swap fails, before network fees */
    public long refundSats() {
        return hasDeposit() ? lockSats - amnestySats : lockSats;
    }

    /** The swaps' details on disk, one small file in the swap data directory */
    public static final class Store {
        private static final Logger log = LoggerFactory.getLogger(Store.class);
        private static final String FILE = "ashigaru-swaps.json";
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        private final File file;
        private final Map<String, SwapDetails> details = new ConcurrentHashMap<>();

        /** Opens the store in a swap data directory, reading what is there; an unreadable file is ignored, not fatal */
        public Store(File dataDir) {
            this.file = new File(dataDir, FILE);
            if(file.isFile()) {
                try {
                    //By hand: the Gson this project pins cannot read Java records by reflection
                    JsonObject read = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                    for(String swapId : read.keySet()) {
                        details.put(swapId, fromJson(read.getAsJsonObject(swapId)));
                    }
                } catch(Exception e) {
                    log.warn("Could not read " + file + ": " + e.getMessage());
                }
            }
        }

        private static JsonObject toJson(SwapDetails d) {
            JsonObject json = new JsonObject();
            json.addProperty("lockSats", d.lockSats());
            json.addProperty("feeSats", d.feeSats());
            json.addProperty("piconero", d.piconero());
            json.addProperty("amnestySats", d.amnestySats());
            json.addProperty("fullRefund", d.fullRefund());
            json.addProperty("xmrAddress", d.xmrAddress());
            json.addProperty("maker", d.maker());
            return json;
        }

        private static SwapDetails fromJson(JsonObject json) {
            return new SwapDetails(json.get("lockSats").getAsLong(), json.get("feeSats").getAsLong(), json.get("piconero").getAsLong(),
                    json.get("amnestySats").getAsLong(), json.get("fullRefund").getAsBoolean(),
                    json.has("xmrAddress") && !json.get("xmrAddress").isJsonNull() ? json.get("xmrAddress").getAsString() : null,
                    json.has("maker") && !json.get("maker").isJsonNull() ? json.get("maker").getAsString() : null);
        }

        public SwapDetails get(String swapId) {
            return swapId == null ? null : details.get(swapId);
        }

        public synchronized void put(String swapId, SwapDetails swap) {
            details.put(swapId, swap);
            try {
                File dir = file.getParentFile();
                if(dir != null) {
                    Files.createDirectories(dir.toPath());
                }
                //Write beside and move into place, so a crash never leaves half a file
                File temp = new File(file.getPath() + ".tmp");
                JsonObject all = new JsonObject();
                details.forEach((id, swapDetails) -> all.add(id, toJson(swapDetails)));
                Files.writeString(temp.toPath(), GSON.toJson(all), StandardCharsets.UTF_8);
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch(IOException e) {
                log.warn("Could not save " + file + ": " + e.getMessage());
            }
        }
    }
}
