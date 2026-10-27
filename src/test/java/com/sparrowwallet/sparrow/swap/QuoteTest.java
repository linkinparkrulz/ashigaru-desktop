package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuoteTest {
    private static SwapEngine.Quote quote(String version) {
        return new SwapEngine.Quote("peer", "/onion3/x:1", version, 680_000, 10_000, 1_000_000, "{}");
    }

    @Test
    void makersBeforeTheFloorAreIncompatible() {
        assertTrue(quote("4.12.3").isIncompatible());
        assertTrue(quote("3.2.0").isIncompatible());
        assertTrue(quote("4.13.0-rc.1").isIncompatible());
        assertFalse(quote("4.13.0").isIncompatible());
        assertFalse(quote("4.16.1").isIncompatible());
        assertFalse(quote("5.0.0").isIncompatible());
    }

    private static SwapEngine.Quote at(String multiaddr, String refundPolicy) {
        return new SwapEngine.Quote("12D3KooWQQeUXdMkwJo8zESkKo7xek7NtVgqh1Q4RPnJCTmESX1Z", multiaddr, "4.16.1", 680_000, 10_000, 1_000_000, refundPolicy);
    }

    @Test
    void refundTermsAreRead() {
        assertEquals(0, at("/ip4/1.2.3.4/tcp/9939", "{\"type\":\"FullRefund\"}").refundRatio());
        assertEquals(0.1, at("/ip4/1.2.3.4/tcp/9939", "{\"type\":\"PartialRefund\",\"content\":{\"anti_spam_deposit_ratio\":0.1}}").refundRatio(), 1e-9);
        assertEquals(0.25, at("/ip4/1.2.3.4/tcp/9939", "{\"type\":\"PartialRefund\",\"content\":{\"anti_spam_deposit_ratio\":\"0.25\"}}").refundRatio(), 1e-9);
        assertEquals(1, at("/ip4/1.2.3.4/tcp/9939", "nonsense").refundRatio());
    }

    @Test
    void providersReadLikeTheLiquidityList() {
        assertEquals("51.15.1.2:9939", at("/ip4/51.15.1.2/tcp/9939/p2p/12D3", "{}").provider());
        assertEquals("maker.example.org:9940", at("/dns4/maker.example.org/tcp/9940/wss", "{}").provider());
        assertEquals("abcdefghij….onion", at("/onion3/abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu:9939", "{}").provider());
        //No usable address: the end of the peer id
        assertEquals("…CTmESX1Z", at("", "{}").provider());
    }

    @Test
    void offersUndercuttingTheMarketAreFlagged() {
        //Market 0.0068 BTC per XMR; this offer asks 680,000 sats (equal), so it is not below market
        assertFalse(quote("4.16.1").isBelowMarket(0.0068));
        //An offer 5% cheaper than the market
        assertTrue(quote("4.16.1").isBelowMarket(0.0068 / 0.95));
        assertFalse(quote("4.16.1").isBelowMarket(null));
    }

    @Test
    void anUnknownVersionGetsAChance() {
        assertFalse(quote(null).isIncompatible());
        assertFalse(quote("").isIncompatible());
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(SwapEngine.Quote.compareVersions("4.9.0", "4.13.0") < 0);
        assertTrue(SwapEngine.Quote.compareVersions("4.13.0", "4.13.0-rc.2") > 0);
        assertEquals(0, SwapEngine.Quote.compareVersions("v4.13.0", "4.13.0"));
    }
}
