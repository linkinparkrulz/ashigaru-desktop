package com.sparrowwallet.sparrow.swap;

import java.time.Duration;
import java.time.Instant;

/**
 * The final review of a swap, shown in the Swaps screen: the terms the maker agreed and a request to
 * approve and publish the BTC lock. (The wallet password and the signing happen before this, when the
 * swap is started, so the clock here is only for reading and deciding.) The screen only draws it and
 * reports the decision; the host decides what a decision means.
 *
 * @param coinSats       the Badbank coin the lock spends, in sats
 * @param lockSats       what is locked in the swap, in sats
 * @param feeSats        the network fee, taken from the coin, in sats
 * @param piconero       the XMR the user receives, in piconero
 * @param amnestySats    the anti-spam deposit the maker may keep if the swap fails, in sats
 * @param fullRefund     true when the maker has signed for a full refund, so no deposit applies
 * @param xmrAddress     where the XMR goes
 * @param maker          a short label for the maker
 * @param expiresAt      when an unanswered approval is rejected, which is a real deadline
 */
public record SwapReview(long coinSats, long lockSats, long feeSats, long piconero, long amnestySats,
                         boolean fullRefund, String xmrAddress, String maker, Instant expiresAt, Decision decision) {

    /** What the user chose; the host answers the engine accordingly. Either is safe to call more than once. */
    public interface Decision {
        /** Approve and publish the lock */
        void confirm();

        /** Reject the swap: nothing is published */
        void decline();
    }

    public static SwapReview commit(long coinSats, long lockSats, long feeSats, long piconero, long amnestySats, boolean fullRefund,
                                    String xmrAddress, String maker, Instant expiresAt, Decision decision) {
        return new SwapReview(coinSats, lockSats, feeSats, piconero, amnestySats, fullRefund, xmrAddress, maker, expiresAt, decision);
    }

    /** True when the maker may keep part of the BTC if the swap fails */
    public boolean hasDeposit() {
        return !fullRefund && amnestySats > 0;
    }

    /** The most that can come back to Badbank if the swap fails, in sats: the lock less any deposit */
    public long refundSats() {
        return hasDeposit() ? lockSats - amnestySats : lockSats;
    }

    public String lockBtc() {
        return BadbankSwapHost.btc(lockSats);
    }

    public String feeBtc() {
        return BadbankSwapHost.btc(feeSats);
    }

    public String coinBtc() {
        return BadbankSwapHost.btc(coinSats);
    }

    public String refundBtc() {
        return BadbankSwapHost.btc(refundSats());
    }

    public String depositBtc() {
        return BadbankSwapHost.btc(amnestySats);
    }

    public String depositPercent() {
        return BadbankSwapHost.percent(amnestySats, lockSats);
    }

    /** The XMR amount to full precision, twelve decimals */
    public String xmr() {
        return BadbankSwapHost.xmr(piconero);
    }

    /** The XMR price of one BTC at these terms */
    public double xmrPerBtc() {
        return lockSats == 0 ? 0 : (piconero / 1e12) / (lockSats / 1e8);
    }

    /** Time left as m:ss, never below 0:00 */
    public static String countdown(Duration remaining) {
        long seconds = Math.max(0, remaining.getSeconds());
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /** A maker's peer id as a short label: the start and the end, which is enough to tell makers apart */
    public static String shortId(String id) {
        if(id == null || id.length() <= 14) {
            return id == null ? "" : id;
        }
        return id.substring(0, 8) + "…" + id.substring(id.length() - 4);
    }
}
