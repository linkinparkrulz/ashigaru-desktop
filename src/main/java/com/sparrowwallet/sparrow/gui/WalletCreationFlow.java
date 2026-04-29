package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.EncryptionType;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.HelpLabel;
import com.sparrowwallet.sparrow.control.LifeHashIcon;
import com.sparrowwallet.sparrow.control.ViewPasswordField;
import com.sparrowwallet.sparrow.event.StorageEvent;
import com.sparrowwallet.sparrow.event.TimedEvent;
import com.sparrowwallet.sparrow.io.Bip39;
import com.sparrowwallet.sparrow.io.ImportException;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.io.StorageException;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import com.sparrowwallet.sparrow.wallet.KeystoreController;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.sparrowwallet.sparrow.whirlpool.WhirlpoolServices;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.concurrent.Task;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;


/**
 * Implements the wallet creation / restore flow as native JavaFX dialogs,
 * mirroring what the TUI does in MasterActionListBox + NewWalletDialog.
 */
public class WalletCreationFlow {
    private static final Logger log = LoggerFactory.getLogger(WalletCreationFlow.class);

    private final Stage owner;
    private final AshigaruMainController mainController;

    public WalletCreationFlow(Stage owner, AshigaruMainController mainController) {
        this.owner = owner;
        this.mainController = mainController;
    }

    /** Entry point — call from the JavaFX UI thread. */
    public void start() {
        String walletName = askWalletName();
        if (walletName == null) return;

        String walletType = askWalletType();
        if (walletType == null) return;

        if ("Hot Wallet".equals(walletType)) {
            showBip39Dialog(walletName);
        } else {
            showWatchOnlyDialog(walletName);
        }
    }

    // -------------------------------------------------------------------------
    // Step 1 – wallet name
    // -------------------------------------------------------------------------

    private String askWalletName() {
        TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle("Create Wallet");
        dlg.setHeaderText("Enter a name for the wallet");
        dlg.setContentText("Name:");
        dlg.initOwner(owner);

        while (true) {
            Optional<String> result = dlg.showAndWait();
            if (result.isEmpty()) return null;
            String name = result.get().trim();
            if (name.isEmpty()) {
                showError("Invalid Name", "Please enter a name for the wallet.");
                dlg.getEditor().setText("");
                continue;
            }
            if (Storage.walletExists(name)) {
                showError("Wallet Exists", "A wallet named \"" + name + "\" already exists. Choose a different name.");
                dlg.getEditor().setText("");
                continue;
            }
            return name;
        }
    }

    // -------------------------------------------------------------------------
    // Step 2 – wallet type
    // -------------------------------------------------------------------------

    private String askWalletType() {
        List<String> choices = List.of("Hot Wallet", "Watch Only");
        ChoiceDialog<String> dlg = new ChoiceDialog<>("Hot Wallet", choices);
        dlg.setTitle("Create Wallet");
        dlg.setHeaderText("Choose the type of wallet");
        dlg.setContentText("Type:");
        dlg.initOwner(owner);
        return dlg.showAndWait().orElse(null);
    }

    // -------------------------------------------------------------------------
    // Step 3a – BIP39 hot wallet dialog
    // -------------------------------------------------------------------------

    private void showBip39Dialog(String walletName) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Create BIP39 Wallet – " + walletName);
        dlg.initOwner(owner);

        Label seedLabel = new Label("Seed words:");
        TextArea seedArea = new TextArea();
        seedArea.setWrapText(true);
        seedArea.setPrefRowCount(5);
        seedArea.setPromptText("Enter your 12/18/24 word BIP39 seed phrase, or generate a new one below.");

        Label passLabel = new Label("BIP39 Passphrase:");
        ViewPasswordField passField = new ViewPasswordField();
        passField.setPromptText("Enter passphrase");

        Label passConfirmLabel = new Label("Confirm Passphrase:");
        ViewPasswordField passConfirmField = new ViewPasswordField();
        passConfirmField.setPromptText("Re-enter passphrase");

        ObjectProperty<byte[]> masterFingerprint = new SimpleObjectProperty<>();

        HBox fingerprintBox = new HBox(10);
        fingerprintBox.setAlignment(Pos.CENTER_LEFT);
        Label fingerprintLabel = new Label("Master fingerprint:");
        TextField fingerprintHex = new TextField();
        fingerprintHex.setDisable(true);
        fingerprintHex.setMaxWidth(80);
        fingerprintHex.getStyleClass().add("fixed-width");
        fingerprintHex.setStyle("-fx-opacity: 0.6");
        masterFingerprint.addListener((obs, oldVal, newVal) ->
                fingerprintHex.setText(newVal != null ? Utils.bytesToHex(newVal) : ""));
        LifeHashIcon lifeHashIcon = new LifeHashIcon();
        lifeHashIcon.dataProperty().bind(masterFingerprint);
        HelpLabel helpLabel = new HelpLabel();
        helpLabel.setHelpText("All passphrases create valid wallets." +
                "\nThe master fingerprint identifies the keystore and changes as the passphrase changes." +
                "\nMake sure you recognise it before proceeding.");
        Button copyFpBtn = new Button("⎘");
        copyFpBtn.getStyleClass().add("copy-icon-btn");
        copyFpBtn.setPrefSize(28, 28);
        copyFpBtn.disableProperty().bind(masterFingerprint.isNull());
        copyFpBtn.setOnAction(e -> {
            if (fingerprintHex.getText().isEmpty()) return;
            ClipboardContent cc = new ClipboardContent();
            cc.putString(fingerprintHex.getText());
            Clipboard.getSystemClipboard().setContent(cc);
            copyFpBtn.setText("✓");
            PauseTransition pause = new PauseTransition(javafx.util.Duration.seconds(1.5));
            pause.setOnFinished(ev -> copyFpBtn.setText("⎘"));
            pause.play();
        });
        fingerprintBox.getChildren().addAll(fingerprintLabel, fingerprintHex, copyFpBtn, lifeHashIcon, helpLabel);

        Button generateBtn = new Button("Generate New Wallet");
        generateBtn.setOnAction(e -> seedArea.setText(generateMnemonic(12)));

        VBox content = new VBox(10, seedLabel, seedArea, passLabel, passField, passConfirmLabel, passConfirmField, fingerprintBox, generateBtn);
        content.setPadding(new Insets(12));
        content.setPrefWidth(480);
        dlg.getDialogPane().setContent(content);

        ButtonType nextType = new ButtonType("Next", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(nextType, ButtonType.CANCEL);

        Button nextNode = (Button) dlg.getDialogPane().lookupButton(nextType);
        nextNode.setDisable(true);

        Bip39 importer = new Bip39();
        Runnable updateNext = () -> {
            boolean valid = isValidSeed(importer, seedArea.getText(), passField.getText())
                    && !passField.getText().isEmpty()
                    && passField.getText().equals(passConfirmField.getText());
            nextNode.setDisable(!valid);
            masterFingerprint.set(computeFingerprint(importer, seedArea.getText(), passField.getText()));
        };
        seedArea.textProperty().addListener((obs, old, text) -> updateNext.run());
        passField.textProperty().addListener((obs, old, text) -> updateNext.run());
        passConfirmField.textProperty().addListener((obs, old, text) -> updateNext.run());

        dlg.setResultConverter(bt -> bt);

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != nextType) return;

        List<String> words = Arrays.asList(seedArea.getText().trim().split("\\s+"));
        String passphrase = passField.getText();

        try {
            Wallet wallet = new Wallet(walletName);
            wallet.setPolicyType(PolicyType.SINGLE);
            wallet.setScriptType(ScriptType.P2WPKH);
            Keystore keystore = importer.getKeystore(ScriptType.P2WPKH.getDefaultDerivation(), words, passphrase);
            wallet.getKeystores().add(keystore);
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
            discoverAndSave(walletName, List.of(wallet));
        } catch (ImportException e) {
            showError("Invalid Seed", "Could not import wallet from seed: " + e.getMessage());
        }
    }

    private boolean isValidSeed(Bip39 importer, String text, String passphrase) {
        String[] words = text.trim().split("\\s+");
        if (words.length < 12) return false;
        try {
            importer.getKeystore(ScriptType.P2WPKH.getDefaultDerivation(), Arrays.asList(words), passphrase);
            return true;
        } catch (ImportException e) {
            return false;
        }
    }

    private byte[] computeFingerprint(Bip39 importer, String seedText, String passphrase) {
        String[] words = seedText.trim().split("\\s+");
        if (words.length < 12) return null;
        try {
            Keystore ks = importer.getKeystore(ScriptType.P2WPKH.getDefaultDerivation(), Arrays.asList(words), passphrase);
            return ks.getExtendedMasterPrivateKey().getKey().getFingerprint();
        } catch (Exception e) {
            return null;
        }
    }

    private String generateMnemonic(int wordCount) {
        int mnemonicSeedLength = wordCount * 11;
        int entropyLength = mnemonicSeedLength - (mnemonicSeedLength / 33);
        SecureRandom rng;
        try {
            rng = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            rng = new SecureRandom();
        }
        DeterministicSeed seed = new DeterministicSeed(rng, entropyLength, "");
        return String.join(" ", seed.getMnemonicCode());
    }

    // -------------------------------------------------------------------------
    // Step 3b – Watch Only wallet dialog
    // -------------------------------------------------------------------------

    private void showWatchOnlyDialog(String walletName) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Create Watch Only Wallet – " + walletName);
        dlg.initOwner(owner);

        Label hint = new Label("Output descriptor or xpub\n(BIP84 Native Segwit Deposit or Postmix account)");
        TextArea descriptorArea = new TextArea();
        descriptorArea.setWrapText(true);
        descriptorArea.setPrefRowCount(6);
        descriptorArea.setPromptText("Paste your xpub or output descriptor here…");

        VBox content = new VBox(10, hint, descriptorArea);
        content.setPadding(new Insets(12));
        content.setPrefWidth(480);
        dlg.getDialogPane().setContent(content);

        ButtonType importType = new ButtonType("Import Wallet", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(importType, ButtonType.CANCEL);

        Button importNode = (Button) dlg.getDialogPane().lookupButton(importType);
        importNode.setDisable(true);
        descriptorArea.textProperty().addListener((obs, old, text) ->
                importNode.setDisable(!isValidDescriptorOrXpub(text.replaceAll("\\s+", ""))));

        dlg.setResultConverter(bt -> bt);

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != importType) return;

        String raw = descriptorArea.getText().replaceAll("\\s+", "");
        List<Wallet> wallets = buildWatchOnlyWallets(walletName, raw);
        if (wallets.isEmpty()) {
            showError("Invalid Input", "Could not parse the descriptor or xpub.");
            return;
        }

        discoverAndSave(walletName, wallets);
    }

    private boolean isValidDescriptorOrXpub(String text) {
        if (text.isEmpty()) return false;
        try {
            OutputDescriptor.getOutputDescriptor(text);
            return true;
        } catch (Exception e1) {
            try {
                ExtendedKey.fromDescriptor(text);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private List<Wallet> buildWatchOnlyWallets(String walletName, String raw) {
        try {
            OutputDescriptor desc = OutputDescriptor.getOutputDescriptor(raw);
            Wallet wallet = desc.toWallet();
            wallet.setName(walletName);
            return List.of(wallet);
        } catch (Exception e1) {
            try {
                ExtendedKey xpub = ExtendedKey.fromDescriptor(raw);
                Wallet wallet = new Wallet(walletName);
                wallet.setPolicyType(PolicyType.SINGLE);
                wallet.setScriptType(ScriptType.P2WPKH);
                Keystore keystore = new Keystore();
                keystore.setSource(KeystoreSource.SW_WATCH);
                keystore.setWalletModel(WalletModel.SPARROW);
                keystore.setKeyDerivation(new KeyDerivation(
                        KeystoreController.DEFAULT_WATCH_ONLY_FINGERPRINT,
                        ScriptType.P2WPKH.getDefaultDerivationPath()));
                keystore.setExtendedPublicKey(xpub);
                wallet.makeLabelsUnique(keystore);
                wallet.getKeystores().add(keystore);
                wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
                return List.of(wallet);
            } catch (Exception e2) {
                log.error("Could not build watch only wallet from: " + raw, e2);
                return Collections.emptyList();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Account discovery + save (mirrors TUI NewWalletDialog logic)
    // -------------------------------------------------------------------------

    private void discoverAndSave(String walletName, List<Wallet> wallets) {
        if (wallets.isEmpty()) return;

        if (AppServices.onlineProperty().get()) {
            ElectrumServer.WalletDiscoveryService svc = new ElectrumServer.WalletDiscoveryService(wallets);

            Dialog<Void> progress = new Dialog<>();
            progress.setTitle(walletName);
            progress.setHeaderText("Discovering accounts…");
            progress.initOwner(owner);
            progress.initModality(Modality.APPLICATION_MODAL);

            // Cancel button — gives user an immediate escape hatch
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            progress.getDialogPane().getButtonTypes().add(cancelType);

            Label descLabel = new Label("Looking for previous transactions on the blockchain.");
            descLabel.setWrapText(true);

            Label statusLabel = new Label();
            statusLabel.textProperty().bind(svc.messageProperty());
            statusLabel.setOpacity(0.65);

            ProgressBar bar = new ProgressBar();
            bar.setPrefWidth(320);
            bar.progressProperty().bind(svc.progressProperty());

            VBox content = new VBox(10, descLabel, bar, statusLabel);
            progress.getDialogPane().setContent(content);

            // Guard — wallet is saved exactly once regardless of which path fires first
            AtomicBoolean proceeded = new AtomicBoolean(false);
            Consumer<Wallet> proceed = wallet -> {
                if (!proceeded.compareAndSet(false, true)) return;
                try { addWhirlpoolAccounts(wallet); } catch (Exception ex) { log.error("Whirlpool setup failed", ex); }
                // Platform.runLater lets the discovery dialog fully close before the save dialog opens
                Platform.runLater(() -> saveWallet(walletName, wallet));
            };

            // Helper to close dialog and stop the timeout — always called exactly once
            Runnable finish = () -> {
                progress.setOnHiding(null);
                progress.close();
            };

            // 2-minute auto-timeout: close dialog + proceed directly, don't wait for onCancelled
            // (the blocking Electrum socket may not respond to thread interruption)
            PauseTransition timeout = new PauseTransition(Duration.seconds(120));
            timeout.setOnFinished(e -> { finish.run(); svc.cancel(); proceed.accept(wallets.get(0)); });

            svc.setOnSucceeded(e -> { timeout.stop(); finish.run(); proceed.accept(svc.getValue().orElseGet(() -> wallets.get(0))); });
            svc.setOnFailed(e -> { timeout.stop(); finish.run(); log.error("Account discovery failed", e.getSource().getException()); proceed.accept(wallets.get(0)); });
            svc.setOnCancelled(e -> { timeout.stop(); finish.run(); proceed.accept(wallets.get(0)); });

            // Cancel button: same as timeout — close + proceed immediately
            Button cancelBtn = (Button) progress.getDialogPane().lookupButton(cancelType);
            cancelBtn.setOnAction(e -> { e.consume(); finish.run(); svc.cancel(); proceed.accept(wallets.get(0)); });

            svc.start();
            timeout.play();
            progress.show(); // non-blocking — callbacks close it when done

            // Prevent the window X-button from closing the dialog while discovery runs
            ((Stage) progress.getDialogPane().getScene().getWindow()).setOnCloseRequest(Event::consume);
        } else {
            Wallet wallet = wallets.get(0);
            try { addWhirlpoolAccounts(wallet); } catch (Exception ex) { log.error("Whirlpool setup failed", ex); }
            saveWallet(walletName, wallet);
        }
    }

    private void addWhirlpoolAccounts(Wallet wallet) {
        Storage tempStorage = new Storage(Storage.getWalletFile(wallet.getName()));
        WalletForm tempForm = new WalletForm(tempStorage, wallet);
        WhirlpoolServices.prepareWhirlpoolWallet(wallet, tempForm.getWalletId(), tempStorage);
    }

    private void saveWallet(String walletName, Wallet wallet) {
        // Ask for optional password
        Dialog<String> pwDlg = new Dialog<>();
        pwDlg.setTitle("Wallet Password");
        pwDlg.setHeaderText("Add a password to the wallet?\nLeave empty for no password.");
        pwDlg.initOwner(owner);

        ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        pwDlg.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        PasswordField pwField = new PasswordField();
        pwField.setPromptText("Leave blank for no password");
        VBox content = new VBox(8, new Label("Password (optional):"), pwField);
        content.setPadding(new Insets(12));
        pwDlg.getDialogPane().setContent(content);
        Platform.runLater(pwField::requestFocus);
        pwDlg.setResultConverter(bt -> bt == okType ? pwField.getText() : null);

        Optional<String> pwResult = pwDlg.showAndWait();
        if (pwResult.isEmpty()) return; // cancelled

        String password = pwResult.get();
        Storage storage = new Storage(Storage.getWalletFile(wallet.getName()));

        if (password.isEmpty()) {
            new Thread(new Task<Void>() {
                @Override
                protected Void call() throws Exception {
                    storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
                    storage.saveWallet(wallet);
                    storage.restorePublicKeysFromSeed(wallet, null);
                    for (Wallet child : wallet.getChildWallets()) {
                        storage.saveWallet(child);
                        storage.restorePublicKeysFromSeed(child, null);
                    }
                    return null;
                }

                @Override
                protected void succeeded() {
                    Platform.runLater(() -> registerWallets(storage, wallet));
                }

                @Override
                protected void failed() {
                    log.error("Error saving wallet", getException());
                    Platform.runLater(() -> showError("Save Error",
                            "Could not save wallet: " + getException().getMessage()));
                }
            }).start();
        } else {
            String walletPath = Storage.getWalletFile(wallet.getName()).getAbsolutePath();
            Storage.KeyDerivationService kds = new Storage.KeyDerivationService(storage, new SecureString(password));
            EventManager.get().post(new StorageEvent(walletPath, TimedEvent.Action.START, "Encrypting wallet…"));

            kds.setOnSucceeded(e -> {
                ECKey encFull = kds.getValue();
                EventManager.get().post(new StorageEvent(walletPath, TimedEvent.Action.END, "Done"));

                new Thread(new Task<Void>() {
                    @Override
                    protected Void call() throws Exception {
                        Key key = null;
                        try {
                            ECKey encPub = ECKey.fromPublicOnly(encFull);
                            key = new Key(encFull.getPrivKeyBytes(), storage.getKeyDeriver().getSalt(),
                                    EncryptionType.Deriver.ARGON2);
                            wallet.encrypt(key);
                            storage.setEncryptionPubKey(encPub);
                            storage.saveWallet(wallet);
                            storage.restorePublicKeysFromSeed(wallet, key);
                            for (Wallet child : wallet.getChildWallets()) {
                                if (!child.isNested()) child.encrypt(key);
                                storage.saveWallet(child);
                                storage.restorePublicKeysFromSeed(child, key);
                            }
                        } catch (IOException | StorageException | MnemonicException ex) {
                            log.error("Error saving encrypted wallet", ex);
                            throw ex;
                        } finally {
                            encFull.clear();
                            if (key != null) key.clear();
                        }
                        return null;
                    }

                    @Override
                    protected void succeeded() {
                        Platform.runLater(() -> registerWallets(storage, wallet));
                    }

                    @Override
                    protected void failed() {
                        log.error("Error saving encrypted wallet", getException());
                        Platform.runLater(() -> showError("Save Error",
                                "Could not save wallet: " + getException().getMessage()));
                    }
                }).start();
            });

            kds.setOnFailed(e -> {
                EventManager.get().post(new StorageEvent(walletPath, TimedEvent.Action.END, "Failed"));
                Platform.runLater(() -> showError("Encryption Error", kds.getException().getMessage()));
            });

            kds.start();
        }
    }

    private void registerWallets(Storage storage, Wallet masterWallet) {
        if (mainController != null) {
            mainController.setPendingSelectFile(storage.getWalletFile());
        }
        AshigaruGui.addWallet(storage, masterWallet);
        for (Wallet child : masterWallet.getChildWallets()) {
            AshigaruGui.addWallet(storage, child);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.initOwner(owner);
        alert.showAndWait();
    }
}
