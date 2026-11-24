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
    void onlyTheIdentityNamedBesideTheFileIsRecoveredNeverAnOlderOne() throws Exception {
        SwapIdentity old = SwapIdentity.current(swapsDir);
        database(old);
        SwapIdentity current = SwapIdentity.renew(swapsDir);
        database(current);
        //The older identity's data is the newer by modification time: recovery must not be fooled by that
        assertTrue(new File(old.getDataDir(), "sqlite").setLastModified(System.currentTimeMillis() + 60_000));
        assertTrue(new File(current.getDataDir(), "sqlite").setLastModified(1_000_000L));
        Files.writeString(new File(swapsDir, "identity").toPath(), "", StandardCharsets.UTF_8);

        SwapIdentity recovered = SwapIdentity.current(swapsDir);
        assertArrayEquals(current.getSeed(), recovered.getSeed());
        assertTrue(recovered.wasRecovered());
        assertFalse(SwapIdentity.current(swapsDir).wasRecovered(), "the file is written again, so the next start is an ordinary one");
    }

    @Test
    void anOlderIdentityFileRestoredFromABackupDoesNotTakeOver() throws Exception {
        SwapIdentity old = SwapIdentity.current(swapsDir);
        database(old);
        byte[] backup = Files.readAllBytes(new File(swapsDir, "identity").toPath());
        SwapIdentity current = SwapIdentity.renew(swapsDir);
        database(current);

        Files.write(new File(swapsDir, "identity").toPath(), backup);
        IOException refused = assertThrows(IOException.class, () -> SwapIdentity.current(swapsDir));
        assertTrue(refused.getMessage().contains(current.getDataDir().getName()), refused.getMessage());
        assertEquals(current.getDataDir().getName(), Files.readString(new File(swapsDir, SwapIdentity.FOLDER).toPath()).trim(), "nothing was rewritten");

        //Moving the restored file away, as the message says, recovers the identity last in use
        assertTrue(new File(swapsDir, "identity").delete());
        assertArrayEquals(current.getSeed(), SwapIdentity.current(swapsDir).getSeed());
    }

    @Test
    void anEarlierIdentityWithUnfinishedSwapsCanBePutBackInCharge() throws Exception {
        SwapIdentity newer = SwapIdentity.current(swapsDir);
        database(newer);
        SwapIdentity restored = SwapIdentity.renew(swapsDir);
        database(restored);

        SwapIdentity adopted = SwapIdentity.adopt(swapsDir, newer.getDataDir().getName());
        assertArrayEquals(newer.getSeed(), adopted.getSeed());
        assertTrue(adopted.wasRecovered(), "not replaced in the same run");
        assertArrayEquals(newer.getSeed(), SwapIdentity.current(swapsDir).getSeed(), "the identity file and folder name now name it");
        assertTrue(new File(restored.getDataDir(), SwapIdentity.SEED_COPY).isFile(), "the identity it replaced keeps its seed");

        //Never a folder without its own matching seed, a retired one, or a name that is not a folder name
        Files.writeString(new File(restored.getDataDir(), SwapIdentity.RETIRED).toPath(), "x", StandardCharsets.UTF_8);
        assertNull(SwapIdentity.adopt(swapsDir, restored.getDataDir().getName()));
        assertNull(SwapIdentity.adopt(swapsDir, "../../etc"));
        assertNull(SwapIdentity.adopt(swapsDir, "0123456789abcdef"));
    }

    @Test
    void aRenewalInterruptedBeforeItsFolderNameWasWrittenIsTakenAsBefore() throws Exception {
        SwapIdentity old = SwapIdentity.current(swapsDir);
        database(old);
        SwapIdentity fresh = SwapIdentity.renew(swapsDir);
        //As if the process died after the identity file and before the folder name
        Files.writeString(new File(swapsDir, SwapIdentity.FOLDER).toPath(), old.getDataDir().getName(), StandardCharsets.UTF_8);
        assertArrayEquals(fresh.getSeed(), SwapIdentity.current(swapsDir).getSeed());
        assertEquals(fresh.getDataDir().getName(), Files.readString(new File(swapsDir, SwapIdentity.FOLDER).toPath()).trim());
    }

    @Test
    void withoutTheFolderMarkerSwapDataIsNotGuessedAt() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        database(identity);
        assertTrue(new File(swapsDir, SwapIdentity.FOLDER).delete());
        Files.writeString(new File(swapsDir, "identity").toPath(), "damaged", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SwapIdentity.current(swapsDir));
    }

    @Test
    void aMarkerThatPointsOutOfTheSwapsFolderIsIgnored() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        database(identity);
        Files.writeString(new File(swapsDir, SwapIdentity.FOLDER).toPath(), "../../etc", StandardCharsets.UTF_8);
        Files.writeString(new File(swapsDir, "identity").toPath(), "damaged", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SwapIdentity.current(swapsDir));
    }

    @Test
    void retiringMovesTheSeedsOfFinishedIdentitiesAsideAndRecoveryNoLongerNeedsThem() throws Exception {
        SwapIdentity old = SwapIdentity.current(swapsDir);
        database(old);
        SwapIdentity unread = SwapIdentity.renew(swapsDir);
        database(unread);
        SwapIdentity current = SwapIdentity.renew(swapsDir);
        database(current);

        //Only the folders named as read and finished; the current one never, even if named
        SwapIdentity.retire(swapsDir, current.getDataDir(), java.util.Set.of(old.getDataDir().getName(), current.getDataDir().getName()));
        assertFalse(new File(old.getDataDir(), SwapIdentity.SEED_COPY).exists(), "the old seed is out of recovery's way");
        assertEquals(Files.readString(new File(unread.getDataDir(), SwapIdentity.SEED_COPY).toPath()).trim(),
                com.sparrowwallet.drongo.Utils.bytesToHex(unread.getSeed()), "a folder not confirmed finished keeps its seed");
        assertFalse(new File(unread.getDataDir(), SwapIdentity.RETIRED).exists());
        assertEquals(com.sparrowwallet.drongo.Utils.bytesToHex(old.getSeed()),
                Files.readString(new File(old.getDataDir(), SwapIdentity.RETIRED_SEED).toPath()).trim(), "the retired seed is kept, not deleted");
        assertTrue(new File(old.getDataDir(), SwapIdentity.RETIRED).isFile());
        assertTrue(new File(old.getDataDir(), "sqlite").isFile(), "the history stays");
        assertTrue(new File(current.getDataDir(), SwapIdentity.SEED_COPY).isFile(), "the current identity keeps its seed");

        //A retired identity's missing seed is no reason to refuse, and the current one is still recovered
        Files.writeString(new File(swapsDir, "identity").toPath(), "damaged", StandardCharsets.UTF_8);
        assertArrayEquals(current.getSeed(), SwapIdentity.current(swapsDir).getSeed());
        //Even with no marker: only retired swap data is left, so a new identity is fine
        assertTrue(new File(current.getDataDir(), "sqlite").delete());
        assertTrue(new File(unread.getDataDir(), "sqlite").delete());
        assertTrue(new File(swapsDir, SwapIdentity.FOLDER).delete());
        Files.writeString(new File(swapsDir, "identity").toPath(), "damaged", StandardCharsets.UTF_8);
        assertEquals(32, SwapIdentity.current(swapsDir).getSeed().length);
    }

    @Test
    void theIdentityFilesAreReadableByTheirOwnerOnly() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        java.nio.file.attribute.PosixFileAttributeView posix = Files.getFileAttributeView(swapsDir.toPath(), java.nio.file.attribute.PosixFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(posix != null, "POSIX permissions only; Windows gets an access list");
        for(File file : new File[] {new File(swapsDir, "identity"), new File(swapsDir, SwapIdentity.FOLDER), new File(identity.getDataDir(), SwapIdentity.SEED_COPY)}) {
            assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file.toPath()), file.getName());
        }
        assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(identity.getDataDir().toPath()));
    }

    @Test
    void filesWrittenByOlderVersionsAreProtectedOnTheNextStart() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        java.nio.file.attribute.PosixFileAttributeView posix = Files.getFileAttributeView(swapsDir.toPath(), java.nio.file.attribute.PosixFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(posix != null, "POSIX permissions only; Windows gets an access list");
        File seed = new File(identity.getDataDir(), SwapIdentity.SEED_COPY);
        File leftover = new File(identity.getDataDir(), SwapIdentity.SEED_COPY + ".tmp");
        Files.writeString(leftover.toPath(), "00".repeat(32), StandardCharsets.UTF_8);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> open = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");
        for(File file : new File[] {new File(swapsDir, "identity"), new File(swapsDir, SwapIdentity.FOLDER), seed}) {
            Files.setPosixFilePermissions(file.toPath(), open);
        }
        Files.setPosixFilePermissions(identity.getDataDir().toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

        SwapIdentity.current(swapsDir);
        for(File file : new File[] {new File(swapsDir, "identity"), new File(swapsDir, SwapIdentity.FOLDER), seed}) {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()), file.getName());
        }
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(identity.getDataDir().toPath()));
        assertFalse(leftover.exists(), "a leftover temporary seed is removed");
    }

    @Test
    void aFoldersAccessListEntryIsInheritedByWhatIsCreatedInIt() throws Exception {
        java.nio.file.attribute.UserPrincipal owner = Files.getOwner(swapsDir.toPath());
        assertEquals(java.util.Set.of(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT, java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT),
                SwapIdentity.ownerOnlyEntry(owner, true).flags());
        assertTrue(SwapIdentity.ownerOnlyEntry(owner, false).flags().isEmpty());
    }

    @Test
    void icaclsIsOnlyTakenFromSystem32ByItsFullPath() throws Exception {
        //No Windows folder here: nothing is found, and nothing is looked up by name
        assertNull(SwapIdentity.icaclsPath("C:\\Windows"));
        assertNull(SwapIdentity.icaclsPath("relative\\folder"));
        assertNull(SwapIdentity.icaclsPath(null));
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

    private static java.nio.file.attribute.UserPrincipal principal(String name) {
        return new java.nio.file.attribute.UserPrincipal() {
            @Override public String getName() { return name; }
            @Override public boolean equals(Object o) { return o instanceof java.nio.file.attribute.UserPrincipal p && p.getName().equals(name); }
            @Override public int hashCode() { return name.hashCode(); }
        };
    }

    @Test
    void onWindowsTheAccountRunningAshigaruIsGrantedEvenWhenAdministratorsOwnTheFile() {
        var user = principal("PC\\alice");
        var admins = principal("BUILTIN\\Administrators");
        var entries = SwapIdentity.windowsEntries(admins, user, true);
        assertEquals(2, entries.size());
        assertEquals(user, entries.get(0).principal(), "the account running Ashigaru comes first");
        assertEquals(admins, entries.get(1).principal());
        assertTrue(entries.stream().allMatch(e -> e.flags().contains(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT)));

        var own = SwapIdentity.windowsEntries(principal("PC\\alice"), user, false);
        assertEquals(1, own.size(), "no duplicate when the account owns the file");
        assertTrue(own.get(0).flags().isEmpty(), "a file's entry is not inherited");
    }

    @Test
    void aFileThatCannotBeReadIsToldApartFromOneThatIsNotASeed() throws Exception {
        File unreadable = new File(swapsDir, "unreadable");
        assertTrue(unreadable.mkdirs());
        SwapIdentity.SeedRead read = SwapIdentity.readSeedFile(unreadable);
        assertNull(read.seed());
        assertTrue(read.unreadable(), read.error());

        File garbage = new File(swapsDir, "garbage");
        Files.writeString(garbage.toPath(), "not hex", StandardCharsets.UTF_8);
        SwapIdentity.SeedRead damaged = SwapIdentity.readSeedFile(garbage);
        assertNull(damaged.seed());
        assertFalse(damaged.unreadable());

        SwapIdentity.SeedRead missing = SwapIdentity.readSeedFile(new File(swapsDir, "missing"));
        assertFalse(missing.unreadable());
    }

    @Test
    void anIdentityFileThatCannotBeReadIsNotCalledDamagedAndNothingIsReplaced() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        database(identity);
        File file = new File(swapsDir, "identity");
        String seedHex = Files.readString(file.toPath());
        //A path that exists but cannot be read as a file; root can read anything chmod would hide
        assertTrue(file.delete());
        assertTrue(file.mkdir());

        IOException refused = assertThrows(IOException.class, () -> SwapIdentity.current(swapsDir));
        assertTrue(refused.getMessage().contains("can't read its swap identity"), refused.getMessage());
        assertFalse(refused.getMessage().contains("damaged"), refused.getMessage());
        assertTrue(file.isDirectory(), "nothing was replaced");
        assertEquals(seedHex, Files.readString(new File(identity.getDataDir(), SwapIdentity.SEED_COPY).toPath()), "the seed copy is untouched");
    }

    @Test
    void aSeedCopyThatCannotBeReadIsReportedTheSameWay() throws Exception {
        SwapIdentity identity = SwapIdentity.current(swapsDir);
        database(identity);
        assertTrue(new File(swapsDir, "identity").delete());
        File copy = new File(identity.getDataDir(), SwapIdentity.SEED_COPY);
        assertTrue(copy.delete());
        assertTrue(copy.mkdir());

        IOException refused = assertThrows(IOException.class, () -> SwapIdentity.current(swapsDir));
        assertTrue(refused.getMessage().contains("can't read its swap identity"), refused.getMessage());
        assertFalse(new File(swapsDir, "identity").exists(), "no new identity was taken");
    }
}
