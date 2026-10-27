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
        Quote fair = offer("fair", "4.16.1", 705_000, 10_000, 1_000_000, partial);
        Quote fullRefund = offer("full", "4.16.1", 712_000, 10_000, 1_000_000, full);
        Quote fake = offer("fake", "4.16.1", 650_000, 10_000, 10_000_000_000L, full);
        Quote outdated = offer("old", "4.12.0", 700_000, 10_000, 1_000_000, full);
        Quote tooSmall = offer("big", "4.16.1", 704_000, 1_000_000, 5_000_000, full);

        SwapsToolController.Offers offers = SwapsToolController.Offers.of(List.of(fake, fullRefund, outdated, fair, tooSmall), 373_000, market);
        assertEquals(List.of(fair, fullRefund), offers.shown());
        assertSame(fair, offers.bestRate());
        assertSame(fullRefund, offers.bestRefund());
        assertEquals(1, offers.belowMarket());
        assertEquals(1, offers.outdated());
        assertEquals(1, offers.wrongSize());
        assertTrue(offers.footer(true).contains("1 priced below the market"));

        //Before the market price loads nothing can be called fake, and the footer says so
        SwapsToolController.Offers early = SwapsToolController.Offers.of(List.of(fake, fair), 373_000, null);
        assertEquals(List.of(fake, fair), early.shown());
        assertTrue(early.footer(false).contains("once the market price has loaded"));
    }
}
