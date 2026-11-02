package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.github.arteam.simplejsonrpc.client.Transport;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.IpAddressMatcher;
import com.sparrowwallet.sparrow.net.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;

public class BitcoindTransport implements Transport {
    private static final Logger log = LoggerFactory.getLogger(BitcoindTransport.class);
    public static final String COOKIE_FILENAME = ".cookie";

    private final Server bitcoindServer;
    private URL bitcoindUrl;
    private File cookieFile;
    private Long cookieFileTimestamp;
    private String bitcoindAuthEncoded;

    public BitcoindTransport(Server bitcoindServer, String bitcoindWallet, String bitcoindAuth) {
        this(bitcoindServer, bitcoindWallet);
        this.bitcoindAuthEncoded = Base64.getEncoder().encodeToString(bitcoindAuth.getBytes(StandardCharsets.UTF_8));
    }

    public BitcoindTransport(Server bitcoindServer, String bitcoindWallet, File bitcoindDir) {
        this(bitcoindServer, bitcoindWallet);
        this.cookieFile = new File(getCookieDir(bitcoindDir), COOKIE_FILENAME);
    }

    private BitcoindTransport(Server bitcoindServer, String bitcoindWallet) {
        this.bitcoindServer = bitcoindServer;
        try {
            String serverUrl = bitcoindServer.getUrl();
            if(!bitcoindServer.getHostAndPort().hasPort()) {
                serverUrl += ":" + Network.get().getDefaultPort();
            }
            this.bitcoindUrl = new URL(serverUrl + "/wallet/" + bitcoindWallet);
        } catch(MalformedURLException e) {
            log.error("Malformed Bitcoin Core RPC URL", e);
        }
    }

    @Override
    public String pass(String request) throws IOException {
        Proxy proxy = AppServices.getProxy();
        //Route remote nodes through the proxy as well as onion ones, so RPC credentials and the node's address do not go out over clearnet
        boolean useProxy = proxy != null && (Protocol.isOnionAddress(bitcoindServer) || !IpAddressMatcher.isLocalNetworkAddress(bitcoindServer.getHost()));
        HttpURLConnection connection = useProxy ? (HttpURLConnection)bitcoindUrl.openConnection(proxy) : (HttpURLConnection)bitcoindUrl.openConnection();

        if(connection instanceof HttpsURLConnection httpsURLConnection) {
            SSLSocketFactory sslSocketFactory = getPinningSocketFactory();
            if(sslSocketFactory != null) {
                httpsURLConnection.setSSLSocketFactory(sslSocketFactory);
            }
        }

        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");

        String auth = getBitcoindAuthEncoded();
        if(auth != null) {
            connection.setRequestProperty("Authorization", "Basic " + auth);
        }

        connection.setDoOutput(true);

        log.debug("> " + request);

        try(OutputStream os = connection.getOutputStream()) {
            byte[] jsonBytes = request.getBytes(StandardCharsets.UTF_8);
            os.write(jsonBytes);
        }

        int statusCode = connection.getResponseCode();
        if(statusCode == 401) {
            throw new IOException((cookieFile == null ? "User/pass" : "Cookie file") + " authentication failed");
        }
        InputStream inputStream = connection.getErrorStream() == null ? connection.getInputStream() : connection.getErrorStream();

        StringBuilder res = new StringBuilder();
        try(BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String responseLine;
            while((responseLine = br.readLine()) != null) {
                if(statusCode == 500) {
                    responseLine = responseLine.replace("\"result\":null,", "");
                }

                res.append(responseLine.trim());
            }
        }

        String response = res.toString();
        log.debug("< " + response);

        return response;
    }

    private String getBitcoindAuthEncoded() throws IOException {
        if(cookieFile != null) {
            if(!cookieFile.exists()) {
                throw new IOException("Cannot find Bitcoin Core cookie file at " + cookieFile.getAbsolutePath());
            }

            if(cookieFileTimestamp == null || cookieFile.lastModified() != cookieFileTimestamp) {
                try {
                    String userPass = Files.readAllLines(cookieFile.toPath()).get(0);
                    bitcoindAuthEncoded = Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
                    cookieFileTimestamp = cookieFile.lastModified();
                } catch(Exception e) {
                    log.warn("Cannot read Bitcoin Core .cookie file", e);
                }
            }
        }

        return bitcoindAuthEncoded;
    }

    private static File getCookieDir(File bitcoindDir) {
        if(Network.get() == Network.TESTNET && Files.exists(Path.of(bitcoindDir.getAbsolutePath(), "testnet3", COOKIE_FILENAME))) {
            return new File(bitcoindDir, "testnet3");
        } else if(Network.get() == Network.TESTNET4 && Files.exists(Path.of(bitcoindDir.getAbsolutePath(), "testnet4", COOKIE_FILENAME))) {
            return new File(bitcoindDir, "testnet4");
        }
        else if(Network.get() == Network.REGTEST && Files.exists(Path.of(bitcoindDir.getAbsolutePath(), "regtest", COOKIE_FILENAME))) {
            return new File(bitcoindDir, "regtest");
        } else if(Network.get() == Network.SIGNET && Files.exists(Path.of(bitcoindDir.getAbsolutePath(), "signet", COOKIE_FILENAME))) {
            return new File(bitcoindDir, "signet");
        }

        return bitcoindDir;
    }

    /**
     * Core RPC servers behind TLS normally use self-signed certificates, so trust the first certificate seen for this
     * server and require the same certificate afterwards, rather than accepting any certificate on every connection.
     */
    private SSLSocketFactory getPinningSocketFactory() {
        String certName = "bitcoind-" + bitcoindServer.getHost() + "-" + bitcoindServer.getHostAndPort().getPortOrDefault(Network.get().getDefaultPort());
        TrustManager[] pinningTrustManagers = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                    throw new CertificateException("Client certificates are not accepted");
                }

                public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                    if(certs == null || certs.length == 0) {
                        throw new CertificateException("No server certificate provided");
                    }

                    File pinnedFile = Storage.getCertificateFile(certName);
                    if(pinnedFile == null) {
                        log.info("Trusting certificate for Bitcoin Core RPC server " + bitcoindServer.getHost() + " on first use");
                        Storage.saveCertificate(certName, certs[0]);
                        return;
                    }

                    try(InputStream inputStream = new FileInputStream(pinnedFile)) {
                        Certificate pinned = CertificateFactory.getInstance("X.509").generateCertificate(inputStream);
                        if(!Arrays.equals(pinned.getEncoded(), certs[0].getEncoded())) {
                            throw new CertificateException("Bitcoin Core RPC server certificate has changed. If this is expected, delete " + pinnedFile.getAbsolutePath() + " to trust the new certificate.");
                        }
                    } catch(IOException e) {
                        throw new CertificateException("Could not read pinned certificate " + pinnedFile.getAbsolutePath(), e);
                    }
                }
            }
        };

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, pinningTrustManagers, null);
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            log.error("Error creating SSL socket factory", e);
        }

        return null;
    }
}
