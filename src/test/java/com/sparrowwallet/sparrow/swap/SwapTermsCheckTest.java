package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;
import com.sparrowwallet.sparrow.swap.SwapTermsCheck.Reason;
import com.sparrowwallet.sparrow.swap.SwapTermsCheck.Result;
import com.sparrowwallet.sparrow.swap.SwapTermsCheck.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwapTermsCheckTest {
    private static final String FULL = "{\"type\":\"FullRefund\"}";

    private static String partial(String ratio) {
        return "{\"type\":\"PartialRefund\",\"content\":{\"anti_spam_deposit_ratio\":" + ratio + "}}";
    }

    //1 BTC at 1,000,000 sats an XMR is exactly 100 XMR: 1e14 piconero, so the percentages below are exact
    private static final long LOCK = 100_000_000L;
    private static final long PRICE = 1_000_000L;
    private static final long EXPECTED = 100_000_000_000_000L;

    private static Quote quote(String policy) {
        return new Quote("peer", "/ip4/1.2.3.4/tcp/9939", "5.0.4", PRICE, 10_000, 1_000_000_000L, policy);
    }

    private static Result check(long piconero) {
        return SwapTermsCheck.check(quote(FULL), LOCK, piconero, 0, true);
    }

    @Test
    void theOfferedPriceIsFine() {
        Result result = check(EXPECTED);
        assertEquals(Verdict.OK, result.verdict());
        assertEquals(Reason.NONE, result.reason());
        assertEquals(0, result.percentLess(), 1e-9);
        assertEquals(EXPECTED, result.expectedPiconero());
    }

    @Test
    void aBetterPriceNeverWarns() {
        assertEquals(Verdict.OK, check(EXPECTED + EXPECTED / 100).verdict());
        assertEquals(0, check(EXPECTED * 2).percentLess(), 1e-9);
    }

    @Test
    void upToTwoPercentShortIsFine() {
        assertEquals(Verdict.OK, check(EXPECTED / 100 * 99).verdict());
        assertEquals(Verdict.OK, check(EXPECTED / 1000 * 981).verdict());
        //Exactly 2% is not more than 2%
        assertEquals(Verdict.OK, check(EXPECTED / 100 * 98).verdict());
    }

    @Test
    void moreThanTwoPercentShortWarns() {
        Result result = check(EXPECTED / 1000 * 979);
        assertEquals(Verdict.WARN, result.verdict());
        assertEquals(Reason.LESS_XMR, result.reason());
        assertEquals(2.1, result.percentLess(), 1e-6);
        assertEquals(EXPECTED / 1000 * 979, result.offeredPiconero());
    }

    @Test
    void exactlyFivePercentShortStillOnlyWarns() {
        assertEquals(Verdict.WARN, check(EXPECTED / 100 * 95).verdict());
        assertEquals(Verdict.WARN, check(EXPECTED / 1000 * 951).verdict());
    }

    @Test
    void moreThanFivePercentShortIsRefused() {
        Result result = check(EXPECTED / 1000 * 949);
        assertEquals(Verdict.STOP, result.verdict());
        assertEquals(Reason.LESS_XMR, result.reason());
        assertEquals(5.1, result.percentLess(), 1e-6);
        assertEquals(Verdict.STOP, check(EXPECTED / 2).verdict());
    }

    @Test
    void aFullRefundThatTurnsIntoADepositIsRefused() {
        Result result = SwapTermsCheck.check(quote(FULL), LOCK, EXPECTED, 1_500_000, false);
        assertEquals(Verdict.STOP, result.verdict());
        assertEquals(Reason.REFUND_POLICY, result.reason());
    }

    @Test
    void aFullRefundSignatureWithNoDepositIsTheSameThing() {
        assertEquals(Verdict.OK, SwapTermsCheck.check(quote(FULL), LOCK, EXPECTED, 0, false).verdict());
        assertEquals(Verdict.OK, SwapTermsCheck.check(quote(FULL), LOCK, EXPECTED, 0, true).verdict());
    }

    @Test
    void aDepositThatBecomesAFullRefundIsRefusedToo() {
        //Not worse for the user, but the terms are not the ones that were chosen
        Result result = SwapTermsCheck.check(quote(partial("0.1")), LOCK, EXPECTED, 0, true);
        assertEquals(Verdict.STOP, result.verdict());
        assertEquals(Reason.REFUND_POLICY, result.reason());
    }

    @Test
    void aDepositMustStayTheSameShareOfTheLock() {
        Quote tenPercent = quote(partial("0.1"));
        assertEquals(Verdict.OK, SwapTermsCheck.check(tenPercent, LOCK, EXPECTED, 10_000_000, false).verdict());
        //Rounding of a sat or two is not a change
        assertEquals(Verdict.OK, SwapTermsCheck.check(tenPercent, LOCK, EXPECTED, 10_000_001, false).verdict());
        assertEquals(Verdict.STOP, SwapTermsCheck.check(tenPercent, LOCK, EXPECTED, 11_000_000, false).verdict());
        assertEquals(Verdict.STOP, SwapTermsCheck.check(tenPercent, LOCK, EXPECTED, 5_000_000, false).verdict());
    }

    @Test
    void aSmallCoinsDepositIsCheckedWithRoundingToo() {
        //35,449 sats at 1.5% is 531.7 sats; a maker's own minimum of 1,020 is a different policy
        Quote small = new Quote("peer", "/ip4/1.2.3.4/tcp/9939", "5.0.4", 700_000, 1_000, 1_000_000, partial("0.015"));
        long expected = 35_449L * 1_000_000_000_000L / 700_000L;
        assertEquals(Verdict.OK, SwapTermsCheck.check(small, 35_449, expected, 531, false).verdict());
        assertEquals(Verdict.OK, SwapTermsCheck.check(small, 35_449, expected, 532, false).verdict());
        assertEquals(Verdict.STOP, SwapTermsCheck.check(small, 35_449, expected, 1_020, false).verdict());
    }

    @Test
    void aChangedPolicyOutranksAPriceThatIsAlsoWorse() {
        Result result = SwapTermsCheck.check(quote(FULL), LOCK, EXPECTED / 2, 2_000_000, false);
        assertEquals(Verdict.STOP, result.verdict());
        assertEquals(Reason.REFUND_POLICY, result.reason());
    }

    @Test
    void termsThatCannotBeCheckedAreRefused() {
        for(Result result : new Result[] {
                SwapTermsCheck.check(quote("{}"), LOCK, EXPECTED, 0, true),
                SwapTermsCheck.check(quote("not json"), LOCK, EXPECTED, 0, true),
                SwapTermsCheck.check(null, LOCK, EXPECTED, 0, true),
                SwapTermsCheck.check(quote(FULL), 0, EXPECTED, 0, true),
                SwapTermsCheck.check(quote(FULL), LOCK, 0, 0, true),
                SwapTermsCheck.check(quote(FULL), LOCK, -5, 0, true),
                SwapTermsCheck.check(new Quote("peer", "/x", "5.0.4", 0, 1, 2, FULL), LOCK, EXPECTED, 0, true)}) {
            assertEquals(Verdict.STOP, result.verdict());
            assertEquals(Reason.UNREADABLE, result.reason());
        }
    }

    @Test
    void largeAmountsDoNotOverflow() {
        //21 million BTC at a price of one sat an XMR: the products pass a long, and the result must still be sensible
        Quote extreme = new Quote("peer", "/x", "5.0.4", 1, 1, Long.MAX_VALUE, FULL);
        Result result = assertDoesNotThrow(() -> SwapTermsCheck.check(extreme, 2_100_000_000_000_000L, Long.MAX_VALUE, 0, true));
        //The offer implies far more XMR than a long can hold, so what was offered is nowhere near it
        assertEquals(Verdict.STOP, result.verdict());
        assertEquals(Reason.LESS_XMR, result.reason());
        assertTrue(result.percentLess() > 99.9);
    }
}
