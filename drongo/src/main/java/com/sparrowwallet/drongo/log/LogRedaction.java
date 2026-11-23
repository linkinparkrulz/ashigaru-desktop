package com.sparrowwallet.drongo.log;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Replaces anything in a line of log that would identify a user or link them to coins: payment codes, extended keys, Bitcoin and Monero
 * addresses, 64-digit hex (transaction ids, and a swap seed if one ever reached a log), onion hosts and derivation paths. Used when a log
 * is shown or shared, and by {@link RedactingMessageConverter} and {@link RedactingThrowableConverter} on every line written to the log file.
 *
 * <p>Amounts are only redacted in the swap service's lines ({@link #redactAmounts}): an exact swap amount finds the lock on chain, while
 * the rest of the app's lines carry no amounts worth hiding and many numbers that are not amounts.
 */
public final class LogRedaction {
    private record Redaction(Pattern pattern, String replacement) {}

    //Ordered longest-match-first so an xpub is not partially consumed by the base58 address pattern.
    private static final List<Redaction> REDACTIONS = List.of(
            new Redaction(Pattern.compile("\\bPM8T[1-9A-HJ-NP-Za-km-z]{100,}"), "[paymentcode]"),
            new Redaction(Pattern.compile("\\b[xyztuvXYZTUV](?:pub|prv)[1-9A-HJ-NP-Za-km-z]{100,}"), "[xpub]"),
            //Monero: 95 characters (106 with a payment id) starting with 4 (main address) or 8 (subaddress). Before the base58 pattern below,
            //which would take its first characters. A swap ties this address to an exact BTC amount that can be found on chain.
            new Redaction(Pattern.compile("\\b[48][1-9A-HJ-NP-Za-km-z]{94}(?:[1-9A-HJ-NP-Za-km-z]{11})?\\b"), "[xmr address]"),
            new Redaction(Pattern.compile("\\b(?:bc|tb|bcrt)1[023456789acdefghjklmnpqrstuvwxyz]{20,}\\b"), "[address]"),
            new Redaction(Pattern.compile("\\b[13][1-9A-HJ-NP-Za-km-z]{25,34}\\b"), "[address]"),
            new Redaction(Pattern.compile("\\b[0-9a-fA-F]{64}\\b"), "[txid]"),
            new Redaction(Pattern.compile("\\b[a-z2-7]{16,56}\\.onion\\b"), "[onion]"),
            new Redaction(Pattern.compile("\\bm(?:/\\d+['h]?)+"), "[path]")
    );


    //Amounts as the swap engine and the swap service write them: "btc_lock_amount":373999, btc_network_fee: 1200, amount=..., 0.00373999 BTC, 373999 sats
    private static final List<Redaction> AMOUNTS = List.of(
            new Redaction(Pattern.compile("(\"?\\b[A-Za-z_]*(?:amount|fee|sats|piconero|balance|value|price)[A-Za-z_]*\"?\\s*[:=]\\s*)(\"?)-?\\d+(?:[_.]\\d+)*\\2",
                    Pattern.CASE_INSENSITIVE), "$1[amount]"),
            new Redaction(Pattern.compile("\\bAmount\\(\\s*-?\\d+(?:[_.]\\d+)*\\s*\\)"), "[amount]"),
            new Redaction(Pattern.compile("(?<![\\w.])-?\\d[\\d_,]*(?:\\.\\d+)?\\s*(?:BTC|XMR|sats?|piconero)\\b(?!/)", Pattern.CASE_INSENSITIVE), "[amount]")
    );

    private LogRedaction() {}

    /** Amounts as well as everything {@link #redact} covers: for lines about swaps */
    public static String redactAmounts(String text) {
        if(text == null) {
            return null;
        }
        String redacted = redact(text);
        for(Redaction redaction : AMOUNTS) {
            redacted = redaction.pattern().matcher(redacted).replaceAll(redaction.replacement());
        }
        return redacted;
    }

    /** The text with every such value replaced by a placeholder. Wallet names, which have no shape, are not covered here. */
    public static String redact(String text) {
        if(text == null) {
            return null;
        }
        String redacted = text;
        for(Redaction redaction : REDACTIONS) {
            redacted = redaction.pattern().matcher(redacted).replaceAll(redaction.replacement());
        }
        return redacted;
    }
}
