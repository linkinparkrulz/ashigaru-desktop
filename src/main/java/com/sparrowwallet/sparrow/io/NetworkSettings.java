package com.sparrowwallet.sparrow.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sparrowwallet.drongo.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Settings that must be readable before a network is chosen, or that span networks.
 *
 * <p>{@link Config} lives in the per-network directory, so it can neither say which network to start on
 * nor hold the mainnet and testnet Whirlpool servers side by side. This small file lives in the Ashigaru
 * home folder instead, and so is shared by every network.
 */
public class NetworkSettings {
    private static final Logger log = LoggerFactory.getLogger(NetworkSettings.class);

    public static final String FILENAME = "network.json";

    /** The networks offered in the UI. Others (testnet3, signet, regtest) remain reachable by launch argument. */
    public static final List<Network> SELECTABLE_NETWORKS = List.of(Network.MAINNET, Network.TESTNET4);

    private String network;
    private WhirlpoolEndpoint mainnetWhirlpool;
    private WhirlpoolEndpoint testnet4Whirlpool;

    private static NetworkSettings INSTANCE;

    public static synchronized NetworkSettings get() {
        if(INSTANCE == null) {
            INSTANCE = load(getFile());
        }

        return INSTANCE;
    }

    /** Discards the cached copy so the next {@link #get()} reads the file again. */
    static synchronized void reset() {
        INSTANCE = null;
    }

    private static File getFile() {
        return new File(Storage.getSparrowHome(), FILENAME);
    }

    static NetworkSettings load(File file) {
        if(file.exists()) {
            try(Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                NetworkSettings settings = getGson().fromJson(reader, NetworkSettings.class);
                if(settings != null) {
                    return settings;
                }
            } catch(Exception e) {
                //A damaged file must not stop the app starting; fall back to mainnet and defaults
                log.error("Error reading " + file.getAbsolutePath() + ", using defaults", e);
            }
        }

        return new NetworkSettings();
    }

    public synchronized void flush() {
        save(getFile());
    }

    void save(File file) {
        try {
            File parent = file.getParentFile();
            if(parent != null && !parent.exists()) {
                Storage.createOwnerOnlyDirectory(parent);
            }

            //Write beside the real file then move it over, so a crash cannot leave half a file
            File temp = new File(file.getParentFile(), FILENAME + ".tmp");
            try(Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
                getGson().toJson(this, writer);
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException e) {
            log.error("Error writing " + file.getAbsolutePath(), e);
        }
    }

    private static Gson getGson() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }

    /**
     * The network chosen in the UI, or null if none was saved.
     */
    public Network getNetwork() {
        return parse(network);
    }

    public void setNetwork(Network network) {
        this.network = network == null ? null : normalize(network).name();
    }

    /**
     * The Whirlpool server override for a network, or null to use the built-in server.
     */
    public WhirlpoolEndpoint getWhirlpool(Network network) {
        WhirlpoolEndpoint endpoint = normalize(network) == Network.MAINNET ? mainnetWhirlpool : testnet4Whirlpool;
        return endpoint == null || endpoint.isEmpty() ? null : endpoint;
    }

    public void setWhirlpool(Network network, WhirlpoolEndpoint endpoint) {
        WhirlpoolEndpoint stored = endpoint == null || endpoint.isEmpty() ? null : endpoint;
        if(normalize(network) == Network.MAINNET) {
            mainnetWhirlpool = stored;
        } else {
            testnet4Whirlpool = stored;
        }
    }

    /**
     * Picks the network to start on. The first one that is set wins: launch argument, then the
     * environment variable, then the choice saved from the UI, then a legacy flag file, then mainnet.
     * An explicit launch argument therefore beats whatever was last saved, and the saved choice beats
     * a stale flag file.
     *
     * @param legacyFlags networks with a legacy {@code network-*} flag file present, in the order the
     *                    flags were historically applied (the last one won)
     */
    public static Network resolveNetwork(Network cliNetwork, String envNetwork, Network savedNetwork, List<Network> legacyFlags) {
        if(cliNetwork != null) {
            return normalize(cliNetwork);
        }

        Network fromEnv = parse(envNetwork);
        if(fromEnv != null) {
            return fromEnv;
        }

        if(savedNetwork != null) {
            return normalize(savedNetwork);
        }

        if(legacyFlags != null && !legacyFlags.isEmpty()) {
            return normalize(legacyFlags.get(legacyFlags.size() - 1));
        }

        return Network.MAINNET;
    }

    /**
     * {@link Network#RESTART} is only a launch-argument stand-in for testnet4 (see {@code Args}); it is
     * never a network in its own right.
     */
    static Network normalize(Network network) {
        return network == Network.RESTART ? Network.TESTNET4 : network;
    }

    private static Network parse(String value) {
        if(value == null || value.isBlank()) {
            return null;
        }

        try {
            return normalize(Network.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch(IllegalArgumentException e) {
            log.warn("Ignoring unrecognised network: " + value);
            return null;
        }
    }

    /**
     * A user-supplied Whirlpool coordinator. All fields are optional; blank means "use the built-in value".
     */
    public static class WhirlpoolEndpoint {
        private String coordinatorUrl;
        private String outputRegUrl;
        private String blindSignatureKeyPath;

        public WhirlpoolEndpoint() {
        }

        public WhirlpoolEndpoint(String coordinatorUrl, String outputRegUrl, String blindSignatureKeyPath) {
            this.coordinatorUrl = blankToNull(coordinatorUrl);
            this.outputRegUrl = blankToNull(outputRegUrl);
            this.blindSignatureKeyPath = blankToNull(blindSignatureKeyPath);
        }

        public String getCoordinatorUrl() {
            return coordinatorUrl;
        }

        /** The output registration URL, which defaults to the coordinator URL. */
        public String getOutputRegUrl() {
            return outputRegUrl == null ? coordinatorUrl : outputRegUrl;
        }

        /** The raw output registration URL as entered, or null if it was left to default. */
        public String getOutputRegUrlOverride() {
            return outputRegUrl;
        }

        public String getBlindSignatureKeyPath() {
            return blindSignatureKeyPath;
        }

        /** An override with no coordinator URL does nothing: the other fields only qualify that server. */
        public boolean isEmpty() {
            return coordinatorUrl == null || coordinatorUrl.isBlank();
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
