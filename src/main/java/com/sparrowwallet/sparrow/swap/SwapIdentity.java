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

/**
 * The swap engine's identity: how makers see this app, and the seed of the engine's own keys. It
 * belongs to the app, not to any wallet, and is replaced whenever no swap is unfinished, so makers
 * cannot link one swap to the next.
 *
 * <p>A swap's Bitcoin and Monero keys are random and kept in the swap database next to the
 * identity, so that directory - not any wallet seed - is what an unfinished swap needs to complete
 * or be refunded. It is never deleted.
 */
public final class SwapIdentity {
    private static final Logger log = LoggerFactory.getLogger(SwapIdentity.class);
    private static final String CURRENT = "identity";

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

    /** The identity in use, creating the first one */
    public static SwapIdentity current(File swapsDir) throws IOException {
        File file = new File(swapsDir, CURRENT);
        if(file.exists()) {
            byte[] seed = Utils.hexToBytes(Files.readString(file.toPath(), StandardCharsets.UTF_8).trim());
            if(seed.length == 32) {
                return new SwapIdentity(seed, swapsDir);
            }
            log.warn("Swap identity file is damaged; starting a new identity");
        }
        return renew(swapsDir);
    }

    /** Replaces the identity in use. The old one's directory, and any swap history in it, stays. */
    public static SwapIdentity renew(File swapsDir) throws IOException {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        Files.createDirectories(swapsDir.toPath());
        File temp = new File(swapsDir, CURRENT + ".tmp");
        Files.writeString(temp.toPath(), Utils.bytesToHex(seed), StandardCharsets.UTF_8);
        Files.move(temp.toPath(), new File(swapsDir, CURRENT).toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return new SwapIdentity(seed, swapsDir);
    }

    /** A directory name for a seed, without revealing the seed */
    static String directoryName(byte[] seed) {
        return Utils.bytesToHex(Sha256Hash.hash(seed)).substring(0, 16);
    }
}
