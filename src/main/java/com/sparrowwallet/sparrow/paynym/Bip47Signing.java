package com.sparrowwallet.sparrow.paynym;

import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.net.dojo.SignedMessageVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Signs a message as a BIP47 payment code: with the private key of its notification address, which is the key the
 * Verifier (and {@link SignedMessageVerifier}) recovers and compares. Free of any screen, so that it can be
 * tested; Tools &rarr; Sign only shows what this returns.
 *
 * <p>The notification key is derived from the BIP47 key a wallet already holds in memory once it is open (that is
 * how Auth47 logins are signed), so signing asks for no password and writes nothing to disk.
 */
public final class Bip47Signing {
    private static final String BEGIN_MESSAGE = "-----BEGIN BITCOIN SIGNED MESSAGE-----";
    private static final String BEGIN_SIGNATURE = "-----BEGIN BITCOIN SIGNATURE-----";
    private static final String END_SIGNATURE = "-----END BITCOIN SIGNATURE-----";

    /** What an Auth47 login challenge starts with */
    static final String AUTH47_SCHEME = "auth47://";

    private Bip47Signing() {}

    /**
     * @param message     exactly what was signed, after the clean-up of {@link #prepare}
     * @param signature   the Base64 signature
     * @param address     the notification address that made it, which a verifier recovers from the signature
     * @param paymentCode the payment code the signature proves control of
     * @param block       the armoured block to send: message and signature together, as the Verifier reads it
     */
    public record Signed(String message, String signature, String address, String paymentCode, String block) {}

    /** True for a wallet that can sign as a payment code: a master wallet that holds its BIP47 key (a seed wallet, open) */
    public static boolean canSign(Wallet wallet) {
        return wallet != null && wallet.isMasterWallet() && wallet.hasPaymentCode();
    }

    /**
     * The private key of the wallet's notification address. Not to be cleared: it shares its key material with the BIP47
     * key the open wallet holds, and wiping it would break PayNym and Auth47 until the wallet is opened again.
     */
    public static ECKey notificationKey(Wallet wallet) {
        Wallet notificationWallet = wallet.getNotificationWallet();
        if(notificationWallet == null) {
            throw new IllegalArgumentException("This wallet has no payment code to sign with.");
        }
        WalletNode notificationNode = notificationWallet.getNode(KeyPurpose.NOTIFICATION);
        ExtendedKey extendedPrivateKey = notificationWallet.getKeystores().get(0).getBip47ExtendedPrivateKey();
        List<ChildNumber> derivation = new ArrayList<>();
        derivation.add(extendedPrivateKey.getKeyChildNumber());
        derivation.addAll(notificationNode.getDerivation());
        return extendedPrivateKey.getKey(derivation);
    }

    /** Signs a challenge as the wallet's payment code, for a login (Auth47) rather than a message block */
    public static String signChallenge(Wallet wallet, String challenge) {
        return notificationKey(wallet).signMessage(challenge, ScriptType.P2PKH);
    }

    /**
     * Cleans a message the way the Verifier reads one back: line breaks become {@code \n}, and blank lines before and
     * after it go, because a signed block cannot carry them. Whatever is signed is exactly what comes out of here.
     *
     * @param addCode when true, a line naming the payment code is added unless the message already names one, so that
     *                whoever verifies it is told the message says whose code it is
     */
    public static String prepare(String text, String paymentCode, boolean addCode) {
        String message = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').replaceFirst("^\\n+", "").replaceFirst("\\n+$", "");
        if(addCode && !message.isBlank() && SignedMessageVerifier.extractPaymentCode(message) == null) {
            message = message + "\n\nBIP47: " + paymentCode;
        }
        return message;
    }

    /**
     * Signs and builds the block. Checks the result the way a verifier would before returning it, so a signature that
     * would not verify is never handed out.
     *
     * @throws IllegalArgumentException with words for the person, when the message cannot be signed
     */
    public static Signed sign(Wallet wallet, String text, boolean addCode) {
        if(!canSign(wallet)) {
            throw new IllegalArgumentException("This wallet cannot sign as a payment code. It needs to be a wallet with a seed that is open.");
        }
        PaymentCode paymentCode = wallet.getPaymentCode();
        String message = prepare(text, paymentCode.toString(), addCode);
        if(message.isBlank()) {
            throw new IllegalArgumentException("Enter the message to sign.");
        }
        String upper = message.toUpperCase(Locale.ROOT);
        if(upper.contains(BEGIN_MESSAGE) || upper.contains(BEGIN_SIGNATURE) || upper.contains(END_SIGNATURE)) {
            throw new IllegalArgumentException("The message contains a signed-message marker (-----BEGIN or -----END text). "
                    + "That would break the signed block, so take it out.");
        }

        //An Auth47 login is a signature of its challenge, "auth47://..." and nothing else, made with this very key. Someone who asks you to "sign a message"
        //that is such a challenge would be asking you to log in as yourself on their behalf, so these are not signed here.
        if(message.toLowerCase(Locale.ROOT).contains(AUTH47_SCHEME)) {
            throw new IllegalArgumentException("The message contains an auth47:// login challenge. Signing it here would log you in to that service with this payment code, "
                    + "so it is not signed. If you mean to log in, use the login option of the service's own app or QR code.");
        }

        String signature = notificationKey(wallet).signMessage(message, ScriptType.P2PKH);
        Address address = paymentCode.getNotificationAddress();
        String block = armor(message, signature, address.toString());

        //What a verifier will do: read the block back, recover the signer and compare with the code's address
        try {
            SignedMessageVerifier.ParsedBlock parsed = SignedMessageVerifier.parse(block);
            if(!parsed.message().equals(message) || !parsed.signature().equals(signature)
                    || !address.equals(SignedMessageVerifier.recoverAddress(parsed.message(), parsed.signature()))) {
                throw new IllegalStateException("mismatch");
            }
        } catch(Exception e) {
            throw new IllegalStateException("The signature could not be checked against the payment code, so it was not used. Nothing was sent anywhere.", e);
        }
        return new Signed(message, signature, address.toString(), paymentCode.toString(), block);
    }

    /** The block layout every Bitcoin signed-message tool writes, and the Verifier reads */
    static String armor(String message, String signature, String address) {
        return BEGIN_MESSAGE + "\n" + message + "\n"
                + BEGIN_SIGNATURE + "\nVersion: Bitcoin-qt (1.0)\nAddress: " + address + "\n\n" + signature + "\n"
                + END_SIGNATURE + "\n";
    }
}
