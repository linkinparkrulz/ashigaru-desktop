package com.sparrowwallet.sparrow.swap;

import java.time.Duration;

/**
 * The words the swap screens use about recovery, in one place so every screen tells the same story.
 *
 * <ul>
 *   <li>A running swap that fails is cancelled and refunded by Ashigaru itself.</li>
 *   <li>The refund is an estimate: up to the amount shown, minus network fees.</li>
 *   <li>Times are always "about", worked out from blocks: Bitcoin blocks come about every ten minutes.</li>
 * </ul>
 */
public final class SwapCopy {
    private SwapCopy() {}

    /** How long until the swap can be cancelled, from the mainnet cancel timelock: "about 5 hours" */
    public static String cancelAfter() {
        return aboutHours(SwapTimelock.CANCEL_BLOCKS);
    }

    /** How long a refund can wait after the cancel confirms, from the mainnet punish timelock: "about 24 hours" */
    public static String refundWindow() {
        return aboutHours(SwapTimelock.PUNISH_BLOCKS);
    }

    private static String aboutHours(int blocks) {
        long hours = Math.round(blocks * SwapTimelock.MINUTES_PER_BLOCK / 60d);
        return "about " + hours + (hours == 1 ? " hour" : " hours");
    }

    /**
     * A duration as an estimate: "about 25 min", "about 3 h 05 min", "about 4 hours". Rounded to five
     * minutes from ten minutes up, so a block count never reads as more precise than it is.
     */
    public static String about(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        if(minutes < 1) {
            return "less than a minute";
        }
        if(minutes >= 10) {
            minutes = Math.round(minutes / 5d) * 5;
        }
        if(minutes < 60) {
            return "about " + minutes + " min";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? "about " + hours + (hours == 1 ? " hour" : " hours") : String.format("about %d h %02d min", hours, rest);
    }

    /** What happens to the BTC if the maker does not complete the swap, for the approval and the dashboard */
    public static String refundTerms(String refundBtc) {
        return "If the maker does not complete the swap, Ashigaru cancels it " + cancelAfter() + " after your lock confirms and refunds up to "
                + refundBtc + " BTC to Badbank, minus network fees. Ashigaru does this by itself while it is running.";
    }

    /** The consequence a user is most likely to miss: the maker's anti-spam deposit */
    public static String depositTerms(String depositBtc, String percent) {
        return depositHeadline(depositBtc, percent) + " " + depositDetail();
    }

    /** The deposit in one sentence, for the line that stands out */
    public static String depositHeadline(String depositBtc, String percent) {
        return "If this swap fails, the maker may keep " + percent + " of your locked BTC (" + depositBtc + " BTC) as an anti-spam deposit.";
    }

    /** What the deposit is and how it comes back */
    public static String depositDetail() {
        return "It is held for a short time after a refund. The maker can withhold it; otherwise you can claim it back.";
    }

    /** What pressing Approve does */
    public static String approveEffect(String lockBtc) {
        return "Publishes the signed lock to the Bitcoin network. From then, " + lockBtc
                + " BTC is committed to this swap until it finishes or is cancelled.";
    }

    /** The same recovery story as {@link #refundTerms}, for a section already titled "If the maker does not complete" */
    public static String cancelAndRefundSteps(String refundBtc) {
        return "You can cancel " + cancelAfter() + " after the lock confirms. Ashigaru then publishes the cancel and the refund for you, "
                + "returning up to " + refundBtc + " BTC to Badbank, minus network fees.";
    }

    /** Why Ashigaru has to stay open until a swap finishes */
    public static String keepRunning() {
        return "Keep Ashigaru running until the swap finishes. " + keepRunningDetail();
    }

    /** The deadline behind keeping Ashigaru open, for a section already titled "Keep Ashigaru running" */
    public static String keepRunningDetail() {
        return "A refund must be published within " + refundWindow() + " of the cancel confirming. Ashigaru does this while it is open, "
                + "so keep it running until the swap finishes.";
    }

    // ---- The maker's final terms against the offer that was chosen (eigenwallet 5.0.4's price warning) ----

    /** An XMR amount to full precision, twelve decimals */
    public static String xmrText(long piconero) {
        return new java.math.BigDecimal(piconero).movePointLeft(12).setScale(12).toPlainString();
    }

    /** A percentage for these messages: "2.7%" */
    public static String percentText(double percent) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", percent);
    }

    public static String priceWarningTitle() {
        return "The price moved against you";
    }

    /** The warning shown before the final approval, when the maker's terms are more than {@link SwapTermsCheck#WARN_PERCENT}% short of its offer */
    public static String priceWarning(double percentLess, String offeredXmr, String expectedXmr) {
        return "This maker's final terms give you " + percentText(percentLess) + " less XMR than the offer you chose: " + offeredXmr
                + " XMR instead of " + expectedXmr + " XMR. Nothing has been locked. If you accept, you still have to approve the lock separately.";
    }

    /** The reason a swap was refused without asking, as a clause: the terms are too far from the offer, or its policy changed */
    private static String refusalClause(SwapTermsCheck.Reason reason, double percentLess) {
        return switch(reason) {
            case LESS_XMR -> "the maker's final terms give you " + percentText(percentLess) + " less XMR than the offer you chose, which is more than the "
                    + SwapTermsCheck.STOP_PERCENT + "% Ashigaru allows";
            case REFUND_POLICY -> "the maker changed its refund policy after you chose its offer";
            default -> "Ashigaru could not check the maker's final terms against the offer you chose";
        };
    }

    private static final String SAFE = " Nothing was locked, and the coin is still in Badbank.";

    /** The notice shown when Ashigaru refuses a swap without asking */
    public static String priceRefusal(SwapTermsCheck.Reason reason, double percentLess) {
        return "Swap not started: " + refusalClause(reason, percentLess) + "." + SAFE + " Choose another maker.";
    }

    /** The same reason as a sentence for the stopped swap's record, which adds on its own that nothing was locked */
    public static String priceRefusalRecord(SwapTermsCheck.Reason reason, double percentLess) {
        return sentence(refusalClause(reason, percentLess));
    }

    /** Why the swap stopped when the user turned down the worse price, as a sentence for the swap's record */
    public static String priceRejectedRecord(double percentLess) {
        return "You rejected the maker's price, which gave " + percentText(percentLess) + " less XMR than the offer you chose.";
    }

    private static String sentence(String clause) {
        return Character.toUpperCase(clause.charAt(0)) + clause.substring(1) + ".";
    }

    /** Shown beside the terms in the final review, once the user has accepted a worse price */
    public static String priceAccepted(double percentLess) {
        return "You accepted " + percentText(percentLess) + " less XMR than the offer you chose.";
    }
}
