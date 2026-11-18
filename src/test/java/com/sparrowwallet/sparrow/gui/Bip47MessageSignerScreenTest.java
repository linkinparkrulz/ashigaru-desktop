package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.net.dojo.SignedMessageVerifier;
import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Sign screen's behaviour through its real FXML and controller, and the round trip that matters: what it signs
 * is accepted by the Verifier's own code.
 */
class Bip47MessageSignerScreenTest {
    private static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @BeforeAll
    static void startToolkit() throws Exception {
        JavaFXTestSupport.ensureStarted();
        javafx.application.Platform.setImplicitExit(false);
        onFx(() -> org.controlsfx.glyphfont.GlyphFontRegistry.register(new com.sparrowwallet.sparrow.glyphfont.FontAwesome5()));
    }

    @AfterEach
    void restoreWalletSource() {
        Bip47MessageSignerController.walletSource = () -> List.of();
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

    private static Wallet wallet(String name) {
        try {
            Wallet wallet = new Wallet(name);
            wallet.setPolicyType(PolicyType.SINGLE);
            wallet.setScriptType(ScriptType.P2WPKH);
            wallet.getKeystores().add(Keystore.fromSeed(new DeterministicSeed(MNEMONIC, "", 0, DeterministicSeed.Type.BIP39),
                    ScriptType.P2WPKH.getDefaultDerivation()));
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
            return wallet;
        } catch(Exception e) {
            throw new AssertionError(e);
        }
    }

    private interface Check {
        void run(Screen screen);
    }

    private static void screen(List<Wallet> wallets, Check check) throws Exception {
        onFx(() -> {
            Bip47MessageSignerController.walletSource = () -> wallets;
            try {
                FXMLLoader loader = new FXMLLoader(Bip47MessageSignerController.class.getResource("bip47-message-signer.fxml"));
                Parent root = loader.load();
                check.run(new Screen(root, loader.getNamespace()));
            } catch(java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    private record Screen(Parent root, Map<String, Object> ids) {
        @SuppressWarnings("unchecked")
        <T> T get(String id) {
            return (T) ids.get(id);
        }

        TextArea message() { return get("messageArea"); }
        TextArea block() { return get("blockArea"); }
        TextField code() { return get("paymentCodeField"); }
        TextField address() { return get("addressField"); }
        Label title() { return get("resultTitleLabel"); }
        Label detail() { return get("resultDetailLabel"); }
        Label note() { return get("messageNote"); }
        Button signButton() { return get("signBtn"); }
        CheckBox addCode() { return get("addCodeCheck"); }
        ComboBox<Wallet> combo() { return get("walletCombo"); }
        Parent resultBox() { return get("resultBox"); }

        void sign() {
            signButton().fire();
        }
    }

    @Test
    void withNoWalletThatCanSignItSaysWhyAndCannotSign() throws Exception {
        screen(List.of(), s -> {
            assertTrue(s.signButton().isDisabled());
            Label why = s.get("noWalletLabel");
            assertTrue(why.isVisible());
            assertTrue(why.getText().contains("seed"));
            assertFalse(s.combo().isVisible());
        });
    }

    @Test
    void theFirstWalletIsChosenAndItsCodeAndAddressAreShown() throws Exception {
        Wallet wallet = wallet("main");
        screen(List.of(wallet), s -> {
            assertSame(wallet, s.combo().getValue());
            assertEquals(wallet.getPaymentCode().toString(), s.code().getText());
            assertEquals(wallet.getPaymentCode().getNotificationAddress().toString(), s.address().getText());
            assertFalse(s.signButton().isDisabled());
            assertTrue(s.title().getText().startsWith("The signed message appears"));
        });
    }

    @Test
    void signingGivesABlockTheVerifierAccepts() throws Exception {
        Wallet wallet = wallet("main");
        screen(List.of(wallet), s -> {
            s.message().setText("Challenge 4711");
            s.sign();
            assertEquals("Message signed", s.title().getText());
            assertTrue(s.resultBox().getStyleClass().contains("verifier-result-success"));
            String block = s.block().getText();
            assertTrue(block.startsWith("-----BEGIN BITCOIN SIGNED MESSAGE-----"));
            assertTrue(SignedMessageVerifier.verify(block, "Challenge 4711", wallet.getPaymentCode().toString()));

            //The same screen's own answer, through the Verifier's logic
            SignedMessageVerifier.ParsedBlock parsed = SignedMessageVerifier.parse(block);
            assertEquals(Bip47Verification.Outcome.VERIFIED,
                    Bip47Verification.verify(wallet.getPaymentCode().toString(), parsed.message(), parsed.signature()).outcome());
        });
    }

    @Test
    void anEmptyMessageIsNotSignedAndTheCursorGoesToIt() throws Exception {
        screen(List.of(wallet("main")), s -> {
            s.sign();
            assertEquals("Cannot sign yet", s.title().getText());
            assertTrue(s.resultBox().getStyleClass().contains("verifier-result-error"));
            assertFalse(((Parent) s.get("blockBox")).isVisible());
            assertTrue(s.block().getText().isEmpty());
        });
    }

    @Test
    void theResultGoesBlankWhenAnythingChanges() throws Exception {
        screen(List.of(wallet("main")), s -> {
            s.message().setText("first");
            s.sign();
            assertEquals("Message signed", s.title().getText());
            s.message().setText("first, edited");
            assertTrue(s.title().getText().startsWith("The signed message appears"));
            assertTrue(s.block().getText().isEmpty());

            s.sign();
            assertEquals("Message signed", s.title().getText());
            s.addCode().setSelected(false);
            assertTrue(s.title().getText().startsWith("The signed message appears"));

            s.sign();
            assertEquals("Message signed", s.title().getText());
            s.combo().getSelectionModel().select(s.combo().getValue());
            //Choosing the same wallet again is not a change
            assertEquals("Message signed", s.title().getText());
        });
    }

    @Test
    void theNoteSaysWhatWillBeAddedToTheMessage() throws Exception {
        Wallet wallet = wallet("main");
        screen(List.of(wallet), s -> {
            assertFalse(s.note().isVisible(), "nothing to say about an empty message");
            s.message().setText("hello");
            assertTrue(s.note().isVisible());
            assertTrue(s.note().getText().contains("BIP47: " + wallet.getPaymentCode()));

            s.addCode().setSelected(false);
            assertFalse(s.note().isVisible());

            s.addCode().setSelected(true);
            s.message().setText("hello\nBIP47: " + wallet.getPaymentCode());
            assertTrue(s.note().getText().contains("already names"));
        });
    }

    @Test
    void withTheBoxUntickedTheMessageIsSignedAsTyped() throws Exception {
        Wallet wallet = wallet("main");
        screen(List.of(wallet), s -> {
            s.addCode().setSelected(false);
            s.message().setText("exactly this");
            s.sign();
            assertEquals("exactly this", SignedMessageVerifier.parse(s.block().getText()).message());
        });
    }

    @Test
    void markersInTheMessageAreExplainedNotSigned() throws Exception {
        screen(List.of(wallet("main")), s -> {
            s.message().setText("x\n-----BEGIN BITCOIN SIGNATURE-----");
            s.sign();
            assertEquals("Cannot sign yet", s.title().getText());
            assertTrue(s.detail().getText().contains("marker"));
        });
    }

    @Test
    void controlEnterSignsFromTheMessageBox() throws Exception {
        screen(List.of(wallet("main")), s -> {
            s.message().setText("keyboard");
            s.message().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "\r", KeyCode.ENTER, false, true, false, false));
            assertEquals("Message signed", s.title().getText());
        });
    }

    @Test
    void severalWalletsAreListedByName() throws Exception {
        Wallet first = wallet("first");
        screen(List.of(first, wallet("second")), s -> {
            assertEquals(2, s.combo().getItems().size());
            assertEquals("first", s.combo().getConverter().toString(s.combo().getItems().get(0)));
            assertEquals("second", s.combo().getConverter().toString(s.combo().getItems().get(1)));
        });
    }
}
