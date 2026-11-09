package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.sparrowwallet.sparrow.swap.SwapDashboard.*;
import static com.sparrowwallet.sparrow.swap.SwapProgress.Step;
import static org.junit.jupiter.api.Assertions.*;

class SwapDashboardTest {
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private static final Instant NOW = T0.plus(Duration.ofMinutes(12));
    private static final String ID = "3f9a21c0-7b64-4d3e-9a10-0c2b7d1e4f55";
    //0.05 BTC coin, 1,240 sats fee, 2% deposit, 14.816324640000 XMR
    private static final SwapDetails TERMS = new SwapDetails(4_998_760, 1_240, 14_816_324_640_000L, 99_975, false,
            "4qRsDBoPDju5bT9fVyMjsF7VfVRbqMKuDbwoZHP33BdRRFZP3mjo9jP3f53MyMqbFRRVohPyPDFRD5ZBoQXfo7FPHRwym", "12D3KooW…b3f9");

    private static SwapProgress.Status at(Step step, String detail) {
        return new SwapProgress.Status(step, detail, T0, step == Step.DONE || step == Step.REFUNDED);
    }

    private static SwapProgress.Status stopped(Step reached) {
        return new SwapProgress.Status(Step.STOPPED, "The swap stopped.", T0, true, reached);
    }

    private static SwapTimelock before(int blocks) {
        return new SwapTimelock(SwapTimelock.Phase.BEFORE_CANCEL, blocks, NOW);
    }

    private static SwapTimelock open(int blocks) {
        return new SwapTimelock(SwapTimelock.Phase.CANCEL_OPEN, blocks, NOW);
    }

    private static SwapDashboard of(SwapProgress.Status status, SwapTimelock timelock, boolean running, SwapDetails details) {
        return SwapDashboard.of(new Input(ID, status, timelock, running, details, false, NOW));
    }

    @Test
    void beforeAnythingIsLockedTheCoinIsSafeAndClosingIsFine() {
        SwapDashboard d = of(at(Step.TERMS, null), null, true, null);
        assertEquals(Tone.INFO, d.banner().tone());
        assertEquals("Nothing for you to do", d.banner().title());
        assertNull(d.banner().cta());
        assertEquals(0, d.milestones().current());
        assertFalse(d.milestones().recovery());
        assertTrue(d.summary().funds().startsWith("Still in Badbank. Nothing is locked yet."));
        assertEquals(Tone.GOOD, d.summary().closing().tone());
        assertEquals("Safe to close", d.summary().closing().label());
        assertEquals("the maker, over Tor", d.now().waitingOn());
    }

    @Test
    void theTimerIsAbsentWhileTheUserIsBeingAsked() {
        SwapDashboard d = of(at(Step.APPROVAL, null), null, true, null);
        assertEquals("Waiting for you", d.banner().title());
        assertEquals(Tone.WARN, d.banner().tone());
        assertNull(d.now().waitingOn(), "a timer during approval adds pressure without helping the decision");
    }

    @Test
    void afterTheLockTheUserShouldKeepAshigaruOpenAndSeesWhenRecoveryOpens() {
        SwapDashboard d = of(at(Step.LOCK_CONFIRM, "1 confirmation · 9c1f4a2e…77e0a1b3"), before(13), true, TERMS);
        assertEquals(1, d.milestones().current());
        assertEquals("Keep open", d.summary().closing().label());
        assertEquals(Tone.WARN, d.summary().closing().tone());
        assertEquals("If the swap stalls", d.summary().recoveryTitle());
        assertTrue(d.summary().recoveryText().contains("cancellation becomes available in about 2 h 10 min"), d.summary().recoveryText());
        assertTrue(d.summary().recoveryText().contains("Ashigaru then cancels and refunds for you"));
        assertTrue(d.summary().timeline() > 0 && d.summary().timeline() < 0.5, "part-way to the cancel marker");
        //Confirmations are labelled by the transaction they belong to
        assertEquals("Bitcoin lock transaction", d.now().facts().get(0).label());
        assertEquals("1 confirmation", d.now().facts().get(0).value());
        assertEquals("the Bitcoin network", d.now().waitingOn());
    }

    @Test
    void withoutTimelockDataRecoveryIsStatedPlainly() {
        SwapDashboard d = of(at(Step.LOCK_CONFIRM, null), null, true, null);
        assertTrue(d.summary().recoveryText().startsWith("Cancellation opens about 5 hours after your lock confirms."));
        assertNull(d.summary().timeline());
    }

    @Test
    void theMakersConfirmationsAreLabelledAsTheirs() {
        SwapDashboard d = of(at(Step.MAKER_CONFIRM, "6 of 10 confirmations"), before(9), true, TERMS);
        assertEquals(2, d.milestones().current());
        assertEquals("Maker’s XMR lock (their transaction)", d.now().facts().get(0).label());
    }

    @Test
    void aRunningSwapIsRefundedByAshigaruItselfSoThereIsNoButton() {
        SwapDashboard d = of(at(Step.REFUND_WAIT, null), open(120), true, TERMS);
        assertEquals(Tone.WARN, d.banner().tone());
        assertTrue(d.banner().title().contains("Ashigaru is cancelling it for you"));
        assertNull(d.banner().cta(), "the engine refuses a manual cancel while the swap is running");
        assertTrue(d.milestones().recovery());
        assertEquals(1, d.milestones().current());
        assertEquals("Recovery", d.summary().recoveryTitle());
        assertTrue(d.summary().recoveryText().startsWith("Automatic."));
        assertTrue(d.summary().timelineCancelOpen());
        assertTrue(d.summary().timeline() > 0.5);
    }

    @Test
    void aStoppedSwapWithCancelOpenGetsTheButton() {
        SwapDashboard d = of(stopped(Step.MAKER_CONFIRM), open(140), false, TERMS);
        assertEquals("This swap has stopped. You can cancel it now.", d.banner().title());
        assertEquals(Action.CANCEL_AND_REFUND, d.banner().cta().action());
        assertTrue(d.banner().cta().enabled());
        assertTrue(d.milestones().recovery());
        assertEquals("Stopped at step 3 of 5: Verify the maker’s XMR", d.milestones().stoppedAt(), "the stall keeps its context");
        assertTrue(d.summary().recoveryText().startsWith("Cancel is available now."));
        //The refund is an estimate: the deposit comes off, and fees are stated as deducted
        assertEquals("up to 0.04898785 BTC", d.now().facts().get(0).value());
        assertTrue(d.now().facts().stream().anyMatch(f -> f.label().equals("Network fees")));
        assertTrue(d.now().facts().stream().anyMatch(f -> f.label().startsWith("Maker may keep") && f.value().contains("0.00099975")));
    }

    @Test
    void aStoppedSwapBeforeCancelOpensShowsTheButtonDisabledWithWhen() {
        SwapDashboard d = of(stopped(Step.MAKER_CONFIRM), before(13), false, TERMS);
        assertEquals("This swap has stopped, and cancelling is not open yet.", d.banner().title());
        assertFalse(d.banner().cta().enabled());
        assertEquals("Available in about 2 h 10 min", d.banner().cta().note());
        assertFalse(d.milestones().recovery(), "the stall has not reached the refund path yet");
        assertTrue(d.milestones().attention());
        assertEquals(2, d.milestones().current());
    }

    @Test
    void aStoppedSwapWithNoTimelockYetSaysWhenCancelNormallyOpens() {
        SwapDashboard d = of(stopped(Step.LOCK_CONFIRM), null, false, TERMS);
        assertFalse(d.banner().cta().enabled());
        assertTrue(d.banner().cta().note().contains("about 5 hours"));
    }

    @Test
    void aSwapThatStoppedBeforeTheLockLockedNothingAndLeadsBackToTheOffers() {
        SwapDashboard d = of(stopped(Step.TERMS), null, false, null);
        assertEquals("This swap stopped", d.banner().title());
        //The way to choose another offer is the main action, so the user is never stranded on this screen
        assertEquals(Action.BACK_TO_OFFERS, d.banner().cta().action());
        assertTrue(d.banner().cta().enabled());
        assertTrue(d.canLeave());
        assertEquals(Tone.GOOD, d.summary().closing().tone());
        assertEquals("Nothing is locked yet, so there is nothing to recover.", d.summary().recoveryText());
    }

    @Test
    void aCompletedSwapAnnouncesSuccessOnceAndTheRestIsAReceipt() {
        SwapDashboard d = of(at(Step.DONE, "XMR transaction 5e07b1d4…c29a7f60"), null, false, TERMS);
        assertEquals(Tone.GOOD, d.banner().tone());
        assertEquals("Swap complete", d.banner().title());
        assertEquals(Action.BACK_TO_OFFERS, d.banner().cta().action());
        assertEquals("Receipt", d.now().title());
        assertFalse(d.now().title().toLowerCase().contains("complete"), "success is announced once, in the banner");
        assertEquals("XMR sent", d.summary().xmrLabel());
        assertTrue(d.now().facts().stream().anyMatch(f -> f.label().equals("XMR sent") && f.value().equals("14.816324640000 XMR")));
        assertTrue(d.now().facts().stream().anyMatch(f -> f.label().equals("XMR transaction") && f.value().equals("5e07b1d4…c29a7f60")));
        assertTrue(d.now().doing().contains("spendable"));
        assertEquals(MAIN.size(), d.milestones().current(), "every stage is done");
        assertEquals("Safe to close", d.summary().closing().label());
    }

    @Test
    void aRefundedSwapIsDoneAndGood() {
        SwapDashboard d = of(at(Step.REFUNDED, "Refund 1234abcd…"), null, false, TERMS);
        assertEquals(Tone.GOOD, d.banner().tone());
        assertTrue(d.milestones().recovery());
        assertEquals(RECOVERY.size(), d.milestones().current());
        assertEquals("Back in Badbank.", d.summary().funds());
    }

    @Test
    void theUserCanAlwaysLeaveASwapThatIsOverOrNotRunning() {
        //Over
        assertTrue(of(at(Step.DONE, null), null, false, TERMS).canLeave());
        assertTrue(of(at(Step.REFUNDED, null), null, false, TERMS).canLeave());
        //Stopped, whatever its recovery needs: they can start another swap while it waits
        assertTrue(of(stopped(Step.MAKER_CONFIRM), open(140), false, TERMS).canLeave());
        assertTrue(of(stopped(Step.MAKER_CONFIRM), before(13), false, TERMS).canLeave());
        assertTrue(of(stopped(Step.LOCK_CONFIRM), null, false, TERMS).canLeave());
        //A swap Ashigaru is running looks after itself and keeps the page
        assertFalse(of(at(Step.MAKER_CONFIRM, null), before(9), true, TERMS).canLeave());
        assertFalse(of(at(Step.REFUND_WAIT, null), open(120), true, TERMS).canLeave());
    }

    @Test
    void whileCancellingTheButtonIsBusy() {
        SwapDashboard d = SwapDashboard.of(new Input(ID, stopped(Step.MAKER_CONFIRM), open(140), false, TERMS, true, NOW));
        assertEquals("Cancelling and refunding", d.banner().title());
        assertFalse(d.banner().cta().enabled());
        assertEquals("Working", d.banner().cta().note());
    }

    @Test
    void missingDetailsShowDashesNotInventedFigures() {
        SwapDashboard d = of(at(Step.MAKER_LOCK, null), null, true, null);
        assertEquals("—", d.summary().btcCommitted());
        assertEquals("—", d.summary().address());
        assertEquals("Not agreed yet", d.summary().xmr());
    }

    @Test
    void theAmountsAreFullPrecisionAndTheAddressIsShortened() {
        SwapDashboard d = of(at(Step.MAKER_LOCK, null), before(20), true, TERMS);
        assertEquals("0.04998760", d.summary().btcCommitted());
        assertEquals("14.816324640000", d.summary().xmr());
        assertEquals("XMR expected", d.summary().xmrLabel());
        assertEquals("4qRsDB…PHRwym", d.summary().address());
        assertEquals("Receiving address", d.summary().identifiers().get(2).label());
        assertEquals(TERMS.xmrAddress(), d.summary().identifiers().get(2).value(), "the full address stays available");
    }

    @Test
    void aSlowStepSaysSo() {
        SwapProgress.Status slow = new SwapProgress.Status(Step.LOCK_PUBLISH, null, T0.minus(Duration.ofMinutes(30)), false);
        SwapDashboard d = of(slow, null, true, null);
        assertNotNull(d.now().slow());
        assertTrue(d.now().slow().contains("longer than usual"));
        SwapProgress.Status fresh = new SwapProgress.Status(Step.LOCK_PUBLISH, null, NOW.minus(Duration.ofMinutes(1)), false);
        assertNull(of(fresh, null, true, null).now().slow());
    }

    @Test
    void theProtocolStepsStayAvailableForTheDetailsSection() {
        SwapDashboard d = of(at(Step.MAKER_LOCK, null), before(20), true, TERMS);
        assertEquals(SwapProgress.MAIN_STEPS.size(), d.protocolSteps().size());
        assertEquals(SwapProgress.MAIN_STEPS.indexOf(Step.MAKER_LOCK), d.protocolCurrent());
    }
}
