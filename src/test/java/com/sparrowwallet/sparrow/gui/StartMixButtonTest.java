package com.sparrowwallet.sparrow.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class StartMixButtonTest {
    @Test
    public void anAccountWithNothingToMixCannotStart() {
        assertTrue(AshigaruWalletController.startMixDisabled(true, false, false, false));
        assertEquals("No coins in Premix to mix yet.", AshigaruWalletController.startMixDisabledReason(true, false, false, false, false));
        assertEquals("No mixed coins in Postmix yet.", AshigaruWalletController.startMixDisabledReason(true, false, false, false, true));
    }

    @Test
    public void coinsToMixAndAConnectionAreEnough() {
        //No selection is asked for: the engine mixes the whole account
        assertFalse(AshigaruWalletController.startMixDisabled(true, false, false, true));
        assertNull(AshigaruWalletController.startMixDisabledReason(true, false, false, true, true));
    }

    @Test
    public void stopStaysAvailableWhileMixingEvenWhenTheAccountEmpties() {
        assertFalse(AshigaruWalletController.startMixDisabled(true, false, true, false));
    }

    @Test
    public void offlineOrSyncingWaits() {
        assertTrue(AshigaruWalletController.startMixDisabled(false, false, false, true));
        assertTrue(AshigaruWalletController.startMixDisabled(true, true, false, true));
    }
}
