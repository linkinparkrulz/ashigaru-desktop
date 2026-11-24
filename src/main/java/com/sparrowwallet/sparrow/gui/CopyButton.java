package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import javafx.animation.PauseTransition;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;

/**
 * Small icon-only button that copies text to the clipboard and flashes a green check briefly. The icons are the bundled Font Awesome
 * ones, drawn at full strength: the text glyph used before (⎘) was thin, faint and looked different on every platform.
 */
public class CopyButton extends Button {
    private static final String SUCCESS_STYLE_CLASS = "success";
    static final double ICON_SIZE = 13;

    public CopyButton() {
        setGraphic(Icons.of(FontAwesome5.Glyph.COPY, ICON_SIZE));
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setTooltip(new Tooltip("Copy"));
        getStyleClass().add("copy-icon-btn");
        setMinSize(28, 28);
        setPrefSize(28, 28);
        setMaxSize(28, 28);
    }

    /** Copies the given text to the system clipboard and flashes the success state. */
    public final void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        flashSuccess();
    }

    /** Briefly shows the green check, then goes back to the copy icon. */
    public final void flashSuccess() {
        setGraphic(Icons.of(FontAwesome5.Glyph.CHECK, ICON_SIZE));
        if(!getStyleClass().contains(SUCCESS_STYLE_CLASS)) {
            getStyleClass().add(SUCCESS_STYLE_CLASS);
        }
        PauseTransition pause = new PauseTransition(Duration.seconds(1.5));
        pause.setOnFinished(e -> {
            setGraphic(Icons.of(FontAwesome5.Glyph.COPY, ICON_SIZE));
            getStyleClass().remove(SUCCESS_STYLE_CLASS);
        });
        pause.play();
    }
}
