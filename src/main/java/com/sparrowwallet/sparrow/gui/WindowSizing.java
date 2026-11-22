package com.sparrowwallet.sparrow.gui;

/**
 * The main window's size. The window opens at 1280 x 800 and cannot be made smaller than that, so every
 * layout has the same room: the widest of them, the swap offers table, needs about 700px, and content gets
 * the window's width less the 252px sidebar and the page's own 44px of padding: 984px at the minimum.
 *
 * <p>A window larger than the usable screen greys out maximize and refuses tiling, so every size is
 * capped at the screen. On a screen narrower than the minimum, the layouts fall back to their
 * narrow forms.
 */
public final class WindowSizing {
    /** The smallest the window can be: the same as the size it opens at */
    public static final double MIN_WIDTH = 1280;
    public static final double MIN_HEIGHT = 800;
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
