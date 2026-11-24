package com.sparrowwallet.sparrow.net;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AD-38: requests through Tor that HttpURLConnection used to make. A fake SOCKS5 proxy accepts each connection, records the host name the
 * request carries, and then answers as the web server; the recording resolver shows no name was looked up on this computer.
 */
class ProxiedHttpTest {
    private static final String ONION = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion";
    private static final String OTHER = "bbcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion";

    @BeforeAll
    static void resolverInstalled() throws Exception {
        InetAddress.getAllByName("localhost");
        assertTrue(RecordingResolverProvider.installed, "the recording resolver is not in use, so lookups cannot be seen");
    }

    /** A SOCKS5 proxy that is also the web server: it answers the n-th connection with the n-th canned response */
    private static final class FakeProxy implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        final List<String> hosts = new CopyOnWriteArrayList<>();
        final List<String> requests = new CopyOnWriteArrayList<>();

        FakeProxy(String... responses) throws IOException {
            Thread thread = new Thread(() -> {
                for(String response : responses) {
                    try(Socket client = server.accept()) {
                        client.setSoTimeout(10_000);
                        DataInputStream in = new DataInputStream(client.getInputStream());
                        OutputStream out = client.getOutputStream();
                        in.readUnsignedByte();
                        in.readFully(new byte[in.readUnsignedByte()]);
                        out.write(new byte[] {5, 0});
                        in.readUnsignedByte(); in.readUnsignedByte(); in.readUnsignedByte();
                        int atyp = in.readUnsignedByte();
                        if(atyp == 3) {
                            byte[] name = new byte[in.readUnsignedByte()];
                            in.readFully(name);
                            hosts.add(new String(name, StandardCharsets.US_ASCII));
                        } else {
                            byte[] address = new byte[atyp == 1 ? 4 : 16];
                            in.readFully(address);
                            hosts.add(InetAddress.getByAddress(address).getHostAddress());
                        }
                        in.readUnsignedShort();
                        out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
                        out.flush();
                        StringBuilder head = new StringBuilder();
                        while(!head.toString().endsWith("\r\n\r\n")) {
                            head.append((char)in.readUnsignedByte());
                        }
                        String text = head.toString();
                        int length = text.toLowerCase().contains("content-length: ") ? Integer.parseInt(text.toLowerCase().split("content-length: ")[1].split("\r\n")[0].trim()) : 0;
                        byte[] body = new byte[length];
                        in.readFully(body);
                        requests.add(text + new String(body, StandardCharsets.UTF_8));
                        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    } catch(IOException e) {
                        return;
                    }
                }
            }, "fake-socks-http");
            thread.setDaemon(true);
            thread.start();
        }

        Proxy proxy() {
            return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", server.getLocalPort()));
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static void assertNotLookedUp() {
        assertFalse(RecordingResolverProvider.LOOKUPS.stream().anyMatch(name -> name.toLowerCase().endsWith(".onion")),
                "an onion name was given to the system's DNS: " + RecordingResolverProvider.LOOKUPS);
    }

    @Test
    void aPostWithASizedResponse() throws Exception {
        try(FakeProxy proxy = new FakeProxy("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 15\r\n\r\n{\"result\":true}")) {
            try(ProxiedHttp.Response response = ProxiedHttp.request("POST", new URL("http://" + ONION + ":8332/wallet/w"),
                    Map.of("Content-Type", "application/json"), "{\"method\":\"x\"}".getBytes(StandardCharsets.UTF_8), proxy.proxy(), null, 10_000, 10_000)) {
                assertEquals(200, response.status());
                assertEquals(15, response.contentLength());
                assertEquals("{\"result\":true}", response.bodyText());
            }
            assertEquals(List.of(ONION), proxy.hosts, "the name goes to the proxy");
            String request = proxy.requests.get(0);
            assertTrue(request.startsWith("POST /wallet/w HTTP/1.1\r\nHost: " + ONION + ":8332\r\nConnection: close\r\n"), request);
            assertTrue(request.endsWith("{\"method\":\"x\"}"));
            assertNotLookedUp();
        }
    }

    @Test
    void aChunkedResponse() throws Exception {
        try(FakeProxy proxy = new FakeProxy("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n7;ext=1\r\n, world\r\n0\r\nX-Trailer: y\r\n\r\n")) {
            try(ProxiedHttp.Response response = ProxiedHttp.request("GET", new URL("http://" + ONION + "/"), Map.of(), null, proxy.proxy(), null, 10_000, 10_000)) {
                assertEquals(-1, response.contentLength());
                assertEquals("hello, world", response.bodyText());
            }
            assertNotLookedUp();
        }
    }

    @Test
    void aBodyThatRunsToTheClose() throws Exception {
        try(FakeProxy proxy = new FakeProxy("HTTP/1.0 200 OK\r\n\r\nall of it")) {
            try(ProxiedHttp.Response response = ProxiedHttp.request("GET", new URL("http://" + ONION + "/x"), Map.of(), null, proxy.proxy(), null, 10_000, 10_000)) {
                assertEquals("all of it", response.bodyText());
            }
        }
    }

    @Test
    void aGetFollowsRedirectsAndNeitherNameIsLookedUp() throws Exception {
        //As GitHub's release downloads do: the asset redirects to its storage host
        try(FakeProxy proxy = new FakeProxy("HTTP/1.1 302 Found\r\nLocation: http://" + OTHER + "/asset?x=1\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndata")) {
            try(ProxiedHttp.Response response = ProxiedHttp.request("GET", new URL("http://" + ONION + "/download"), Map.of("User-Agent", "Ashigaru-Desktop"), null,
                    proxy.proxy(), null, 10_000, 10_000)) {
                assertEquals(200, response.status());
                assertEquals("data", response.bodyText());
            }
            assertEquals(List.of(ONION, OTHER), proxy.hosts);
            assertTrue(proxy.requests.get(1).startsWith("GET /asset?x=1 HTTP/1.1\r\n"));
            assertNotLookedUp();
        }
    }

    @Test
    void withoutASocksProxyNothingIsSent() {
        assertThrows(IOException.class, () -> ProxiedHttp.request("GET", new URL("http://" + ONION + "/"), Map.of(), null, null, null, 1000, 1000));
        assertThrows(IOException.class, () -> ProxiedHttp.request("GET", new URL("http://" + ONION + "/"), Map.of(), null, Proxy.NO_PROXY, null, 1000, 1000));
        assertThrows(IllegalArgumentException.class, () -> ProxiedHttp.requestHead("GET", new URL("http://a/"), "a", 80, false, Map.of("X", "1\r\nInjected: 2"), null));
    }

    @Test
    void noHttpUrlConnectionGoesThroughAProxyAnywhere() throws IOException {
        //HttpURLConnection over SOCKS looks the host up on this computer first; every request through a proxy uses ProxiedHttp or the Jetty client
        try(Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> offenders = files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try {
                    String source = Files.readString(path);
                    return source.matches("(?s).*\\.openConnection\\(\\s*[A-Za-z_.()]*[Pp]roxy[A-Za-z_.()]*\\s*\\).*");
                } catch(IOException e) {
                    return true;
                }
            }).map(Path::toString).toList();
            assertEquals(List.of(), offenders);
        }
    }
}
