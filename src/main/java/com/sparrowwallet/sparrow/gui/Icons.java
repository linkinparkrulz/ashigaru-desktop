package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import org.controlsfx.glyphfont.Glyph;

/**
 * The GUI's one icon set. Icons used to be text glyphs in button labels, which the display face does
 * not contain, so each platform substituted its own. These all come from the bundled Font Awesome.
 */
public final class Icons {
    private Icons() {}

    public static Glyph of(FontAwesome5.Glyph glyph) {
        return of(glyph, 12);
    }

    public static Glyph of(FontAwesome5.Glyph glyph, double size) {
        Glyph g = new Glyph(FontAwesome5.FONT_NAME, glyph);
        g.setFontSize(size);
        g.getStyleClass().add("ag-icon");
        return g;
    }

    /** Puts an icon in front of a button's or label's text. */
    public static void set(Labeled control, FontAwesome5.Glyph glyph) {
        control.setGraphic(of(glyph));
        control.setGraphicTextGap(9);
    }

    public static void set(MenuItem item, FontAwesome5.Glyph glyph) {
        item.setGraphic(of(glyph));
    }
}
