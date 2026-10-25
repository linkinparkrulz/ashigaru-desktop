package com.sparrowwallet.sparrow.swap;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.crypto.DeterministicKey;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.MnemonicException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

/**
 * The swap engine's identity, derived from the wallet so the same wallet always gets the same swap
 * keys and an interrupted swap can be resumed, even after reinstalling. It is a hardened child on a
 * path no wallet account uses, put through a tagged HMAC, so it reveals nothing about the wallet's
 * own keys and cannot be mistaken for them.
 */
public final class SwapSeed {
    //"ASHW" - hardened, outside the BIP43 purposes wallets use
    static final List<ChildNumber> PATH = List.of(new ChildNumber(0x41534857, true), new ChildNumber(0, true));
    private static final byte[] TAG = "ashigaru-swap-seed-v1".getBytes(StandardCharsets.UTF_8);

    private SwapSeed() {}

    /** @param keystore a decrypted master keystore with its private key */
    public static byte[] derive(Keystore keystore) throws MnemonicException, GeneralSecurityException {
        DeterministicKey child = keystore.getExtendedMasterPrivateKey().getKey(PATH);
        byte[] privKey = child.getPrivKeyBytes();
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(TAG, "HmacSHA512"));
            return Arrays.copyOf(mac.doFinal(privKey), 32);
        } finally {
            Arrays.fill(privKey, (byte)0);
        }
    }

    /** A directory name for this seed's swap database, without revealing the seed */
    public static String directoryName(byte[] seed) {
        return Utils.bytesToHex(Sha256Hash.hash(seed)).substring(0, 16);
    }
}
