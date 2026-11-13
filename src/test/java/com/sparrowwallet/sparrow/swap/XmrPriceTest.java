package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XmrPriceTest {
    @Test
    void cheaperOfferIsBetterThanMarket() {
        //Market 0.004 BTC per XMR; an offer at 396,000 sats per XMR is 1% cheaper
        assertEquals(1.0, XmrPrice.offerVersusMarket(396_000, 0.004), 1e-9);
        assertEquals(-2.5, XmrPrice.offerVersusMarket(410_000, 0.004), 1e-9);
    }
}
