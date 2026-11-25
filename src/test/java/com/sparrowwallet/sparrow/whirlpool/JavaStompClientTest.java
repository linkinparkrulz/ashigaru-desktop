package com.sparrowwallet.sparrow.whirlpool;

import com.google.common.net.HostAndPort;
import com.samourai.http.client.HttpUsage;
import com.samourai.stomp.client.IStompClient;
import com.samourai.stomp.client.IStompMessage;
import com.samourai.wallet.util.MessageErrorListener;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.samourai.whirlpool.protocol.websocket.messages.RegisterInputRequest;
import com.samourai.whirlpool.protocol.websocket.messages.SubscribePoolResponse;
import com.sparrowwallet.nightjar.http.JavaHttpClientService;
import com.sparrowwallet.nightjar.stomp.JavaStompClientService;
import com.sparrowwallet.nightjar.stomp.StompFrame;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the native SockJS + STOMP 1.2 client (replacing Spring WebSocket/Messaging)
 * and the JDK PEM RSA key loader (replacing BouncyCastle bcpkix).
 */
class JavaStompClientTest {

    @Test
    void stompFrameEncodeDecodeRoundTripAndEscaping() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("destination", "/user/queue/reply");
        headers.put("custom", "a:b\\c\nd\re");

        String encoded = new StompFrame("MESSAGE", headers, "{\"ok\":true}").encode();
        assertTrue(encoded.endsWith("\u0000"));
        assertTrue(encoded.contains("custom:a\\cb\\\\c\\nd\\re\n"));

        StompFrame decoded = StompFrame.decode(encoded);
        assertNotNull(decoded);
        assertEquals("MESSAGE", decoded.getCommand());
        assertEquals("/user/queue/reply", decoded.getHeader("destination"));
        assertEquals("a:b\\c\nd\re", decoded.getHeader("custom"));
        assertEquals("{\"ok\":true}", decoded.getBody());

        assertNull(StompFrame.decode("\n"));
        assertNull(StompFrame.decode("\r\n"));
    }

    @Test
    void cipherHelperLoadsStandardPemRsaPublicKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        RSAPublicKey expected = (RSAPublicKey) kp.getPublic();

        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(expected.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";

        Object loaded = Class.forName("com.samourai.whirlpool.client.utils.CipherHelper")
                .getMethod("loadRSAPublicKey", InputStream.class)
                .invoke(null, new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(loaded);
        assertEquals(Boolean.FALSE, loaded.getClass().getMethod("isPrivate").invoke(loaded));
        assertEquals(expected.getModulus(), loaded.getClass().getMethod("getModulus").invoke(loaded));
        assertEquals(expected.getPublicExponent(), loaded.getClass().getMethod("getExponent").invoke(loaded));
    }

    @Test
    void sockJsStompHandshakeSubscribeSendAndMessageDelivery() throws Exception {
        CopyOnWriteArrayList<StompFrame> serverReceived = new CopyOnWriteArrayList<>();
        CountDownLatch sendReceivedLatch = new CountDownLatch(1);
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<IStompMessage> clientReceived = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();

        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket sock = server.accept()) {
                    InputStream in = sock.getInputStream();
                    OutputStream out = sock.getOutputStream();

                    // Perform RFC 6455 WebSocket upgrade handshake
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
                    String requestLine = reader.readLine();
                    requestPath.set(requestLine);
                    String wsKey = null;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                            wsKey = line.substring("sec-websocket-key:".length()).trim();
                        }
                    }
                    String accept = Base64.getEncoder().encodeToString(
                            MessageDigest.getInstance("SHA-1").digest(
                                    (wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    String response = "HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    // 1. Send SockJS open frame
                    writeWsTextFrame(out, "o");

                    // 2. Read CONNECT frame (SockJS wrapped as ["CONNECT\n..."])
                    String connectJson = readWsTextFrame(in);
                    StompFrame connectFrame = StompFrame.decode(unwrapSockJsSingleFrame(connectJson));
                    serverReceived.add(connectFrame);

                    // 3. Reply with CONNECTED
                    Map<String, String> connectedHeaders = new LinkedHashMap<>();
                    connectedHeaders.put("version", "1.2");
                    connectedHeaders.put("heart-beat", "20000,20000");
                    writeWsTextFrame(out, wrapSockJsServerFrame(new StompFrame("CONNECTED", connectedHeaders, null).encode()));

                    // 4. Read SUBSCRIBE
                    String subJson = readWsTextFrame(in);
                    StompFrame subFrame = StompFrame.decode(unwrapSockJsSingleFrame(subJson));
                    serverReceived.add(subFrame);

                    // 5. Deliver a Whirlpool SubscribePoolResponse MESSAGE
                    Map<String, String> msgHeaders = new LinkedHashMap<>();
                    msgHeaders.put("subscription", subFrame.getHeader("id"));
                    msgHeaders.put("destination", subFrame.getHeader("destination"));
                    msgHeaders.put(WhirlpoolProtocol.HEADER_PROTOCOL_VERSION, WhirlpoolProtocol.PROTOCOL_VERSION);
                    msgHeaders.put(WhirlpoolProtocol.HEADER_MESSAGE_TYPE, SubscribePoolResponse.class.getName());
                    String payloadJson = "{\"networkId\":\"main\",\"denomination\":1000000,\"mustMixBalanceMin\":1000500,\"mustMixBalanceMax\":1050000}";
                    writeWsTextFrame(out, wrapSockJsServerFrame(new StompFrame("MESSAGE", msgHeaders, payloadJson).encode()));

                    // 6. Read SEND from client
                    String sendJson = readWsTextFrame(in);
                    StompFrame sendFrame = StompFrame.decode(unwrapSockJsSingleFrame(sendJson));
                    serverReceived.add(sendFrame);
                    sendReceivedLatch.countDown();
                } catch (Exception ignored) {
                }
            }, "mock-stomp-server");
            serverThread.setDaemon(true);
            serverThread.start();

            JavaHttpClientService httpService = new JavaHttpClientService((HostAndPort) null, 10_000);
            JavaStompClientService stompService = new JavaStompClientService(httpService);
            try {
                IStompClient client = stompService.newStompClient();
                Map<String, String> connectHeaders = Map.of(WhirlpoolProtocol.HEADER_POOL_ID, "0.01btc");
                client.connect("ws://127.0.0.1:" + port + "/ws/connect", connectHeaders, new MessageErrorListener<>() {
                    @Override
                    public void onMessage(Void message) {
                        connectedLatch.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                    }
                });

                assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "STOMP CONNECTED should fire");
                assertNotNull(requestPath.get());
                assertTrue(requestPath.get().matches("GET /ws/connect/\\d{3}/[a-z0-9]{8}/websocket HTTP/1\\.1"),
                        "Unexpected SockJS path: " + requestPath.get());

                client.subscribe(
                        Map.of("destination", "/user/queue/reply", WhirlpoolProtocol.HEADER_POOL_ID, "0.01btc"),
                        new MessageErrorListener<>() {
                            @Override
                            public void onMessage(IStompMessage message) {
                                clientReceived.set(message);
                                messageLatch.countDown();
                            }

                            @Override
                            public void onError(String error) {
                                fail("Unexpected subscribe error: " + error);
                            }
                        });

                assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "STOMP MESSAGE should be delivered");
                IStompMessage msg = clientReceived.get();
                assertNotNull(msg);
                assertEquals(WhirlpoolProtocol.PROTOCOL_VERSION, msg.getStompHeader(WhirlpoolProtocol.HEADER_PROTOCOL_VERSION));
                assertInstanceOf(SubscribePoolResponse.class, msg.getPayload());
                assertEquals(1_000_000L, ((SubscribePoolResponse) msg.getPayload()).denomination);

                RegisterInputRequest req = new RegisterInputRequest("0.01btc", "deadbeef", 0L, "sig", false);
                client.send(Map.of("destination", "/ws/registerInput", WhirlpoolProtocol.HEADER_PROTOCOL_VERSION, WhirlpoolProtocol.PROTOCOL_VERSION), req);

                assertTrue(sendReceivedLatch.await(5, TimeUnit.SECONDS), "STOMP SEND should reach server");
                assertEquals(3, serverReceived.size());
                assertEquals("CONNECT", serverReceived.get(0).getCommand());
                assertEquals("0.01btc", serverReceived.get(0).getHeader(WhirlpoolProtocol.HEADER_POOL_ID));
                assertEquals("SUBSCRIBE", serverReceived.get(1).getCommand());
                assertEquals("SEND", serverReceived.get(2).getCommand());
                assertEquals("/ws/registerInput", serverReceived.get(2).getHeader("destination"));
                assertEquals("application/json", serverReceived.get(2).getHeader("content-type"));
                assertTrue(serverReceived.get(2).getBody().contains("\"deadbeef\""));

                client.disconnect();
            } finally {
                stompService.shutdown();
                httpService.shutdown();
            }
        }
    }

    private static String wrapSockJsServerFrame(String stompFrame) throws Exception {
        return "a" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new String[]{stompFrame});
    }

    private static String unwrapSockJsSingleFrame(String sockJsPayload) throws Exception {
        String[] frames = new com.fasterxml.jackson.databind.ObjectMapper().readValue(sockJsPayload, String[].class);
        return frames[0];
    }

    private static void writeWsTextFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81); // FIN + text frame
        if (payload.length < 126) {
            out.write(payload.length);
        } else {
            out.write(126);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        }
        out.write(payload);
        out.flush();
    }

    private static String readWsTextFrame(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 < 0 || b2 < 0) {
            throw new IOException("EOF");
        }
        boolean masked = (b2 & 0x80) != 0;
        int len = b2 & 0x7F;
        if (len == 126) {
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            long l = 0;
            for (int i = 0; i < 8; i++) {
                l = (l << 8) | (in.read() & 0xFF);
            }
            len = (int) l;
        }
        byte[] mask = new byte[4];
        if (masked) {
            in.readNBytes(mask, 0, 4);
        }
        byte[] payload = in.readNBytes(len);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        return new String(payload, StandardCharsets.UTF_8);
    }
}
