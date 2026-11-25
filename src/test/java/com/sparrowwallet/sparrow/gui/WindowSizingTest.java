package com.sparrowwallet.sparrow.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowSizingTest {
    @Test
    void aRoomyScreenGetsTheDefaultSize() {
        assertEquals(1280, WindowSizing.width(1920));
        assertEquals(800, WindowSizing.height(1040));
        //The window cannot be made smaller than the size it opens at
        assertEquals(1280, WindowSizing.minWidth(1920));
        assertEquals(800, WindowSizing.minHeight(1040));
    }

    @Test
    void theMinimumIsTheDefault() {
        assertEquals(WindowSizing.DEFAULT_WIDTH, WindowSizing.MIN_WIDTH);
        assertEquals(WindowSizing.DEFAULT_HEIGHT, WindowSizing.MIN_HEIGHT);
        assertEquals(1280, WindowSizing.MIN_WIDTH);
        assertEquals(800, WindowSizing.MIN_HEIGHT);
    }

    @Test
    void nothingExceedsTheUsableScreen() {
        //A window bigger than the usable area breaks maximize and tiling, so a small screen caps every size
        assertEquals(1024, WindowSizing.width(1024));
        assertEquals(1024, WindowSizing.minWidth(1024));
        assertEquals(700, WindowSizing.height(700));
        assertEquals(500, WindowSizing.minHeight(500));
    }

    @Test
    void aLaptopScreenFitsTheDefaultButNotAboveIt() {
        assertEquals(1280, WindowSizing.width(1366));
        assertEquals(1280, WindowSizing.minWidth(1366));
        assertEquals(728, WindowSizing.minHeight(728));
        assertEquals(728, WindowSizing.height(728));
    }
}
