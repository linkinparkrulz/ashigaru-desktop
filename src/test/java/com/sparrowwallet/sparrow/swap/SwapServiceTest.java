package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class SwapServiceTest {
    private static SwapService newService() throws Exception {
        //Not get(): that registers the singleton for Tor events
        Constructor<SwapService> constructor = SwapService.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Wallet badbankOf(Wallet master) {
        Wallet badbank = new Wallet();
        badbank.setMasterWallet(master);
        return badbank;
    }

    private static BlockTransactionHashIndex coin() {
        return new BlockTransactionHashIndex(Sha256Hash.ZERO_HASH, 900_000, new Date(), 0L, 2, 374_120);
    }

    @Test
    void lockingAWalletForgetsItsChosenCoin() throws Exception {
        SwapService service = newService();
        Wallet master = new Wallet();
        service.chooseCoin("FTF2", badbankOf(master), coin());
        assertNotNull(service.coinProperty().get());

        service.walletUnloaded(new Wallet());
        assertNotNull(service.coinProperty().get(), "another wallet locking keeps the coin");

        service.walletUnloaded(master);
        assertNull(service.coinProperty().get());
    }

    @Test
    void walletRequestsAreRefusedWhenNoSwapIsBeingSetUp() throws Exception {
        SwapService service = newService();
        assertFalse(service.isSwapActive());
        assertTrue(service.handle("fund_lock", new com.google.gson.JsonObject()).isCompletedExceptionally());
        assertTrue(service.handle("sign", new com.google.gson.JsonObject()).isCompletedExceptionally());
    }

    @org.junit.jupiter.api.Test
    void onlyThisIdentitysSwapsMeanItWasUsed() {
        com.google.gson.JsonArray swaps = new com.google.gson.JsonArray();
        org.junit.jupiter.api.Assertions.assertFalse(SwapService.usedByThisIdentity(swaps));

        //A finished swap from an earlier identity, listed so it stays in Your swaps
        com.google.gson.JsonObject past = new com.google.gson.JsonObject();
        past.addProperty("swapId", "282210d2-18f1-4f90-a4fa-1eccbd9c2466");
        past.addProperty("past", true);
        swaps.add(past);
        org.junit.jupiter.api.Assertions.assertFalse(SwapService.usedByThisIdentity(swaps));

        com.google.gson.JsonObject current = new com.google.gson.JsonObject();
        current.addProperty("swapId", "98a303f2-39aa-43c9-aec3-2f8b2303103c");
        current.addProperty("past", false);
        swaps.add(current);
        org.junit.jupiter.api.Assertions.assertTrue(SwapService.usedByThisIdentity(swaps));
    }
}
