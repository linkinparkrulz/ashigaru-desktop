package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.sparrow.io.Server;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ServerAddressTest {
    private static final String ONION = "uluwrkrmha3hwpf6d6bt4bsobtanlquxyrqeeovxo7nuurn3w7encsqd.onion";

    @Test
    public void bareHost() {
        ServerAddress address = ServerAddress.parse(ONION);
        Assertions.assertEquals(new ServerAddress(null, ONION, null), address);
        Assertions.assertFalse(address.hasExtraParts());
    }

    @Test
    public void tcpUrl() {
        ServerAddress address = ServerAddress.parse("tcp://" + ONION + ":50001");
        Assertions.assertEquals(new ServerAddress(Protocol.TCP, ONION, 50001), address);
        Server server = new Server(address.protocol().toUrlString(address.host(), address.port()));
        Assertions.assertEquals("tcp://" + ONION + ":50001", server.getUrl());
        Assertions.assertTrue(server.isOnionAddress());
    }

    @Test
    public void sslUrlWithWhitespaceAndPath() {
        Assertions.assertEquals(new ServerAddress(Protocol.SSL, "electrum.example.com", 50002), ServerAddress.parse("  SSL://electrum.example.com:50002/ \n"));
    }

    @Test
    public void hostAndPort() {
        Assertions.assertEquals(new ServerAddress(null, ONION, 50001), ServerAddress.parse(ONION + ":50001"));
    }

    @Test
    public void electrumFormat() {
        Assertions.assertEquals(new ServerAddress(Protocol.TCP, ONION, 50001), ServerAddress.parse(ONION + ":50001:t"));
        Assertions.assertEquals(new ServerAddress(Protocol.SSL, "10.0.0.5", 50002), ServerAddress.parse("10.0.0.5:50002:s"));
    }

    @Test
    public void ipv6() {
        Assertions.assertEquals(new ServerAddress(Protocol.TCP, "::1", 50001), ServerAddress.parse("tcp://[::1]:50001"));
    }

    @Test
    public void invalid() {
        Assertions.assertNull(ServerAddress.parse(null));
        Assertions.assertNull(ServerAddress.parse(""));
        Assertions.assertNull(ServerAddress.parse("http://" + ONION + ":50001"));
        Assertions.assertNull(ServerAddress.parse(ONION + ":notaport"));
        Assertions.assertNull(ServerAddress.parse("my host"));
    }
}
