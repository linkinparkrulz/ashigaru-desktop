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

    /** How long until the swap can be cancelled, from the mainnet cancel timelock: "about 4 hours" */
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
        return "If this swap fails, the maker may keep " + percent + " of your locked BTC (" + depositBtc + " BTC) as an anti-spam deposit. "
                + "It is held for a short time after a refund. The maker can withhold it; otherwise you can claim it back.";
    }

    /** Why Ashigaru has to stay open until a swap finishes */
    public static String keepRunning() {
        return "Keep Ashigaru running until the swap finishes. A refund must be published within " + refundWindow()
                + " of the cancel confirming, and Ashigaru does this while it is open.";
    }
}
