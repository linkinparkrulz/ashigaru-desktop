package com.sparrowwallet.sparrow.gui;

/**
 * The main window's size. The layouts are designed for a minimum width: the swap screens sit inside
 * Tools, which puts a 190px menu beside the 236px sidebar, so the Swaps content gets the window's
 * width less about 480px, and its offers table needs about 700px.
 *
 * <p>A window larger than the usable screen greys out maximize and refuses tiling, so every size is
 * capped at the screen. On a screen narrower than the minimum, the layouts fall back to their
 * narrow forms.
 */
public final class WindowSizing {
    /** Narrower than this and the swap offers table has to fold */
    public static final double MIN_WIDTH = 1200;
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
