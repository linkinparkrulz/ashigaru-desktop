package com.sparrowwallet.sparrow.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Decides which wallets to reopen on launch. The recent wallet list holds the master wallets that were open when
 * Ashigaru last ran, so reopening them resumes mixing after a restart.
 */
public final class WalletReopen {
    private WalletReopen() {
    }

    /**
     * @param recentWalletFiles the recorded open wallets, most relevant first (may be null)
     * @param loadRecentWallets the "Load recent wallets" setting
     * @param exists            whether a wallet file is still present
     * @return the wallets to reopen, in their recorded order, without duplicates or missing files
     */
    public static List<File> walletsToReopen(List<File> recentWalletFiles, boolean loadRecentWallets, Predicate<File> exists) {
        if(!loadRecentWallets || recentWalletFiles == null) {
            return Collections.emptyList();
        }

        LinkedHashSet<File> toReopen = new LinkedHashSet<>();
        recentWalletFiles.stream().filter(Objects::nonNull).filter(exists).forEach(toReopen::add);
        return new ArrayList<>(toReopen);
    }
}
