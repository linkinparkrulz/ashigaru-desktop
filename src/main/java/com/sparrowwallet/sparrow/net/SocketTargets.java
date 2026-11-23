package com.sparrowwallet.sparrow.net;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Where a socket connects to. Through a proxy the host name is never looked up here: the address is left unresolved, so the SOCKS request carries
 * the name and the proxy (Tor) resolves it. Without a proxy an onion name is refused rather than given to the system's DNS, which would leak it.
 */
public final class SocketTargets {
    private SocketTargets() {}

    public static InetSocketAddress target(String host, int port, boolean throughProxy) throws IOException {
        if(throughProxy) {
            return InetSocketAddress.createUnresolved(host, port);
        }
        if(Protocol.isOnionHost(host)) {
            throw new IOException("An onion address can only be reached through Tor, and is not looked up without it: " + host);
        }
        return new InetSocketAddress(host, port);
    }
}
