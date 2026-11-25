package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.net.update.ReleaseTrust;
import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Verifier screen's behaviour, driven through its real FXML and controller without a window. What it looks like
 * is checked by eye; this covers what it does: one paste is read as it is pasted, the typed boxes take a block's
 * place, a later paste is never outvoted by an earlier one, and the result goes back to blank when an input changes.
 */
class Bip47MessageVerifierScreenTest {
    private static final String CODE = ReleaseTrust.RELEASE_SIGNING_PAYMENT_CODE;
    private static final String MESSAGE = "https://paymentcode.io/\n\nBIP47: " + CODE;
    private static final String SIGNATURE = "H5mR/qaxInXzuSuzlaC0EO9/pqtHIygLRKOEVkgefTEuAJy7kyP1d1wkXDgafJPoLRjsKgt0PRuZZF46Ha/gm2o=";
    private static final String BLOCK = "-----BEGIN BITCOIN SIGNED MESSAGE-----\n" + MESSAGE + "\n-----BEGIN BITCOIN SIGNATURE-----\n"
            + "Version: Bitcoin-qt (1.0)\n\n" + SIGNATURE + "\n-----END BITCOIN SIGNATURE-----\n";

    @BeforeAll
    static void startToolkit() throws Exception {
        JavaFXTestSupport.ensureStarted();
        onFx(() -> org.controlsfx.glyphfont.GlyphFontRegistry.register(new com.sparrowwallet.sparrow.glyphfont.FontAwesome5()));
    }

    /** Runs on the FX thread and rethrows what failed there, which runAndWait alone would leave on that thread */
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

    private interface Check {
        void run(Screen screen);
    }

    private static void screen(Check check) throws Exception {
        onFx(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(Bip47MessageVerifierController.class.getResource("bip47-message-verifier.fxml"));
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

        TextArea block() { return get("signedBlockArea"); }
        TextArea message() { return get("messageArea"); }
        TextArea signature() { return get("signatureArea"); }
        TextField code() { return get("paymentCodeField"); }
        Label title() { return get("resultTitleLabel"); }
        Label status() { return get("blockStatus"); }

        void verify() {
            ((Button) get("verifyBtn")).fire();
        }
    }

    @Test
    void startsBlankWithNothingSaidAboutTheBlock() throws Exception {
        screen(s -> {
            assertTrue(s.title().getText().startsWith("The result appears"));
            assertFalse(s.status().isVisible());
        });
    }

    @Test
    void aPastedBlockIsReadAtOnceAndVerifiesWithoutAParseStep() throws Exception {
        screen(s -> {
            s.code().setText(CODE);
            s.block().setText(BLOCK);
            assertEquals(MESSAGE, s.message().getText());
            assertEquals(SIGNATURE, s.signature().getText());
            assertTrue(s.status().getText().contains("recognised"));
            s.verify();
            assertEquals("Signature verified", s.title().getText());
            assertTrue(((Parent) s.get("resultBox")).getStyleClass().contains("verifier-result-success"));
        });
    }

    @Test
    void aLaterPasteReplacesAnEarlierOneInsteadOfBeingOutvotedByIt() throws Exception {
        screen(s -> {
            s.code().setText(CODE);
            s.block().setText(BLOCK);
            s.block().setText(BLOCK.replace("https://paymentcode.io/", "https://example.org/"));
            assertTrue(s.message().getText().startsWith("https://example.org/"));
            s.verify();
            assertTrue(s.title().getText().startsWith("Signature does not match"));
        });
    }

    @Test
    void typingTheMessageAndSignatureTakesTheBlocksPlace() throws Exception {
        screen(s -> {
            s.code().setText(CODE);
            s.block().setText(BLOCK);
            //Putting back the very text the block already gave is no edit, so a real change is what is typed here
            s.message().setText("something else");
            assertTrue(s.block().getText().isEmpty(), "the block no longer says what will be verified");
            s.message().setText(MESSAGE);
            s.signature().setText(SIGNATURE);
            assertTrue(s.status().getText().contains("entered below"));
            s.verify();
            assertEquals("Signature verified", s.title().getText());
        });
    }

    @Test
    void changingAnInputTakesTheOldAnswerAway() throws Exception {
        screen(s -> {
            s.code().setText(CODE);
            s.block().setText(BLOCK);
            s.verify();
            assertEquals("Signature verified", s.title().getText());
            s.code().setText(CODE + " ");
            assertTrue(s.title().getText().startsWith("The result appears"));
        });
    }

    @Test
    void ctrlEnterVerifiesFromATextArea() throws Exception {
        screen(s -> {
            s.code().setText(CODE);
            s.block().setText(BLOCK);
            //Enter alone types a line break in a text area; Ctrl+Enter is the shortcut
            s.block().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "\r", KeyCode.ENTER, false, true, false, false));
            assertEquals("Signature verified", s.title().getText());
        });
    }

    @Test
    void aBlockThatCannotBeReadSaysSoAndLeavesNothingBehindFromAnEarlierOne() throws Exception {
        screen(s -> {
            s.block().setText(BLOCK);
            s.block().setText("-----BEGIN BITCOIN SIGNED MESSAGE-----\nhello");
            assertTrue(s.message().getText().isEmpty());
            assertTrue(s.signature().getText().isEmpty());
            assertTrue(s.status().getStyleClass().contains("verifier-status-warn"));
            s.code().setText(CODE);
            s.verify();
            assertEquals("Cannot verify yet", s.title().getText());
        });
    }

    @Test
    void aMissingPaymentCodeIsSaidInPlainWords() throws Exception {
        screen(s -> {
            s.block().setText(BLOCK);
            s.verify();
            assertEquals("Cannot verify yet", s.title().getText());
            Label detail = s.get("resultDetailLabel");
            assertTrue(detail.getText().contains("payment code"));
        });
    }

    @Test
    void clearEmptiesEveryInput() throws Exception {
        screen(s -> {
            s.block().setText(BLOCK);
            ((Button) s.get("clearBtn")).fire();
            assertTrue(s.block().getText().isEmpty());
            assertTrue(s.message().getText().isEmpty());
            assertTrue(s.signature().getText().isEmpty());
            assertFalse(s.status().isVisible());
        });
    }
}
