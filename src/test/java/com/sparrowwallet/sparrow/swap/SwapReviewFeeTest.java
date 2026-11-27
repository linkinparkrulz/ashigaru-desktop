package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** A lock at the 1,000-sat minimum is said to be the minimum, so a block explorer's fee rate for it is not read as a wrong one */
class SwapReviewFeeTest {
    private static SwapReview review(long feeSats) {
        return SwapReview.commit(5_000_000, 5_000_000 - feeSats, feeSats, 1L, 0, true, "4x", "maker", Instant.now(), null);
    }

    @Test
    void theMinimumIsRecognised() {
        assertTrue(review(1_000).minimumFee());
        assertFalse(review(1_450).minimumFee());
        assertEquals(SwapReview.MIN_LOCK_FEE_SATS, BadbankSwapHost.MIN_LOCK_FEE_SATS);
    }

    @Test
    void theLockFeeIsNeverBelowTheMinimum() {
        assertEquals(1_000, BadbankSwapHost.lockFee(726));
        assertEquals(1_450, BadbankSwapHost.lockFee(1_450));
    }
}
