package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class SwapIdentityTest {
    @TempDir
    File swapsDir;

    private static void database(SwapIdentity identity) throws IOException {
        Files.createDirectories(identity.getDataDir().toPath());
        Files.writeString(new File(identity.getDataDir(), "sqlite").toPath(), "swaps", StandardCharsets.UTF_8);
    }

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

    @Test
    void everyIdentityKeepsItsSeedInItsOwnDirectory() throws Exception {
        SwapIdentity first = SwapIdentity.current(swapsDir);
        SwapIdentity second = SwapIdentity.renew(swapsDir);
        for(SwapIdentity identity : new SwapIdentity[] {first, second}) {
            File copy = new File(identity.getDataDir(), SwapIdentity.SEED_COPY);
            assertTrue(copy.isFile());
            assertEquals(com.sparrowwallet.drongo.Utils.bytesToHex(identity.getSeed()), Files.readString(copy.toPath()).trim());
        }
        assertFalse(new File(swapsDir, "identity.tmp").exists());
    }

    @Test
    void anInstallFromBeforeTheCopyGetsItWrittenOnTheNextStart() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        File copy = new File(identity.getDataDir(), SwapIdentity.SEED_COPY);
        assertTrue(copy.delete());
        SwapIdentity.current(swapsDir);
        assertTrue(copy.isFile());
    }

    @Test
    void aDamagedIdentityFileIsRecoveredFromTheSwapsOwnSeed() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        database(identity);
        Files.writeString(new File(swapsDir, "identity").toPath(), "not a seed", StandardCharsets.UTF_8);

        SwapIdentity recovered = SwapIdentity.current(swapsDir);
        assertArrayEquals(identity.getSeed(), recovered.getSeed(), "the unfinished swap's own seed, not a new one");
        assertEquals(identity.getDataDir(), recovered.getDataDir());
        //And the identity file is written again
        assertArrayEquals(identity.getSeed(), SwapIdentity.current(swapsDir).getSeed());
    }

    @Test
    void aMissingIdentityFileIsRecoveredTheSameWay() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        database(identity);
        assertTrue(new File(swapsDir, "identity").delete());
        assertArrayEquals(identity.getSeed(), SwapIdentity.current(swapsDir).getSeed());
    }

    @Test
    void theNewestIdentityIsRecoveredWhenThereAreSeveral() throws Exception {
        SwapIdentity old = SwapIdentity.current(swapsDir);
        database(old);
        assertTrue(new File(old.getDataDir(), "sqlite").setLastModified(1_000_000L));
        SwapIdentity newer = SwapIdentity.renew(swapsDir);
        database(newer);
        Files.writeString(new File(swapsDir, "identity").toPath(), "", StandardCharsets.UTF_8);
        assertArrayEquals(newer.getSeed(), SwapIdentity.current(swapsDir).getSeed());
    }

    @Test
    void swapDataWithNoSeedIsNotTakenOverByANewIdentity() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        database(identity);
        assertTrue(new File(identity.getDataDir(), SwapIdentity.SEED_COPY).delete());
        Files.writeString(new File(swapsDir, "identity").toPath(), "damaged", StandardCharsets.UTF_8);

        IOException refused = assertThrows(IOException.class, () -> SwapIdentity.current(swapsDir));
        assertTrue(refused.getMessage().contains("damaged"), refused.getMessage());
        assertEquals("damaged", Files.readString(new File(swapsDir, "identity").toPath()), "nothing was overwritten");
    }

    @Test
    void aSeedThatDoesNotMatchItsDirectoryIsNotTrusted() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        database(identity);
        Files.writeString(new File(identity.getDataDir(), SwapIdentity.SEED_COPY).toPath(), "00".repeat(32), StandardCharsets.UTF_8);
        Files.writeString(new File(swapsDir, "identity").toPath(), "damaged", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SwapIdentity.current(swapsDir));
    }

    @Test
    void aDamagedFileWithNoSwapDataStartsAFreshIdentity() throws Exception {
        Files.createDirectories(swapsDir.toPath());
        Files.writeString(new File(swapsDir, "identity").toPath(), "damaged", StandardCharsets.UTF_8);
        SwapIdentity fresh = SwapIdentity.current(swapsDir);
        assertEquals(32, fresh.getSeed().length);
        assertArrayEquals(fresh.getSeed(), SwapIdentity.current(swapsDir).getSeed());
    }
}
