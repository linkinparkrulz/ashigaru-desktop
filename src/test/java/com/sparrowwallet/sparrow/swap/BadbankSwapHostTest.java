package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.P2WPKHAddress;
import com.sparrowwallet.drongo.address.P2WSHAddress;
import com.sparrowwallet.drongo.protocol.Transaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BadbankSwapHostTest {
    private static final Address LOCK = new P2WSHAddress(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
    private static final Address CHANGE = new P2WPKHAddress(new byte[20]);

    private static Transaction lock(long... changeAmounts) {
        Transaction transaction = new Transaction();
        transaction.addOutput(50_000, LOCK);
        for(long change : changeAmounts) {
            transaction.addOutput(change, CHANGE);
        }
        return transaction;
    }

    @Test
    void lockWithChangeIsAccepted() {
        BadbankSwapHost.checkLockTransaction(lock(12_345), LOCK, 50_000);
        BadbankSwapHost.checkLockTransaction(lock(), LOCK, 50_000);
    }

    @Test
    void extraOutputIsRefused() {
        assertThrows(IllegalStateException.class, () -> BadbankSwapHost.checkLockTransaction(lock(1_000, 2_000), LOCK, 50_000));
    }

    @Test
    void wrongAmountIsRefused() {
        assertThrows(IllegalStateException.class, () -> BadbankSwapHost.checkLockTransaction(lock(12_345), LOCK, 50_001));
    }

    @Test
    void missingOrDoubledLockIsRefused() {
        Transaction none = new Transaction();
        none.addOutput(50_000, CHANGE);
        assertThrows(IllegalStateException.class, () -> BadbankSwapHost.checkLockTransaction(none, LOCK, 50_000));

        Transaction twice = lock();
        twice.addOutput(50_000, LOCK);
        assertThrows(IllegalStateException.class, () -> BadbankSwapHost.checkLockTransaction(twice, LOCK, 50_000));
    }

    @Test
    void amountsFormat() {
        assertEquals("0.00050000", BadbankSwapHost.btc(50_000));
        assertEquals("1.500000000000", BadbankSwapHost.xmr(1_500_000_000_000L));
    }
}
