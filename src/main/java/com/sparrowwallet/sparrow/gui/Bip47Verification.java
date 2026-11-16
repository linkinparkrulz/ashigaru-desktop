package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.sparrow.net.dojo.SignedMessageVerifier;

import java.util.Locale;

/**
 * What the Verifier tab does when asked to verify, apart from the screen: whether a signed message was
 * signed by the notification address of a BIP47 payment code. Kept free of any control so that it can be
 * tested, and so the screen only has to show a {@link Result}.
 */
public final class Bip47Verification {
    private Bip47Verification() {}

    /** The input a problem belongs to, so the screen can put the cursor there */
    public enum Field { PAYMENT_CODE, MESSAGE, SIGNATURE }

    public enum Outcome {
        /** The signature was made by the payment code's notification address */
        VERIFIED,
        /** The signature is well formed but was made by some other address */
        MISMATCH,
        /** Something could not be checked: an input is missing or unreadable */
        PROBLEM
    }

    /**
     * @param problem            for {@link Outcome#PROBLEM}: what is wrong, in words for the person
     * @param field              for {@link Outcome#PROBLEM}: which input
     * @param notificationAddress the address derived from the payment code, when it could be
     * @param signerAddress      the address recovered from the signature, when it could be
     * @param namedCode          whether the message names this payment code: null when it names none
     */
    public record Result(Outcome outcome, String problem, Field field, String notificationAddress, String signerAddress, Boolean namedCode) {
        static Result problem(Field field, String problem) {
            return new Result(Outcome.PROBLEM, problem, field, null, null, null);
        }
    }

    /** A signed block read as the message and signature it holds */
    public record Block(String message, String signature) {}

    /**
     * Reads a pasted signed block. Null when the text is not one; {@link #blockProblem} says why, for text that
     * at least starts like one.
     */
    public static Block parseBlock(String text) {
        try {
            SignedMessageVerifier.ParsedBlock parsed = SignedMessageVerifier.parse(text);
            return new Block(parsed.message(), parsed.signature());
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** True when the text looks like it is meant as a signed block, whether or not it is a whole one */
    public static boolean looksLikeBlock(String text) {
        return text != null && text.toUpperCase(Locale.ROOT).contains("BEGIN BITCOIN SIGNED MESSAGE");
    }

    /** Why a block that looks like one cannot be read, from the parser's own wording; null if it can */
    public static String blockProblem(String text) {
        try {
            SignedMessageVerifier.parse(text);
            return null;
        } catch(RuntimeException e) {
            return e.getMessage();
        }
    }

    public static Result verify(String paymentCodeText, String message, String signature) {
        String codeText = paymentCodeText == null ? "" : paymentCodeText.trim();
        if(codeText.isEmpty()) {
            return Result.problem(Field.PAYMENT_CODE, "Enter the BIP47 payment code the message should have been signed with.");
        }
        if(message == null || message.isEmpty()) {
            return Result.problem(Field.MESSAGE, "Enter the message that was signed, or paste the whole signed block above.");
        }
        String signatureText = signature == null ? "" : signature.trim();
        if(signatureText.isEmpty()) {
            return Result.problem(Field.SIGNATURE, "Enter the signature, or paste the whole signed block above.");
        }

        PaymentCode paymentCode;
        Address notificationAddress;
        try {
            paymentCode = new PaymentCode(codeText);
            notificationAddress = paymentCode.getNotificationAddress();
        } catch(Exception e) {
            return Result.problem(Field.PAYMENT_CODE, "That is not a valid BIP47 payment code. A payment code starts with PM8T.");
        }

        Address signerAddress;
        try {
            signerAddress = SignedMessageVerifier.recoverAddress(message, signatureText);
        } catch(Exception e) {
            return Result.problem(Field.SIGNATURE, "That signature could not be read. It should be the Base64 text from the signed block, "
                    + "on one line, with nothing added.");
        }

        //The message's own claim about whose code it is. Never used to fill the field in: a forged message could pick it.
        PaymentCode named = SignedMessageVerifier.extractPaymentCode(message);
        Boolean namedCode = named == null ? null : named.toString().equals(paymentCode.toString());

        Outcome outcome = notificationAddress.equals(signerAddress) ? Outcome.VERIFIED : Outcome.MISMATCH;
        return new Result(outcome, null, null, notificationAddress.toString(), signerAddress.toString(), namedCode);
    }
}
