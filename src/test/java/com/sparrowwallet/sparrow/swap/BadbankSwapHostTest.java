package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonObject;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.P2WPKHAddress;
import com.sparrowwallet.drongo.address.P2WSHAddress;
import com.sparrowwallet.drongo.crypto.ECDSASignature;
import com.sparrowwallet.drongo.crypto.ECKey;
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
    void lockFeeNeverGoesBelowWhatMakersAccept() {
        //A maker refused a 121 sats lock fee: "too low compared to our estimate (0.00000121 BTC vs 0.00001000 BTC)"
        assertEquals(1_000, BadbankSwapHost.lockFee(121));
        assertEquals(2_500, BadbankSwapHost.lockFee(2_500));
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

    private static ECKey key() {
        return ECKey.fromPrivate(new java.math.BigInteger("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 16));
    }

    private static byte[] challenge() {
        byte[] challenge = new byte[32];
        for(int i = 0; i < 32; i++) {
            challenge[i] = (byte)(i * 7 + 3);
        }
        return challenge;
    }

    private static byte[] p2wpkh(ECKey key) {
        byte[] script = new byte[22];
        script[1] = 20;
        System.arraycopy(key.getPubKeyHash(), 0, script, 2, 20);
        return script;
    }

    @Test
    void anOwnershipProofIsACompactSignatureOverTheChallengeAndTheKeyThatOwnsTheCoin() {
        ECKey key = key();
        JsonObject proof = BadbankSwapHost.ownershipProof(key, challenge(), p2wpkh(key));

        byte[] publicKey = Utils.hexToBytes(proof.get("publicKey").getAsString());
        assertEquals(33, publicKey.length, "compressed, as the maker expects");
        assertArrayEquals(key.getPubKey(true), publicKey);

        byte[] compact = Utils.hexToBytes(proof.get("signature").getAsString());
        assertEquals(64, compact.length);
        ECDSASignature signature = new ECDSASignature(new java.math.BigInteger(1, java.util.Arrays.copyOfRange(compact, 0, 32)),
                new java.math.BigInteger(1, java.util.Arrays.copyOfRange(compact, 32, 64)));
        assertTrue(signature.verify(challenge(), publicKey), "the signature is over the challenge itself, not a hash of it");
        assertTrue(signature.isCanonical(), "low S, which the maker's verifier requires");
    }

    @Test
    void aProofIsRefusedForACoinTheKeyDoesNotControlOrForAnythingButNativeSegwit() {
        ECKey key = key();
        ECKey other = ECKey.fromPrivate(java.math.BigInteger.valueOf(42));
        assertThrows(IllegalStateException.class, () -> BadbankSwapHost.ownershipProof(key, challenge(), p2wpkh(other)));
        //Pay to script hash, and a taproot output
        assertThrows(IllegalStateException.class, () -> BadbankSwapHost.ownershipProof(key, challenge(), new byte[23]));
        byte[] taproot = new byte[34];
        taproot[0] = 0x51;
        taproot[1] = 32;
        assertThrows(IllegalStateException.class, () -> BadbankSwapHost.ownershipProof(key, challenge(), taproot));
        //The challenge is always a 32 byte digest; anything else is not one the engine made
        assertThrows(IllegalArgumentException.class, () -> BadbankSwapHost.ownershipProof(key, new byte[31], p2wpkh(key)));
    }
}
