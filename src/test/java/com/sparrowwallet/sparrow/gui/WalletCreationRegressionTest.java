package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.EncryptionType;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.control.LifeHashIcon;
import com.sparrowwallet.sparrow.control.SeedDisplayDialog;
import com.sparrowwallet.sparrow.control.SeedEntryDialog;
import com.sparrowwallet.sparrow.io.Bip39;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.io.WalletAndKey;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression test covering hot wallet creation UI dialogs (SeedEntryDialog,
 * SeedDisplayDialog, LifeHashIcon) and encrypted/unencrypted wallet persistence via Storage + H2.
 */
class WalletCreationRegressionTest {

    private static final List<String> TEST_WORDS_12 = List.of(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    );

    @BeforeAll
    static void startFx() throws Exception {
        if (com.sparrowwallet.sparrow.AppServices.get() == null) {
            com.sparrowwallet.sparrow.AppServices.initialize(null);
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            latch.countDown();
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "JavaFX toolkit failed to start");
    }

    @Test
    void seedEntryAndDisplayDialogsAndLifeHashConstructCleanly() throws Exception {
        runOnFx(() -> {
            SeedEntryDialog entry12 = new SeedEntryDialog("Test12", 12);
            assertNotNull(entry12.getDialogPane().getContent());

            SeedEntryDialog entry24 = new SeedEntryDialog("Test24", 24);
            assertNotNull(entry24.getDialogPane().getContent());

            Keystore ks = new Bip39().getKeystore(ScriptType.P2WPKH.getDefaultDerivation(), TEST_WORDS_12, "pass");
            SeedDisplayDialog display = new SeedDisplayDialog(ks);
            assertNotNull(display.getDialogPane().getContent());

            LifeHashIcon icon = new LifeHashIcon();
            icon.setData(ks.getExtendedMasterPrivateKey().getKey().getFingerprint());
            assertFalse(icon.getChildren().isEmpty(), "LifeHashIcon should render a rectangle");
        });
    }

    @Test
    void createEncryptSaveAndReloadHotWallet(@TempDir Path tempDir) throws Exception {
        Wallet wallet = new Wallet("CreatedWallet");
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2WPKH);
        Keystore ks = new Bip39().getKeystore(ScriptType.P2WPKH.getDefaultDerivation(), TEST_WORDS_12, "dice-pass-word");
        wallet.getKeystores().add(ks);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
        wallet.checkWallet();

        for (StandardAccount account : StandardAccount.WHIRLPOOL_ACCOUNTS) {
            wallet.addChildWallet(account);
        }

        File walletFile = tempDir.resolve("CreatedWallet.mv.db").toFile();
        Storage storage = new Storage(walletFile);
        try {
            ECKey encFull = storage.getEncryptionKey(new SecureString("secret-pw"));
            ECKey encPub = ECKey.fromPublicOnly(encFull);
            Key key = new Key(encFull.getPrivKeyBytes(), storage.getKeyDeriver().getSalt(), EncryptionType.Deriver.ARGON2);
            wallet.encrypt(key);
            storage.setEncryptionPubKey(encPub);
            storage.saveWallet(wallet);
            for (Wallet child : wallet.getChildWallets()) {
                child.encrypt(key);
                storage.saveWallet(child);
            }
        } finally {
            storage.close();
        }

        assertTrue(walletFile.exists(), "Wallet DB file should exist on disk");

        Storage reopen = new Storage(walletFile);
        try {
            WalletAndKey loaded = reopen.loadEncryptedWallet(new SecureString("secret-pw"));
            assertEquals("CreatedWallet", loaded.getWallet().getName());
            assertEquals(3, loaded.getWallet().getChildWallets().size());
        } finally {
            reopen.close();
        }
    }

    private static void runOnFx(ThrowingRunnable task) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(15, TimeUnit.SECONDS), "FX task timed out");
        if (failure.get() != null) {
            if (failure.get() instanceof Exception e) throw e;
            throw new AssertionError(failure.get());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
