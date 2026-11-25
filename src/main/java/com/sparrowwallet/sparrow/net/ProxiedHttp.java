package com.sparrowwallet.sparrow.net;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A small HTTP/1.1 client for requests that go through a SOCKS proxy (Tor) and must never look the host name up on this computer.
 * {@link java.net.HttpURLConnection} does, even through a SOCKS proxy: it resolves the host before handing it to the proxy, which sends
 * an onion name, or any name, to the system's DNS. Here the socket is connected to an unresolved address ({@link SocketTargets}), so the
 * SOCKS request carries the name and the proxy resolves it.
 *
 * <p>TLS is layered over that socket: with the caller's socket factory (Bitcoin Core's certificate pinning), or the default one with
 * hostname verification. One request per connection ({@code Connection: close}); bodies may be sized, chunked or read to the close.
 * GET follows up to {@value #MAX_REDIRECTS} redirects (GitHub release downloads redirect to their storage host), never from https to http.
 * Every body is capped: {@value #DEFAULT_MAX_BODY_BYTES} bytes unless the caller sets its own cap.
 */
public final class ProxiedHttp {
    static final int MAX_REDIRECTS = 5;
    /** The body cap when the caller does not set one: 16 MiB */
    public static final long DEFAULT_MAX_BODY_BYTES = 16L * 1024 * 1024;

    private ProxiedHttp() {}

    /** A response: its status, its headers (names in lower case), and its body, which must be closed */
    public record Response(int status, Map<String, String> headers, InputStream body, Socket socket) implements Closeable {
        /** The Content-Length, or -1 when the body is chunked or runs to the close */
        public long contentLength() {
            String length = headers.get("content-length");
            try {
                return length == null || headers.containsKey("transfer-encoding") ? -1 : Long.parseLong(length.trim());
            } catch(NumberFormatException e) {
                return -1;
            }
        }

        /** The whole body as UTF-8 text */
        public String bodyText() throws IOException {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * Sends one request through the proxy.
     *
     * @param ssl the TLS socket factory for https (for certificate pinning), or null for the default with hostname verification
     */
    public static Response request(String method, URL url, Map<String, String> headers, byte[] body, Proxy proxy, SSLSocketFactory ssl,
                                   int connectTimeoutMillis, int readTimeoutMillis) throws IOException {
        return request(method, url, headers, body, proxy, ssl, connectTimeoutMillis, readTimeoutMillis, DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * Sends one request through the proxy, with a cap on the response body: a body declared larger is refused before it is read, and one that
     * runs past the cap (chunked, or to the close) fails when it does.
     *
     * @param ssl the TLS socket factory for https (for certificate pinning), or null for the default with hostname verification
     * @param maxBodyBytes the most body this caller accepts
     */
    public static Response request(String method, URL url, Map<String, String> headers, byte[] body, Proxy proxy, SSLSocketFactory ssl,
                                   int connectTimeoutMillis, int readTimeoutMillis, long maxBodyBytes) throws IOException {
        return request(method, url, headers, body, proxy, ssl, connectTimeoutMillis, readTimeoutMillis, maxBodyBytes, null);
    }

    /**
     * As above, and hands each connection's socket to {@code opened} as soon as it exists, so a caller that gives up (a timeout in the UI) can
     * close it and end the request rather than leave it running.
     */
    public static Response request(String method, URL url, Map<String, String> headers, byte[] body, Proxy proxy, SSLSocketFactory ssl,
                                   int connectTimeoutMillis, int readTimeoutMillis, long maxBodyBytes,
                                   java.util.function.Consumer<Socket> opened) throws IOException {
        if(proxy == null || proxy.type() != Proxy.Type.SOCKS) {
            throw new IOException("Not sent: a request to " + url.getHost() + " only goes through a SOCKS proxy (Tor)");
        }
        URL current = url;
        for(int redirects = 0; ; redirects++) {
            Response response = once(method, current, headers, body, proxy, ssl, connectTimeoutMillis, readTimeoutMillis, maxBodyBytes, opened);
            String location = response.headers().get("location");
            if(!"GET".equals(method) || !isRedirect(response.status()) || location == null || redirects >= MAX_REDIRECTS) {
                return response;
            }
            response.close();
            current = redirectTarget(current, location);
        }
    }

    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** Where a redirect goes, relative to the URL that gave it; only http and https, and never from https down to http */
    static URL redirectTarget(URL from, String location) throws IOException {
        try {
            URL target = from.toURI().resolve(location.trim()).toURL();
            String protocol = target.getProtocol().toLowerCase(Locale.ROOT);
            if(!protocol.equals("http") && !protocol.equals("https")) {
                throw new IOException("Redirect to an unsupported address: " + location);
            }
            if(from.getProtocol().equalsIgnoreCase("https") && protocol.equals("http")) {
                throw new IOException("Not followed: a redirect from https to plain http (" + target.getHost() + ")");
            }
            return target;
        } catch(java.net.URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Bad redirect: " + location, e);
        }
    }

    private static Response once(String method, URL url, Map<String, String> headers, byte[] body, Proxy proxy, SSLSocketFactory ssl,
                                 int connectTimeoutMillis, int readTimeoutMillis, long maxBodyBytes,
                                 java.util.function.Consumer<Socket> opened) throws IOException {
        boolean https = url.getProtocol().equalsIgnoreCase("https");
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : url.getDefaultPort();

        Socket socket = new Socket(proxy);
        if(opened != null) {
            opened.accept(socket);
        }
        try {
            socket.setSoTimeout(readTimeoutMillis);
            //Unresolved: the proxy resolves the name, this computer never does
            socket.connect(SocketTargets.target(host, port, true), connectTimeoutMillis);
            if(https) {
                SSLSocketFactory factory = ssl != null ? ssl : (SSLSocketFactory)SSLSocketFactory.getDefault();
                SSLSocket tls = (SSLSocket)factory.createSocket(socket, host, port, true);
                if(ssl == null) {
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    tls.setSSLParameters(parameters);
                }
                tls.startHandshake();
                socket = tls;
            }

            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(requestHead(method, url, host, port, https, headers, body).getBytes(StandardCharsets.ISO_8859_1));
            if(body != null) {
                out.write(body);
            }
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            int status = parseStatus(readLine(in));
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for(String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if(colon > 0) {
                    responseHeaders.merge(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim(), (a, b) -> a + ", " + b);
                }
            }
            return new Response(status, responseHeaders, capped(bodyStream(method, status, responseHeaders, in), responseHeaders, maxBodyBytes), socket);
        } catch(IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    static String requestHead(String method, URL url, String host, int port, boolean https, Map<String, String> headers, byte[] body) {
        String path = url.getFile() == null || url.getFile().isEmpty() ? "/" : url.getFile();
        boolean defaultPort = port == (https ? 443 : 80);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host.contains(":") ? "[" + host + "]" : host).append(defaultPort ? "" : ":" + port).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Accept-Encoding: identity\r\n");
        if(headers != null) {
            for(Map.Entry<String, String> header : headers.entrySet()) {
                if(header.getKey().indexOf('\n') >= 0 || header.getValue().indexOf('\n') >= 0 || header.getKey().indexOf('\r') >= 0 || header.getValue().indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("A header may not contain a line break");
                }
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        if(body != null) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("\r\n");
        return head.toString();
    }

    static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if(parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("Not an HTTP response: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch(NumberFormatException e) {
            throw new IOException("Not an HTTP status: " + statusLine);
        }
    }

    private static InputStream bodyStream(String method, int status, Map<String, String> headers, InputStream in) throws IOException {
        if("HEAD".equals(method) || status == 204 || status == 304 || (status >= 100 && status < 200)) {
            return InputStream.nullInputStream();
        }
        String encoding = headers.get("transfer-encoding");
        if(encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return new ChunkedInputStream(in);
        }
        String length = headers.get("content-length");
        if(length != null) {
            try {
                return new BoundedInputStream(in, Long.parseLong(length.trim()));
            } catch(NumberFormatException e) {
                throw new IOException("Bad Content-Length: " + length);
            }
        }
        return in;
    }

    /** The body with the caller's cap: refused at once when its declared length is over it, else failing when it runs past it */
    static InputStream capped(InputStream body, Map<String, String> headers, long maxBodyBytes) throws IOException {
        String length = headers.get("content-length");
        if(length != null && !headers.containsKey("transfer-encoding")) {
            try {
                if(Long.parseLong(length.trim()) > maxBodyBytes) {
                    throw new IOException("Response too large: " + length.trim() + " bytes, more than the " + maxBodyBytes + " this request accepts");
                }
            } catch(NumberFormatException e) {
                //bodyStream has already refused a bad length
            }
        }
        return new CappedInputStream(body, maxBodyBytes);
    }

    /** Fails once more than the cap has been read */
    static final class CappedInputStream extends FilterInputStream {
        private final long cap;
        private long read;

        CappedInputStream(InputStream in, long cap) {
            super(in);
            this.cap = cap;
        }

        private void count(long n) throws IOException {
            read += n;
            if(read > cap) {
                throw new IOException("Response too large: more than the " + cap + " bytes this request accepts");
            }
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if(c != -1) {
                count(1);
            }
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if(n > 0) {
                count(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count(skipped);
            return skipped;
        }
    }

    /** A line up to CRLF (or LF), without it, in ISO-8859-1 */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while((c = in.read()) != -1) {
            if(c == '\n') {
                break;
            }
            if(line.size() > 64 * 1024) {
                throw new IOException("Header line too long");
            }
            line.write(c);
        }
        if(c == -1 && line.size() == 0) {
            throw new EOFException("Connection closed before the response");
        }
        String text = line.toString(StandardCharsets.ISO_8859_1);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    /** A body of a known length */
    static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long length) {
            super(in);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if(remaining <= 0) {
                return -1;
            }
            int c = super.read();
            if(c == -1) {
                throw new EOFException("Connection closed before the end of the body");
            }
            remaining--;
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if(remaining <= 0) {
                return -1;
            }
            int read = super.read(b, off, (int)Math.min(len, remaining));
            if(read == -1) {
                throw new EOFException("Connection closed before the end of the body");
            }
            remaining -= read;
            return read;
        }
    }

    /** A chunked body: hex size lines, data, and a last chunk of size 0 with optional trailers */
    static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long chunkLeft;
        private boolean done;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        private boolean nextChunk() throws IOException {
            if(done) {
                return false;
            }
            if(chunkLeft == 0) {
                String sizeLine = readLine(in);
                if(sizeLine.isEmpty()) {
                    //The CRLF after the previous chunk's data
                    sizeLine = readLine(in);
                }
                int extension = sizeLine.indexOf(';');
                try {
                    chunkLeft = Long.parseLong((extension >= 0 ? sizeLine.substring(0, extension) : sizeLine).trim(), 16);
                } catch(NumberFormatException e) {
                    throw new IOException("Bad chunk size: " + sizeLine);
                }
                if(chunkLeft == 0) {
                    //Trailers, up to the empty line
                    while(!readLine(in).isEmpty()) {
                        //ignored
                    }
                    done = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if(len == 0) {
                return 0;
            }
            if(!nextChunk()) {
                return -1;
            }
            int read = in.read(b, off, (int)Math.min(len, chunkLeft));
            if(read == -1) {
                throw new EOFException("Connection closed inside a chunk");
            }
            chunkLeft -= read;
            return read;
        }
    }

    /** The SOCKS proxy for a host and port */
    public static Proxy socks(com.google.common.net.HostAndPort proxy) {
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxy.getHost(), proxy.getPort()));
    }
}
