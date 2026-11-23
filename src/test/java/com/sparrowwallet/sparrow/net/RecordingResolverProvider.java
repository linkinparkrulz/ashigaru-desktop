package com.sparrowwallet.sparrow.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/** A name resolver for the tests that records every name the JVM is asked to look up, and then looks it up as usual */
public class RecordingResolverProvider extends InetAddressResolverProvider {
    static volatile boolean installed;
    static final List<String> LOOKUPS = new CopyOnWriteArrayList<>();

    @Override
    public InetAddressResolver get(Configuration configuration) {
        installed = true;
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
                LOOKUPS.add(host);
                return builtin.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "Recording resolver (tests)";
    }
}
