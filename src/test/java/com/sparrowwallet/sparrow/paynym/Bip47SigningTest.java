package com.sparrowwallet.sparrow.paynym;

import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.gui.Bip47Verification;
import com.sparrowwallet.sparrow.gui.Bip47Verification.Outcome;
import com.sparrowwallet.sparrow.net.dojo.SignedMessageVerifier;
import com.sparrowwallet.sparrow.paynym.Bip47Signing.Signed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Signing as a payment code, checked by the same code that Tools > Verifier runs */
class Bip47SigningTest {
    private static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static final String OTHER_CODE = "PM8TJS2JxQ5ztXUpBBRnpTbcUXbUHy2T1abfrb3KkAAtMEGNbey4oumH7Hc578WgQJhPjBxteQ5GHHToTYHE3A1w6p7tU6KSoFmWBVbFGjKPisZDbP97";

    private static Wallet wallet() throws Exception {
        Wallet wallet = new Wallet("signer");
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2WPKH);
        wallet.getKeystores().add(Keystore.fromSeed(new DeterministicSeed(MNEMONIC, "", 0, DeterministicSeed.Type.BIP39),
                ScriptType.P2WPKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
        return wallet;
    }

    @Test
    void aSeedWalletCanSignAndAWatchOnlyOneCannot() throws Exception {
        Wallet wallet = wallet();
        assertTrue(Bip47Signing.canSign(wallet));
        assertFalse(Bip47Signing.canSign(null));

        wallet.getKeystores().get(0).setBip47ExtendedPrivateKey(null);
        assertFalse(Bip47Signing.canSign(wallet), "no BIP47 key held, no payment code");
        assertThrows(IllegalArgumentException.class, () -> Bip47Signing.sign(wallet, "hello", true));
    }

    @Test
    void whatIsSignedVerifiesAsThePaymentCode() throws Exception {
        Wallet wallet = wallet();
        String code = wallet.getPaymentCode().toString();
        Signed signed = Bip47Signing.sign(wallet, "I control this payment code.", true);

        Bip47Verification.Result result = Bip47Verification.verify(code, signed.message(), signed.signature());
        assertEquals(Outcome.VERIFIED, result.outcome());
        assertEquals(wallet.getPaymentCode().getNotificationAddress().toString(), signed.address());
        assertEquals(signed.address(), result.signerAddress());
        assertEquals(code, signed.paymentCode());
    }

    @Test
    void theBlockIsReadBackByTheVerifierExactly() throws Exception {
        Wallet wallet = wallet();
        Signed signed = Bip47Signing.sign(wallet, "line one\n\nline three", true);

        SignedMessageVerifier.ParsedBlock parsed = SignedMessageVerifier.parse(signed.block());
        assertEquals(signed.message(), parsed.message());
        assertEquals(signed.signature(), parsed.signature());
        assertEquals(Outcome.VERIFIED, Bip47Verification.verify(signed.paymentCode(), parsed.message(), parsed.signature()).outcome());
        //The same check the app does on a whole block
        assertTrue(SignedMessageVerifier.verify(signed.block(), null));
    }

    @Test
    void theMessageNamesTheCodeSoTheVerifierSaysSo() throws Exception {
        Wallet wallet = wallet();
        Signed named = Bip47Signing.sign(wallet, "Challenge 4711", true);
        assertTrue(named.message().endsWith("\n\nBIP47: " + named.paymentCode()));
        assertEquals(Boolean.TRUE, Bip47Verification.verify(named.paymentCode(), named.message(), named.signature()).namedCode());

        Signed plain = Bip47Signing.sign(wallet, "Challenge 4711", false);
        assertEquals("Challenge 4711", plain.message());
        assertNull(Bip47Verification.verify(plain.paymentCode(), plain.message(), plain.signature()).namedCode());
    }

    @Test
    void aMessageThatAlreadyNamesACodeIsNotGivenASecondLine() throws Exception {
        Wallet wallet = wallet();
        String text = "https://example.org/\n\nBIP47: " + wallet.getPaymentCode();
        assertEquals(text, Bip47Signing.sign(wallet, text, true).message());
    }

    @Test
    void anotherCodeDoesNotVerifyIt() throws Exception {
        Signed signed = Bip47Signing.sign(wallet(), "hello", true);
        assertEquals(Outcome.MISMATCH, Bip47Verification.verify(OTHER_CODE, signed.message(), signed.signature()).outcome());
    }

    @Test
    void aChangedMessageOrSignatureDoesNotVerify() throws Exception {
        Signed signed = Bip47Signing.sign(wallet(), "pay 1 BTC", false);
        assertEquals(Outcome.MISMATCH, Bip47Verification.verify(signed.paymentCode(), "pay 2 BTC", signed.signature()).outcome());
        String other = new ECKey().signMessage(signed.message(), ScriptType.P2PKH);
        assertEquals(Outcome.MISMATCH, Bip47Verification.verify(signed.paymentCode(), signed.message(), other).outcome());
    }

    @Test
    void signingLeavesTheOpenWalletUsable() throws Exception {
        //The notification key shares its material with the key the wallet keeps: signing must never wipe it
        Wallet wallet = wallet();
        for(int i = 0; i < 3; i++) {
            Signed signed = Bip47Signing.sign(wallet, "again " + i, true);
            assertEquals(Outcome.VERIFIED, Bip47Verification.verify(signed.paymentCode(), signed.message(), signed.signature()).outcome());
        }
        assertTrue(Bip47Signing.canSign(wallet));
        assertNotNull(Bip47Signing.signChallenge(wallet, "still works"));
    }

    @Test
    void theSameWalletAlwaysMakesTheSameSignature() throws Exception {
        //Deterministic signing (RFC 6979): useful to a person comparing two copies of a wallet
        assertEquals(Bip47Signing.sign(wallet(), "same", true).signature(), Bip47Signing.sign(wallet(), "same", true).signature());
    }

    @Test
    void theMessageIsCleanedTheWayTheVerifierReadsItBack() throws Exception {
        assertEquals("a\nb", Bip47Signing.prepare("\r\n\r\na\r\nb\r\n\r\n", "PM8T", false));
        assertEquals("a\n\nb", Bip47Signing.prepare("a\n\nb\n", "PM8T", false), "blank lines inside stay");
        assertEquals("", Bip47Signing.prepare("  \n\n", "PM8T", true).trim());
        assertEquals("", Bip47Signing.prepare(null, "PM8T", true));

        //A message with Windows line breaks and blank lines around it still verifies after the round trip
        Wallet wallet = wallet();
        Signed signed = Bip47Signing.sign(wallet, "\r\n\r\nhello\r\nworld\r\n\r\n", true);
        assertEquals("hello\nworld\n\nBIP47: " + signed.paymentCode(), signed.message());
        assertEquals(Outcome.VERIFIED, Bip47Verification.verify(signed.paymentCode(), SignedMessageVerifier.parse(signed.block()).message(), signed.signature()).outcome());
    }

    @Test
    void unicodeAndLongMessagesRoundTrip() throws Exception {
        Wallet wallet = wallet();
        String text = "Ashigaru — アシガル ☃ " + "x".repeat(5000);
        Signed signed = Bip47Signing.sign(wallet, text, true);
        assertTrue(SignedMessageVerifier.verify(signed.block(), null));
    }

    @Test
    void anEmptyMessageIsNotSigned() throws Exception {
        Wallet wallet = wallet();
        assertThrows(IllegalArgumentException.class, () -> Bip47Signing.sign(wallet, "", true));
        assertThrows(IllegalArgumentException.class, () -> Bip47Signing.sign(wallet, " \n\n ", false).message().isEmpty());
    }

    @Test
    void signedMessageMarkersInTheTextAreRefused() throws Exception {
        Wallet wallet = wallet();
        for(String text : new String[] {"x\n-----BEGIN BITCOIN SIGNATURE-----\ny", "-----BEGIN BITCOIN SIGNED MESSAGE-----\nhello",
                "x\n-----end bitcoin signature-----"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Bip47Signing.sign(wallet, text, true));
            assertTrue(e.getMessage().contains("marker"), e.getMessage());
        }
    }

    @Test
    void aLoginChallengeIsNotSignedAsAMessage() throws Exception {
        Wallet wallet = wallet();
        for(String text : new String[] {"auth47://abc?r=https://example.org", "Please sign: AUTH47://abc?r=https://example.org\nthanks", "x auth47://abc"}) {
            for(boolean addCode : new boolean[] {true, false}) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Bip47Signing.sign(wallet, text, addCode));
                assertTrue(e.getMessage().contains("auth47://"), e.getMessage());
            }
        }
        //The login itself still works, as it does not go through message signing
        assertNotNull(Bip47Signing.signChallenge(wallet, "auth47://abc?r=https://example.org"));
        //And an ordinary message that mentions neither is signed as before
        assertNotNull(Bip47Signing.sign(wallet, "I control this payment code.", true));
    }

    @Test
    void theLoginSignatureIsTheSameKeyAsTheMessageSignature() throws Exception {
        //Auth47 signs with this key too; what it signs verifies against the same notification address
        Wallet wallet = wallet();
        String signature = Bip47Signing.signChallenge(wallet, "auth47://abc?r=https://example.org");
        assertEquals(wallet.getPaymentCode().getNotificationAddress(), SignedMessageVerifier.recoverAddress("auth47://abc?r=https://example.org", signature));
    }
}
