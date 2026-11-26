package com.sparrowwallet.sparrow.io;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.net.Protocol;

import java.util.Arrays;

public class Server {
    /** A suffix earlier versions put in the names of Dojo Bay servers. It is no longer used, and is taken off names saved with it. */
    static final String RETIRED_ALIAS_SUFFIX = " (unverified endpoint)";

    private final String url;
    private final String alias;

    public Server(String url) {
        this(url, null);
    }

    public Server(String url, String alias) {
        if(url == null) {
            throw new IllegalArgumentException("Url cannot be null");
        }

        if(url.isEmpty()) {
            throw new IllegalArgumentException("Url cannot be empty");
        }

        if(Protocol.getProtocol(url) == null) {
            throw new IllegalArgumentException("Unknown protocol for url " + url + ", must be one of " + Arrays.toString(Protocol.values()));
        }

        if(Protocol.getHost(url) == null) {
            throw new IllegalArgumentException("Cannot determine host for url " + url);
        }

        if(alias != null && alias.isEmpty()) {
            throw new IllegalArgumentException("Server alias cannot be an empty string");
        }

        this.url = url;
        this.alias = withoutRetiredSuffix(alias);
    }

    public String getUrl() {
        return url;
    }

    public Protocol getProtocol() {
        return Protocol.getProtocol(url);
    }

    public HostAndPort getHostAndPort() {
        return getProtocol().getServerHostAndPort(url);
    }

    public String getHost() {
        return getHostAndPort().getHost();
    }

    public boolean isOnionAddress() {
        return Protocol.isOnionAddress(getHostAndPort());
    }

    public String getAlias() {
        return alias;
    }

    public String getDisplayName() {
        return alias == null ? url : alias;
    }

    /**
     * A name short enough for the top bar: the alias when there is one; otherwise the host, without protocol or port, with an onion
     * address shortened to its first 8 and last 6 characters. The full url is for a tooltip.
     */
    public String getShortDisplayName() {
        if(alias != null) {
            return alias;
        }
        return shortHost(getHost());
    }

    static String shortHost(String host) {
        if(host == null) {
            return "";
        }
        String suffix = ".onion";
        if(host.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
            String name = host.substring(0, host.length() - suffix.length());
            if(name.length() > 16) {
                return name.substring(0, 8) + "\u2026" + name.substring(name.length() - 6) + suffix;
            }
        }
        return host;
    }

    private static String withoutRetiredSuffix(String alias) {
        if(alias != null && alias.endsWith(RETIRED_ALIAS_SUFFIX) && alias.length() > RETIRED_ALIAS_SUFFIX.length()) {
            return alias.substring(0, alias.length() - RETIRED_ALIAS_SUFFIX.length());
        }
        return alias;
    }

    public String toString() {
        return url + (alias == null ? "" : "|" + alias);
    }

    public boolean portEquals(String port) {
        if(port == null) {
            return !getHostAndPort().hasPort();
        }

        return port.equals(getHostAndPort().hasPort() ? Integer.toString(getHostAndPort().getPort()) : "");
    }

    public static Server fromString(String server) {
        String[] parts = server.split("\\|");
        if(parts.length >= 2) {
            return new Server(parts[0], parts[1]);
        }

        return new Server(parts[0], null);
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) {
            return true;
        }
        if(o == null || getClass() != o.getClass()) {
            return false;
        }

        Server server = (Server)o;
        return url.equals(server.url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }
}
