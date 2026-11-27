package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwapsToolControllerTest {
    //Monero's donation address, a standard mainnet address
    private static final String MAINNET = "44AFFq5kSiGBoZ4NMDwYtN18obc8AemS33DBLWs3H7otXft3XjrpDtQGv7SqSsaBYBb98uNbr2VBBEt7f2wfn3RVGQBEP3A";

    @Test
    void mainnetAddressesAreAccepted() {
        assertTrue(SwapsToolController.isMainnetMoneroAddress(MAINNET));
        assertTrue(SwapsToolController.isMainnetMoneroAddress("8" + MAINNET.substring(1)));
    }

    @Test
    void otherAddressesAreRefused() {
        assertFalse(SwapsToolController.isMainnetMoneroAddress("5" + MAINNET.substring(1)));
        assertFalse(SwapsToolController.isMainnetMoneroAddress(MAINNET.substring(1)));
        assertFalse(SwapsToolController.isMainnetMoneroAddress(MAINNET.substring(0, 94) + "0"));
        assertFalse(SwapsToolController.isMainnetMoneroAddress(""));
    }

    private static Quote offer(String peer, String version, long priceSats, long min, long max, String refund) {
        return new Quote(peer, "/ip4/1.2.3.4/tcp/9939", version, priceSats, min, max, refund);
    }

    @Test
    void onlyOffersThatCanCompleteASwapAreShown() {
        String full = "{\"type\":\"FullRefund\"}";
        String partial = "{\"type\":\"PartialRefund\",\"content\":{\"anti_spam_deposit_ratio\":0.1}}";
        double market = 0.0070; //BTC per XMR
        Quote fair = offer("fair", "5.0.2", 705_000, 10_000, 1_000_000, partial);
        Quote fullRefund = offer("full", "5.0.2", 712_000, 10_000, 1_000_000, full);
        Quote fake = offer("fake", "5.0.2", 650_000, 10_000, 10_000_000_000L, full);
        Quote outdated = offer("old", "4.16.1", 700_000, 10_000, 1_000_000, full);
        Quote tooSmall = offer("big", "5.0.2", 704_000, 1_000_000, 5_000_000, full);

        SwapsToolController.Offers offers = SwapsToolController.Offers.of(List.of(fake, fullRefund, outdated, fair, tooSmall), 373_000, market);
        assertEquals(List.of(fair, fullRefund), offers.shown());
        assertSame(fair, offers.bestRate());
        assertSame(fullRefund, offers.bestRefund());
        assertEquals(1, offers.belowMarket());
        assertEquals(1, offers.outdated());
        assertEquals(0, offers.newer());
        assertEquals(1, offers.wrongSize());
        String footer = offers.footer(true, "0.00373000");
        assertTrue(footer.contains("1 priced below market"), footer);
        assertTrue(footer.contains("1 that can't take 0.00373000 BTC"), footer);
        //Rows say "premium" (priced above market) or "discount" (below it), so the hidden ones are put in the same terms
        assertFalse(footer.contains("more XMR than the market"), footer);
        //With no coin chosen no amount is mentioned
        assertTrue(offers.footer(true, null).contains("1 that can't take this amount"));

        //Before the market price loads nothing can be called fake, and the footer says so
        SwapsToolController.Offers early = SwapsToolController.Offers.of(List.of(fake, fair), 373_000, null);
        assertEquals(List.of(fake, fair), early.shown());
        assertTrue(early.footer(false, null).contains("once the market price has loaded"));
    }

    @Test
    void makersOnTheNewerProtocolAreLeftOutAndSaidSo() {
        String full = "{\"type\":\"FullRefund\"}";
        Quote current = offer("a", "5.0.2", 705_000, 10_000, 1_000_000, full);
        Quote newer = offer("b", "6.0.0", 704_000, 10_000, 1_000_000, full);
        Quote old = offer("c", "4.16.1", 703_000, 10_000, 1_000_000, full);
        SwapsToolController.Offers offers = SwapsToolController.Offers.of(List.of(current, newer, old), 373_000, 0.0070);
        assertEquals(List.of(current), offers.shown());
        assertEquals(1, offers.newer());
        assertEquals(1, offers.outdated());
        String footer = offers.footer(true, "0.00373000");
        assertTrue(footer.contains("1 running newer swap software than this version of Ashigaru supports"), footer);
        assertTrue(footer.contains("1 running eigenwallet 4 or older"), footer);
    }

    @Test
    void swapStartTimesAreRead() {
        com.google.gson.JsonObject swap = new com.google.gson.JsonObject();
        swap.addProperty("startedAt", "2026-10-30 6:41:05.123456 +00:00:00");
        assertEquals(java.time.Instant.parse("2026-10-30T06:41:05Z"), SwapsToolController.startedAt(swap));
        swap.addProperty("startedAt", "2026-10-30 16:41:05.0 +00:00:00");
        assertEquals(java.time.Instant.parse("2026-10-30T16:41:05Z"), SwapsToolController.startedAt(swap));
        swap.add("startedAt", com.google.gson.JsonNull.INSTANCE);
        assertNull(SwapsToolController.startedAt(swap));
        assertNull(SwapsToolController.startedAt(new com.google.gson.JsonObject()));
    }

    @Test
    void aMakerThatRefusedThisCoinIsNotOfferedForItAgain() {
        String full = "{\"type\":\"FullRefund\"}";
        //Both list a range that includes the coin; the first one then said it buys 0.01 BTC at least
        Quote refuser = offer("refuser", "5.0.3", 705_000, 10_000, 1_000_000, full);
        Quote other = offer("other", "5.0.2", 706_000, 10_000, 1_000_000, full);
        com.sparrowwallet.sparrow.swap.MakerRefusals refusals = new com.sparrowwallet.sparrow.swap.MakerRefusals();
        refusals.record("refuser", com.sparrowwallet.sparrow.swap.MakerRefusals.parse(
                "Seller refused to buy 0.00035449 BTC because the minimum configured buy limit is 0.01000000 BTC", 35_449, java.time.Instant.now()).orElseThrow());

        SwapsToolController.Offers offers = SwapsToolController.Offers.of(List.of(refuser, other), 35_449, null, refusals);
        assertEquals(List.of(other), offers.shown());
        assertEquals(1, offers.wrongSize());
        //With no coin chosen, nothing is hidden on a maker's say-so about a coin
        assertEquals(2, SwapsToolController.Offers.of(List.of(refuser, other), 0, null, refusals).shown().size());
    }
}
