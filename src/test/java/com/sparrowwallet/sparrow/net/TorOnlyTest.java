package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.Mode;
import com.sparrowwallet.sparrow.paynym.PayNymService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AD-28: Whirlpool statistics, fee rates and the PayNym directory never go out directly, and nothing goes out offline */
class TorOnlyTest {
    private static final HostAndPort TOR = HostAndPort.fromParts("127.0.0.1", 9050);

    @Test
    void offlineOrWithoutATorProxyNothingIsFetched() {
        assertNotNull(TorOnly.refusal(Mode.OFFLINE, TOR), "offline, even with Tor running");
        assertNotNull(TorOnly.refusal(Mode.ONLINE, null), "no Tor proxy (starting or gone) is a refusal, not a direct connection");
        assertNotNull(TorOnly.refusal(null, null));
        assertNull(TorOnly.refusal(Mode.ONLINE, TOR));
        assertNull(TorOnly.refusal(null, TOR));
    }

    @Test
    void theServicesAreOnlyReachedAtTheirOnionAddresses() {
        assertTrue(PayNymService.HOST_URL.matches("http://[a-z2-7]{56}\\.onion"), PayNymService.HOST_URL);
        assertTrue(com.sparrowwallet.sparrow.gui.StatsControllerAccess.apiBase().matches("http://[a-z2-7]{56}\\.onion/api"));
    }

    @Test
    void withoutTorTheFeeRateSourceAddsNothingAndConnectsNowhere() {
        //No Tor in a test run: the refusal comes before any request, and the server's own estimates are kept
        assertEquals(Map.of(), FeeRatesSource.MEMPOOL_SPACE.getBlockTargetFeeRates(Map.of(1, 5.0, 6, 3.0)));
        assertEquals(Map.of(), FeeRatesSource.BITCOINFEES_EARN_COM.getBlockTargetFeeRates(Map.of(1, 5.0)));
    }
}
