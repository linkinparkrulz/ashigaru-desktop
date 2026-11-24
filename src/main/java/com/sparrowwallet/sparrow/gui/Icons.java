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

    /** A label that is only an icon, carrying a style class for its size, shape and colour. */
    public static javafx.scene.control.Label label(FontAwesome5.Glyph glyph, double size, String styleClass) {
        javafx.scene.control.Label label = new javafx.scene.control.Label();
        label.setGraphic(of(glyph, size));
        label.getStyleClass().add(styleClass);
        label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return label;
    }

    /** Puts an icon in front of a button's or label's text. */
    public static void set(Labeled control, FontAwesome5.Glyph glyph) {
        control.setGraphic(of(glyph));
        control.setGraphicTextGap(9);
    }

    public static void set(MenuItem item, FontAwesome5.Glyph glyph) {
        item.setGraphic(of(glyph));
    }

    /**
     * A menu item as every menu in the app has them, like the wallet's 3-dot menu: its icon, then its words. Menus are built with this,
     * so none gets an item without an icon.
     */
    public static MenuItem menuItem(String text, FontAwesome5.Glyph glyph, javafx.event.EventHandler<javafx.event.ActionEvent> onAction) {
        MenuItem item = new MenuItem(text, of(glyph));
        if(onAction != null) {
            item.setOnAction(onAction);
        }
        return item;
    }
}
