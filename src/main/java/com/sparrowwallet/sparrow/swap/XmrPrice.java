package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.net.HttpClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The market XMR/BTC price, to compare maker offers against and chart the last day. Fetched only
 * through Tor: Ashigaru's web client goes direct when Tor is down, and swap users should not leak
 * that they are looking at Monero. CoinGecko first; Kraken when CoinGecko refuses Tor, as it often
 * does.
 */
public final class XmrPrice {
    private static final Logger log = LoggerFactory.getLogger(XmrPrice.class);

    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept", "application/json");

    /** BTC for one XMR now, and over the last day as (epoch millis, BTC per XMR) points, oldest first */
    public record Snapshot(double btcPerXmr, List<double[]> history, String source) {}

    private XmrPrice() {}

    /** Blocks; call off the JavaFX thread */
    public static Snapshot fetch() throws Exception {
        //A client of its own, built from the proxy that was checked and never from the shared one, whose proxy can change or go while this runs:
        //a request that cannot reach that proxy fails, and does not go out directly
        com.google.common.net.HostAndPort proxy = AppServices.getTorProxyIfUp();
        requireTor(proxy);
        HttpClientService http = new HttpClientService(proxy);
        try {
            //Any proxy set in Settings is offered as "the Tor proxy" when Ashigaru's own Tor is not running; it may be a VPN or SSH tunnel.
            //Only Ashigaru's own Tor is taken as Tor without asking; any other proxy must be shown to be Tor first.
            boolean internal = AppServices.isTorRunning();
            if(!internal && !confirmedTor.contains(proxy)) {
                requireTorExit(isTorExit(http));
                confirmedTor.add(proxy);
            }
            try {
                return fromCoinGecko(http);
            } catch(Exception e) {
                log.info("CoinGecko XMR price unavailable, trying Kraken: " + e.getMessage());
            }
            return fromKraken(http);
        } finally {
            http.shutdown();
        }
    }

    //Proxies already shown to exit through Tor in this run, so the check is made once per proxy
    private static final java.util.Set<com.google.common.net.HostAndPort> confirmedTor = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Asks the Tor Project, through the client's proxy, whether the request came out of Tor. Any failure is a no. */
    private static boolean isTorExit(HttpClientService http) {
        try {
            TorCheck check = http.requestJson("https://check.torproject.org/api/ip", TorCheck.class, HEADERS);
            return check != null && check.IsTor;
        } catch(Exception e) {
            log.info("Could not check that the proxy is Tor: " + e.getMessage());
            return false;
        }
    }

    /** A configured proxy that is not shown to be Tor is refused, not trusted */
    static void requireTorExit(boolean exitsThroughTor) {
        if(!exitsThroughTor) {
            throw new IllegalStateException("The XMR price is only fetched through Tor, and the proxy in Settings could not be shown to be Tor");
        }
    }

    /** The price is never fetched without Tor: a client with no Tor proxy is refused */
    static void requireTor(com.google.common.net.HostAndPort clientTorProxy) {
        if(clientTorProxy == null) {
            throw new IllegalStateException("The XMR price is only fetched through Tor");
        }
    }

    private static Snapshot fromCoinGecko(HttpClientService http) throws Exception {
        CoinGeckoChart chart = http.requestJson("https://api.coingecko.com/api/v3/coins/monero/market_chart?vs_currency=btc&days=1",
                CoinGeckoChart.class, HEADERS);
        List<double[]> history = new ArrayList<>();
        for(List<Number> point : chart.prices) {
            if(point.size() >= 2) {
                history.add(new double[] {point.get(0).doubleValue(), point.get(1).doubleValue()});
            }
        }
        return snapshot(history, "CoinGecko");
    }

    @SuppressWarnings("unchecked")
    private static Snapshot fromKraken(HttpClientService http) throws Exception {
        KrakenOhlc ohlc = http.requestJson("https://api.kraken.com/0/public/OHLC?pair=XMRXBT&interval=15", KrakenOhlc.class, HEADERS);
        if(ohlc.error != null && !ohlc.error.isEmpty()) {
            throw new IllegalStateException("Kraken: " + ohlc.error);
        }
        List<double[]> history = new ArrayList<>();
        long dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for(Map.Entry<String, Object> entry : ohlc.result.entrySet()) {
            if(!(entry.getValue() instanceof List<?> rows)) {
                continue;
            }
            //[time, open, high, low, close, vwap, volume, count]
            for(Object row : rows) {
                List<Object> fields = (List<Object>)row;
                double time = Double.parseDouble(String.valueOf(fields.get(0))) * 1000;
                if(time >= dayAgo) {
                    history.add(new double[] {time, Double.parseDouble(String.valueOf(fields.get(4)))});
                }
            }
        }
        return snapshot(history, "Kraken");
    }

    private static Snapshot snapshot(List<double[]> history, String source) {
        if(history.isEmpty()) {
            throw new IllegalStateException(source + " returned no prices");
        }
        return new Snapshot(history.get(history.size() - 1)[1], history, source);
    }

    /**
     * How much better (positive) or worse an offer is than the market, in percent. Makers quote the
     * BTC price of one XMR, so a lower price is better for the taker.
     */
    public static double offerVersusMarket(long offerSatsPerXmr, double marketBtcPerXmr) {
        double offer = offerSatsPerXmr / 100_000_000d;
        return (marketBtcPerXmr - offer) / marketBtcPerXmr * 100;
    }

    private static class CoinGeckoChart {
        public List<List<Number>> prices = new ArrayList<>();
    }

    @SuppressWarnings("unused")
    private static class TorCheck {
        public boolean IsTor;
    }

    private static class KrakenOhlc {
        public List<String> error;
        public Map<String, Object> result = Map.of();
    }
}
