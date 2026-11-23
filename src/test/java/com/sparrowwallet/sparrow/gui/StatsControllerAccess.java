package com.sparrowwallet.sparrow.gui;

/** Test access to StatsController's package-private address */
public final class StatsControllerAccess {
    private StatsControllerAccess() {}

    public static String apiBase() {
        return StatsController.apiBase();
    }
}
