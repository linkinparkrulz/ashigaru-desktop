package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class MakerRefusalsTest {
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final long COIN = 35_449;

    private static final String MIN_BUY = "Failed to setup swap after retries: A network error occurred while setting up the swap: "
            + "Something went wrong during the swap setup protocol: Seller refused to buy 0.00035449 BTC because the minimum configured buy limit is 0.01000000 BTC";
    private static final String DEPOSIT = "Failed to setup swap after retries: Swap rejected: Anti-spam deposit 0.00000531 BTC is too small; minimum required is 0.00001020 BTC";
    private static final String SELLER = "Failed to setup swap after retries: A network error occurred while setting up the swap: "
            + "Something went wrong during the swap setup protocol: Seller encountered a problem, please try again later.";

    @Test
    void aBuyLimitTeachesTheSmallestSwapTheMakerTakes() {
        MakerRefusals.Refusal refusal = MakerRefusals.parse(MIN_BUY, COIN, NOW).orElseThrow();
        assertEquals(MakerRefusals.Kind.MIN_BUY, refusal.kind());
        assertEquals(1_000_000, refusal.minSats());
    }

    @Test
    void aDepositUnderTheMinimumTeachesTheSmallestCoinThatWorks() {
        MakerRefusals.Refusal refusal = MakerRefusals.parse(DEPOSIT, COIN, NOW).orElseThrow();
        assertEquals(MakerRefusals.Kind.DEPOSIT_TOO_SMALL, refusal.kind());
        //531 sats was the deposit on 35,449; 1,020 are needed, so about 68,000 sats
        assertEquals(68_095, refusal.minSats());
    }

    @Test
    void unknownErrorsAreNotRefusals() {
        assertTrue(MakerRefusals.parse("Tor stopped", COIN, NOW).isEmpty());
        assertTrue(MakerRefusals.parse(null, COIN, NOW).isEmpty());
        assertNull(MakerRefusals.explain("Tor stopped"));
    }

    @Test
    void aMakerThatRefusedIsLeftOutForCoinsItWouldRefuseAgain() {
        MakerRefusals refusals = new MakerRefusals();
        refusals.record("maker", MakerRefusals.parse(MIN_BUY, COIN, NOW).orElseThrow());
        assertTrue(refusals.rulesOut("maker", COIN, NOW));
        assertTrue(refusals.rulesOut("maker", 999_999, NOW));
        assertFalse(refusals.rulesOut("maker", 1_000_000, NOW));
        assertFalse(refusals.rulesOut("someone else", COIN, NOW));
    }

    @Test
    void aDepositRefusalLeavesLargerCoinsOpen() {
        MakerRefusals refusals = new MakerRefusals();
        refusals.record("maker", MakerRefusals.parse(DEPOSIT, COIN, NOW).orElseThrow());
        assertTrue(refusals.rulesOut("maker", 60_000, NOW));
        assertFalse(refusals.rulesOut("maker", 70_000, NOW));
    }

    @Test
    void aMakerWithAProblemOfItsOwnReturnsAfterAWhile() {
        MakerRefusals refusals = new MakerRefusals();
        refusals.record("maker", MakerRefusals.parse(SELLER, COIN, NOW).orElseThrow());
        assertTrue(refusals.rulesOut("maker", 5_000_000, NOW.plusSeconds(60)));
        assertFalse(refusals.rulesOut("maker", 5_000_000, NOW.plus(MakerRefusals.SELLER_PROBLEM_FOR).plus(Duration.ofSeconds(1))));
    }

    @Test
    void theStoppedSwapSaysWhatHappenedAndThatNothingWasLocked() {
        String reason = SwapProgress.stopReason(MIN_BUY, null);
        assertTrue(reason.contains("only swaps 0.01 BTC or more"));
        assertTrue(reason.contains("Nothing was locked"));
        assertTrue(SwapProgress.stopReason(DEPOSIT, null).contains("too small for this maker"));
        assertTrue(SwapProgress.stopReason(SELLER, null).contains("problem on its own side"));
    }
}
