package com.sparrowwallet.nightjar.stomp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samourai.stomp.client.IStompClient;
import com.samourai.stomp.client.IStompMessage;
import com.samourai.wallet.util.MessageErrorListener;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.sparrowwallet.nightjar.http.JavaHttpClient;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight SockJS + STOMP 1.2 client running directly on Jetty's WebSocketClient,
 * replacing Spring's WebSocketStompClient/SockJsClient stack.
 */
public class JavaStompClient implements IStompClient {
    private static final Logger log = LoggerFactory.getLogger(JavaStompClient.class);
    private static final long HEARTBEAT_DELAY_MS = 20_000L;
    private static final long CONNECT_TIMEOUT_SECONDS = 60L;
    private static final String SESSION_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JavaHttpClient httpClient;
    private final ScheduledExecutorService heartbeatScheduler;
    private final Map<String, MessageErrorListener<IStompMessage, String>> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger subscriptionSeq = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean errorNotified = new AtomicBoolean(false);
    private final AtomicLong lastReceivedAt = new AtomicLong(0L);

    private volatile WebSocketClient webSocketClient;
    private volatile Session wsSession;
    private volatile ScheduledFuture<?> sendHeartbeatTask;
    private volatile ScheduledFuture<?> recvHeartbeatTask;
    private volatile MessageErrorListener<Void, Throwable> connectListener;

    public JavaStompClient(JavaHttpClient httpClient, ScheduledExecutorService heartbeatScheduler) {
        this.httpClient = httpClient;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void connect(String url, Map<String, String> stompHeaders, MessageErrorListener<Void, Throwable> onConnectOnDisconnectListener) {
        this.connectListener = onConnectOnDisconnectListener;
        this.closed.set(false);
        this.errorNotified.set(false);
        this.subscriptions.clear();

        CompletableFuture<Void> connectedFuture = new CompletableFuture<>();
        try {
            HttpClient jettyHttpClient = httpClient.getJettyHttpClient();
            WebSocketClient wsClient = new WebSocketClient(jettyHttpClient);
            wsClient.setStopAtShutdown(false);
            wsClient.start();
            this.webSocketClient = wsClient;

            URI sockJsUri = buildSockJsWebSocketUri(url);
            ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
            upgradeRequest.setHeader("User-Agent", ClientUtils.USER_AGENT);

            if (log.isDebugEnabled()) {
                log.debug("Connecting SockJS/STOMP websocket to " + sockJsUri);
            }

            SockJsStompSocket socket = new SockJsStompSocket(stompHeaders, connectedFuture);
            wsClient.connect(socket, sockJsUri, upgradeRequest).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            connectedFuture.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            disconnect();
            notifyTransportError(e);
        }
    }

    @Override
    public void subscribe(Map<String, String> stompHeaders, MessageErrorListener<IStompMessage, String> onMessageOnErrorListener) {
        String subId = String.valueOf(subscriptionSeq.getAndIncrement());
        subscriptions.put(subId, onMessageOnErrorListener);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("id", subId);
        if (stompHeaders != null) {
            headers.putAll(stompHeaders);
        }
        sendSockJsFrame(new StompFrame("SUBSCRIBE", headers, null).encode());
    }

    @Override
    public void send(Map<String, String> stompHeaders, Object payload) {
        try {
            String body = payload == null ? "" : OBJECT_MAPPER.writeValueAsString(payload);
            Map<String, String> headers = new LinkedHashMap<>();
            if (stompHeaders != null) {
                headers.putAll(stompHeaders);
            }
            if (payload != null) {
                headers.putIfAbsent("content-type", "application/json");
                headers.put("content-length", String.valueOf(StompFrame.utf8ByteLength(body)));
            }
            sendSockJsFrame(new StompFrame("SEND", headers, body).encode());
        } catch (Exception e) {
            log.error(" ! transportException: " + e.getClass().getName() + ": " + e.getMessage());
            disconnect();
            notifyTransportError(e);
        }
    }

    @Override
    public void disconnect() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopHeartbeats();
        subscriptions.clear();

        Session session = wsSession;
        wsSession = null;
        if (session != null) {
            try {
                if (session.isOpen()) {
                    sendSockJsFrameDirect(session, new StompFrame("DISCONNECT", null, null).encode());
                    session.close();
                }
            } catch (Exception ignored) {
            }
        }

        WebSocketClient wsClient = webSocketClient;
        webSocketClient = null;
        if (wsClient != null) {
            try {
                // Keep the shared Jetty HttpClient alive; only stop the WebSocketClient wrapper
                wsClient.removeBean(wsClient.getHttpClient());
                wsClient.stop();
            } catch (Exception ignored) {
            }
        }
    }

    static URI buildSockJsWebSocketUri(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (trimmed.startsWith("https://")) {
            trimmed = "wss://" + trimmed.substring("https://".length());
        } else if (trimmed.startsWith("http://")) {
            trimmed = "ws://" + trimmed.substring("http://".length());
        }
        String serverId = String.format("%03d", RANDOM.nextInt(1000));
        StringBuilder sessionId = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sessionId.append(SESSION_ALPHABET.charAt(RANDOM.nextInt(SESSION_ALPHABET.length())));
        }
        return URI.create(trimmed + "/" + serverId + "/" + sessionId + "/websocket");
    }

    private void sendSockJsFrame(String stompFrame) {
        Session session = wsSession;
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("STOMP WebSocket session is not open");
        }
        try {
            sendSockJsFrameDirect(session, stompFrame);
        } catch (IOException e) {
            log.error(" ! transportError: " + e.getClass().getName() + ": " + e.getMessage());
            disconnect();
            notifyTransportError(e);
        }
    }

    private synchronized void sendSockJsFrameDirect(Session session, String stompFrame) throws IOException {
        String encoded = OBJECT_MAPPER.writeValueAsString(new String[]{stompFrame});
        session.getRemote().sendString(encoded);
    }

    private void startHeartbeats(String serverHeartbeatHeader) {
        long sx = 0L;
        long sy = 0L;
        if (serverHeartbeatHeader != null && !serverHeartbeatHeader.isBlank()) {
            String[] parts = serverHeartbeatHeader.split(",");
            if (parts.length == 2) {
                try {
                    sx = Long.parseLong(parts[0].trim());
                    sy = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        long sendInterval = (HEARTBEAT_DELAY_MS == 0 || sy == 0) ? 0 : Math.max(HEARTBEAT_DELAY_MS, sy);
        long recvInterval = (HEARTBEAT_DELAY_MS == 0 || sx == 0) ? 0 : Math.max(HEARTBEAT_DELAY_MS, sx);

        lastReceivedAt.set(System.currentTimeMillis());

        if (sendInterval > 0) {
            sendHeartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
                Session session = wsSession;
                if (closed.get() || session == null || !session.isOpen()) {
                    return;
                }
                try {
                    sendSockJsFrameDirect(session, "\n");
                } catch (Exception e) {
                    log.debug("Heartbeat send failed", e);
                    disconnect();
                    notifyTransportError(e);
                }
            }, sendInterval, sendInterval, TimeUnit.MILLISECONDS);
        }

        if (recvInterval > 0) {
            long maxInactivityMs = recvInterval * 2;
            recvHeartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
                if (closed.get()) {
                    return;
                }
                long idle = System.currentTimeMillis() - lastReceivedAt.get();
                if (idle > maxInactivityMs) {
                    IOException timeout = new IOException("STOMP heartbeat timeout after " + idle + "ms");
                    log.error(" ! transportError: " + timeout.getMessage());
                    disconnect();
                    notifyTransportError(timeout);
                }
            }, recvInterval, recvInterval, TimeUnit.MILLISECONDS);
        }
    }

    private void stopHeartbeats() {
        ScheduledFuture<?> sendTask = sendHeartbeatTask;
        sendHeartbeatTask = null;
        if (sendTask != null) {
            sendTask.cancel(false);
        }
        ScheduledFuture<?> recvTask = recvHeartbeatTask;
        recvHeartbeatTask = null;
        if (recvTask != null) {
            recvTask.cancel(false);
        }
    }

    private void notifyTransportError(Throwable error) {
        if (errorNotified.compareAndSet(false, true)) {
            MessageErrorListener<Void, Throwable> listener = connectListener;
            if (listener != null) {
                listener.onError(error);
            }
        }
    }

    private final class SockJsStompSocket extends WebSocketAdapter {
        private final Map<String, String> connectHeaders;
        private final CompletableFuture<Void> connectedFuture;

        SockJsStompSocket(Map<String, String> connectHeaders, CompletableFuture<Void> connectedFuture) {
            this.connectHeaders = connectHeaders;
            this.connectedFuture = connectedFuture;
        }

        @Override
        public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            wsSession = session;
            lastReceivedAt.set(System.currentTimeMillis());
        }

        @Override
        public void onWebSocketText(String message) {
            lastReceivedAt.set(System.currentTimeMillis());
            if (message == null || message.isEmpty()) {
                return;
            }

            char type = message.charAt(0);
            if (type == 'o') {
                // SockJS session open -> send STOMP CONNECT
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("accept-version", "1.1,1.2");
                headers.put("heart-beat", HEARTBEAT_DELAY_MS + "," + HEARTBEAT_DELAY_MS);
                if (connectHeaders != null) {
                    headers.putAll(connectHeaders);
                }
                try {
                    sendSockJsFrameDirect(wsSession, new StompFrame("CONNECT", headers, null).encode());
                } catch (Exception e) {
                    failConnect(e);
                }
                return;
            }

            if (type == 'h') {
                // SockJS heartbeat
                return;
            }

            if (type == 'c') {
                // SockJS close frame: c[code,"reason"]
                IOException closeEx = new IOException("SockJS closed: " + message.substring(1));
                failConnect(closeEx);
                disconnect();
                notifyTransportError(closeEx);
                return;
            }

            if (type == 'a') {
                try {
                    String[] frames = OBJECT_MAPPER.readValue(message.substring(1), String[].class);
                    for (String rawFrame : frames) {
                        handleStompFrame(rawFrame);
                    }
                } catch (Exception e) {
                    log.error(" ! transportException: " + e.getClass().getName() + ": " + e.getMessage());
                    failConnect(e);
                    disconnect();
                    notifyTransportError(e);
                }
            }
        }

        private void handleStompFrame(String rawFrame) {
            StompFrame frame = StompFrame.decode(rawFrame);
            if (frame == null) {
                return; // STOMP heartbeat
            }

            switch (frame.getCommand()) {
                case "CONNECTED" -> {
                    if (log.isDebugEnabled()) {
                        log.debug("connected, connectedHeaders=" + frame.getHeaders());
                    }
                    startHeartbeats(frame.getHeader("heart-beat"));
                    connectedFuture.complete(null);
                    MessageErrorListener<Void, Throwable> listener = connectListener;
                    if (listener != null) {
                        listener.onMessage(null);
                    }
                }
                case "MESSAGE" -> {
                    String subId = frame.getHeader("subscription");
                    MessageErrorListener<IStompMessage, String> subListener = subId != null ? subscriptions.get(subId) : null;
                    if (subListener == null && subscriptions.size() == 1) {
                        subListener = subscriptions.values().iterator().next();
                    }
                    if (subListener == null) {
                        log.warn("No subscription listener for STOMP MESSAGE subscription=" + subId);
                        return;
                    }

                    String messageType = frame.getHeader(WhirlpoolProtocol.HEADER_MESSAGE_TYPE);
                    if (messageType == null) {
                        subListener.onError("missing " + WhirlpoolProtocol.HEADER_MESSAGE_TYPE + " header");
                        return;
                    }
                    try {
                        Class<?> payloadClass = Class.forName(messageType);
                        Object payload = frame.getBody().isEmpty()
                                ? null
                                : OBJECT_MAPPER.readValue(frame.getBody(), payloadClass);
                        subListener.onMessage(new JavaStompMessage(frame.getHeaders(), payload));
                    } catch (ClassNotFoundException e) {
                        log.error("unknown message type: " + messageType, e);
                        subListener.onError("unknown message type: " + messageType);
                    } catch (Exception e) {
                        log.error(" ! transportException: " + e.getClass().getName() + ": " + e.getMessage());
                        subListener.onError("failed to decode message: " + e.getMessage());
                    }
                }
                case "ERROR" -> {
                    String msg = frame.getHeader("message");
                    IOException err = new IOException("STOMP ERROR: " + (msg != null ? msg : frame.getBody()));
                    log.error(" ! transportError: " + err.getMessage());
                    failConnect(err);
                    disconnect();
                    notifyTransportError(err);
                }
                default -> {
                    if (log.isDebugEnabled()) {
                        log.debug("Ignoring STOMP frame: " + frame.getCommand());
                    }
                }
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            super.onWebSocketClose(statusCode, reason);
            IOException closeEx = new IOException("WebSocket closed (" + statusCode + "): " + reason);
            failConnect(closeEx);
            if (!closed.get()) {
                disconnect();
                notifyTransportError(closeEx);
            }
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            super.onWebSocketError(cause);
            log.error(" ! transportError: " + cause.getClass().getName() + ": " + cause.getMessage());
            failConnect(cause);
            disconnect();
            notifyTransportError(cause);
        }

        private void failConnect(Throwable cause) {
            if (!connectedFuture.isDone()) {
                connectedFuture.completeExceptionally(cause);
            }
        }
    }
}
