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
        assertTrue(terms.contains("about 4 hours"));
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
    void noDepositMeansTheWholeLockIsRefundable() {
        String none = BadbankSwapHost.recoveryTerms(4_998_760, 0, false);
        assertFalse(none.contains("anti-spam"));
        assertTrue(none.contains("up to 0.04998760 BTC"));
        //A full refund signature overrides any deposit amount
        String full = BadbankSwapHost.recoveryTerms(4_998_760, 99_975, true);
        assertFalse(full.contains("anti-spam"));
        assertTrue(full.contains("up to 0.04998760 BTC"));
    }
}
