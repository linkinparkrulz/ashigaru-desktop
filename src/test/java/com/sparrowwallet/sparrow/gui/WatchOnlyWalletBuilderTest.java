package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.io.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bare zpub watch-only import used to fail to save: drongo parses a bare key as an origin-less descriptor, giving a
 * keystore without a fingerprint or derivation path, which the wallet database rejects. Uses the BIP84 test vector.
 */
class WatchOnlyWalletBuilderTest {
    private static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static final String ACCOUNT_0_ZPUB = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs";
    private static final String FIRST_RECEIVE_ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";

    @TempDir
    Path tempDir;

    @Test
    void bareZpub() throws Exception {
        Wallet wallet = WatchOnlyWalletBuilder.build("watch", ACCOUNT_0_ZPUB);
        assertAccount0(wallet, "00000000");
    }

    @Test
    void bareZpubWithWhitespace() throws Exception {
        Wallet wallet = WatchOnlyWalletBuilder.build("watch", "  " + ACCOUNT_0_ZPUB.substring(0, 40) + "\n" + ACCOUNT_0_ZPUB.substring(40) + " ");
        assertAccount0(wallet, "00000000");
    }

    @Test
    void descriptorWithoutKeyOrigin() throws Exception {
        Wallet wallet = WatchOnlyWalletBuilder.build("watch", "wpkh(" + ACCOUNT_0_ZPUB + ")");
        assertAccount0(wallet, "00000000");
    }

    @Test
    void fullDescriptor() throws Exception {
        Wallet wallet = WatchOnlyWalletBuilder.build("watch", "wpkh([73c5da0a/84h/0h/0h]" + ACCOUNT_0_ZPUB + "/<0;1>/*)");
        assertAccount0(wallet, "73c5da0a");
    }

    @Test
    void postmixZpubGetsPostmixPath() throws Exception {
        String postmixZpub = deriveZpub(StandardAccount.WHIRLPOOL_POSTMIX.getAccountNumber());
        Wallet wallet = WatchOnlyWalletBuilder.build("postmix", postmixZpub);
        assertTrue(wallet.isValid());
        assertEquals("m/84'/0'/2147483646'", wallet.getKeystores().get(0).getKeyDerivation().getDerivationPath());
        assertEquals(postmixZpub, wallet.getKeystores().get(0).getExtendedPublicKey().toString(ExtendedKey.Header.zpub));
    }

    @Test
    void derivedAccount0MatchesTestVector() throws Exception {
        assertEquals(ACCOUNT_0_ZPUB, deriveZpub(0));
    }

    @Test
    void ypubIsRejected() throws Exception {
        Keystore keystore = Keystore.fromSeed(seed(), ScriptType.P2SH_P2WPKH.getDefaultDerivation());
        String ypub = keystore.getExtendedPublicKey().toString(ExtendedKey.Header.ypub);
        assertEquals(WatchOnlyWalletBuilder.NATIVE_SEGWIT_ONLY, WatchOnlyWalletBuilder.validate(ypub));
        assertThrows(WatchOnlyWalletBuilder.InvalidWatchOnlyInputException.class, () -> WatchOnlyWalletBuilder.build("watch", ypub));
    }

    @Test
    void taprootDescriptorIsRejected() throws Exception {
        Keystore keystore = Keystore.fromSeed(seed(), ScriptType.P2TR.getDefaultDerivation());
        String descriptor = "tr([73c5da0a/86h/0h/0h]" + keystore.getExtendedPublicKey().toString() + "/<0;1>/*)";
        assertEquals(WatchOnlyWalletBuilder.NATIVE_SEGWIT_ONLY, WatchOnlyWalletBuilder.validate(descriptor));
    }

    @Test
    void privateKeyIsRejected() throws Exception {
        Keystore keystore = Keystore.fromSeed(seed(), ScriptType.P2WPKH.getDefaultDerivation());
        String zprv = keystore.getExtendedPrivateKey().toString(ExtendedKey.Header.zprv);
        assertNotNull(WatchOnlyWalletBuilder.validate(zprv));
    }

    @Test
    void garbageIsRejected() {
        assertNotNull(WatchOnlyWalletBuilder.validate(""));
        assertNotNull(WatchOnlyWalletBuilder.validate("   "));
        assertNotNull(WatchOnlyWalletBuilder.validate("not a key"));
        assertNotNull(WatchOnlyWalletBuilder.validate(ACCOUNT_0_ZPUB.substring(0, ACCOUNT_0_ZPUB.length() - 1)));
        assertNotNull(WatchOnlyWalletBuilder.validate("wpkh(garbage)"));
    }

    @Test
    void bareZpubSavesAndReloads() throws Exception {
        //Regression test for "NULL not allowed for column derivationPath"
        assertRoundTrip(WatchOnlyWalletBuilder.build("bare", ACCOUNT_0_ZPUB), "00000000");
    }

    @Test
    void originlessDescriptorSavesAndReloads() throws Exception {
        assertRoundTrip(WatchOnlyWalletBuilder.build("originless", "wpkh(" + ACCOUNT_0_ZPUB + ")"), "00000000");
    }

    @Test
    void fullDescriptorSavesAndReloads() throws Exception {
        assertRoundTrip(WatchOnlyWalletBuilder.build("full", "wpkh([73c5da0a/84h/0h/0h]" + ACCOUNT_0_ZPUB + "/<0;1>/*)"), "73c5da0a");
    }

    private void assertRoundTrip(Wallet wallet, String fingerprint) throws Exception {
        File walletFile = tempDir.resolve(wallet.getName() + ".mv.db").toFile();
        Storage storage = new Storage(walletFile);
        storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
        storage.saveWallet(wallet);

        Storage reloadStorage = new Storage(walletFile);
        Wallet reloaded = reloadStorage.loadUnencryptedWallet().getWallet();
        assertAccount0(reloaded, fingerprint);
    }

    private static void assertAccount0(Wallet wallet, String fingerprint) {
        assertTrue(wallet.isValid());
        assertEquals(ScriptType.P2WPKH, wallet.getScriptType());
        assertEquals(1, wallet.getKeystores().size());
        Keystore keystore = wallet.getKeystores().get(0);
        assertEquals(KeystoreSource.SW_WATCH, keystore.getSource());
        assertEquals(fingerprint, keystore.getKeyDerivation().getMasterFingerprint());
        assertEquals("m/84'/0'/0'", keystore.getKeyDerivation().getDerivationPath());
        assertEquals(FIRST_RECEIVE_ADDRESS, wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString());
    }

    private static DeterministicSeed seed() {
        return new DeterministicSeed(MNEMONIC, "", 0, DeterministicSeed.Type.BIP39);
    }

    private static String deriveZpub(int account) throws Exception {
        Keystore keystore = Keystore.fromSeed(seed(), ScriptType.P2WPKH.getDefaultDerivation(account));
        return keystore.getExtendedPublicKey().toString(ExtendedKey.Header.zpub);
    }
}
