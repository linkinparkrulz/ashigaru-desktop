package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SwapCopyTest {
    @Test
    void timesAreAlwaysEstimates() {
        assertEquals("less than a minute", SwapCopy.about(Duration.ofSeconds(20)));
        assertEquals("about 7 min", SwapCopy.about(Duration.ofMinutes(7)));
        //From ten minutes up, rounded to five so a block count never reads as precise
        assertEquals("about 25 min", SwapCopy.about(Duration.ofMinutes(24)));
        assertEquals("about 1 hour", SwapCopy.about(Duration.ofMinutes(60)));
        assertEquals("about 4 hours", SwapCopy.about(Duration.ofMinutes(240)));
        assertEquals("about 3 h 05 min", SwapCopy.about(Duration.ofMinutes(185)));
        assertEquals("about 2 h 10 min", SwapCopy.about(Duration.ofMinutes(130)));
    }

    @Test
    void theRefundIsAnEstimateAndAshigaruDoesItByItself() {
        String terms = SwapCopy.refundTerms("0.04898785");
        assertTrue(terms.contains("up to 0.04898785 BTC"));
        assertTrue(terms.contains("minus network fees"));
        assertTrue(terms.contains("by itself while it is running"));
        assertTrue(terms.contains("about 5 hours"));
    }

    @Test
    void theDepositIsStatedPlainly() {
        String deposit = SwapCopy.depositTerms("0.00099975", "2.0%");
        assertTrue(deposit.startsWith("If this swap fails, the maker may keep 2.0% of your locked BTC (0.00099975 BTC)"));
        assertTrue(deposit.contains("withhold"));
    }

    @Test
    void approvalTermsLeadWithTheDepositWhenThereIsOne() {
        //2% of 0.0499876 BTC
        String terms = BadbankSwapHost.recoveryTerms(4_998_760, 99_975, false);
        assertTrue(terms.startsWith("If this swap fails, the maker may keep 2.0% of your locked BTC (0.00099975 BTC)"));
        //The refund is what is left after the deposit
        assertTrue(terms.contains("up to 0.04898785 BTC"));
        assertTrue(terms.contains("within about 24 hours of the cancel confirming"));
    }

    @Test
    void theReviewColumnsTellTheSameStoryAsTheDialogs() {
        assertTrue(SwapCopy.cancelAndRefundSteps("0.04898785").contains("about 5 hours after the lock confirms"));
        assertTrue(SwapCopy.cancelAndRefundSteps("0.04898785").contains("up to 0.04898785 BTC"));
        assertTrue(SwapCopy.keepRunningDetail().contains("within about 24 hours of the cancel confirming"));
        assertTrue(SwapCopy.keepRunning().endsWith(SwapCopy.keepRunningDetail()));
        assertTrue(SwapCopy.approveEffect("0.04998760").contains("0.04998760 BTC is committed"));
        //The headline and the detail together are the whole deposit statement
        assertEquals(SwapCopy.depositHeadline("0.00099975", "2.0%") + " " + SwapCopy.depositDetail(), SwapCopy.depositTerms("0.00099975", "2.0%"));
    }

    @Test
    void noDepositMeansTheWholeLockIsRefundable() {
        String none = BadbankSwapHost.recoveryTerms(4_998_760, 0, false);
        assertFalse(none.contains("anti-spam"));
        assertTrue(none.contains("up to 0.04998760 BTC"));
        //A full refund signature overrides any deposit amount
        String full = BadbankSwapHost.recoveryTerms(4_998_760, 99_975, true);
        assertFalse(full.contains("anti-spam"));
        assertTrue(full.contains("up to 0.04998760 BTC"));
    }

    @Test
    void thePriceWarningSaysWhatMovedAndThatNothingIsLockedYet() {
        String text = SwapCopy.priceWarning(2.74, "48.700000000000", "50.000000000000");
        assertTrue(text.contains("2.7% less XMR"));
        assertTrue(text.contains("48.700000000000 XMR instead of 50.000000000000 XMR"));
        assertTrue(text.contains("Nothing has been locked"));
        assertTrue(text.contains("approve the lock separately"));
    }

    @Test
    void aRefusedSwapSaysWhyAndThatTheCoinIsSafe() {
        String less = SwapCopy.priceRefusal(SwapTermsCheck.Reason.LESS_XMR, 6.1);
        assertTrue(less.startsWith("Swap not started"));
        assertTrue(less.contains("6.1% less XMR"));
        assertTrue(less.contains("5%"));
        assertTrue(less.contains("Nothing was locked"));
        assertTrue(SwapCopy.priceRefusal(SwapTermsCheck.Reason.REFUND_POLICY, 0).contains("refund policy"));
        assertTrue(SwapCopy.priceRefusal(SwapTermsCheck.Reason.UNREADABLE, 0).contains("could not check"));
        //Never a Java word in a message the user reads
        assertFalse(less.contains("LESS_XMR"));
    }

    @Test
    void theStoppedSwapRecordGivesTheReasonWithoutRepeatingThatNothingWasLocked() {
        //The stopped-swap screen adds that itself, so the reason must not
        String record = SwapCopy.priceRefusalRecord(SwapTermsCheck.Reason.LESS_XMR, 6.1);
        assertEquals("The maker's final terms give you 6.1% less XMR than the offer you chose, which is more than the 5% Ashigaru allows.", record);
        assertFalse(record.contains("Nothing was locked"));
        assertTrue(SwapCopy.priceRefusalRecord(SwapTermsCheck.Reason.REFUND_POLICY, 0).startsWith("The maker changed its refund policy"));
        assertEquals("You rejected the maker's price, which gave 2.7% less XMR than the offer you chose.", SwapCopy.priceRejectedRecord(2.74));
    }

    @Test
    void anAcceptedWorsePriceIsNamedInTheReview() {
        assertEquals("You accepted a price 3.0% below the offer you chose.", SwapCopy.priceAccepted(3.0));
    }
}
