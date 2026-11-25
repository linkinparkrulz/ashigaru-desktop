package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AD-36: a name reached through a SOCKS proxy is never looked up on this computer. A fake proxy records the name the SOCKS request carries, and a
 * recording resolver (installed for the test JVM through META-INF/services) shows the name was never given to the system's DNS.
 */
class NoLocalOnionLookupTest {
    private static final String ONION = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion";

    @BeforeAll
    static void resolverInstalled() throws Exception {
        //Any lookup loads the resolver; the check below would prove nothing with the built-in one
        InetAddress.getByName("127.0.0.1");
        InetAddress.getAllByName("localhost");
        assertTrue(RecordingResolverProvider.installed, "the recording resolver is not in use, so lookups cannot be seen");
    }

    /** A one-shot SOCKS4a/SOCKS5 server: reads one CONNECT, completes with the host name it asked for, then refuses it */
    private static CompletableFuture<String> fakeProxy(ServerSocket server) {
        CompletableFuture<String> requested = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try(Socket client = server.accept()) {
                client.setSoTimeout(10_000);
                DataInputStream in = new DataInputStream(client.getInputStream());
                OutputStream out = client.getOutputStream();
                int version = in.readUnsignedByte();
                if(version == 5) {
                    int methods = in.readUnsignedByte();
                    in.readFully(new byte[methods]);
                    out.write(new byte[] {5, 0});
                    out.flush();
                    in.readUnsignedByte(); in.readUnsignedByte(); in.readUnsignedByte();
                    int atyp = in.readUnsignedByte();
                    String host;
                    if(atyp == 3) {
                        byte[] name = new byte[in.readUnsignedByte()];
                        in.readFully(name);
                        host = new String(name, StandardCharsets.US_ASCII);
                    } else {
                        byte[] address = new byte[atyp == 1 ? 4 : 16];
                        in.readFully(address);
                        host = InetAddress.getByAddress(address).getHostAddress();
                    }
                    requested.complete(host);
                    out.write(new byte[] {5, 1, 0, 1, 0, 0, 0, 0, 0, 0});
                } else if(version == 4) {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    byte[] ip = new byte[4];
                    in.readFully(ip);
                    while(in.readUnsignedByte() != 0) {}
                    String host;
                    if(ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0) {
                        StringBuilder name = new StringBuilder();
                        int c;
                        while((c = in.readUnsignedByte()) != 0) {
                            name.append((char)c);
                        }
                        host = name.toString();
                    } else {
                        host = InetAddress.getByAddress(ip).getHostAddress();
                    }
                    requested.complete(host);
                    out.write(new byte[] {0, 0x5B, 0, 0, 0, 0, 0, 0});
                } else {
                    requested.completeExceptionally(new IOException("not SOCKS: " + version));
                }
                out.flush();
            } catch(Exception e) {
                requested.completeExceptionally(e);
            }
        }, "fake-socks");
        thread.setDaemon(true);
        thread.start();
        return requested;
    }

    private static void assertNotLookedUp() {
        assertFalse(RecordingResolverProvider.LOOKUPS.stream().anyMatch(name -> name.toLowerCase().endsWith(".onion")),
                "an onion name was given to the system's DNS: " + RecordingResolverProvider.LOOKUPS);
    }

    @Test
    void anElectrumConnectionThroughAProxySendsTheNameToTheProxy() throws Exception {
        try(ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> requested = fakeProxy(server);
            TcpTransport transport = new TcpTransport(HostAndPort.fromParts(ONION, 50001), HostAndPort.fromParts("127.0.0.1", server.getLocalPort()));
            assertThrows(Exception.class, transport::connect, "the fake proxy refuses the connection");
            assertEquals(ONION, requested.get(10, TimeUnit.SECONDS));
            assertNotLookedUp();
        }
    }

    @Test
    void aTlsElectrumConnectionThroughAProxySendsTheNameToTheProxy() throws Exception {
        try(ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> requested = fakeProxy(server);
            ProxyTcpOverTlsTransport transport = new ProxyTcpOverTlsTransport(HostAndPort.fromParts(ONION, 50002), HostAndPort.fromParts("127.0.0.1", server.getLocalPort()));
            assertThrows(Exception.class, transport::connect);
            assertEquals(ONION, requested.get(10, TimeUnit.SECONDS));
            assertNotLookedUp();
        }
    }

    @Test
    void anHttpFetchThroughTheProxySendsTheNameToTheProxy() throws Exception {
        //Stats, fee rates and the PayNym directory and avatars all use this client
        for(boolean bytes : new boolean[] {false, true}) {
            try(ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                CompletableFuture<String> requested = fakeProxy(server);
                HttpClientService http = new HttpClientService(HostAndPort.fromParts("127.0.0.1", server.getLocalPort()));
                try {
                    assertThrows(Exception.class, () -> {
                        if(bytes) {
                            http.requestBytes("http://" + ONION + "/avatar", java.util.Map.of());
                        } else {
                            http.requestString("http://" + ONION + "/api/summary", java.util.Map.of());
                        }
                    });
                } finally {
                    http.shutdown();
                }
                assertEquals(ONION, requested.get(10, TimeUnit.SECONDS));
                assertNotLookedUp();
            }
        }
    }

    @Test
    void withoutAProxyAnOnionNameIsRefusedNotLookedUp() {
        assertThrows(IOException.class, () -> SocketTargets.target(ONION, 50001, false));
        assertTrue(assertDoesNotThrow(() -> SocketTargets.target("electrum.example.org", 50002, true)).isUnresolved(), "through a proxy, no name is resolved here");
        assertNotLookedUp();
    }
}
