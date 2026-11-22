package com.sparrowwallet.sparrow.gui;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Replaces anything in a line of log that would identify a user or link them to coins: payment codes, extended keys, Bitcoin and Monero
 * addresses, 64-digit hex (transaction ids, and a swap seed if one ever reached a log), onion hosts and derivation paths. Used when a log
 * is shown or shared, and by the swap service before a line is written to the log file.
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


    private LogRedaction() {}

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
