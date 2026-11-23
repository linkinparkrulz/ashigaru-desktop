package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.Mode;
import com.sparrowwallet.sparrow.io.Config;

import java.io.IOException;

/**
 * The rule for Ashigaru's own web requests to third parties (Whirlpool statistics, fee rates, the PayNym directory and avatars): nothing in
 * offline mode, and nothing without a Tor proxy. A missing proxy (Tor still starting, Tor gone) is an error, never a reason to connect directly,
 * and these services are only reached at their onion addresses.
 */
public final class TorOnly {
    private TorOnly() {}

    /** Why a request must not be made now, or null when it may go through the Tor proxy */
    public static String refusal(Mode mode, HostAndPort torProxy) {
        if(mode == Mode.OFFLINE) {
            return "Ashigaru is in offline mode";
        }
        if(torProxy == null) {
            return "Tor is not running, and Ashigaru does not connect to this service directly";
        }
        return null;
    }

    /** The refusal for the current mode and proxy, or null */
    public static String refusal() {
        return refusal(Config.get().getMode(), AppServices.getTorProxy());
    }

    /** Throws when a request must not be made now */
    public static void require(String service) throws IOException {
        String refusal = refusal();
        if(refusal != null) {
            throw new IOException(service + " not fetched: " + refusal + ".");
        }
    }
}
