package com.sparrowwallet.sparrow.swap;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What makers have told this session about swaps they will not take. A maker advertises one range of sizes
 * but can still turn a swap down for reasons its offer does not show: a larger buy limit than it lists, an
 * anti-spam deposit that is too small for its minimum, or a fault on its side. The engine retries such a
 * refusal for about two minutes and then gives up, and the maker would be on the offer list again for the
 * same coin. Reading the refusal lets the offers leave that maker out for the coin that was refused, and
 * lets the stopped swap say plainly what happened.
 *
 * <p>Nothing here is stored: a refusal is a fact about now, and makers change their settings.
 */
public final class MakerRefusals {
    /** How long a maker with a problem on its own side is left out. Its limits are kept until the app closes. */
    static final Duration SELLER_PROBLEM_FOR = Duration.ofMinutes(15);

    private static final Pattern MIN_BUY = Pattern.compile("minimum configured buy limit is ([0-9.]+) BTC");
    private static final Pattern DEPOSIT = Pattern.compile("Anti-spam deposit ([0-9.]+) BTC is too small; minimum required is ([0-9.]+) BTC");
    private static final String SELLER_PROBLEM = "Seller encountered a problem";

    /** What a refusal teaches. Zero means nothing was learned about that limit. */
    public record Refusal(Kind kind, long minSats, long coinSats, Instant at) {}

    public enum Kind {
        /** The maker buys no less than {@code minSats} */
        MIN_BUY,
        /** The anti-spam deposit on this coin was under the maker's minimum; the coin was {@code coinSats}, so larger ones may work */
        DEPOSIT_TOO_SMALL,
        /** The maker failed on its own side and may be fine later */
        SELLER_PROBLEM
    }

    private final Map<String, Refusal> byMaker = new ConcurrentHashMap<>();

    /** Reads the engine's error. Empty when it is not a refusal this class knows. */
    public static Optional<Refusal> parse(String error, long coinSats, Instant now) {
        if(error == null) {
            return Optional.empty();
        }
        Matcher minBuy = MIN_BUY.matcher(error);
        if(minBuy.find()) {
            return Optional.of(new Refusal(Kind.MIN_BUY, sats(minBuy.group(1)), coinSats, now));
        }
        Matcher deposit = DEPOSIT.matcher(error);
        if(deposit.find()) {
            long offered = sats(deposit.group(1));
            long required = sats(deposit.group(2));
            //The deposit is a share of the coin, so the smallest coin it works for follows from the two figures
            long smallest = offered <= 0 ? 0 : (long)Math.ceil((double)coinSats * required / offered);
            return Optional.of(new Refusal(Kind.DEPOSIT_TOO_SMALL, smallest, coinSats, now));
        }
        if(error.contains(SELLER_PROBLEM)) {
            return Optional.of(new Refusal(Kind.SELLER_PROBLEM, 0, coinSats, now));
        }
        return Optional.empty();
    }

    /** A sentence for the person: what the maker said, and what to do about it. Null when the error is not a refusal. */
    public static String explain(String error) {
        Optional<Refusal> refusal = parse(error, 0, Instant.EPOCH);
        if(refusal.isEmpty()) {
            return null;
        }
        Refusal r = refusal.get();
        return switch(r.kind()) {
            case MIN_BUY -> "This maker only swaps " + btc(r.minSats()) + " BTC or more, and this coin is smaller. "
                    + "Choose a maker that takes smaller swaps.";
            case DEPOSIT_TOO_SMALL -> "This coin is too small for this maker. It keeps a minimum anti-spam deposit in case a swap is cancelled, "
                    + "and the share it asks for would come to less than that minimum on a coin this size. "
                    + "Choose another maker.";
            case SELLER_PROBLEM -> "The maker reported a problem on its own side and would not start the swap. "
                    + "Try another maker; this one is left out of the offers for a while.";
        };
    }

    /** Remembers a refusal from this maker */
    public void record(String peerId, Refusal refusal) {
        if(peerId == null) {
            return;
        }
        byMaker.merge(peerId, refusal, (old, fresh) -> {
            //A limit only ever tightens what is known: keep the largest minimum for a maker that has taught two
            if(old.kind() == fresh.kind() && old.minSats() > fresh.minSats()) {
                return new Refusal(fresh.kind(), old.minSats(), fresh.coinSats(), fresh.at());
            }
            return fresh;
        });
    }

    /**
     * True when this maker has refused something that rules out a swap of {@code sats} now. A maker that turned
     * down a coin of one size has said nothing about a larger one beyond what it taught.
     */
    public boolean rulesOut(String peerId, long sats, Instant now) {
        Refusal refusal = peerId == null ? null : byMaker.get(peerId);
        if(refusal == null) {
            return false;
        }
        return switch(refusal.kind()) {
            case MIN_BUY, DEPOSIT_TOO_SMALL -> sats < refusal.minSats() || (refusal.minSats() <= 0 && sats <= refusal.coinSats());
            case SELLER_PROBLEM -> now.isBefore(refusal.at().plus(SELLER_PROBLEM_FOR));
        };
    }

    /** Forgets everything. For tests, and for a new Tor identity if it is ever wanted. */
    public void clear() {
        byMaker.clear();
    }

    private static long sats(String btc) {
        try {
            return new BigDecimal(btc).movePointRight(8).longValue();
        } catch(NumberFormatException e) {
            return 0;
        }
    }

    private static String btc(long sats) {
        return new BigDecimal(sats).movePointLeft(8).stripTrailingZeros().toPlainString();
    }
}
