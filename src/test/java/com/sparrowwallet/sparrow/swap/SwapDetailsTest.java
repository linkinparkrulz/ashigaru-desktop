package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class SwapDetailsTest {
    private static final SwapDetails STARTED = SwapDetails.started(4_998_760, 1_240, "4abc", "12D3KooW…b3f9");

    @Test
    void theTermsArriveAfterTheStart() {
        assertEquals(0, STARTED.piconero());
        assertFalse(STARTED.hasDeposit());
        SwapDetails agreed = STARTED.withTerms(14_816_324_640_000L, 99_975, false);
        assertEquals(14_816_324_640_000L, agreed.piconero());
        assertTrue(agreed.hasDeposit());
        assertEquals(4_898_785, agreed.refundSats());
        //What was known at the start is kept
        assertEquals("4abc", agreed.xmrAddress());
        assertEquals(4_998_760, agreed.lockSats());
    }

    @Test
    void aFullRefundSignatureMeansNoDeposit() {
        SwapDetails full = STARTED.withTerms(1, 99_975, true);
        assertFalse(full.hasDeposit());
        assertEquals(4_998_760, full.refundSats());
    }

    @Test
    void detailsSurviveARestart(@TempDir File dir) {
        SwapDetails.Store store = new SwapDetails.Store(dir);
        assertNull(store.get("a"));
        store.put("a", STARTED);
        store.put("a", STARTED.withTerms(5, 7, false));
        store.put("b", STARTED);

        SwapDetails.Store reopened = new SwapDetails.Store(dir);
        assertEquals(STARTED.withTerms(5, 7, false), reopened.get("a"));
        assertEquals(STARTED, reopened.get("b"));
        assertNull(reopened.get("missing"));
        assertNull(reopened.get(null));
    }

    @Test
    void anUnreadableFileIsIgnoredNotFatal(@TempDir File dir) throws Exception {
        Files.writeString(new File(dir, "ashigaru-swaps.json").toPath(), "{ this is not json");
        SwapDetails.Store store = new SwapDetails.Store(dir);
        assertNull(store.get("a"));
        //And it can be written over
        store.put("a", STARTED);
        assertEquals(STARTED, new SwapDetails.Store(dir).get("a"));
    }

    @Test
    void aMissingDirectoryIsCreatedOnFirstWrite(@TempDir File dir) {
        File nested = new File(dir, "identity/abc");
        SwapDetails.Store store = new SwapDetails.Store(nested);
        store.put("a", STARTED);
        assertEquals(STARTED, new SwapDetails.Store(nested).get("a"));
    }
}
