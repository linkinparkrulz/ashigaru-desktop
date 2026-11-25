package com.sparrowwallet.nightjar.stomp;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal STOMP 1.2 frame encoder/decoder for Whirlpool's coordinator WebSocket protocol.
 */
public final class StompFrame {
    private final String command;
    private final Map<String, String> headers;
    private final String body;

    public StompFrame(String command, Map<String, String> headers, String body) {
        this.command = command;
        this.headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? "" : body;
    }

    public String getCommand() {
        return command;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public String getBody() {
        return body;
    }

    /** Encodes a STOMP 1.2 frame terminated by NUL (\u0000). */
    public String encode() {
        boolean escapeHeaders = !"CONNECT".equals(command) && !"CONNECTED".equals(command);
        StringBuilder sb = new StringBuilder();
        sb.append(command).append('\n');
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (escapeHeaders) {
                sb.append(escapeHeader(entry.getKey())).append(':').append(escapeHeader(entry.getValue())).append('\n');
            } else {
                sb.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
            }
        }
        sb.append('\n');
        if (!body.isEmpty()) {
            sb.append(body);
        }
        sb.append('\u0000');
        return sb.toString();
    }

    /**
     * Parses a STOMP 1.2 frame. Returns {@code null} for heartbeat frames (empty or only CR/LF).
     */
    public static StompFrame decode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int start = 0;
        int len = raw.length();
        while (start < len && (raw.charAt(start) == '\r' || raw.charAt(start) == '\n')) {
            start++;
        }
        if (start >= len) {
            return null; // heartbeat
        }

        int lineEnd = findLineEnd(raw, start);
        if (lineEnd < 0) {
            throw new IllegalArgumentException("Malformed STOMP frame: missing command terminator");
        }
        String command = stripTrailingCr(raw.substring(start, lineEnd));
        boolean unescapeHeaders = !"CONNECT".equals(command) && !"CONNECTED".equals(command);

        Map<String, String> headers = new LinkedHashMap<>();
        int pos = lineEnd + 1;
        while (pos < len) {
            int nextEnd = findLineEnd(raw, pos);
            if (nextEnd < 0) {
                throw new IllegalArgumentException("Malformed STOMP frame: unterminated headers");
            }
            String line = stripTrailingCr(raw.substring(pos, nextEnd));
            pos = nextEnd + 1;
            if (line.isEmpty()) {
                break; // end of headers
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon);
                String value = line.substring(colon + 1);
                if (unescapeHeaders) {
                    key = unescapeHeader(key);
                    value = unescapeHeader(value);
                }
                // STOMP 1.2: first header entry takes precedence
                headers.putIfAbsent(key, value);
            }
        }

        int nul = raw.indexOf('\u0000', pos);
        String body;
        if (nul >= 0) {
            body = raw.substring(pos, nul);
        } else {
            body = raw.substring(pos);
        }

        return new StompFrame(command, headers, body);
    }

    public static int utf8ByteLength(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    static String escapeHeader(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\r' -> out.append("\\r");
                case '\n' -> out.append("\\n");
                case ':' -> out.append("\\c");
                case '\\' -> out.append("\\\\");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String unescapeHeader(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'r' -> out.append('\r');
                    case 'n' -> out.append('\n');
                    case 'c' -> out.append(':');
                    case '\\' -> out.append('\\');
                    default -> {
                        out.append('\\');
                        out.append(next);
                    }
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int findLineEnd(String s, int from) {
        return s.indexOf('\n', from);
    }

    private static String stripTrailingCr(String line) {
        return !line.isEmpty() && line.charAt(line.length() - 1) == '\r'
                ? line.substring(0, line.length() - 1)
                : line;
    }
}
