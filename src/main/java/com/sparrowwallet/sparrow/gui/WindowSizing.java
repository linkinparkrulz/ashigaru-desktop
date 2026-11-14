package com.sparrowwallet.sparrow.gui;

/**
 * The main window's size. The layouts are designed for a minimum width. The widest of them, the
 * swap offers table, needs about 700px. Tools and Settings are tabs now, so content gets the
 * window's width less the 252px sidebar and the page's own 44px of padding: 728px at the minimum.
 * (When Tools had its own side menu the minimum had to be 1200 to leave the same room.)
 *
 * <p>A window larger than the usable screen greys out maximize and refuses tiling, so every size is
 * capped at the screen. On a screen narrower than the minimum, the layouts fall back to their
 * narrow forms.
 */
public final class WindowSizing {
    /** Narrower than this and the swap offers table has to fold */
    public static final double MIN_WIDTH = 1024;
    public static final double MIN_HEIGHT = 520;
    public static final double DEFAULT_WIDTH = 1280;
    public static final double DEFAULT_HEIGHT = 800;

    private WindowSizing() {}

    /** The window's first width on a screen with this much usable width */
    public static double width(double usableWidth) {
        return Math.min(DEFAULT_WIDTH, usableWidth);
    }

    public static double height(double usableHeight) {
        return Math.min(DEFAULT_HEIGHT, usableHeight);
    }

    /** The narrowest the user can drag the window */
    public static double minWidth(double usableWidth) {
        return Math.min(MIN_WIDTH, usableWidth);
    }

    public static double minHeight(double usableHeight) {
        return Math.min(MIN_HEIGHT, usableHeight);
    }
}
