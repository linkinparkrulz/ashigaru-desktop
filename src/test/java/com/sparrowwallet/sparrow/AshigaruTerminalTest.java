package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AshigaruTerminalTest {
    @Test
    void theInstanceLockIsNotSparrowWallets() {
        //Sparrow's lock id is its package name; sharing it made Ashigaru exit silently whenever Sparrow was open
        assertFalse(AshigaruTerminal.APP_ID.startsWith("com.sparrowwallet"));
        assertTrue(AshigaruTerminal.APP_ID.contains("ashigaru"));
    }
}
