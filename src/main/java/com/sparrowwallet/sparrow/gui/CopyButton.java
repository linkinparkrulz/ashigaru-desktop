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

    private final javafx.scene.Node copyGraphic = Icons.of(FontAwesome5.Glyph.COPY, ICON_SIZE);
    private final javafx.scene.Node checkGraphic = Icons.of(FontAwesome5.Glyph.CHECK, ICON_SIZE);
    private final PauseTransition resetPause = new PauseTransition(Duration.seconds(1.5));
    private final Tooltip tooltip = new Tooltip("Copy");
    private String copyText;

    public CopyButton() {
        setGraphic(copyGraphic);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setTooltip(tooltip);
        getStyleClass().add("copy-icon-btn");
        setMinSize(28, 28);
        setPrefSize(28, 28);
        setMaxSize(28, 28);
        resetPause.setOnFinished(e -> {
            setGraphic(copyGraphic);
            getStyleClass().remove(SUCCESS_STYLE_CLASS);
        });
    }

    /** Says on hover what the button copies, e.g. "Copy TxID" */
    public final void setTooltipText(String text) {
        tooltip.setText(text);
    }

    public final String getTooltipText() {
        return tooltip.getText();
    }

    /** Binds this button to copy the given text on click, and says on hover what that text is */
    public final void setCopyText(String text, String tooltipText) {
        setTooltipText(tooltipText);
        setCopyText(text);
    }

    /** Binds this button to copy the given text on click, allowing a single button to be reused across cell updates. */
    public final void setCopyText(String text) {
        this.copyText = text;
        if(getOnAction() == null) {
            setOnAction(e -> {
                if(copyText != null) {
                    copy(copyText);
                }
            });
        }
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
        setGraphic(checkGraphic);
        if(!getStyleClass().contains(SUCCESS_STYLE_CLASS)) {
            getStyleClass().add(SUCCESS_STYLE_CLASS);
        }
        resetPause.playFromStart();
    }
}
