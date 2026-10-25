package com.sparrowwallet.sparrow.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwapsToolControllerTest {
    //Monero's donation address, a standard mainnet address
    private static final String MAINNET = "44AFFq5kSiGBoZ4NMDwYtN18obc8AemS33DBLWs3H7otXft3XjrpDtQGv7SqSsaBYBb98uNbr2VBBEt7f2wfn3RVGQBEP3A";

    @Test
    void mainnetAddressesAreAccepted() {
        assertTrue(SwapsToolController.isMainnetMoneroAddress(MAINNET));
        assertTrue(SwapsToolController.isMainnetMoneroAddress("8" + MAINNET.substring(1)));
    }

    @Test
    void otherAddressesAreRefused() {
        assertFalse(SwapsToolController.isMainnetMoneroAddress("5" + MAINNET.substring(1)));
        assertFalse(SwapsToolController.isMainnetMoneroAddress(MAINNET.substring(1)));
        assertFalse(SwapsToolController.isMainnetMoneroAddress(MAINNET.substring(0, 94) + "0"));
        assertFalse(SwapsToolController.isMainnetMoneroAddress(""));
    }
}
