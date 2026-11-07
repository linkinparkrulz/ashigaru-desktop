package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A server address as entered in a host field, which users often paste as a full url rather than a bare host.
 * Accepts a bare host, host:port, tcp://host:port, ssl://host:port and Electrum's host:port:t / host:port:s form.
 */
public record ServerAddress(Protocol protocol, String host, Integer port) {
    private static final Pattern ELECTRUM_SUFFIX = Pattern.compile("^(.+):(\\d{1,5}):([tTsS])$");

    /**
     * @return the parsed address, or null if no host can be determined from the input
     */
    public static ServerAddress parse(String input) {
        if(input == null) {
            return null;
        }

        String text = input.trim();
        Protocol protocol = null;

        int schemeEnd = text.indexOf("://");
        if(schemeEnd >= 0) {
            String scheme = text.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            if(scheme.equals("tcp")) {
                protocol = Protocol.TCP;
            } else if(scheme.equals("ssl")) {
                protocol = Protocol.SSL;
            } else {
                return null;
            }
            text = text.substring(schemeEnd + 3);
        }

        int pathStart = text.indexOf('/');
        if(pathStart >= 0) {
            text = text.substring(0, pathStart);
        }

        Matcher electrumMatcher = ELECTRUM_SUFFIX.matcher(text);
        if(electrumMatcher.matches()) {
            text = electrumMatcher.group(1) + ":" + electrumMatcher.group(2);
            if(protocol == null) {
                protocol = electrumMatcher.group(3).equalsIgnoreCase("s") ? Protocol.SSL : Protocol.TCP;
            }
        }

        try {
            HostAndPort hostAndPort = HostAndPort.fromString(text);
            String host = hostAndPort.getHost();
            if(host.isEmpty() || host.chars().anyMatch(Character::isWhitespace)) {
                return null;
            }

            return new ServerAddress(protocol, host, hostAndPort.hasPort() ? hostAndPort.getPort() : null);
        } catch(IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * @return true if the input carried more than a bare host, i.e. a protocol or port that belongs in other fields
     */
    public boolean hasExtraParts() {
        return protocol != null || port != null;
    }
}
