package com.sparrowwallet.sparrow.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.paynym.Bip47Signing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Auth47 {
    private static final Logger log = LoggerFactory.getLogger(Auth47.class);

    public static final String SCHEME = "auth47";
    public static final String VERSION = "1.0";
    public static final String HTTPS_PROTOCOL = "https://";
    public static final String SRBN_PROTOCOL = "srbn://";

    private final String nonce;
    private final URL callback;
    private final String expiry;
    private boolean srbn;
    private String srbnName;
    private String resource;

    public Auth47(URI uri) throws MalformedURLException {
        this.nonce = uri.getHost();

        Map<String, String> parameterMap = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if(query == null) {
            throw new IllegalArgumentException("No callback parameter provided.");
        }

        if(query.startsWith("?")) {
            query = query.substring(1);
        }

        String[] pairs = query.split("&");
        for(String pair : pairs) {
            int idx = pair.indexOf("=");
            if(idx < 0) {
                continue;
            }
            parameterMap.put(pair.substring(0, idx), pair.substring(idx + 1));
        }

        String strCallback = parameterMap.get("c");
        if(strCallback == null) {
            throw new IllegalArgumentException("No callback parameter provided.");
        }
        if(strCallback.startsWith(SRBN_PROTOCOL)) {
            String srbnCallback = HTTPS_PROTOCOL + strCallback.substring(SRBN_PROTOCOL.length());
            URL srbnUrl = new URL(srbnCallback);
            this.srbn = true;
            this.srbnName = srbnUrl.getUserInfo();
            this.callback = new URL(HTTPS_PROTOCOL + srbnUrl.getHost());
        } else {
            this.callback = new URL(strCallback);
        }

        this.expiry = parameterMap.get("e");
        this.resource = parameterMap.get("r");
        if(resource == null) {
            if(srbn) {
                this.resource = "srbn";
            } else if(strCallback.startsWith("http")) {
                this.resource = strCallback;
            } else {
                throw new IllegalArgumentException("Invalid callback parameter (not http/s or srbn): " + strCallback);
            }
        }
    }

    public void sendResponse(Wallet wallet) throws IOException, Auth47Exception {
        if(!wallet.hasPaymentCode()) {
            throw new Auth47Exception("A software wallet is required to authenticate.");
        }

        if(srbn) {
            sendSorobanResponse(wallet);
        } else {
            sendHttpResponse(wallet);
        }
    }

    public void sendHttpResponse(Wallet wallet) throws IOException, Auth47Exception {
        String json = getJsonResponse(wallet);

        send(json);
    }

    public void sendSorobanResponse(Wallet wallet) throws IOException, Auth47Exception {
        String json = getJsonResponse(wallet);

        SorobanResponse sorobanResponse = new SorobanResponse(srbnName, json);
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        String jsonRpc = gson.toJson(sorobanResponse);

        send(jsonRpc);
    }

    private String getJsonResponse(Wallet wallet) {
        String challenge = getChallenge();
        String signature = sign(wallet, challenge);

        Response response = new Response(VERSION, challenge, signature, wallet.getPaymentCode().toString(), null);
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        return gson.toJson(response);
    }

    private void send(String json) throws IOException, Auth47Exception {
        if(log.isInfoEnabled()) {
            //The response contains the wallet's payment code, so only log where it goes
            log.info("Sending auth47 response to " + callback.getHost());
        }

        //A login never goes out directly, and never in offline mode (AD-33). Through the proxy the callback's name is not looked up here (AD-38).
        String refusal = TorOnly.refusal();
        if(refusal != null) {
            throw new Auth47Exception("The login was not sent: " + refusal + ".");
        }
        Proxy proxy = AppServices.getProxy();
        if(proxy == null) {
            throw new Auth47Exception("The login was not sent: there is no Tor proxy to send it through.");
        }

        int statusCode;
        String res;
        try(ProxiedHttp.Response response = ProxiedHttp.request("POST", callback, java.util.Map.of("Content-Type", "application/json"),
                json.getBytes(StandardCharsets.UTF_8), proxy, null, 30000, 30000)) {
            statusCode = response.status();
            if(statusCode == 404) {
                throw new Auth47Exception("Could not authenticate. Callback URL of " + callback + " returned a 404 response.");
            }
            res = response.bodyText().trim();
        }

        if(log.isDebugEnabled()) {
            log.debug("Received " + statusCode + " " + res);
        }

        if(statusCode < 200 || statusCode >= 300) {
            throw new Auth47Exception("Could not authenticate. Server returned " + res);
        }
    }

    private String sign(Wallet wallet, String challenge) {
        try {
            return Bip47Signing.signChallenge(wallet, challenge);
        } catch(Exception e) {
            log.error("Error signing auth47 challenge", e);
            throw new IllegalStateException("Error signing auth47 challenge: " + e.getMessage(), e);
        }
    }

    private String getChallenge() {
        return SCHEME + "://" + nonce + "?r=" + resource + (expiry == null ? "" : "&e=" + expiry);
    }

    public URL getCallback() {
        return callback;
    }

    private static class Response {
        public Response(String version, String challenge, String signature, String paymentCode, String address) {
            this.auth47_response = version;
            this.challenge = challenge;
            this.signature = signature;
            this.nym = paymentCode;
            this.address = address;
        }

        public String auth47_response;
        public String challenge;
        public String signature;
        public String nym;
        public String address;
    }

    private static class SorobanResponse {
        public SorobanResponse(String name, String response) {
            params.add(new SorobanParam(name, response));
        }

        public String jsonrpc = "2.0";
        public int id = 0;
        public String method = "directory.Add";
        public List<SorobanParam> params = new ArrayList<>();
    }

    private static class SorobanParam {
        public SorobanParam(String name, String entry) {
            Name = name;
            Entry = entry;
        }

        public String Name;
        public String Entry;
        public String Mode = "short";
    }

    public static final class Auth47Exception extends Exception {
        public Auth47Exception(String message) {
            super(message);
        }

        public Auth47Exception(String message, Throwable cause) {
            super(message, cause);
        }

        public Auth47Exception(Throwable cause) {
            super(cause);
        }

        public Auth47Exception(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
            super(message, cause, enableSuppression, writableStackTrace);
        }
    }
}
