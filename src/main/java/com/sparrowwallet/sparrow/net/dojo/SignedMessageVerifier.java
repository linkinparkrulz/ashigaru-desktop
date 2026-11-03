package com.sparrowwallet.sparrow.net.dojo;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.ScriptType;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and verifies Bitcoin signed message blocks that embed a BIP47 payment code, as used by
 * the Ashigaru/Dojo node directory and the Tools &rarr; Verifier.
 *
 * <p>A block looks like:
 * <pre>
 * -----BEGIN BITCOIN SIGNED MESSAGE-----
 * { ...pairing json... }
 * BIP47: PM8T...
 * -----BEGIN BITCOIN SIGNATURE-----
 * Version: Bitcoin-qt (1.0)
 * Address: 1KRaAucnrP5L5wfuoJUuVazW8NyNEttHra
 *
 * II/cUBbl...=
 * -----END BITCOIN SIGNATURE-----
 * </pre>
 *
 * Verification recovers the signer's key from the message + signature, derives its P2PKH address,
 * and checks it equals the notification address of the embedded BIP47 payment code — i.e. the
 * claimed operator identity actually signed the message.
 */
public class SignedMessageVerifier {
    private static final Pattern PAYMENT_CODE_PATTERN = Pattern.compile("PM8T[1-9A-HJ-NP-Za-km-z]+");

    public record ParsedBlock(String message, String signature) { }

    /**
     * Verifies a signed block against the BIP47 payment code embedded within it. Optionally requires
     * the block to contain {@code mustContain} (e.g. the node's pairing url) so a valid signature
     * cannot be replayed over an unrelated payload. Returns false on any parsing/verification error.
     */
    public static boolean verify(String signedBlock, String mustContain) {
        return verify(signedBlock, mustContain, null);
    }

    /**
     * Verifies a signed block against the BIP47 payment code embedded within it, and — when
     * {@code expectedPaymentCode} is supplied — additionally binds the signed identity to the payment
     * code the directory advertises for this node. Without that binding a valid signature only proves
     * the block is <em>self-consistent</em> (the embedded code signed it); a hostile directory could
     * still display an honest operator's identity while embedding a block validly signed by an
     * attacker's own payment code. Requiring the advertised code to resolve to the same notification
     * address as the embedded/recovered one closes that identity spoof.
     *
     * <p>{@code mustContain} (e.g. the node's pairing url) ties the payload the caller will act on to
     * the signed content, so a valid signature cannot be replayed over an unrelated payload. Returns
     * false on any parsing/verification error, including an unparseable {@code expectedPaymentCode}.
     */
    public static boolean verify(String signedBlock, String mustContain, String expectedPaymentCode) {
        try {
            if(signedBlock == null || signedBlock.isBlank()) {
                return false;
            }
            ParsedBlock parsed = parse(signedBlock);
            //Only the message is covered by the signature, so the required content must appear there rather than anywhere in the block
            if(mustContain != null && !parsed.message().contains(mustContain)) {
                return false;
            }

            PaymentCode paymentCode = extractPaymentCode(parsed.message());
            if(paymentCode == null) {
                return false;
            }

            Address notificationAddress = paymentCode.getNotificationAddress();
            Address recoveredAddress = recoverAddress(parsed.message(), parsed.signature());
            if(!notificationAddress.equals(recoveredAddress)) {
                return false;
            }

            if(expectedPaymentCode != null && !expectedPaymentCode.isBlank()) {
                Address advertisedNotificationAddress = new PaymentCode(expectedPaymentCode).getNotificationAddress();
                if(!notificationAddress.equals(advertisedNotificationAddress)) {
                    return false;
                }
            }

            return true;
        } catch(Exception e) {
            return false;
        }
    }

    public static Address recoverAddress(String message, String signature) throws Exception {
        ECKey recoveredKey = ECKey.signedMessageToKey(message, signature, false);
        return ScriptType.P2PKH.getAddress(recoveredKey);
    }

    /**
     * Extracts the first BIP47 payment code (a {@code PM8T…} base58 token) from the message body,
     * tolerating both {@code BIP47: PM8T…} and {@code BIP47:\nPM8T…} layouts. Returns null if none
     * is present or it is not a valid payment code.
     */
    public static PaymentCode extractPaymentCode(String message) {
        if(message == null) {
            return null;
        }
        Matcher matcher = PAYMENT_CODE_PATTERN.matcher(message);
        while(matcher.find()) {
            try {
                return new PaymentCode(matcher.group());
            } catch(Exception e) {
                // not a valid payment code, keep scanning
            }
        }
        return null;
    }

    public static ParsedBlock parse(String block) {
        if(block == null || block.isBlank()) {
            throw new IllegalArgumentException("Signed message block is empty");
        }

        String upper = block.toUpperCase(Locale.ROOT);
        String beginMessage = "-----BEGIN BITCOIN SIGNED MESSAGE-----";
        String beginSignature = "-----BEGIN BITCOIN SIGNATURE-----";
        String endSignature = "-----END BITCOIN SIGNATURE-----";

        int messageStart = upper.indexOf(beginMessage);
        int signatureStart = upper.indexOf(beginSignature);
        if(messageStart < 0 || signatureStart < 0 || signatureStart <= messageStart) {
            throw new IllegalArgumentException("Signed block must include BEGIN BITCOIN SIGNED MESSAGE and BEGIN BITCOIN SIGNATURE markers");
        }

        int signatureEnd = upper.indexOf(endSignature, signatureStart + beginSignature.length());
        if(signatureEnd < 0) {
            throw new IllegalArgumentException("Signed block is missing END BITCOIN SIGNATURE marker");
        }

        String message = stripOuterLineBreaks(block.substring(messageStart + beginMessage.length(), signatureStart));
        String signatureSection = block.substring(signatureStart + beginSignature.length(), signatureEnd);
        String signature = "";
        for(String line : signatureSection.split("\\R")) {
            String trimmed = line.trim();
            if(trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).startsWith("version:") || trimmed.toLowerCase(Locale.ROOT).startsWith("address:")) {
                continue;
            }
            signature = trimmed;
        }

        if(message.isEmpty()) {
            throw new IllegalArgumentException("Could not extract message from signed block");
        }
        if(signature.isEmpty()) {
            throw new IllegalArgumentException("Could not extract signature from signed block");
        }

        return new ParsedBlock(message, signature);
    }

    private static String stripOuterLineBreaks(String value) {
        return value.replaceFirst("^\\R+", "").replaceFirst("\\R+$", "");
    }
}
