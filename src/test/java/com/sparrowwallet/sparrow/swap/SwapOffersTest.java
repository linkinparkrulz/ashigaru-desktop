package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;
import com.sparrowwallet.sparrow.swap.SwapOffers.Row;
import com.sparrowwallet.sparrow.swap.SwapOffers.Sort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwapOffersTest {
    private static final String NONE = "{\"type\":\"FullRefund\"}";
    private static String deposit(double ratio) {
        return "{\"type\":\"PartialRefund\",\"content\":{\"anti_spam_deposit_ratio\":" + ratio + "}}";
    }

    private static Quote offer(String host, long priceSats, String refund) {
        return new Quote("peer-" + host, "/ip4/" + host + "/tcp/9939", "5.0.2", priceSats, 10_000, 100_000_000, refund);
    }

    //Prices are sats per XMR: a lower price gives more XMR. Market 0.0033 BTC per XMR.
    private static final Quote A = offer("1.1.1.1", 336_300, deposit(0.02));  //the most XMR, 2% deposit
    private static final Quote B = offer("2.2.2.2", 336_800, NONE);
    private static final Quote C = offer("3.3.3.3", 337_400, NONE);
    private static final Quote D = offer("4.4.4.4", 337_700, deposit(0.05));
    private static final List<Quote> SHOWN = List.of(D, B, A, C);
    private static final double MARKET = 0.0033;
    private static final long COIN = 4_998_760;

    @Test
    void offersAreComparedByWhatTheUserWouldReceive() {
        List<Row> rows = SwapOffers.rows(SHOWN, COIN, MARKET, Sort.MOST_XMR);
        assertEquals(List.of(A, B, C, D), rows.stream().map(Row::quote).toList());
        assertEquals(COIN / 336_300d, rows.get(0).xmr(), 1e-12);
        assertTrue(rows.get(0).xmr() > rows.get(1).xmr());
        assertEquals(0, rows.get(0).behindMost(), 1e-12);
        assertEquals(rows.get(0).xmr() - rows.get(1).xmr(), rows.get(1).behindMost(), 1e-12);
    }

    @Test
    void theDeductionIsStatedInBtcForTheChosenCoin() {
        Row a = SwapOffers.rows(SHOWN, COIN, MARKET, Sort.MOST_XMR).get(0);
        assertEquals(0.02, a.deductionRatio(), 1e-12);
        assertEquals(99_975, a.deductionSats());
        assertFalse(a.noDeduction());
        Row b = SwapOffers.rows(SHOWN, COIN, MARKET, Sort.MOST_XMR).get(1);
        assertTrue(b.noDeduction());
        assertEquals(0, b.deductionSats());
    }

    @Test
    void theBadgesGoToTheMetricTheyDescribe() {
        List<Row> rows = SwapOffers.rows(SHOWN, COIN, MARKET, Sort.MOST_XMR);
        assertTrue(rows.get(0).mostXmr());
        assertEquals(1, rows.stream().filter(Row::mostXmr).count());
        //Best refund is the best rate among those with no deduction, not just the first of several ties
        assertEquals(1, rows.stream().filter(Row::bestRefund).count());
        assertSame(B, rows.stream().filter(Row::bestRefund).findFirst().orElseThrow().quote());
        //And the other no-deduction offer is not made to look worse: it says so in its own column
        assertTrue(rows.get(2).noDeduction());
    }

    @Test
    void sortingIsExplicitAndChangesTheOrder() {
        assertEquals(List.of(B, C, A, D), SwapOffers.rows(SHOWN, COIN, MARKET, Sort.LOWEST_DEDUCTION).stream().map(Row::quote).toList(),
                "no deduction first, then by the most XMR");
        assertEquals(List.of(A, B, C, D), SwapOffers.rows(SHOWN, COIN, MARKET, Sort.MAKER).stream().map(Row::quote).toList());
        //Whatever the order, the badges stay with the same offers
        List<Row> byDeduction = SwapOffers.rows(SHOWN, COIN, MARKET, Sort.LOWEST_DEDUCTION);
        assertSame(A, byDeduction.stream().filter(Row::mostXmr).findFirst().orElseThrow().quote());
        assertSame(B, byDeduction.stream().filter(Row::bestRefund).findFirst().orElseThrow().quote());
    }

    @Test
    void withoutACoinThereIsNoEstimateButTheRatesStillCompare() {
        List<Row> rows = SwapOffers.rows(SHOWN, 0, MARKET, Sort.MOST_XMR);
        assertNull(rows.get(0).xmr());
        assertNull(rows.get(0).behindMost());
        assertEquals(0, rows.get(0).deductionSats());
        assertEquals(100_000_000d / 336_300, rows.get(0).xmrPerBtc(), 1e-9);
        assertTrue(rows.get(0).mostXmr());
    }

    @Test
    void theGapToTheMarketKeepsItsSign() {
        Row a = SwapOffers.rows(SHOWN, COIN, MARKET, Sort.MOST_XMR).get(0);
        //0.0033 per XMR is the market; this maker wants 0.003363, so the user gets fewer XMR than the market pays
        assertTrue(a.versusMarket() < 0);
        assertEquals("−1.9%", SwapOffers.percent(a.versusMarket()));
        assertEquals("+0.3%", SwapOffers.percent(0.26));
        assertNull(SwapOffers.rows(SHOWN, COIN, null, Sort.MOST_XMR).get(0).versusMarket(), "no market price, no comparison");
    }

    @Test
    void theSummaryNeverShowsThePenaltyWithoutAWayOut() {
        SwapOffers.Summary s = SwapOffers.summary(SwapOffers.rows(SHOWN, COIN, MARKET, Sort.MOST_XMR));
        assertSame(A, s.mostXmr().quote());
        assertSame(B, s.mostWithNoDeduction().quote());

        //When the offer with the most XMR has no deduction there is only one figure to show
        SwapOffers.Summary clean = SwapOffers.summary(SwapOffers.rows(List.of(B, C), COIN, MARKET, Sort.MOST_XMR));
        assertSame(B, clean.mostXmr().quote());
        assertNull(clean.mostWithNoDeduction());

        //And when every offer keeps something, there is no clean alternative to claim
        SwapOffers.Summary all = SwapOffers.summary(SwapOffers.rows(List.of(A, D), COIN, MARKET, Sort.MOST_XMR));
        assertNull(all.mostWithNoDeduction());
        assertNull(SwapOffers.summary(List.of()).mostXmr());
    }

    @Test
    void anEmptyListIsEmpty() {
        assertTrue(SwapOffers.rows(List.of(), COIN, MARKET, Sort.MOST_XMR).isEmpty());
    }

    // ----------------------------------------------------------------- unfinished swaps

    private static JsonArray swaps(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void aStoppedSwapThatHoldsBtcNeedsAttention() {
        JsonArray list = swaps("[{\"swapId\":\"c20e9a17-0000\",\"state\":\"waiting for cancel timelock expiration\",\"complete\":false,\"running\":false,\"past\":false}]");
        List<SwapAttention> needing = SwapAttention.of(list, Instant.now());
        assertEquals(1, needing.size());
        assertEquals("c20e9a17-0000", needing.get(0).swapId());
        assertEquals("Waiting to cancel", needing.get(0).title());
        assertEquals("1 swap needs attention", SwapAttention.headline(needing));
        assertEquals("Swap c20e9a17 is not running (stopped at “Waiting to cancel”). Your BTC is still locked in it.", SwapAttention.text(needing));
    }

    @Test
    void swapsThatNeedNothingAreLeftOut() {
        JsonArray list = swaps("["
                + "{\"swapId\":\"a\",\"state\":\"btc is locked\",\"complete\":false,\"running\":true,\"past\":false},"      //running: looks after itself
                + "{\"swapId\":\"b\",\"state\":\"xmr is redeemed\",\"complete\":true,\"running\":false,\"past\":false},"    //done
                + "{\"swapId\":\"c\",\"state\":\"btc is refunded\",\"complete\":true,\"running\":false,\"past\":false},"    //refunded
                + "{\"swapId\":\"d\",\"state\":\"quote has been requested\",\"complete\":false,\"running\":false,\"past\":false},"  //nothing locked
                + "{\"swapId\":\"e\",\"state\":\"btc is locked\",\"complete\":false,\"running\":false,\"past\":true}"       //an earlier identity: cannot be acted on
                + "]");
        assertTrue(SwapAttention.of(list, Instant.now()).isEmpty());
    }

    @Test
    void severalSwapsAreCountedAndTheFirstIsNamed() {
        JsonArray list = swaps("["
                + "{\"swapId\":\"11111111-aaaa\",\"state\":\"btc is locked\",\"complete\":false,\"running\":false,\"past\":false},"
                + "{\"swapId\":\"22222222-bbbb\",\"state\":\"xmr lock transaction seen\",\"complete\":false,\"running\":false,\"past\":false}]");
        List<SwapAttention> needing = SwapAttention.of(list, Instant.now());
        assertEquals(2, needing.size());
        assertEquals("2 swaps need attention", SwapAttention.headline(needing));
        assertTrue(SwapAttention.text(needing).startsWith("Swap 11111111 and 1 more is not running"));
        assertTrue(SwapAttention.text(needing).endsWith("locked in them."));
    }
}
