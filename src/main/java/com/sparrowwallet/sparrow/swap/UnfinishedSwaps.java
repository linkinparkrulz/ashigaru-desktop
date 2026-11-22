package com.sparrowwallet.sparrow.swap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * The ids of the swaps the swap engine last said were unfinished, kept in a small file in the swaps folder, so that Ashigaru can tell the user a swap
 * still holds their BTC when the engine is not running (swaps turned off, Tor gone, a failed start) and so cannot be asked.
 * Only ids: nothing about amounts or addresses.
 */
final class UnfinishedSwaps {
    private static final Logger log = LoggerFactory.getLogger(UnfinishedSwaps.class);
    static final String FILE_NAME = "unfinished";

    private final File file;
    private final Set<String> ids = new TreeSet<>();

    /** @param swapsDir where the list is kept, or null to keep it in memory only */
    UnfinishedSwaps(File swapsDir) {
        file = swapsDir == null ? null : new File(swapsDir, FILE_NAME);
        if(file != null && file.isFile()) {
            try {
                for(String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    if(!line.isBlank()) {
                        ids.add(line.trim());
                    }
                }
            } catch(IOException e) {
                log.warn("Could not read the list of unfinished swaps: " + e.getMessage());
            }
        }
    }

    synchronized int count() {
        return ids.size();
    }

    synchronized Set<String> ids() {
        return new TreeSet<>(ids);
    }

    synchronized void add(String swapId) {
        if(swapId != null && ids.add(swapId)) {
            save();
        }
    }

    synchronized void remove(String swapId) {
        if(swapId != null && ids.remove(swapId)) {
            save();
        }
    }

    /** The engine's full list of swaps says which are unfinished: that replaces what was known */
    synchronized void replaceAll(Collection<String> unfinished) {
        Set<String> now = new TreeSet<>(unfinished);
        if(!now.equals(ids)) {
            ids.clear();
            ids.addAll(now);
            save();
        }
    }

    private void save() {
        if(file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            File temp = new File(file.getParentFile(), FILE_NAME + ".tmp");
            Files.writeString(temp.toPath(), String.join("\n", ids), StandardCharsets.UTF_8);
            SwapIdentity.restrictToOwner(temp.toPath(), false);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException e) {
            log.warn("Could not save the list of unfinished swaps: " + e.getMessage());
        }
    }
}
