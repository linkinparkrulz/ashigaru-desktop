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
 * can take it. So the seed is also kept inside its own directory ({@value #SEED_COPY}), and the directory is
 * never deleted. If the {@value #CURRENT} file is damaged or missing, the seed is recovered from there; if swap data
 * exists and no seed can be recovered, starting is refused instead of taking a new identity over the old swaps.
 */
public final class SwapIdentity {
    private static final Logger log = LoggerFactory.getLogger(SwapIdentity.class);
    private static final String CURRENT = "identity";
    /** The seed, kept in the identity's own data directory */
    static final String SEED_COPY = "seed";
    private static final String DATABASE = "sqlite";

    private final byte[] seed;
    private final File dataDir;

    private SwapIdentity(byte[] seed, File swapsDir) {
        this.seed = seed;
        this.dataDir = new File(swapsDir, directoryName(seed));
    }

    public byte[] getSeed() {
        return seed.clone();
    }

    /** The engine's database and Monero wallet for this identity */
    public File getDataDir() {
        return dataDir;
    }

    /**
     * The identity in use, creating the first one. A damaged or missing identity file is recovered from the
     * seed kept with the swap data; with swap data and no seed, it refuses instead of taking a new identity.
     */
    public static SwapIdentity current(File swapsDir) throws IOException {
        File file = new File(swapsDir, CURRENT);
        if(file.exists()) {
            byte[] seed = readSeed(file);
            if(seed != null) {
                SwapIdentity identity = new SwapIdentity(seed, swapsDir);
                identity.keepSeedWithData();
                return identity;
            }
            log.warn("Swap identity file is damaged");
        }

        SwapIdentity recovered = recover(swapsDir);
        if(recovered != null) {
            log.warn("Swap identity recovered from " + recovered.dataDir.getName());
            writeCurrent(swapsDir, recovered.seed);
            return recovered;
        }
        if(hasSwapData(swapsDir)) {
            throw new IOException("The swap identity file (" + file.getAbsolutePath() + ") is missing or damaged, and the seed of the swaps in "
                    + swapsDir.getAbsolutePath() + " could not be recovered. Unfinished swaps may need it to be refunded, so Ashigaru will not take a new "
                    + "identity over them. Restore the file from a backup, or see the swap folder's seed files.");
        }
        return renew(swapsDir);
    }

    /** Replaces the identity in use. The old one's directory, and any swap history in it, stays. */
    public static SwapIdentity renew(File swapsDir) throws IOException {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        SwapIdentity identity = new SwapIdentity(seed, swapsDir);
        //The copy first: if the identity file is then written and the process dies, the seed is still with its data
        identity.keepSeedWithData();
        writeCurrent(swapsDir, seed);
        return identity;
    }

    private static byte[] readSeed(File file) {
        try {
            byte[] seed = Utils.hexToBytes(Files.readString(file.toPath(), StandardCharsets.UTF_8).trim());
            return seed.length == 32 ? seed : null;
        } catch(Exception e) {
            return null;
        }
    }

    private static void writeCurrent(File swapsDir, byte[] seed) throws IOException {
        writeAtomically(new File(swapsDir, CURRENT), Utils.bytesToHex(seed));
    }

    /** Writes the seed into this identity's own directory, if it is not already there */
    private void keepSeedWithData() throws IOException {
        File copy = new File(dataDir, SEED_COPY);
        if(copy.isFile() && java.util.Arrays.equals(readSeed(copy), seed)) {
            return;
        }
        Files.createDirectories(dataDir.toPath());
        writeAtomically(copy, Utils.bytesToHex(seed));
    }

    private static void writeAtomically(File target, String text) throws IOException {
        Files.createDirectories(target.getParentFile().toPath());
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.writeString(temp.toPath(), text, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(temp.toPath(), java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch(UnsupportedOperationException | IOException e) {
            //Not a POSIX file system: the folder's own permissions apply
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The newest identity whose directory still holds its own seed, which has to match the directory's name */
    private static SwapIdentity recover(File swapsDir) {
        File[] dirs = swapsDir.listFiles(File::isDirectory);
        if(dirs == null) {
            return null;
        }
        SwapIdentity best = null;
        long bestTime = Long.MIN_VALUE;
        for(File dir : dirs) {
            File copy = new File(dir, SEED_COPY);
            byte[] seed = copy.isFile() ? readSeed(copy) : null;
            if(seed == null || !directoryName(seed).equals(dir.getName())) {
                continue;
            }
            File database = new File(dir, DATABASE);
            long time = database.isFile() ? database.lastModified() : copy.lastModified();
            if(time > bestTime) {
                bestTime = time;
                best = new SwapIdentity(seed, swapsDir);
            }
        }
        return best;
    }

    /** Whether any identity's directory holds a swap database */
    private static boolean hasSwapData(File swapsDir) {
        File[] dirs = swapsDir.listFiles(dir -> dir.isDirectory() && new File(dir, DATABASE).isFile());
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
