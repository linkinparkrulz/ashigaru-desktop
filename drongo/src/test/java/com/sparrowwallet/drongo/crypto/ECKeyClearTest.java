package com.sparrowwallet.drongo.crypto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

public class ECKeyClearTest {
    @Test
    public void clearDropsPrivateKey() {
        ECKey key = ECKey.fromPrivate(BigInteger.TEN);
        byte[] pubKey = key.getPubKey();
        key.clear();
        Assertions.assertFalse(key.hasPrivKey());
        Assertions.assertThrows(ECKey.MissingPrivateKeyException.class, key::getPrivKeyBytes);
        Assertions.assertArrayEquals(pubKey, key.getPubKey());
        key.clear();
    }
}
