package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnfinishedSwapsTest {
    @TempDir
    File swapsDir;

    @Test
    void noSwapDataAndNoFileIsNone() {
        assertEquals(0, new UnfinishedSwaps(swapsDir).count());
    }

    @Test
    void swapDataWithNoFileIsUnknownUntilTheEngineLists() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        Files.writeString(new File(identity.getDataDir(), "sqlite").toPath(), "swaps");
        UnfinishedSwaps unfinished = new UnfinishedSwaps(swapsDir);
        assertEquals(UnfinishedSwaps.UNKNOWN, unfinished.count());

        //A swap started now is not the whole list: still unknown, and nothing is written that the next start would believe
        unfinished.add("a");
        assertEquals(UnfinishedSwaps.UNKNOWN, unfinished.count());
        assertFalse(new File(swapsDir, UnfinishedSwaps.FILE_NAME).exists());

        //The engine's list settles it, even when it is empty
        unfinished.replaceAll(List.of());
        assertEquals(0, unfinished.count());
        assertTrue(new File(swapsDir, UnfinishedSwaps.FILE_NAME).isFile());
        assertEquals(0, new UnfinishedSwaps(swapsDir).count());
    }
}
