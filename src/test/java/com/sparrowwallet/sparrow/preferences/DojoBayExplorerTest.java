package com.sparrowwallet.sparrow.preferences;

import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.net.BlockExplorer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Choosing a Dojo from Dojo Bay uses its block explorer, unless explorer links were turned off */
class DojoBayExplorerTest {
    private static final Server DOJO = new Server("http://dojoexplorerabc.onion", "yellow");

    @Test
    void theDojosExplorerIsUsed() {
        assertEquals(DOJO, ServerPreferencesController.explorerToUse(BlockExplorer.MEMPOOL_SPACE.getServer(), DOJO));
        assertEquals(DOJO, ServerPreferencesController.explorerToUse(null, DOJO));
    }

    @Test
    void explorerLinksTurnedOffStayOff() {
        Server none = BlockExplorer.NONE.getServer();
        assertEquals(none, ServerPreferencesController.explorerToUse(none, DOJO));
    }
}
