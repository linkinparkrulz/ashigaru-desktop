package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwapSeedTest {
    private static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static final List<ChildNumber> ACCOUNT = List.of(new ChildNumber(84, true), new ChildNumber(0, true), new ChildNumber(0, true));

    private static Keystore keystore(String passphrase) throws Exception {
        return Keystore.fromSeed(new DeterministicSeed(MNEMONIC, passphrase, 0, DeterministicSeed.Type.BIP39), ACCOUNT);
    }

    @Test
    void sameWalletGivesTheSameSeed() throws Exception {
        byte[] seed = SwapSeed.derive(keystore(""));
        assertEquals(32, seed.length);
        assertArrayEquals(seed, SwapSeed.derive(keystore("")));
    }

    @Test
    void anotherWalletGivesAnotherSeed() throws Exception {
        //A BIP39 passphrase makes a different wallet from the same words
        assertFalse(java.util.Arrays.equals(SwapSeed.derive(keystore("")), SwapSeed.derive(keystore("TREZOR"))));
    }

    @Test
    void directoryNameIsStableAndShort() throws Exception {
        byte[] seed = SwapSeed.derive(keystore(""));
        String name = SwapSeed.directoryName(seed);
        assertEquals(16, name.length());
        assertEquals(name, SwapSeed.directoryName(seed));
    }
}
