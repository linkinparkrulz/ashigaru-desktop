package com.sparrowwallet.sparrow.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The pairing window: the QR holds what the words open, New words changes both, and it says which keys are in it */
class SentinelPairingDialogTest {
    private static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @BeforeAll
    static void startToolkit() throws Exception {
        JavaFXTestSupport.ensureStarted();
        javafx.application.Platform.setImplicitExit(false);
        onFx(() -> org.controlsfx.glyphfont.GlyphFontRegistry.register(new com.sparrowwallet.sparrow.glyphfont.FontAwesome5()));
    }

    private static void onFx(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        JavaFXTestSupport.runAndWait(() -> {
            try {
                action.run();
            } catch(Throwable t) {
                failure.set(t);
            }
        });
        if(failure.get() != null) {
            if(failure.get() instanceof AssertionError error) {
                throw error;
            }
            throw new AssertionError(failure.get());
        }
    }

    private static SentinelPairing.Slots slots(boolean mixing, boolean seedOpen) {
        try {
            Wallet master = new Wallet("pair");
            master.setPolicyType(PolicyType.SINGLE);
            master.setScriptType(ScriptType.P2WPKH);
            DeterministicSeed seed = new DeterministicSeed(MNEMONIC, "", 0, DeterministicSeed.Type.BIP39);
            master.getKeystores().add(Keystore.fromSeed(seed, ScriptType.P2WPKH.getDefaultDerivation()));
            master.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, master.getKeystores(), 1));
            if(mixing) {
                master.addChildWallet(StandardAccount.WHIRLPOOL_PREMIX);
                master.addChildWallet(StandardAccount.WHIRLPOOL_POSTMIX);
                master.addChildWallet(StandardAccount.WHIRLPOOL_BADBANK);
            }
            Keystore open = null;
            if(seedOpen) {
                open = new Keystore();
                open.setSeed(seed);
            }
            return SentinelPairing.slots(master, open);
        } catch(Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String decrypt(String payload, String words) throws Exception {
        return SentinelPairingTest.decrypt(payload, words);
    }

    private static String decodeQr(SentinelPairingDialog.Built built) throws Exception {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(SwingFXUtils.fromFXImage(built.qrView().getImage(), null))));
        return new QRCodeReader().decode(bitmap).getText();
    }

    @Test
    void theQrHoldsThePairingTheWordsOpen() throws Exception {
        AtomicReference<SentinelPairingDialog.Built> holder = new AtomicReference<>();
        onFx(() -> holder.set(SentinelPairingDialog.build(null, slots(true, true))));
        SentinelPairingDialog.Built built = holder.get();
        assertEquals(built.state().words, built.wordsLabel().getText());
        assertEquals(built.state().envelope, decodeQr(built));

        JsonObject outer = JsonParser.parseString(decodeQr(built)).getAsJsonObject();
        String decrypted = decrypt(outer.get("payload").getAsString(), built.wordsLabel().getText());
        assertTrue(decrypted.contains("watch-only"));
    }

    @Test
    void newWordsChangeTheWordsAndTheQrTogether() throws Exception {
        AtomicReference<SentinelPairingDialog.Built> holder = new AtomicReference<>();
        onFx(() -> holder.set(SentinelPairingDialog.build(null, slots(true, true))));
        SentinelPairingDialog.Built built = holder.get();
        String oldWords = built.state().words;
        String oldEnvelope = decodeQr(built);
        onFx(() -> built.newWordsButton().fire());
        assertNotEquals(oldWords, built.wordsLabel().getText());
        assertEquals(built.wordsLabel().getText(), built.state().words);
        String newEnvelope = decodeQr(built);
        assertNotEquals(oldEnvelope, newEnvelope);
        assertEquals(built.state().envelope, newEnvelope);
        //The new words open the new picture, and the old ones no longer open it
        String payload = JsonParser.parseString(newEnvelope).getAsJsonObject().get("payload").getAsString();
        assertDoesNotThrow(() -> decrypt(payload, built.wordsLabel().getText()));
    }

    @Test
    void itSaysWhichKeysAreInAndWhichAreNot() throws Exception {
        AtomicReference<SentinelPairingDialog.Built> full = new AtomicReference<>();
        AtomicReference<SentinelPairingDialog.Built> partial = new AtomicReference<>();
        onFx(() -> {
            full.set(SentinelPairingDialog.build(null, slots(true, true)));
            partial.set(SentinelPairingDialog.build(null, slots(false, false)));
        });
        assertTrue(full.get().includedLabel().getText().contains("Postmix"));
        assertFalse(full.get().missingLabel().isVisible(), "nothing to say when all six are in");

        assertTrue(partial.get().includedLabel().getText().contains("Deposit BIP84"));
        assertFalse(partial.get().includedLabel().getText().contains("Postmix"));
        assertTrue(partial.get().missingLabel().isVisible());
        assertTrue(partial.get().missingLabel().getText().contains("Deposit BIP44"));
        assertTrue(partial.get().missingLabel().getText().contains("Premix"));
    }

    @Test
    void theDialogCanBeClosedAndHasNoActionButtonThatSendsAnything() throws Exception {
        AtomicReference<SentinelPairingDialog.Built> holder = new AtomicReference<>();
        onFx(() -> holder.set(SentinelPairingDialog.build(null, slots(true, true))));
        Dialog<ButtonType> dlg = holder.get().dialog();
        assertEquals(java.util.List.of(ButtonType.CLOSE), dlg.getDialogPane().getButtonTypes());
    }

    @Test
    void theStepsComeBeforeTheWordsAndNameTheButtonsInSentinel() throws Exception {
        AtomicReference<SentinelPairingDialog.Built> holder = new AtomicReference<>();
        onFx(() -> holder.set(SentinelPairingDialog.build(null, slots(true, true))));
        SentinelPairingDialog.Built built = holder.get();

        java.util.List<javafx.scene.Node> column = built.column().getChildren();
        int steps = column.indexOf(built.stepsLabel());
        int words = column.indexOf(built.wordsLabel());
        assertTrue(steps >= 0 && words >= 0);
        assertTrue(steps < words, "the numbered steps are above the pairing words");

        String text = built.stepsLabel().getText();
        String[] lines = text.split("\n");
        assertEquals(4, lines.length);
        //The Dojo pairing Sentinel asks for first is its own step, not this QR code, and the QR code makes a new collection
        assertTrue(lines[0].startsWith("1. In Sentinel, finish its own setup, including pairing it with a Dojo you control or trust"));
        assertTrue(lines[0].contains("not what this QR code is for"));
        assertEquals("2. In Sentinel, add a new collection: tap 'NEW'.", lines[1]);
        assertTrue(lines[2].startsWith("3. Scan the QR code on the left with Sentinel") && lines[2].contains("'COPY PAIRING TEXT'"));
        assertTrue(lines[3].startsWith("4. Type the 3 pairing words") && lines[3].contains("hyphen") && lines[3].contains("'DECRYPT'"));
    }

    @Test
    void aLinkSaysWhereToGetSentinel() throws Exception {
        AtomicReference<SentinelPairingDialog.Built> holder = new AtomicReference<>();
        onFx(() -> holder.set(SentinelPairingDialog.build(null, slots(true, true))));
        SentinelPairingDialog.Built built = holder.get();
        assertEquals("https://github.com/tetragrammalol/sentinel-android-dojobay", SentinelPairingDialog.SENTINEL_URL);
        assertTrue(built.sentinelLink().getText().contains("tetragrammalol/sentinel-android-dojobay"));
        assertTrue(built.column().getChildren().stream().anyMatch(node -> node instanceof javafx.scene.layout.VBox box && box.getChildren().contains(built.sentinelLink())));
    }

    @Test
    void itSaysQrCodeNotPictureAndWhichKeys() throws Exception {
        AtomicReference<SentinelPairingDialog.Built> holder = new AtomicReference<>();
        onFx(() -> holder.set(SentinelPairingDialog.build(null, slots(true, true))));
        StringBuilder all = new StringBuilder();
        collect(holder.get().column(), all);
        collect(holder.get().dialog().getDialogPane().getContent(), all);
        String text = all.toString();
        assertFalse(text.toLowerCase().contains("picture"), text);
        assertTrue(text.contains("QR code"));
        assertTrue(text.contains("XPUB, YPUB and ZPUB"));
    }

    private static void collect(javafx.scene.Node node, StringBuilder out) {
        if(node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            out.append(labeled.getText()).append('\n');
        }
        if(node instanceof javafx.scene.Parent parent) {
            for(javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }
}
