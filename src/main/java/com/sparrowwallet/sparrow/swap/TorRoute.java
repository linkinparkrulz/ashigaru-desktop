package com.sparrowwallet.sparrow.swap;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.net.HttpClientService;
import com.sparrowwallet.sparrow.net.Tor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The proxy swaps go through, and whether it is Tor. When Ashigaru's own Tor is not running, the proxy set in Settings is offered as "the Tor proxy",
 * though it may be a VPN or an SSH tunnel. Ashigaru's own Tor is taken as Tor; any other proxy must be shown to be Tor first.
 *
 * <p>Both answers come from one look at Tor ({@link #current()}), so the proxy and whether it is Ashigaru's own cannot come from two different moments.
 * A proxy from Settings is shown to be Tor when, through it, the Tor Project says the request left through Tor <em>and</em> an onion service answers:
 * a proxy that only sends some destinations through Tor could pass one check, not both so easily. It still cannot prove how the proxy routes every
 * destination, which is why Ashigaru's own Tor is preferred. A proof is kept for {@value #PROOF_MINUTES} minutes for that proxy, then asked again.
 */
public final class TorRoute {
    private static final Logger log = LoggerFactory.getLogger(TorRoute.class);
    static final long PROOF_MINUTES = 10;
    private static final String TOR_CHECK = "https://check.torproject.org/api/ip";
    //The Tor Project's own onion service: only reachable through Tor
    private static final String TOR_ONION = "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/";
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Accept", "application/json");

    private static final Map<HostAndPort, Instant> proven = new ConcurrentHashMap<>();

    /** @param internal true when the proxy is Ashigaru's own Tor */
    public record Route(HostAndPort proxy, boolean internal) {}

    private TorRoute() {}

    /** The proxy to use now, and whether it is Ashigaru's own Tor, from one look at Tor; null when there is none */
    public static Route current() {
        Tor tor = Tor.getDefault();
        if(tor != null) {
            HostAndPort proxy = tor.findProxyHostAndPort();
            return proxy == null ? null : new Route(proxy, true);
        }
        HostAndPort configured = AppServices.getTorProxy();
        return configured == null ? null : new Route(configured, false);
    }

    /** Whether this route may be used as Tor: Ashigaru's own Tor, or a proxy shown to be Tor recently. Blocks while a proxy is checked. */
    public static boolean isTor(Route route) {
        if(route == null) {
            return false;
        }
        if(route.internal()) {
            return true;
        }
        Instant provenAt = proven.get(route.proxy());
        if(stillProven(provenAt, Instant.now())) {
            return true;
        }
        proven.remove(route.proxy());
        boolean tor = check(route.proxy());
        if(tor) {
            proven.put(route.proxy(), Instant.now());
        }
        return tor;
    }

    static boolean stillProven(Instant provenAt, Instant now) {
        return provenAt != null && Duration.between(provenAt, now).compareTo(Duration.ofMinutes(PROOF_MINUTES)) < 0;
    }

    private static boolean check(HostAndPort proxy) {
        HttpClientService http = new HttpClientService(proxy);
        try {
            TorCheck answer = http.requestJson(TOR_CHECK, TorCheck.class, HEADERS);
            if(answer == null || !answer.IsTor) {
                log.info("The proxy in Settings is not Tor: the Tor Project's check says the request did not come from Tor");
                return false;
            }
            http.requestJson(TOR_ONION, String.class, Map.of("User-Agent", HEADERS.get("User-Agent")));
            return true;
        } catch(Exception e) {
            log.info("Could not show that the proxy in Settings is Tor: " + e.getMessage());
            return false;
        } finally {
            http.shutdown();
        }
    }

    @SuppressWarnings("unused")
    private static class TorCheck {
        public boolean IsTor;
    }
}
