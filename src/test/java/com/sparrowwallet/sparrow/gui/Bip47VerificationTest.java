package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.sparrow.gui.Bip47Verification.Field;
import com.sparrowwallet.sparrow.gui.Bip47Verification.Outcome;
import com.sparrowwallet.sparrow.gui.Bip47Verification.Result;
import com.sparrowwallet.sparrow.net.update.ReleaseTrust;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Bip47VerificationTest {
    private static final String CODE = ReleaseTrust.RELEASE_SIGNING_PAYMENT_CODE;
    //The release code's notification address, as published in the README and ReleaseTrust
    private static final String RELEASE_ADDRESS = "1K8CDoBYWBuaeAhejLAk5hiACAgbbPnDCJ";
    //A second, valid payment code (BIP47's own test vector)
    private static final String OTHER_CODE = "PM8TJS2JxQ5ztXUpBBRnpTbcUXbUHy2T1abfrb3KkAAtMEGNbey4oumH7Hc578WgQJhPjBxteQ5GHHToTYHE3A1w6p7tU6KSoFmWBVbFGjKPisZDbP97";

    //A real message signed with the release code's key, recovered and checked independently of this code
    private static final String MESSAGE = "https://paymentcode.io/\n\nBIP47: " + CODE;
    private static final String SIGNATURE = "H5mR/qaxInXzuSuzlaC0EO9/pqtHIygLRKOEVkgefTEuAJy7kyP1d1wkXDgafJPoLRjsKgt0PRuZZF46Ha/gm2o=";
    private static final String BLOCK = "-----BEGIN BITCOIN SIGNED MESSAGE-----\n" + MESSAGE + "\n"
            + "-----BEGIN BITCOIN SIGNATURE-----\nVersion: Bitcoin-qt (1.0)\nAddress: " + RELEASE_ADDRESS + "\n\n" + SIGNATURE + "\n"
            + "-----END BITCOIN SIGNATURE-----\n";

    @Test
    void aMessageSignedByThePaymentCodeVerifies() {
        Result result = Bip47Verification.verify(CODE, MESSAGE, SIGNATURE);
        assertEquals(Outcome.VERIFIED, result.outcome());
        assertEquals(RELEASE_ADDRESS, result.notificationAddress());
        assertEquals(RELEASE_ADDRESS, result.signerAddress());
        assertEquals(Boolean.TRUE, result.namedCode());
    }

    @Test
    void spacesAroundThePaymentCodeAndSignatureDoNotMatter() {
        assertEquals(Outcome.VERIFIED, Bip47Verification.verify("  " + CODE + "\n", MESSAGE, "  " + SIGNATURE + "\n").outcome());
    }

    @Test
    void theMessageMustMatchWhatWasSignedExactly() {
        //The signature covers the exact bytes, so even one trailing line break is a different message
        assertEquals(Outcome.MISMATCH, Bip47Verification.verify(CODE, MESSAGE + "\n", SIGNATURE).outcome());
        assertEquals(Outcome.MISMATCH, Bip47Verification.verify(CODE, MESSAGE.replace("\n\n", "\n"), SIGNATURE).outcome());
    }

    @Test
    void aSignatureByAnotherKeyDoesNotMatch() {
        String signature = new ECKey().signMessage(MESSAGE, ScriptType.P2PKH);
        Result result = Bip47Verification.verify(CODE, MESSAGE, signature);
        assertEquals(Outcome.MISMATCH, result.outcome());
        assertEquals(RELEASE_ADDRESS, result.notificationAddress());
        assertNotEquals(RELEASE_ADDRESS, result.signerAddress());
    }

    @Test
    void aGoodSignatureIsNotEnoughForAnotherPaymentCode() {
        Result result = Bip47Verification.verify(OTHER_CODE, MESSAGE, SIGNATURE);
        assertEquals(Outcome.MISMATCH, result.outcome());
        //The message names the release code, not the one being checked
        assertEquals(Boolean.FALSE, result.namedCode());
    }

    @Test
    void aMessageThatNamesNoCodeSaysNothingAboutIt() {
        String message = "Hello";
        String signature = new ECKey().signMessage(message, ScriptType.P2PKH);
        assertNull(Bip47Verification.verify(CODE, message, signature).namedCode());
    }

    @Test
    void aMissingInputIsNamedSoTheCursorCanGoThere() {
        assertProblem(Field.PAYMENT_CODE, Bip47Verification.verify("", MESSAGE, SIGNATURE));
        assertProblem(Field.PAYMENT_CODE, Bip47Verification.verify(null, MESSAGE, SIGNATURE));
        assertProblem(Field.MESSAGE, Bip47Verification.verify(CODE, "", SIGNATURE));
        assertProblem(Field.MESSAGE, Bip47Verification.verify(CODE, null, SIGNATURE));
        assertProblem(Field.SIGNATURE, Bip47Verification.verify(CODE, MESSAGE, "   "));
        assertProblem(Field.SIGNATURE, Bip47Verification.verify(CODE, MESSAGE, null));
    }

    @Test
    void anUnreadableInputIsSaidInPlainWords() {
        Result code = Bip47Verification.verify("PM8Tnotapaymentcode", MESSAGE, SIGNATURE);
        assertProblem(Field.PAYMENT_CODE, code);
        assertTrue(code.problem().contains("not a valid BIP47 payment code"));

        Result signature = Bip47Verification.verify(CODE, MESSAGE, "this is not a signature");
        assertProblem(Field.SIGNATURE, signature);
        assertTrue(signature.problem().contains("could not be read"));
        //No Java exception wording reaches the screen
        assertFalse(signature.problem().contains("Exception"));
    }

    @Test
    void aSignedBlockIsReadAsItsMessageAndSignature() {
        Bip47Verification.Block block = Bip47Verification.parseBlock(BLOCK);
        assertNotNull(block);
        //The Version and Address lines are not the signature, and the message keeps its inner line breaks
        assertEquals(MESSAGE, block.message());
        assertEquals(SIGNATURE, block.signature());
        assertEquals(Outcome.VERIFIED, Bip47Verification.verify(CODE, block.message(), block.signature()).outcome());
    }

    @Test
    void aBlockWithBlankLinesAroundItIsStillRead() {
        Bip47Verification.Block block = Bip47Verification.parseBlock("\n\n  " + BLOCK + "\n\n");
        assertNotNull(block);
        assertEquals(MESSAGE, block.message());
    }

    @Test
    void textThatIsNotABlockIsNotRead() {
        assertNull(Bip47Verification.parseBlock("just some words"));
        assertNull(Bip47Verification.parseBlock(""));
        assertNull(Bip47Verification.parseBlock(null));
        assertFalse(Bip47Verification.looksLikeBlock("just some words"));
        assertFalse(Bip47Verification.looksLikeBlock(null));
    }

    @Test
    void aBlockThatIsStillBeingPastedSaysWhatIsMissing() {
        String partial = "-----BEGIN BITCOIN SIGNED MESSAGE-----\n" + MESSAGE + "\n";
        assertNull(Bip47Verification.parseBlock(partial));
        assertTrue(Bip47Verification.looksLikeBlock(partial));
        assertTrue(Bip47Verification.blockProblem(partial).contains("BEGIN BITCOIN SIGNATURE"));

        String noEnd = BLOCK.replace("-----END BITCOIN SIGNATURE-----", "");
        assertTrue(Bip47Verification.blockProblem(noEnd).contains("END BITCOIN SIGNATURE"));
        assertNull(Bip47Verification.blockProblem(BLOCK));
    }

    private static void assertProblem(Field field, Result result) {
        assertEquals(Outcome.PROBLEM, result.outcome());
        assertEquals(field, result.field());
        assertNotNull(result.problem());
    }
}
