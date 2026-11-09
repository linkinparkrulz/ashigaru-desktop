package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SwapReviewTest {
    private static final SwapReview.Decision NONE = new SwapReview.Decision() {
        @Override
        public void confirm() {}

        @Override
        public void decline() {}
    };

    private static SwapReview commit(long amnesty, boolean fullRefund) {
        return SwapReview.commit(5_000_000, 4_998_760, 1_240, 14_816_324_640_000L, amnesty, fullRefund, "4abc", "maker", Instant.now(), NONE);
    }

    @Test
    void theAmountsAreShownToFullPrecision() {
        SwapReview review = commit(99_975, false);
        assertEquals("0.05000000", review.coinBtc());
        assertEquals("0.04998760", review.lockBtc());
        assertEquals("0.00001240", review.feeBtc());
        assertEquals("14.816324640000", review.xmr());
        //The fee is inside the coin: lock plus fee is exactly what leaves Badbank
        assertEquals(review.coinSats(), review.lockSats() + review.feeSats());
    }

    @Test
    void theDepositComesOffTheRefund() {
        SwapReview review = commit(99_975, false);
        assertTrue(review.hasDeposit());
        assertEquals(4_898_785, review.refundSats());
        assertEquals("0.04898785", review.refundBtc());
        assertEquals("0.00099975", review.depositBtc());
        assertEquals("2.0%", review.depositPercent());
    }

    @Test
    void noDepositMeansTheWholeLockCanComeBack() {
        assertFalse(commit(0, false).hasDeposit());
        assertEquals(4_998_760, commit(0, false).refundSats());
        //A full refund signature overrides a deposit amount
        SwapReview full = commit(99_975, true);
        assertFalse(full.hasDeposit());
        assertEquals(4_998_760, full.refundSats());
    }

    @Test
    void thePriceIsWorkedOutFromTheTerms() {
        assertEquals(296.4, commit(0, false).xmrPerBtc(), 0.01);
    }

    @Test
    void theCountdownReadsMinutesAndSeconds() {
        assertEquals("2:41", SwapReview.countdown(Duration.ofSeconds(161)));
        assertEquals("0:05", SwapReview.countdown(Duration.ofSeconds(5)));
        assertEquals("0:00", SwapReview.countdown(Duration.ofSeconds(-3)));
    }

    @Test
    void aMakersIdIsShortenedToStartAndEnd() {
        assertEquals("12D3KooW…SX1Z", SwapReview.shortId("12D3KooWQQeUXdMkwJo8zESkKo7xek7NtVgqh1Q4RPnJCTmESX1Z"));
        assertEquals("short", SwapReview.shortId("short"));
        assertEquals("", SwapReview.shortId(null));
    }
}
