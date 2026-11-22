package com.sparrowwallet.sparrow.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimedClipboardTest {
    private static class FakeBoard implements TimedClipboard.Board {
        String text;
        boolean cleared;

        @Override
        public String getString() {
            return text;
        }

        @Override
        public void clear() {
            text = null;
            cleared = true;
        }
    }

    @Test
    void whatWasCopiedIsTakenBackIfItIsStillThere() {
        FakeBoard board = new FakeBoard();
        board.text = "ranging-fragility-cringing";
        assertTrue(TimedClipboard.clearIfUnchanged(board, "ranging-fragility-cringing"));
        assertTrue(board.cleared);
        assertNull(board.text);
    }

    @Test
    void anythingCopiedSinceIsLeftAlone() {
        FakeBoard board = new FakeBoard();
        board.text = "something the user copied afterwards";
        assertFalse(TimedClipboard.clearIfUnchanged(board, "ranging-fragility-cringing"));
        assertFalse(board.cleared);
        assertEquals("something the user copied afterwards", board.text);

        //An empty or non-text clipboard is left alone too
        FakeBoard empty = new FakeBoard();
        assertFalse(TimedClipboard.clearIfUnchanged(empty, "words"));
        assertFalse(TimedClipboard.clearIfUnchanged(empty, null));
    }

    @Test
    void itWaitsAMinute() {
        assertEquals(60, TimedClipboard.SECONDS);
    }
}
