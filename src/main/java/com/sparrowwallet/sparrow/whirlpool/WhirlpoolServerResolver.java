package com.sparrowwallet.sparrow.whirlpool;

import com.samourai.whirlpool.client.wallet.beans.WhirlpoolServer;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.io.NetworkSettings;

import java.util.Locale;

/**
 * Chooses the Whirlpool coordinator for a network: the user's override from Settings if there is one,
 * otherwise the built-in {@link WhirlpoolServer}.
 */
public final class WhirlpoolServerResolver {
    private WhirlpoolServerResolver() {
    }

    /**
     * The built-in server for a network. Chosen explicitly rather than by looking the network's name up
     * among {@link WhirlpoolServer}'s constants: those names do not line up (testnet3 is named "testnet3"
     * but its server constant is TESTNET), so a name lookup throws for one testnet and happens to work for another.
     */
    public static WhirlpoolServer builtInServer(Network network) {
        if(!Whirlpool.WHIRLPOOL_NETWORKS.contains(network)) {
            throw new IllegalArgumentException("Whirlpool is not available on " + network.getName().toUpperCase(Locale.ROOT));
        }

        return network == Network.MAINNET ? WhirlpoolServer.MAINNET : WhirlpoolServer.TESTNET;
    }

    /**
     * @param onion whether the built-in onion address should be used in preference to the clear one. A user override is
     *              a single address, used as given whichever way the connection is made.
     */
    public static ServerUrls resolve(WhirlpoolServer builtIn, NetworkSettings.WhirlpoolEndpoint override, boolean onion) {
        if(override != null && !override.isEmpty()) {
            return new ServerUrls(override.getCoordinatorUrl(), override.getOutputRegUrl(), true);
        }

        return new ServerUrls(builtIn.getServerUrl(onion), builtIn.getServerUrlOutputReg(), false);
    }

    public static ServerUrls resolve(Network network, boolean onion) {
        return resolve(builtInServer(network), NetworkSettings.get().getWhirlpool(network), onion);
    }

    /**
     * The user's blind-signature public key file for the network, or null to use the bundled key.
     */
    public static String blindSignatureKeyFile(Network network) {
        NetworkSettings.WhirlpoolEndpoint override = NetworkSettings.get().getWhirlpool(network);
        return override == null ? null : override.getBlindSignatureKeyPath();
    }

    /**
     * Identifies the user's server settings for a network, so an engine can tell that they changed since it
     * built its connection. Empty when there is no override.
     */
    public static String overrideFingerprint(Network network) {
        NetworkSettings.WhirlpoolEndpoint override = NetworkSettings.get().getWhirlpool(network);
        return override == null ? "" : override.getCoordinatorUrl() + "|" + override.getOutputRegUrl() + "|" + override.getBlindSignatureKeyPath();
    }

    /**
     * @param overridden true if these came from the user's settings rather than the built-in server
     */
    public record ServerUrls(String serverUrl, String serverUrlOutputReg, boolean overridden) {
    }
}
