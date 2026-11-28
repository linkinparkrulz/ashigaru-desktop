package com.sparrowwallet.sparrow.gui;

import javafx.scene.paint.Color;

/**
 * The Ashigaru palette for code that draws rather than styles. A {@link javafx.scene.canvas.Canvas} cannot read
 * CSS, so the {@code -ag-*} tokens have to exist as Java values too; this is the one place they are written down.
 * Each constant names the token it mirrors — keep it in step with {@code ag-tokens.css}.
 */
public final class AgPalette {
    /** {@code -ag-text} */
    public static final Color TEXT = Color.web("#ECECEC");
    /** {@code -ag-text-dim} */
    public static final Color TEXT_DIM = Color.web("#A6A7AD");
    /** {@code -ag-text-faint} */
    public static final Color TEXT_FAINT = Color.web("#8B8D95");
    /** {@code -ag-accent}, Ashigaru red */
    public static final Color ACCENT = Color.web("#CC1111");
    /** {@code -ag-accent-ink}, small red type */
    public static final Color ACCENT_INK = Color.web("#FF5A4D");
    /** {@code -ag-warn} */
    public static final Color WARN = Color.web("#E5A00C");
    /** {@code -ag-warn-ink}, small amber type */
    public static final Color WARN_INK = Color.web("#F2B53A");

    /** The body face, as loaded in {@code AshigaruGui} */
    public static final String FONT_BODY = "Nikkyou Sans";
    /** The figures face, as loaded in {@code AshigaruGui} */
    public static final String FONT_MONO = "Roboto Mono";

    private AgPalette() {}
}
