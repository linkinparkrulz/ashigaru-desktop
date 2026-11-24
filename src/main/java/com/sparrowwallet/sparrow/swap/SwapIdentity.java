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
 * readable by its owner only (on Windows, also by the account running Ashigaru when the owner is another, e.g. Administrators).
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
        protectExisting(swapsDir);
        File file = new File(swapsDir, CURRENT);
        if(file.exists()) {
            SeedRead read = readSeedFile(file);
            if(read.seed() != null) {
                SwapIdentity identity = new SwapIdentity(read.seed(), swapsDir, false);
                refuseOlderIdentity(swapsDir, file, identity.dataDir);
                identity.keepSeedWithData();
                writeFolder(swapsDir, identity.dataDir.getName());
                return identity;
            }
            if(read.unreadable()) {
                //Not damaged: there, but this account may not read it. Never replaced: the folders beside it may be unreadable too, so
                //whether swaps depend on it cannot be told
                log.warn("Swap identity file cannot be read: " + read.error());
                throw new IOException(unreadableMessage(swapsDir, file, read.error()));
            }
            log.warn("Swap identity file is damaged");
        }

        File folderFile = new File(swapsDir, FOLDER);
        if(folderFile.exists() && !Files.isReadable(folderFile.toPath())) {
            throw new IOException(unreadableMessage(swapsDir, folderFile, "access denied"));
        }
        String folder = readFolder(swapsDir);
        SeedRead copyRead = null;
        if(folder != null) {
            File dir = new File(swapsDir, folder);
            File copy = new File(dir, SEED_COPY);
            copyRead = readSeedFile(copy);
            byte[] seed = copyRead.seed();
            if(seed != null && directoryName(seed).equals(folder)) {
                log.warn("Swap identity recovered from " + folder);
                SwapIdentity identity = new SwapIdentity(seed, swapsDir, true);
                writeCurrent(swapsDir, seed);
                return identity;
            }
            if(copyRead.unreadable()) {
                log.warn("Swap identity seed copy cannot be read: " + copyRead.error());
                throw new IOException(unreadableMessage(swapsDir, copy, copyRead.error()));
            }
        }
        if(hasSwapData(swapsDir)) {
            throw new IOException("The swap identity file (" + file.getAbsolutePath() + ") is missing or damaged, and the seed of the swaps in "
                    + swapsDir.getAbsolutePath() + " could not be recovered. Unfinished swaps may need it to be refunded, so Ashigaru will not take a new "
                    + "identity over them. Restore the swaps folder from a backup.");
        }
        return renew(swapsDir);
    }

    /**
     * What to tell the person when a swap identity file is there but cannot be read: not damage, permissions. On Windows this happens when
     * Ashigaru once ran as administrator and its files came to belong to the Administrators group.
     */
    static String unreadableMessage(File swapsDir, File file, String error) {
        String start = "Ashigaru can't read its swap identity (" + file.getAbsolutePath() + ": " + error + "). The files are still there and nothing was "
                + "changed, but this account isn't allowed to read them. ";
        if(isWindows()) {
            String domain = System.getenv("USERDOMAIN");
            String user = (domain != null && !domain.isBlank() ? domain + "\\" : "") + System.getProperty("user.name", "%USERNAME%");
            return start + "This can happen after Ashigaru was run with \"Run as administrator\". To fix it, start Ashigaru once with \"Run as "
                    + "administrator\" (it then gives this account access again), or run this in a Command Prompt opened as administrator: icacls \""
                    + swapsDir.getAbsolutePath() + "\" /grant \"" + user + ":(OI)(CI)F\" /T";
        }
        return start + "Check that " + swapsDir.getAbsolutePath() + " and everything in it belong to the account running Ashigaru.";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    /**
     * Refuses an identity file that names a different identity from the one last in use ({@value #FOLDER}) when that one still has swap data
     * and the file's own identity has been used before (it has a database, or was retired): an identity file restored from a backup, which
     * would put an older identity in charge and leave the newer one's unfinished swaps with nothing to complete or refund them.
     *
     * <p>A file whose identity has no data yet is a renewal that was interrupted between writing the identity file and its folder name, and is
     * taken as it always was.
     */
    private static void refuseOlderIdentity(File swapsDir, File file, File fileDir) throws IOException {
        String last = readFolder(swapsDir);
        if(last == null || last.equals(fileDir.getName())) {
            return;
        }
        File lastDir = new File(swapsDir, last);
        boolean lastHasSwaps = new File(lastDir, DATABASE).isFile() && !new File(lastDir, RETIRED).isFile();
        boolean fileUsedBefore = new File(fileDir, DATABASE).isFile() || new File(fileDir, RETIRED).isFile();
        if(lastHasSwaps && fileUsedBefore) {
            throw new IOException("The swap identity file (" + file.getAbsolutePath() + ") names an earlier identity (" + fileDir.getName()
                    + ") than the one last in use (" + last + "), which has swap data. It may have been restored from a backup. Ashigaru will not put the "
                    + "earlier identity in charge: the swaps of " + last + " could then not be completed or refunded. To go on with " + last
                    + ", move the identity file out of the swaps folder and start again; it is recovered from " + last + "'s own seed.");
        }
    }

    /**
     * Puts an earlier identity back in charge: the one whose folder holds unfinished swaps, e.g. after a backup restored both the identity file and
     * its folder name to an older identity. Only from that folder's own seed, and only when the seed matches the folder and it was not retired.
     * The identity it replaces keeps its folder and its swaps. Returns null when that cannot be done.
     */
    public static SwapIdentity adopt(File swapsDir, String folder) throws IOException {
        if(folder == null || !folder.matches("[0-9a-f]{16}")) {
            return null;
        }
        File dir = new File(swapsDir, folder);
        if(new File(dir, RETIRED).isFile()) {
            return null;
        }
        byte[] seed = readSeed(new File(dir, SEED_COPY));
        if(seed == null || !directoryName(seed).equals(folder)) {
            return null;
        }
        writeCurrent(swapsDir, seed);
        writeFolder(swapsDir, folder);
        log.warn("Swap identity " + folder + " put back in charge: its swaps are unfinished");
        //Counted as recovered, so it is not replaced in the same run
        return new SwapIdentity(seed, swapsDir, true);
    }

    /** Replaces the identity in use. The old one's directory, and any swap history in it, stays. */
    public static SwapIdentity renew(File swapsDir) throws IOException {
        protectExisting(swapsDir);
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

    /** A seed file as read: the seed; or why not, telling a file that cannot be read (permissions) from one that is not a seed */
    record SeedRead(byte[] seed, boolean unreadable, String error) {}

    static SeedRead readSeedFile(File file) {
        String text;
        try {
            text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch(java.nio.file.NoSuchFileException e) {
            return new SeedRead(null, false, "missing");
        } catch(IOException | SecurityException e) {
            //Never the content: only what kind of failure it was
            return new SeedRead(null, true, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        try {
            byte[] seed = Utils.hexToBytes(text.trim());
            return seed.length == 32 ? new SeedRead(seed, false, null) : new SeedRead(null, false, "not a seed");
        } catch(RuntimeException e) {
            return new SeedRead(null, false, "not a seed");
        }
    }

    private static byte[] readSeed(File file) {
        return readSeedFile(file).seed();
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

    /**
     * Protects what is already in the swaps folder, on every start: versions before the files were made owner-only wrote the identity file, the
     * seed copies and the identity folders (with the engine's database and Monero wallet) with the default permissions, and nothing rewrites a
     * file that is already right. Also removes the temporary files a write that was interrupted left behind, which can hold a seed.
     */
    static void protectExisting(File swapsDir) {
        if(!swapsDir.isDirectory()) {
            return;
        }
        restrictToOwner(swapsDir.toPath(), true);
        File[] entries = swapsDir.listFiles();
        if(entries == null) {
            return;
        }
        for(File entry : entries) {
            if(entry.isDirectory()) {
                restrictToOwner(entry.toPath(), true);
                for(String name : new String[] {SEED_COPY, RETIRED_SEED, RETIRED}) {
                    File inside = new File(entry, name);
                    if(inside.isFile()) {
                        restrictToOwner(inside.toPath(), false);
                    }
                }
                deleteLeftoverTemp(new File(entry, SEED_COPY + ".tmp"));
            } else if(entry.getName().equals(CURRENT + ".tmp") || entry.getName().equals(FOLDER + ".tmp")) {
                //Only this class's own: another file's temporary copy may be being written right now
                deleteLeftoverTemp(entry);
            } else if(entry.isFile()) {
                restrictToOwner(entry.toPath(), false);
            }
        }
    }

    private static void deleteLeftoverTemp(File temp) {
        try {
            if(Files.deleteIfExists(temp.toPath())) {
                log.info("Removed a leftover temporary file from the swaps folder");
            }
        } catch(IOException e) {
            log.warn("Could not remove a leftover temporary file from the swaps folder: " + e.getMessage());
        }
    }

    private static void createOwnerOnlyDirectory(File dir) throws IOException {
        Files.createDirectories(dir.toPath());
        restrictToOwner(dir.toPath(), true);
    }

    /**
     * Makes a file or folder readable by its owner only: permissions 600 or 700 where the file system has them. On Windows, an access list with
     * the account running Ashigaru and the file's owner, if that is someone else, as its only entries: a file created while Ashigaru ran as
     * administrator belongs to the Administrators group, which a normal start of the same account cannot use, so the owner alone would lock
     * that account out of its own swaps. A folder's entries are inherited by what is created in it later (the engine's database and wallet
     * files), and the folder no longer inherits from its parent, so a change to the parent's permissions does not open it again. If the
     * account running Ashigaru cannot be named, or could not use the path afterwards, the access list is left or put back as it was. A file
     * system with neither is left to its folder's own protection.
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
                java.nio.file.attribute.UserPrincipal user = currentUser(path);
                if(user == null) {
                    log.debug("The account running Ashigaru could not be named; " + path + " keeps its access list");
                    return;
                }
                List<java.nio.file.attribute.AclEntry> before = acl.getAcl();
                List<java.nio.file.attribute.AclEntry> entries = windowsEntries(acl.getOwner(), user, directory);
                if(entries.equals(before)) {
                    return;
                }
                acl.setAcl(entries);
                if(!usable(path, directory)) {
                    acl.setAcl(before);
                    log.warn("Left the permissions of " + path + " as they were: this account could not have used it afterwards");
                    return;
                }
                stopInheriting(path);
                if(!usable(path, directory)) {
                    acl.setAcl(before);
                    icacls(path, "/inheritance:e");
                    log.warn("Put back the permissions of " + path + ": this account could not use it afterwards");
                }
            }
        } catch(IOException | UnsupportedOperationException | SecurityException e) {
            log.debug("Could not restrict " + path + " to its owner: " + e.getMessage());
        }
    }

    /** The account this process runs as, as the file system names it; with its domain first, as Windows knows it. Null when it cannot be found. */
    private static java.nio.file.attribute.UserPrincipal currentUser(java.nio.file.Path path) {
        java.nio.file.attribute.UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();
        String name = System.getProperty("user.name");
        if(name == null || name.isBlank()) {
            return null;
        }
        String domain = System.getenv("USERDOMAIN");
        if(domain != null && !domain.isBlank()) {
            try {
                return lookup.lookupPrincipalByName(domain + "\\" + name);
            } catch(IOException | UnsupportedOperationException e) {
                //tried by name alone below
            }
        }
        try {
            return lookup.lookupPrincipalByName(name);
        } catch(IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static boolean usable(java.nio.file.Path path, boolean directory) {
        return Files.isReadable(path) && (!directory || Files.isWritable(path));
    }

    /** The access list for a Windows file or folder: the account running Ashigaru, and the owner if that is a different account or group */
    static List<java.nio.file.attribute.AclEntry> windowsEntries(java.nio.file.attribute.UserPrincipal owner, java.nio.file.attribute.UserPrincipal user,
                                                                 boolean directory) {
        if(owner == null || owner.equals(user)) {
            return List.of(ownerOnlyEntry(user, directory));
        }
        return List.of(ownerOnlyEntry(user, directory), ownerOnlyEntry(owner, directory));
    }

    /** One access list entry: everything for this account, and for a folder, inherited by the files and folders created in it */
    static java.nio.file.attribute.AclEntry ownerOnlyEntry(java.nio.file.attribute.UserPrincipal owner, boolean directory) {
        java.nio.file.attribute.AclEntry.Builder builder = java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(java.nio.file.attribute.AclEntryPermission.values());
        if(directory) {
            builder.setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT, java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT);
        }
        return builder.build();
    }

    /**
     * Java can set an access list but not mark it protected, so a later change to the parent's inheritable entries could be added to it again.
     * On Windows, icacls marks it protected (inherited entries removed, the entries set above kept). Best effort: the list set above stands either way.
     */
    private static void stopInheriting(java.nio.file.Path path) {
        icacls(path, "/inheritance:r");
    }

    private static void icacls(java.nio.file.Path path, String change) {
        if(!isWindows()) {
            return;
        }
        File icacls = icaclsPath(System.getenv("SystemRoot"));
        if(icacls == null) {
            log.debug("icacls not found in System32; " + path + " keeps the access list set through Java");
            return;
        }
        try {
            Process process = new ProcessBuilder(icacls.getAbsolutePath(), path.toString(), change, "/Q").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("icacls did not finish for " + path);
            } else if(process.exitValue() != 0) {
                log.debug("icacls " + change + " failed for " + path + " (exit " + process.exitValue() + ")");
            }
        } catch(IOException e) {
            log.debug("Could not run icacls for " + path + ": " + e.getMessage());
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * icacls from System32 by its full path, never by name: a name is looked up in the working directory and on PATH first, where any icacls.exe
     * would run. SystemRoot names the Windows folder; when it is unset or odd, C:\Windows is used. Null when it is not there.
     */
    static File icaclsPath(String systemRoot) {
        String root = systemRoot != null && systemRoot.matches("[A-Za-z]:\\\\[^;\"*?<>|]*") ? systemRoot : "C:\\Windows";
        File icacls = new File(new File(root, "System32"), "icacls.exe");
        return icacls.isFile() ? icacls : null;
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
