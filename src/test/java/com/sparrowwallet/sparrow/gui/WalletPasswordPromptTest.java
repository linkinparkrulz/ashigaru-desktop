package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The small password window every Ashigaru flow shares, and the one wording for a wrong password */
class WalletPasswordPromptTest {
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
            throw failure.get() instanceof AssertionError error ? error : new AssertionError(failure.get());
        }
    }

    @Test
    void itIsTheCompactWindowNotSparrowsFixedSizeOne() throws Exception {
        onFx(() -> {
            Dialog<String> dialog = WalletPasswordPrompt.dialog(null, "My wallet", "PAIR WITH SENTINEL");
            //Sparrow's dialog is 380 x 260 with a 50 px lock; this one sizes to its content
            assertEquals(javafx.scene.layout.Region.USE_COMPUTED_SIZE, dialog.getDialogPane().getPrefWidth());
            assertEquals(javafx.scene.layout.Region.USE_COMPUTED_SIZE, dialog.getDialogPane().getPrefHeight());
            assertNull(dialog.getDialogPane().getGraphic());
            assertEquals(2, dialog.getDialogPane().getButtonTypes().size());
            assertTrue(dialog.getDialogPane().getButtonTypes().stream().anyMatch(type -> type.getText().equals("Unlock")
                    && type.getButtonData() == ButtonBar.ButtonData.OK_DONE));
            assertTrue(dialog.getDialogPane().getButtonTypes().contains(ButtonType.CANCEL));
        });
    }

    @Test
    void itSaysWhatItIsForAndWhichWallet() throws Exception {
        onFx(() -> {
            Dialog<String> dialog = WalletPasswordPrompt.dialog(null, "My wallet", "VIEW SEED");
            String text = String.join(" ", allText(dialog.getDialogPane().getHeader()));
            assertTrue(text.contains("VIEW SEED"), text);
            assertTrue(text.contains("My wallet"), text);
        });
    }

    private static java.util.List<String> allText(javafx.scene.Node node) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if(node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            out.add(labeled.getText());
        }
        if(node instanceof javafx.scene.Parent parent) {
            for(javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                out.addAll(allText(child));
            }
        }
        return out;
    }

    @Test
    void aWrongPasswordIsExplainedWithoutTheCiphersWords() {
        String text = WalletPasswordPrompt.wrongPasswordText("My wallet");
        assertTrue(text.contains("My wallet"));
        assertTrue(text.contains("Nothing was"));
        for(String geek : new String[] {"decrypt", "bytes", "cipher", "padding", "Exception", "AES", "Argon"}) {
            assertFalse(text.toLowerCase().contains(geek.toLowerCase()), geek);
        }
    }
}
