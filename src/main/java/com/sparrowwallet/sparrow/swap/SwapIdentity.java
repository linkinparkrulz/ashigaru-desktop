package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.List;

/**
 * The swap engine's identity: how makers see this app, and the seed of the engine's own keys. It
 * belongs to the app, not to any wallet, and is replaced whenever no swap is unfinished, so makers
 * cannot link one swap to the next.
 *
 * <p>The engine derives each swap's Bitcoin and Monero secret keys from this seed and the swap's id, and
 * keeps the swap's records in a database in the identity's own directory. An unfinished swap therefore needs
 * both to complete or be refunded: lose the seed and the BTC locked in it cannot be reclaimed, and the maker
 * can take it. So the seed is also kept inside its own directory ({@value #SEED_COPY}), the directory is never deleted,
 * and the name of the current directory is written beside the identity file ({@value #FOLDER}).
 *
 * <p>If the {@value #CURRENT} file is damaged or missing, exactly the identity named in {@value #FOLDER} is recovered from its own
 * seed copy, never "the newest one": that could put an older identity in charge of the swaps of a newer one. If that cannot be done and
 * any swap data is left that is not marked {@value #RETIRED}, starting is refused rather than taking a new identity over those swaps.
 *
 * <p>An identity whose swaps were all read from its database and are all finished is retired: its seed is moved aside to
 * {@value #RETIRED_SEED} (nothing should need it, but it is never deleted), the data stays, and a {@value #RETIRED} marker tells
 * recovery that the missing seed is intended. An identity whose database could not be read is never retired. Every file here is
 * readable by its owner only.
 */
public final class SwapIdentity {
    private static final Logger log = LoggerFactory.getLogger(SwapIdentity.class);
    private static final String CURRENT = "identity";
    /** The name of the current identity's directory, beside the identity file */
    static final String FOLDER = "identity.folder";
    /** The seed, kept in the identity's own data directory */
    static final String SEED_COPY = "seed";
    /** Marks a directory whose swaps are all finished and whose seed was moved aside on purpose */
    static final String RETIRED = "retired";
    /** Where a retired identity's seed is kept: out of recovery's way, but not deleted */
    static final String RETIRED_SEED = "seed.retired";
    private static final String DATABASE = "sqlite";

    private final byte[] seed;
    private final File dataDir;
    private final boolean recovered;

    private SwapIdentity(byte[] seed, File swapsDir, boolean recovered) {
        this.seed = seed;
        this.dataDir = new File(swapsDir, directoryName(seed));
        this.recovered = recovered;
    }

    public byte[] getSeed() {
        return seed.clone();
    }

    /** The engine's database and Monero wallet for this identity */
    public File getDataDir() {
        return dataDir;
    }

    /** True when the identity file was damaged or missing and this identity was recovered from its own folder. A recovered identity is not replaced in the same run. */
    public boolean wasRecovered() {
        return recovered;
    }

    /**
     * The identity in use, creating the first one. A damaged or missing identity file is recovered from the seed kept with the swap
     * data of the identity named beside it; with swap data and no way to recover, it refuses instead of taking a new identity.
     */
    public static SwapIdentity current(File swapsDir) throws IOException {
        File file = new File(swapsDir, CURRENT);
        if(file.exists()) {
            byte[] seed = readSeed(file);
            if(seed != null) {
                SwapIdentity identity = new SwapIdentity(seed, swapsDir, false);
                identity.keepSeedWithData();
                writeFolder(swapsDir, identity.dataDir.getName());
                return identity;
            }
            log.warn("Swap identity file is damaged");
        }

        String folder = readFolder(swapsDir);
        if(folder != null) {
            File dir = new File(swapsDir, folder);
            byte[] seed = readSeed(new File(dir, SEED_COPY));
            if(seed != null && directoryName(seed).equals(folder)) {
                log.warn("Swap identity recovered from " + folder);
                SwapIdentity identity = new SwapIdentity(seed, swapsDir, true);
                writeCurrent(swapsDir, seed);
                return identity;
            }
        }
        if(hasSwapData(swapsDir)) {
            throw new IOException("The swap identity file (" + file.getAbsolutePath() + ") is missing or damaged, and the seed of the swaps in "
                    + swapsDir.getAbsolutePath() + " could not be recovered. Unfinished swaps may need it to be refunded, so Ashigaru will not take a new "
                    + "identity over them. Restore the swaps folder from a backup.");
        }
        return renew(swapsDir);
    }

    /** Replaces the identity in use. The old one's directory, and any swap history in it, stays. */
    public static SwapIdentity renew(File swapsDir) throws IOException {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        SwapIdentity identity = new SwapIdentity(seed, swapsDir, false);
        //The copy first: if the identity file is then written and the process dies, the seed is still with its data
        identity.keepSeedWithData();
        writeCurrent(swapsDir, seed);
        writeFolder(swapsDir, identity.dataDir.getName());
        return identity;
    }

    /**
     * Retires the identities named in {@code finishedFolders}, never the current one: each seed is moved aside to {@value #RETIRED_SEED}
     * and a marker says so. Pass only folders whose swap database was read and whose swaps are all finished: a folder whose database
     * could not be read must keep its seed, since nothing can complete or refund a swap without it. Folders not named are left alone.
     */
    public static void retire(File swapsDir, File current, java.util.Set<String> finishedFolders) {
        File[] dirs = swapsDir.listFiles(File::isDirectory);
        if(dirs == null) {
            return;
        }
        for(File dir : dirs) {
            if(dir.equals(current) || !finishedFolders.contains(dir.getName())) {
                continue;
            }
            File copy = new File(dir, SEED_COPY);
            if(copy.exists()) {
                try {
                    //The marker first: if the move then fails, the seed is still where recovery looks for it
                    Files.writeString(new File(dir, RETIRED).toPath(), "Every swap of this identity was finished; its seed was moved to " + RETIRED_SEED + ".", StandardCharsets.UTF_8);
                    restrictToOwner(new File(dir, RETIRED).toPath(), false);
                    Files.move(copy.toPath(), new File(dir, RETIRED_SEED).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    restrictToOwner(new File(dir, RETIRED_SEED).toPath(), false);
                } catch(IOException e) {
                    log.warn("Could not retire swap identity " + dir.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    private static byte[] readSeed(File file) {
        try {
            byte[] seed = Utils.hexToBytes(Files.readString(file.toPath(), StandardCharsets.UTF_8).trim());
            return seed.length == 32 ? seed : null;
        } catch(Exception e) {
            return null;
        }
    }

    private static String readFolder(File swapsDir) {
        try {
            String name = Files.readString(new File(swapsDir, FOLDER).toPath(), StandardCharsets.UTF_8).trim();
            //A name only: nothing that points out of the swaps folder
            return name.matches("[0-9a-f]{16}") ? name : null;
        } catch(IOException e) {
            return null;
        }
    }

    private static void writeCurrent(File swapsDir, byte[] seed) throws IOException {
        writeAtomically(new File(swapsDir, CURRENT), Utils.bytesToHex(seed));
    }

    private static void writeFolder(File swapsDir, String name) throws IOException {
        if(!name.equals(readFolder(swapsDir))) {
            writeAtomically(new File(swapsDir, FOLDER), name);
        }
    }

    /** Writes the seed into this identity's own directory, if it is not already there */
    private void keepSeedWithData() throws IOException {
        File copy = new File(dataDir, SEED_COPY);
        if(copy.isFile() && java.util.Arrays.equals(readSeed(copy), seed)) {
            return;
        }
        createOwnerOnlyDirectory(dataDir);
        writeAtomically(copy, Utils.bytesToHex(seed));
    }

    private static void writeAtomically(File target, String text) throws IOException {
        createOwnerOnlyDirectory(target.getParentFile());
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.writeString(temp.toPath(), text, StandardCharsets.UTF_8);
        restrictToOwner(temp.toPath(), false);
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void createOwnerOnlyDirectory(File dir) throws IOException {
        Files.createDirectories(dir.toPath());
        restrictToOwner(dir.toPath(), true);
    }

    /**
     * Makes a file or folder readable by its owner only: permissions 600 or 700 where the file system has them, and on Windows an access list with
     * the owner as its only entry. A file system with neither is left to its folder's own protection.
     */
    static void restrictToOwner(java.nio.file.Path path, boolean directory) {
        try {
            java.nio.file.attribute.PosixFileAttributeView posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
            if(posix != null) {
                java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = new java.util.HashSet<>(java.util.List.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                if(directory) {
                    permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                }
                posix.setPermissions(permissions);
                return;
            }
            java.nio.file.attribute.AclFileAttributeView acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
            if(acl != null) {
                java.nio.file.attribute.UserPrincipal owner = acl.getOwner();
                java.nio.file.attribute.AclEntry entry = java.nio.file.attribute.AclEntry.newBuilder()
                        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(java.nio.file.attribute.AclEntryPermission.values())
                        .build();
                acl.setAcl(List.of(entry));
            }
        } catch(IOException | UnsupportedOperationException | SecurityException e) {
            log.debug("Could not restrict " + path + " to its owner: " + e.getMessage());
        }
    }

    /** Whether any identity that has not been retired holds a swap database */
    static boolean hasSwapData(File swapsDir) {
        File[] dirs = swapsDir.listFiles(dir -> dir.isDirectory() && new File(dir, DATABASE).isFile() && !new File(dir, RETIRED).isFile());
        return dirs != null && dirs.length > 0;
    }

    /**
     * The data directories of earlier identities that hold a swap database. Each identity keeps its
     * own, so these are what the list of past swaps is read from.
     */
    public static List<File> pastDataDirs(File swapsDir, File current) {
        File[] dirs = swapsDir.listFiles(dir -> dir.isDirectory() && !dir.equals(current) && new File(dir, "sqlite").isFile());
        return dirs == null ? List.of() : List.of(dirs);
    }

    /** A directory name for a seed, without revealing the seed */
    static String directoryName(byte[] seed) {
        return Utils.bytesToHex(Sha256Hash.hash(seed)).substring(0, 16);
    }
}
