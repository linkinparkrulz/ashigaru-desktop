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
    public void stopIsAvailableWhileMixingCoinsHere() {
        assertFalse(AshigaruWalletController.startMixDisabled(true, false, true, true));
    }

    @Test
    public void whirlpoolRunningWithNothingHereIsGreyedAndSaysSo() {
        //Whirlpool starts on its own once connected: an empty Premix or Postmix offers no Stop, and says Whirlpool is on
        assertTrue(AshigaruWalletController.startMixDisabled(true, false, true, false));
        assertEquals("Whirlpool is on and will mix coins as they arrive in Premix.",
                AshigaruWalletController.startMixDisabledReason(true, false, true, false, false));
        assertEquals("Whirlpool is on and will remix mixed coins as they arrive in Postmix.",
                AshigaruWalletController.startMixDisabledReason(true, false, true, false, true));
    }

    @Test
    public void offlineOrSyncingWaits() {
        assertTrue(AshigaruWalletController.startMixDisabled(false, false, false, true));
        assertTrue(AshigaruWalletController.startMixDisabled(true, true, false, true));
    }
}
