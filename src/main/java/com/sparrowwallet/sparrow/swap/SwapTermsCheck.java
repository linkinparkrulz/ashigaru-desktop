package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Compares the maker's final terms with the offer the user chose. A maker's offer is a price, and between choosing
 * it and the final approval the maker can answer with something worse: fewer XMR for the same BTC, or a different
 * refund policy. eigenwallet 5.0.4 made its own GUI warn when the price is more than 2% worse, and its setup stop
 * when the XMR is more than 5% short or the refund policy differs. Ashigaru's engine does not use that GUI, so this
 * is the same rule applied to Ashigaru's own approval.
 *
 * <ul>
 *   <li>Within {@link #WARN_PERCENT}% short, or better than the offer: nothing to say.</li>
 *   <li>More than {@link #WARN_PERCENT}% short: a warning the user has to confirm.</li>
 *   <li>More than {@link #STOP_PERCENT}% short, a changed refund policy, or terms that cannot be checked: the swap is
 *       refused and the user is not asked, because nothing is locked until they approve.</li>
 * </ul>
 */
public final class SwapTermsCheck {
    /** More than this many percent short of the offer: warn. As in eigenwallet 5.0.4's GUI. */
    public static final int WARN_PERCENT = 2;
    /** More than this many percent short of the offer: refuse. As eigenwallet 5.0.4's swap setup does. */
    public static final int STOP_PERCENT = 5;

    private static final BigInteger PICONERO_PER_XMR = BigInteger.TEN.pow(12);

    private SwapTermsCheck() {}

    public enum Verdict { OK, WARN, STOP }

    /** Why the verdict is not OK */
    public enum Reason { NONE, LESS_XMR, REFUND_POLICY, UNREADABLE }

    /**
     * @param percentLess      how far the XMR offered is below what the chosen offer implies, in percent; 0 when it is the same or more
     * @param expectedPiconero the XMR the chosen offer implies for this much BTC, in piconero
     * @param offeredPiconero  the XMR the maker's final terms give, in piconero
     */
    public record Result(Verdict verdict, Reason reason, double percentLess, long expectedPiconero, long offeredPiconero) {
        static Result ok(double percentLess, long expected, long offered) {
            return new Result(Verdict.OK, Reason.NONE, percentLess, expected, offered);
        }

        static Result unreadable(long expected, long offered) {
            return new Result(Verdict.STOP, Reason.UNREADABLE, 0, expected, offered);
        }
    }

    /**
     * @param quote       the offer the user chose
     * @param lockSats    the BTC the lock puts into the swap
     * @param piconero    the XMR the maker's final terms give
     * @param amnestySats the anti-spam deposit the maker may keep if the swap fails
     * @param fullRefund  true when the maker signed for a full refund
     */
    public static Result check(Quote quote, long lockSats, long piconero, long amnestySats, boolean fullRefund) {
        if(quote == null || quote.priceSats() <= 0 || lockSats <= 0 || piconero <= 0) {
            return Result.unreadable(0, Math.max(piconero, 0));
        }

        //What the offer implies: the BTC divided by the price of one XMR
        BigInteger expected = BigInteger.valueOf(lockSats).multiply(PICONERO_PER_XMR).divide(BigInteger.valueOf(quote.priceSats()));
        BigInteger offered = BigInteger.valueOf(piconero);
        if(expected.signum() <= 0) {
            return Result.unreadable(0, piconero);
        }

        double percentLess = offered.compareTo(expected) >= 0 ? 0
                : new BigDecimal(expected.subtract(offered)).multiply(BigDecimal.valueOf(100)).divide(new BigDecimal(expected), 6, java.math.RoundingMode.HALF_UP).doubleValue();
        long expectedLong = expected.bitLength() < 63 ? expected.longValueExact() : Long.MAX_VALUE;

        boolean policyMatches;
        try {
            policyMatches = refundPolicyMatches(quote, lockSats, amnestySats, fullRefund);
        } catch(SwapEngineException e) {
            return Result.unreadable(expectedLong, piconero);
        }
        if(!policyMatches) {
            return new Result(Verdict.STOP, Reason.REFUND_POLICY, percentLess, expectedLong, piconero);
        }

        //Exact, in integers: more than N percent short means offered * 100 is under expected * (100 - N)
        if(shortBy(offered, expected, STOP_PERCENT)) {
            return new Result(Verdict.STOP, Reason.LESS_XMR, percentLess, expectedLong, piconero);
        }
        if(shortBy(offered, expected, WARN_PERCENT)) {
            return new Result(Verdict.WARN, Reason.LESS_XMR, percentLess, expectedLong, piconero);
        }
        return Result.ok(percentLess, expectedLong, piconero);
    }

    private static boolean shortBy(BigInteger offered, BigInteger expected, int percent) {
        return offered.multiply(BigInteger.valueOf(100)).compareTo(expected.multiply(BigInteger.valueOf(100 - percent))) < 0;
    }

    /**
     * Whether the refund policy in the final terms is the one in the offer. A full refund stays a full refund; a
     * deposit stays the same share of the lock, give or take the rounding of a few sats.
     */
    static boolean refundPolicyMatches(Quote quote, long lockSats, long amnestySats, boolean fullRefund) throws SwapEngineException {
        BigDecimal ratio = new BigDecimal(quote.refundRatioText());
        if(ratio.signum() == 0) {
            return fullRefund || amnestySats == 0;
        }
        if(fullRefund) {
            return false;
        }
        BigDecimal expectedDeposit = ratio.multiply(BigDecimal.valueOf(lockSats));
        BigDecimal tolerance = BigDecimal.valueOf(lockSats).multiply(new BigDecimal("0.0001")).max(BigDecimal.valueOf(2));
        return expectedDeposit.subtract(BigDecimal.valueOf(amnestySats)).abs().compareTo(tolerance) <= 0;
    }
}
