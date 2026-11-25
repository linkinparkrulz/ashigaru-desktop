package com.sparrowwallet.sparrow.gui;

import javafx.animation.PauseTransition;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;

/**
 * Copies a secret to the system clipboard and takes it back after a while, as long as the clipboard still holds it. Anything else
 * that was copied in the meantime is left alone.
 */
final class TimedClipboard {
    /** How long a copied secret stays on the clipboard */
    static final int SECONDS = 60;

    /** The part of a clipboard this needs, so the rule can be tested without a toolkit */
    interface Board {
        String getString();

        void clear();
    }

    private TimedClipboard() {}

    /** Clears the board if it still holds exactly what was copied. True when it did. */
    static boolean clearIfUnchanged(Board board, String copied) {
        if(copied != null && copied.equals(board.getString())) {
            board.clear();
            return true;
        }
        return false;
    }

    /** Copies the text now and clears it from the system clipboard after {@link #SECONDS} seconds if it is still there */
    static void copy(String text) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);

        PauseTransition wait = new PauseTransition(Duration.seconds(SECONDS));
        wait.setOnFinished(event -> clearIfUnchanged(new Board() {
            @Override
            public String getString() {
                return clipboard.hasString() ? clipboard.getString() : null;
            }

            @Override
            public void clear() {
                clipboard.clear();
            }
        }, text));
        wait.play();
    }
}
