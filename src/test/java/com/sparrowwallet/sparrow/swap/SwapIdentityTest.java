package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class SwapIdentityTest {
    @TempDir
    File swapsDir;

    @Test
    void currentIdentityPersists() throws Exception {
        SwapIdentity first = SwapIdentity.current(swapsDir);
        SwapIdentity again = SwapIdentity.current(swapsDir);
        assertEquals(32, first.getSeed().length);
        assertArrayEquals(first.getSeed(), again.getSeed());
        assertEquals(first.getDataDir(), again.getDataDir());
        assertEquals(swapsDir, first.getDataDir().getParentFile());
    }

    @Test
    void renewingGivesANewIdentityAndKeepsIt() throws Exception {
        SwapIdentity first = SwapIdentity.current(swapsDir);
        SwapIdentity renewed = SwapIdentity.renew(swapsDir);
        assertFalse(java.util.Arrays.equals(first.getSeed(), renewed.getSeed()));
        assertNotEquals(first.getDataDir(), renewed.getDataDir());
        assertArrayEquals(renewed.getSeed(), SwapIdentity.current(swapsDir).getSeed());
    }
}
