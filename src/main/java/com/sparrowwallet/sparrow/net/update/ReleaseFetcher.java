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
        HttpURLConnection connection = open(url, "text/plain, */*");
        try(InputStream in = connection.getInputStream()) {
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
            connection.disconnect();
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
        HttpURLConnection connection = open(url, "application/octet-stream, */*");
        long total = connection.getContentLengthLong();
        if(total <= 0) {
            total = expectedSize;
        }

        try(InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(partial)) {
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
            connection.disconnect();
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
        HttpURLConnection connection = open(url, "application/vnd.github+json");
        StringBuilder builder = new StringBuilder();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } finally {
            connection.disconnect();
        }
        return builder.toString();
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        if(Config.get().requiresInternalTor() && !AppServices.isTorRunning()) {
            throw new IOException("The bundled Tor is not running; refusing to connect to " + url + " over clearnet");
        }

        Proxy proxy = AppServices.getProxy();
        HttpURLConnection connection = (HttpURLConnection)(proxy != null
                ? new URL(url).openConnection(proxy)
                : new URL(url).openConnection());
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        //GitHub rejects API requests without a User-Agent
        connection.setRequestProperty("User-Agent", "Ashigaru-Desktop");
        connection.setRequestProperty("Accept", accept);

        int code = connection.getResponseCode();
        if(code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }

        return connection;
    }

    private ReleaseFetcher() {}
}
