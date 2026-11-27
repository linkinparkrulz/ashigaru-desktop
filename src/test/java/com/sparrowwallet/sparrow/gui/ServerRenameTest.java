package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.io.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Renaming the server in use from the top bar keeps its address, so the connection is the same one */
class ServerRenameTest {
    private static final Server UNNAMED = new Server("tcp://fart9fulcrumaddressexample.onion:50001");

    @Test
    void aNameIsTrimmedAndTheAddressKept() {
        Server renamed = AshigaruMainController.renamedServer(UNNAMED, "  fart9 fulcrum ");
        assertEquals("fart9 fulcrum", renamed.getAlias());
        assertEquals(UNNAMED.getUrl(), renamed.getUrl());
        //Servers are the same server by address, so the saved entry and the connection are matched
        assertEquals(UNNAMED, renamed);
    }

    @Test
    void aBlankNameShowsTheAddress() {
        assertNull(AshigaruMainController.renamedServer(new Server(UNNAMED.getUrl(), "old"), "  ").getAlias());
    }
}
