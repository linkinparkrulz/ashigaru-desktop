package com.sparrowwallet.sparrow.net.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches release metadata and files, always through the configured proxy when there is one, so an
 * update check does not announce this install over clearnet.
 */
public class ReleaseFetcher {
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 60000;
    /** The most any one response may hold through the proxy: the release packages are a few hundred MB; each caller checks its own size too */
    private static final long MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024;

    /** A release as published on GitHub. */
    public record Release(String tag, List<ReleaseAsset> assets) {
        public ReleaseAsset asset(String name) {
            return assets.stream().filter(a -> a.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** Reports download progress; returning false cancels the transfer. */
    public interface Progress {
        boolean onProgress(long bytesRead, long total);
    }

    public static Release fetchLatestRelease() throws IOException {
        JsonObject json = JsonParser.parseString(get(ReleaseTrust.LATEST_RELEASE_URL)).getAsJsonObject();
        return parseRelease(json);
    }

    static Release parseRelease(JsonObject json) throws IOException {
        JsonElement tagElement = json.get("tag_name");
        if(tagElement == null || tagElement.isJsonNull()) {
            throw new IOException("The latest release has no tag");
        }

        List<ReleaseAsset> assets = new ArrayList<>();
        JsonElement assetsElement = json.get("assets");
        if(assetsElement != null && assetsElement.isJsonArray()) {
            for(JsonElement element : assetsElement.getAsJsonArray()) {
                JsonObject asset = element.getAsJsonObject();
                JsonElement name = asset.get("name");
                JsonElement url = asset.get("browser_download_url");
                if(name == null || url == null || name.isJsonNull() || url.isJsonNull()) {
                    continue;
                }
                JsonElement size = asset.get("size");
                assets.add(new ReleaseAsset(name.getAsString(), url.getAsString(),
                        size == null || size.isJsonNull() ? 0L : size.getAsLong()));
            }
        }

        return new Release(tagElement.getAsString(), assets);
    }

    /** Downloads a small text asset, such as SHA256SUMS or the signature, into memory. */
    public static byte[] fetchBytes(String url, int maxBytes) throws IOException {
        Opened connection = open(url, "text/plain, */*");
        try(InputStream in = connection.in()) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if(out.size() > maxBytes) {
                    throw new IOException("Attestation file at " + url + " is larger than expected");
                }
            }
            return out.toByteArray();
        } finally {
            connection.close();
        }
    }

    /**
     * Downloads to a temporary file beside the destination and moves it into place only on success,
     * so a cancelled or failed transfer never leaves something that looks like a complete download.
     */
    public static void download(String url, Path destination, long expectedSize, Progress progress) throws IOException {
        Files.createDirectories(destination.getParent());
        checkFreeSpace(destination, expectedSize);

        Path partial = destination.resolveSibling(destination.getFileName() + ".part");
        Opened connection = open(url, "application/octet-stream, */*");
        long total = connection.contentLength();
        if(total <= 0) {
            total = expectedSize;
        }

        try(InputStream in = connection.in(); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            long read = 0;
            int count;
            while((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
                read += count;
                if(progress != null && !progress.onProgress(read, total)) {
                    throw new CancelledException();
                }
            }
        } catch(IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        } finally {
            connection.close();
        }

        Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Thrown when the user cancels a download; distinguishable from a genuine transfer failure. */
    public static class CancelledException extends IOException {
        public CancelledException() {
            super("Download cancelled");
        }
    }

    private static void checkFreeSpace(Path destination, long expectedSize) throws IOException {
        if(expectedSize <= 0) {
            return;
        }

        //Leave room for the temporary file and the move, rather than filling the disk exactly
        long required = expectedSize * 2;
        long usable = destination.getParent().toFile().getUsableSpace();
        if(usable > 0 && usable < required) {
            throw new IOException("Not enough free space to download the update: "
                    + (required / 1024 / 1024) + " MB needed, " + (usable / 1024 / 1024) + " MB available");
        }
    }

    private static String get(String url) throws IOException {
        Opened connection = open(url, "application/vnd.github+json");
        StringBuilder builder = new StringBuilder();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(connection.in(), StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } finally {
            connection.close();
        }
        return builder.toString();
    }

    /** An open response: its body, its length if known (-1 otherwise), and how to close it */
    record Opened(InputStream in, long contentLength, java.io.Closeable closer) implements java.io.Closeable {
        @Override
        public void close() {
            try {
                closer.close();
            } catch(IOException e) {
                //Closing after the body was read; nothing to do
            }
        }
    }

    private static Opened open(String url, String accept) throws IOException {
        if(Config.get().requiresInternalTor() && !AppServices.isTorRunning()) {
            throw new IOException("The bundled Tor is not running; refusing to connect to " + url + " over clearnet");
        }

        Proxy proxy = AppServices.getProxy();
        //GitHub rejects API requests without a User-Agent
        java.util.Map<String, String> headers = java.util.Map.of("User-Agent", "Ashigaru-Desktop", "Accept", accept);
        if(proxy != null) {
            //Through the proxy with the name unresolved: HttpURLConnection would look github.com up on this computer first (AD-38)
            com.sparrowwallet.sparrow.net.ProxiedHttp.Response response = com.sparrowwallet.sparrow.net.ProxiedHttp.request("GET", new URL(url), headers, null,
                    proxy, null, CONNECT_TIMEOUT, READ_TIMEOUT, MAX_DOWNLOAD_BYTES);
            if(response.status() != HttpURLConnection.HTTP_OK) {
                response.close();
                throw new IOException("HTTP " + response.status() + " for " + url);
            }
            return new Opened(response.body(), response.contentLength(), response);
        }

        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        headers.forEach(connection::setRequestProperty);

        int code = connection.getResponseCode();
        if(code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }

        return new Opened(connection.getInputStream(), connection.getContentLengthLong(), connection::disconnect);
    }

    private ReleaseFetcher() {}
}
