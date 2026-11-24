package com.sparrowwallet.sparrow.io.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The one line said when a wallet schema is newer than Ashigaru's migrations (upstream Sparrow's V9) */
class NewerSchemaNoteTest {
    @Test
    void itNamesTheVersionWhatItIsAndThatTheWalletStillOpens() {
        String note = DbPersistence.newerSchemaNote("9", "Some Change", "8");
        assertTrue(note.startsWith("A wallet schema is at version 9 (Some Change), written by a newer Sparrow; Ashigaru's migrations go up to 8."), note);
        assertTrue(note.contains("The wallet opens"));
        assertFalse(DbPersistence.newerSchemaNote("9", null, "8").contains("()"));
    }
}
