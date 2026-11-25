package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The offers as a comparison: each one in terms of what the user would actually receive for their coin,
 * what the maker may keep if the swap is cancelled, and how it stands against the others and against the
 * market. The leading figures are the estimated XMR and the cancellation deduction; the rate and the gaps
 * to the best offer and to the market are secondary.
 *
 * <p>Nothing here decides which offers can be shown (that is {@code SwapsToolController.Offers}); it
 * orders and describes the ones that can.
 */
public final class SwapOffers {
    private SwapOffers() {}

    /** The order the table is in, stated on screen so it is never an unexplained "best first" */
    public enum Sort {
        MOST_XMR("Most XMR"),
        BEST_REFUND("Best refund"),
        MAKER("Maker");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * One offer. {@code xmr} is the estimate for the chosen coin, or null when no coin is chosen;
     * {@code versusMarket} is how far the rate is from the market in percent (negative: fewer XMR than the
     * market pays), or null while the market price is unknown; {@code behindMost} is how many XMR fewer than
     * the offer with the most.
     */
    public record Row(Quote quote, Double xmr, double xmrPerBtc, Double versusMarket, double deductionRatio, long deductionSats,
                      boolean mostXmr, boolean bestRefund, Double behindMost) {
        /** True when the maker keeps nothing if the swap is cancelled (network fees still apply) */
        public boolean noDeduction() {
            return deductionRatio == 0;
        }
    }

    /** The most XMR, and, when that offer has a deduction, the most XMR among offers with none */
    public record Summary(Row mostXmr, Row mostWithNoDeduction) {}

    /**
     * Describes and orders the shown offers for a coin of {@code swapSats} (0 when no coin is chosen).
     * Best refund is the best rate among the offers with the lowest deduction, so several makers can share
     * "no deduction" without the others looking worse.
     */
    public static List<Row> rows(List<Quote> shown, long swapSats, Double marketBtcPerXmr, Sort sort) {
        if(shown.isEmpty()) {
            return List.of();
        }
        Quote mostXmr = shown.stream().min(Comparator.comparingLong(Quote::priceSats).thenComparingDouble(Quote::refundRatio)).orElseThrow();
        double lowestDeduction = shown.stream().mapToDouble(Quote::refundRatio).min().orElse(0);
        Quote bestRefund = shown.stream().filter(quote -> quote.refundRatio() == lowestDeduction)
                .min(Comparator.comparingLong(Quote::priceSats)).orElseThrow();
        Double mostEstimate = swapSats > 0 ? swapSats / (double)mostXmr.priceSats() : null;

        List<Row> rows = new ArrayList<>();
        for(Quote quote : shown) {
            Double xmr = swapSats > 0 ? swapSats / (double)quote.priceSats() : null;
            Double versusMarket = marketBtcPerXmr == null ? null : XmrPrice.offerVersusMarket(quote.priceSats(), marketBtcPerXmr);
            double ratio = quote.refundRatio();
            long deduction = swapSats > 0 ? Math.round(swapSats * ratio) : 0;
            Double behind = xmr == null || mostEstimate == null ? null : Math.max(0, mostEstimate - xmr);
            rows.add(new Row(quote, xmr, 100_000_000d / quote.priceSats(), versusMarket, ratio, deduction, quote == mostXmr, quote == bestRefund, behind));
        }
        Comparator<Row> order = switch(sort) {
            case MOST_XMR -> Comparator.comparingLong((Row row) -> row.quote().priceSats()).thenComparingDouble(Row::deductionRatio);
            case BEST_REFUND -> Comparator.comparingDouble(Row::deductionRatio).thenComparingLong(row -> row.quote().priceSats());
            case MAKER -> Comparator.comparing((Row row) -> row.quote().provider(), String.CASE_INSENSITIVE_ORDER);
        };
        rows.sort(order);
        return List.copyOf(rows);
    }

    /** The two figures the summary card leads with, so the best offer's penalty is never shown without a way out */
    public static Summary summary(List<Row> rows) {
        Row most = rows.stream().filter(Row::mostXmr).findFirst().orElse(null);
        if(most == null || most.noDeduction()) {
            return new Summary(most, null);
        }
        Row clean = rows.stream().filter(Row::noDeduction).min(Comparator.comparingLong(row -> row.quote().priceSats())).orElse(null);
        return new Summary(most, clean);
    }

    /** A percentage with a true minus sign and one decimal: "−0.5%", "+0.3%" */
    public static String percent(double value) {
        return (value < 0 ? "−" : "+") + String.format(Locale.ROOT, "%.1f%%", Math.abs(value));
    }

    /**
     * How an offer's rate compares with the market, in words. A bare sign is read both ways: "−3.4%" looked like a
     * discount to some and a penalty to others. Negative here means fewer XMR than the market pays.
     */
    public static String versusMarketText(double value) {
        double rounded = Math.round(Math.abs(value) * 10) / 10.0;
        if(rounded == 0) {
            return "at market";
        }
        return String.format(Locale.ROOT, "%.1f%%", rounded) + (value < 0 ? " worse than market" : " better than market");
    }

    /** How many XMR fewer than the offer with the most, in words */
    public static String behindMostText(double xmr) {
        return String.format(Locale.ROOT, "%.4f", xmr) + " XMR less than the most";
    }

    /**
     * The share of the locked BTC that comes back if the swap is cancelled, as eigenwallet's own screen shows it: "98% refund".
     * A maker that keeps nothing gives "100% refund". Null when the terms could not be read (a ratio of 1 or more, or none),
     * so a figure that is not known is never shown as "0% refund".
     */
    public static String refundText(double depositRatio) {
        if(Double.isNaN(depositRatio) || depositRatio < 0 || depositRatio >= 1) {
            return null;
        }
        double refund = (1 - depositRatio) * 100;
        double rounded = Math.round(refund * 10) / 10.0;
        return (rounded == Math.rint(rounded) ? String.format(Locale.ROOT, "%d", (long)rounded) : String.format(Locale.ROOT, "%.1f", rounded)) + "% refund";
    }
}
