package com.sparrowwallet.sparrow.paynym;

import com.sparrowwallet.drongo.bip47.InvalidPaymentCodeException;
import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import io.reactivex.Observable;
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.reactivex.schedulers.Schedulers;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public class PayNymService {
    private static final Logger log = LoggerFactory.getLogger(PayNymService.class);

    private PayNymService() {
        //private constructor
    }

    public static Observable<Map<String, Object>> createPayNym(Wallet wallet) {
        return createPayNym(getPaymentCode(wallet));
    }

    public static Observable<Map<String, Object>> createPayNym(PaymentCode paymentCode) {
        if(paymentCode == null) {
            throw new IllegalStateException("Payment code is null");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");

        HashMap<String, Object> body = new HashMap<>();
        body.put("code", paymentCode.toString());

        String url = getHostUrl() + "/api/v1/create";
        if(log.isInfoEnabled()) {
            log.info("Creating PayNym using " + url);
        }

        return post(url, headers, body)
                .subscribeOn(Schedulers.io())
                .observeOn(JavaFxScheduler.platform())
                .map(Optional::get);
    }

    public static Observable<Map<String, Object>> updateToken(PaymentCode paymentCode) {
        if(paymentCode == null) {
            throw new IllegalStateException("Payment code is null");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");

        HashMap<String, Object> body = new HashMap<>();
        body.put("code", paymentCode.toString());

        String url = getHostUrl() + "/api/v1/token";
        if(log.isInfoEnabled()) {
            log.info("Updating PayNym token using " + url);
        }

        return post(url, headers, body)
                .subscribeOn(Schedulers.io())
                .observeOn(JavaFxScheduler.platform())
                .map(Optional::get);
    }

    public static void claimPayNym(Wallet wallet, Map<String, Object> createMap, boolean segwit) {
        if(createMap.get("claimed") == Boolean.FALSE) {
            getAuthToken(wallet, createMap).subscribe(authToken -> {
                String signature = getSignature(wallet, authToken);
                claimPayNym(authToken, signature).subscribe(claimMap -> {
                    log.debug("Claimed payment code " + claimMap.get("claimed"));
                    addPaymentCode(getPaymentCode(wallet), authToken, signature, segwit).subscribe(addMap -> {
                        log.debug("Added payment code " + addMap);
                    });
                }, error -> {
                    getAuthToken(wallet, new HashMap<>()).subscribe(newAuthToken -> {
                        String newSignature = getSignature(wallet, newAuthToken);
                        claimPayNym(newAuthToken, newSignature).subscribe(claimMap -> {
                            log.debug("Claimed payment code " + claimMap.get("claimed"));
                            addPaymentCode(getPaymentCode(wallet), newAuthToken, newSignature, segwit).subscribe(addMap -> {
                                log.debug("Added payment code " + addMap);
                            });
                        }, newError -> {
                            log.error("Error claiming PayNym with new authToken", newError);
                        });
                    }, newError -> {
                        log.error("Error retrieving new authToken", newError);
                    });
                });
            }, error -> {
                log.error("Error retrieving authToken", error);
            });
        }
    }

    private static Observable<Map<String, Object>> claimPayNym(String authToken, String signature) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("auth-token", authToken);

        HashMap<String, Object> body = new HashMap<>();
        body.put("signature", signature);

        String url = getHostUrl() + "/api/v1/claim";
        if(log.isInfoEnabled()) {
            log.info("Claiming PayNym using " + url);
        }

        return post(url, headers, body)
                .subscribeOn(Schedulers.io())
                .observeOn(JavaFxScheduler.platform())
                .map(Optional::get);
    }

    public static Observable<Map<String, Object>> addPaymentCode(PaymentCode paymentCode, String authToken, String signature, boolean segwit) {
        String strPaymentCode;
        try {
            strPaymentCode = segwit ? paymentCode.makeSamouraiPaymentCode() : paymentCode.toString();
        } catch(InvalidPaymentCodeException e) {
            log.warn("Error creating segwit enabled payment code", e);
            strPaymentCode = paymentCode.toString();
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("auth-token", authToken);

        HashMap<String, Object> body = new HashMap<>();
        body.put("nym", paymentCode.toString());
        body.put("code", strPaymentCode);
        body.put("signature", signature);

        String url = getHostUrl() + "/api/v1/nym/add";
        if(log.isInfoEnabled()) {
            log.info("Adding payment code to PayNym using " + url);
        }

        return post(url, headers, body)
                .subscribeOn(Schedulers.io())
                .observeOn(JavaFxScheduler.platform())
                .map(Optional::get);
    }

    public static Observable<Map<String, Object>> followPaymentCode(com.samourai.wallet.bip47.rpc.PaymentCode paymentCode, String authToken, String signature) {
        return followPaymentCode(PaymentCode.fromString(paymentCode.toString()), authToken, signature);
    }

    public static Observable<Map<String, Object>> followPaymentCode(PaymentCode paymentCode, String authToken, String signature) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("auth-token", authToken);

        HashMap<String, Object> body = new HashMap<>();
        body.put("signature", signature);
        body.put("target", paymentCode.toString());

        String url = getHostUrl() + "/api/v1/follow";
        if(log.isInfoEnabled()) {
            log.info("Following payment code using " + url);
        }

        return post(url, headers, body)
                .subscribeOn(Schedulers.io())
                .observeOn(JavaFxScheduler.platform())
                .map(Optional::get);
    }

    public static Observable<Map<String, Object>> fetchPayNym(String nymIdentifier, boolean compact) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");

        HashMap<String, Object> body = new HashMap<>();
        body.put("nym", nymIdentifier);

        String url = getHostUrl() + "/api/v1/nym" + (compact ? "?compact=true" : "");
        if(log.isInfoEnabled()) {
            log.info("Fetching PayNym using " + url);
        }

        return post(url, headers, body)
                .subscribeOn(Schedulers.io())
                .observeOn(JavaFxScheduler.platform())
                .map(Optional::get);
    }

    public static Observable<PayNym> getPayNym(String nymIdentifier) {
        return getPayNym(nymIdentifier, false);
    }

    public static Observable<PayNym> getPayNym(String nymIdentifier, boolean compact) {
        return fetchPayNym(nymIdentifier, compact).map(nymMap -> toPayNym(nymMap, compact));
    }

    /** How long a lookup may take, end to end, before its connection is closed */
    public static final int LOOKUP_TIMEOUT_MILLIS = 30_000;
    /** The most a lookup's answer may hold: a full listing of followers and following is well under this */
    static final long LOOKUP_MAX_BYTES = 8L * 1024 * 1024;

    /**
     * Looks a PayNym up with a timeout that ends the request itself, not only the wait for it: through Tor with Ashigaru's own client
     * ({@link com.sparrowwallet.sparrow.net.ProxiedHttp}), whose connection is closed when the timeout passes or the caller stops waiting.
     * The shared client would keep the request running for up to two minutes after the screen had given up on it.
     */
    public static Observable<PayNym> lookupPayNym(String nymIdentifier, boolean compact, int timeoutMillis) {
        String refusal = com.sparrowwallet.sparrow.net.TorOnly.refusal();
        com.google.common.net.HostAndPort tor = refusal == null ? AppServices.getHttpClientService().getTorProxy() : null;
        if(tor == null) {
            return Observable.error(new java.io.IOException("PayNym directory not contacted: " + (refusal != null ? refusal : "Tor is not running") + "."));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("nym", nymIdentifier);
        String url = getHostUrl() + "/api/v1/nym" + (compact ? "?compact=true" : "");
        if(log.isInfoEnabled()) {
            log.info("Fetching PayNym using " + url);
        }
        return postDirect(com.sparrowwallet.sparrow.net.ProxiedHttp.socks(tor), url, new com.google.gson.Gson().toJson(body), timeoutMillis)
                .subscribeOn(Schedulers.io())
                .observeOn(JavaFxScheduler.platform())
                .map(nymMap -> toPayNym(nymMap, compact));
    }

    /** One JSON POST through the proxy; disposing it closes the connection, so nothing keeps running after the caller gives up */
    static Observable<Map<String, Object>> postDirect(java.net.Proxy proxy, String url, String json, int timeoutMillis) {
        return Observable.create(emitter -> {
            java.util.concurrent.atomic.AtomicReference<java.net.Socket> socket = new java.util.concurrent.atomic.AtomicReference<>();
            emitter.setCancellable(() -> {
                java.net.Socket open = socket.get();
                if(open != null) {
                    open.close();
                }
            });
            try(com.sparrowwallet.sparrow.net.ProxiedHttp.Response response = com.sparrowwallet.sparrow.net.ProxiedHttp.request("POST", new java.net.URL(url),
                    Map.of("Content-Type", "application/json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8), proxy, null, timeoutMillis,
                    timeoutMillis, LOOKUP_MAX_BYTES, socket::set)) {
                if(response.status() < 200 || response.status() >= 300) {
                    throw new java.io.IOException("PayNym directory answered HTTP " + response.status());
                }
                Map<String, Object> map = new com.google.gson.Gson().fromJson(response.bodyText(), Map.class);
                if(map == null) {
                    throw new java.io.IOException("PayNym directory sent an empty answer");
                }
                emitter.onNext(map);
                emitter.onComplete();
            } catch(Exception e) {
                //After a dispose the closed socket fails the read: nobody is waiting, so it is not an error to report
                emitter.tryOnError(e);
            }
        });
    }

    private static PayNym toPayNym(Map<String, Object> nymMap, boolean compact) throws InvalidPaymentCodeException {
        List<Map<String, Object>> codes = (List<Map<String, Object>>)nymMap.get("codes");
        PaymentCode code = new PaymentCode((String)codes.stream().filter(codeMap -> codeMap.get("segwit") == Boolean.FALSE).map(codeMap -> codeMap.get("code")).findFirst().orElse(codes.get(0).get("code")));

        if(compact) {
            return new PayNym(code, (String)nymMap.get("nymID"), (String)nymMap.get("nymName"), (Boolean)nymMap.get("segwit"), Collections.emptyList(), Collections.emptyList());
    }

        List<Map<String, Object>> followingMaps = (List<Map<String, Object>>)nymMap.get("following");
        List<PayNym> following = followingMaps.stream().map(followingMap -> {
            return PayNym.fromString((String)followingMap.get("code"), (String)followingMap.get("nymId"), (String)followingMap.get("nymName"), (Boolean)followingMap.get("segwit"), Collections.emptyList(), Collections.emptyList());
        }).collect(Collectors.toList());

        List<Map<String, Object>> followersMaps = (List<Map<String, Object>>)nymMap.get("followers");
        List<PayNym> followers = followersMaps.stream().map(followerMap -> {
            return PayNym.fromString((String)followerMap.get("code"), (String)followerMap.get("nymId"), (String)followerMap.get("nymName"), (Boolean)followerMap.get("segwit"), Collections.emptyList(), Collections.emptyList());
        }).collect(Collectors.toList());

        return new PayNym(code, (String)nymMap.get("nymID"), (String)nymMap.get("nymName"), (Boolean)nymMap.get("segwit"), following, followers);
    }

    public static Observable<String> getAuthToken(Wallet wallet, Map<String, Object> map) {
        if(map.containsKey("token")) {
            return Observable.just((String)map.get("token"));
        }

        return updateToken(wallet).map(tokenMap -> (String)tokenMap.get("token"));
    }

    public static Observable<Map<String, Object>> updateToken(Wallet wallet) {
        return updateToken(getPaymentCode(wallet));
    }

    public static String getSignature(Wallet wallet, String authToken) {
        //The directory chooses the token: it is only signed if it cannot be a login challenge, a signed message or a Dojo listing
        Bip47Signing.checkPayNymToken(authToken);
        Wallet masterWallet = wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
        Keystore keystore = masterWallet.getKeystores().get(0);
        List<ChildNumber> derivation = keystore.getKeyDerivation().getDerivation();
        ChildNumber derivationStart = derivation.isEmpty() ? ChildNumber.ZERO_HARDENED : derivation.get(derivation.size() - 1);
        ECKey notificationPrivKey = keystore.getBip47ExtendedPrivateKey().getKey(List.of(derivationStart, new ChildNumber(0)));
        return notificationPrivKey.signMessage(authToken, ScriptType.P2PKH);
    }

    private static PaymentCode getPaymentCode(Wallet wallet) {
        Wallet masterWallet = wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
        return masterWallet.getPaymentCode();
    }

    /** The PayNym directory, only at its onion address: it is never reached directly (TorOnly) */
    public static final String HOST_URL = "http://paynym25chftmsywv4v2r67agbrr62lcxagsf4tymbzpeeucucy2ivad.onion";

    private static String getHostUrl() {
        return HOST_URL;
    }

    /** A request to the directory, through Tor; refused (as an error, not sent directly) offline or without a Tor proxy */
    @SuppressWarnings("rawtypes")
    private static Observable<java.util.Optional<Map>> post(String url, Map<String, String> headers, Object body) {
        String refusal = com.sparrowwallet.sparrow.net.TorOnly.refusal();
        if(refusal != null) {
            return Observable.error(new java.io.IOException("PayNym directory not contacted: " + refusal + "."));
        }
        return AppServices.getHttpClientService().postJson(url, Map.class, headers, body);
    }
}
