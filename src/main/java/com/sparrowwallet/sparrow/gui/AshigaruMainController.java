package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.InvalidPasswordException;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.Mode;
import com.sparrowwallet.sparrow.control.SeedDisplayDialog;
import com.sparrowwallet.sparrow.control.WalletPasswordDialog;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.preferences.PreferencesController;
import com.sparrowwallet.sparrow.preferences.PreferenceGroup;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.io.StorageException;
import com.sparrowwallet.sparrow.io.WalletAndKey;
import com.sparrowwallet.sparrow.io.WalletLabels;
import com.sparrowwallet.sparrow.net.TorUtils;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.util.*;
import javafx.concurrent.Task;

public class AshigaruMainController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(AshigaruMainController.class);

    @FXML private Label networkLabel;
    @FXML private Label connectionLabel;
    @FXML private Label blockHeightLabel;
    @FXML private ComboBox<WalletListItem> walletSelector;
    @FXML private BorderPane contentPane;
    @FXML private Label statusLabel;
    @FXML private StackPane welcomePane;

    // Account sidebar controls
    @FXML private VBox accountButtonsBox;
    @FXML private Button deleteWalletBtn;
    @FXML private Button viewSeedBtn;
    @FXML private Button exportLabelsBtn;
    @FXML private Button importLabelsBtn;
    @FXML private Button lockWalletBtn;
    @FXML private ToggleGroup accountToggleGroup;
    @FXML private ToggleButton depositBtn;
    @FXML private ToggleButton premixBtn;
    @FXML private ToggleButton postmixBtn;
    @FXML private ToggleButton badbankBtn;

    private static final WalletListItem PLACEHOLDER = new WalletListItem(null, "Select a wallet\u2026", null);

    private final ObservableList<WalletListItem> walletItems = FXCollections.observableArrayList();
    private final LinkedHashSet<File> unloadedWalletFiles = new LinkedHashSet<>();
    private File pendingSelectFile;

    private AshigaruWalletController currentWalletController;
    private WalletForm currentWalletForm;

    // Track which account is selected for the current wallet
    private StandardAccount selectedAccount = StandardAccount.ACCOUNT_0;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Wallet selector setup
        walletSelector.setItems(walletItems);
        walletSelector.setCellFactory(lv -> new WalletListCell());
        walletSelector.setButtonCell(new WalletListCell());
        walletSelector.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null || selected.isPlaceholder()) {
                showWelcome();
            } else if (selected.isLoaded()) {
                selectWallet(selected.walletId());
            } else {
                // Locked wallet — prompt for passphrase
                unlockWallet(selected);
            }
        });

        // Account toggle group - prevent deselecting all
        accountToggleGroup.selectedToggleProperty().addListener((obs, old, neu) -> {
            if (neu == null && old != null) {
                old.setSelected(true);
            }
        });

        walletItems.add(PLACEHOLDER);
        walletSelector.getSelectionModel().select(PLACEHOLDER);
        showWelcome();
        EventManager.get().register(this);
        updateNetworkLabel();
        if(AppServices.isConnected()) {
            setConnState(ConnState.CONNECTED);
        } else if(AppServices.isConnecting() || Config.get().getMode() == Mode.ONLINE) {
            setConnState(ConnState.CONNECTING);
        } else {
            setConnState(ConnState.DISCONNECTED);
        }
        connectionLabel.setContextMenu(createTorMenu());
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    private void maybeReconnectOnLeavingPrefs() {
        if (contentPane.getUserData() instanceof PreferencesController prefsController) {
            contentPane.setUserData(null);
            if (prefsController.isReconnectOnClosing() && !(AppServices.isConnecting() || AppServices.isConnected())) {
                EventManager.get().post(new RequestConnectEvent());
            }
        }
    }

    private void showWelcome() {
        if (currentWalletController != null) {
            EventManager.get().unregister(currentWalletController);
            currentWalletController.unbindWhirlpoolState();
            currentWalletController = null;
        }
        maybeReconnectOnLeavingPrefs();
        contentPane.setCenter(welcomePane);
        // walletSelector stays visible in the sidebar at all times
        accountButtonsBox.setVisible(false);
        accountButtonsBox.setManaged(false);
        deleteWalletBtn.setVisible(false);
        deleteWalletBtn.setManaged(false);
        viewSeedBtn.setVisible(false);
        viewSeedBtn.setManaged(false);
        exportLabelsBtn.setVisible(false);
        exportLabelsBtn.setManaged(false);
        importLabelsBtn.setVisible(false);
        importLabelsBtn.setManaged(false);
        lockWalletBtn.setVisible(false);
        lockWalletBtn.setManaged(false);
    }

    private void selectWallet(String walletId) {
        WalletForm walletForm = AshigaruGui.get().getWalletForms().get(walletId);
        if (walletForm == null) return;

        currentWalletForm = walletForm;
        selectedAccount = StandardAccount.ACCOUNT_0; // Default to Deposit

        // Show account section and delete button (wallet selector is always visible)
        accountButtonsBox.setVisible(true);
        accountButtonsBox.setManaged(true);
        deleteWalletBtn.setVisible(true);
        deleteWalletBtn.setManaged(true);
        viewSeedBtn.setVisible(true);
        viewSeedBtn.setManaged(true);
        exportLabelsBtn.setVisible(true);
        exportLabelsBtn.setManaged(true);
        importLabelsBtn.setVisible(true);
        importLabelsBtn.setManaged(true);

        boolean encrypted = false;
        try { encrypted = walletForm.getStorage().isEncrypted(); } catch (IOException ignored) {}
        boolean needsPassphrase = walletForm.getWallet().getKeystores().stream()
                .anyMatch(ks -> ks.hasSeed() && ks.getSeed().needsPassphrase());
        boolean lockable = encrypted || needsPassphrase;
        lockWalletBtn.setVisible(lockable);
        lockWalletBtn.setManaged(lockable);

        // Select Deposit by default
        depositBtn.setSelected(true);

        showAccountView();
    }

    private void showAccountView() {
        if (currentWalletForm == null) return;

        maybeReconnectOnLeavingPrefs();
        try {
            // Unregister previous controller
            if (currentWalletController != null) {
                EventManager.get().unregister(currentWalletController);
                currentWalletController.unbindWhirlpoolState();
                currentWalletController = null;
            }

            // Get the appropriate account form based on selected account
            WalletForm activeForm = getAccountForm(currentWalletForm, selectedAccount);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("ashigaru-wallet.fxml"));
            Node walletPanel = loader.load();
            currentWalletController = loader.getController();
            currentWalletController.setWalletForm(activeForm, currentWalletForm);
            //Scroll sideways rather than clip when the window is narrower than the header and toggle
            //rows, whose buttons keep their full labels instead of shrinking to "…"
            ScrollPane walletScroll = new ScrollPane(walletPanel);
            walletScroll.setFitToWidth(true);
            walletScroll.setFitToHeight(true);
            walletScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            walletScroll.getStyleClass().add("content-scroll");
            contentPane.setCenter(walletScroll);
            // Kick off a fresh history fetch so UTXOs/transactions appear immediately.
            // For non-Deposit accounts, also refresh the child form — master history
            // service does not fetch Premix/Postmix/Badbank address history.
            WalletForm childForm = activeForm != currentWalletForm ? activeForm : null;
            Platform.runLater(() -> {
                currentWalletForm.refreshHistory(AppServices.getCurrentBlockHeight());
                if (childForm != null) {
                    childForm.refreshHistory(AppServices.getCurrentBlockHeight());
                }
            });

            // Continue the guided tour with the wallet-scoped steps once the first wallet appears.
            if (tourAwaitingWallet) {
                tourAwaitingWallet = false;
                runTour(walletTourSteps(), null, this::ensureWalletTourView);
            }
        } catch (Exception e) {
            log.error("Error loading wallet panel", e);
            showError("Error", "Could not load wallet view: " + e.getMessage());
        }
    }

    private WalletForm getAccountForm(WalletForm masterForm, StandardAccount account) {
        if (account == StandardAccount.ACCOUNT_0) {
            return masterForm;
        }
        for (WalletForm nested : masterForm.getNestedWalletForms()) {
            if (nested.getWallet().getStandardAccountType() == account) {
                return nested;
            }
        }
        return masterForm; // Fallback to master
    }

    @FXML
    private void onAccountSelected() {
        ToggleButton selected = (ToggleButton) accountToggleGroup.getSelectedToggle();
        if (selected == null) return;

        if (selected == depositBtn) {
            selectedAccount = StandardAccount.ACCOUNT_0;
        } else if (selected == premixBtn) {
            selectedAccount = StandardAccount.WHIRLPOOL_PREMIX;
        } else if (selected == postmixBtn) {
            selectedAccount = StandardAccount.WHIRLPOOL_POSTMIX;
        } else if (selected == badbankBtn) {
            selectedAccount = StandardAccount.WHIRLPOOL_BADBANK;
        }

        showAccountView();
    }

    // -------------------------------------------------------------------------
    // Header label helpers
    // -------------------------------------------------------------------------

    private void updateNetworkLabel() {
        Network network = Network.get();
        networkLabel.setText(network.getName().toUpperCase());
        networkLabel.getStyleClass().removeAll("mainnet", "testnet");
        networkLabel.getStyleClass().add(network == Network.MAINNET ? "mainnet" : "testnet");
    }

    private enum ConnState { DISCONNECTED, CONNECTING, CONNECTED }

    private ConnState connState = ConnState.DISCONNECTED;

    private void setConnState(ConnState state) {
        connState = state;
        connectionLabel.getStyleClass().removeAll("connected", "disconnected", "connecting");
        switch (state) {
            case CONNECTED -> {
                connectionLabel.setText("● Connected");
                connectionLabel.getStyleClass().add("connected");
                connectionLabel.setTooltip(new Tooltip("Click to disconnect · right-click for Tor options"));
            }
            case CONNECTING -> {
                connectionLabel.setText("◍ Connecting…");
                connectionLabel.getStyleClass().add("connecting");
                connectionLabel.setTooltip(new Tooltip("Connecting… · right-click for Tor options"));
            }
            case DISCONNECTED -> {
                connectionLabel.setText("○ Disconnected");
                connectionLabel.getStyleClass().add("disconnected");
                connectionLabel.setTooltip(new Tooltip("Click to connect · right-click for Tor options"));
            }
        }
    }

    /**
     * Right-click menu on the connection status. Left-click stays the connect/disconnect toggle;
     * these are the recovery steps when the bundled Tor stops getting anywhere.
     */
    private ContextMenu createTorMenu() {
        MenuItem newIdentity = new MenuItem("New Tor Identity");
        newIdentity.setOnAction(event -> {
            TorUtils.changeIdentity(AppServices.getTorProxy());
            statusLabel.setText("Requested new Tor circuits");
        });
        MenuItem restartTor = new MenuItem("Restart Tor");
        restartTor.setOnAction(event -> restartTor());

        ContextMenu menu = new ContextMenu(newIdentity, restartTor);
        menu.setOnShowing(event -> {
            boolean internalTor = Config.get().requiresInternalTor();
            newIdentity.setDisable(!AppServices.isTorRunning());
            restartTor.setDisable(!internalTor);
        });
        return menu;
    }

    void restartTor() {
        if(AppServices.get().restartTor()) {
            setConnState(ConnState.CONNECTING);
            statusLabel.setText("Restarting Tor…");
        }
    }

    @FXML
    private void onConnectionLabelClicked() {
        switch (connState) {
            case DISCONNECTED -> {
                // Remember the choice so future launches auto-connect, then request a connection.
                Config.get().setMode(Mode.ONLINE);
                setConnState(ConnState.CONNECTING);
                EventManager.get().post(new RequestConnectEvent());
            }
            case CONNECTED -> {
                Config.get().setMode(Mode.OFFLINE);
                EventManager.get().post(new RequestDisconnectEvent());
            }
            case CONNECTING -> {
                // Connection attempt already in flight — ignore clicks to avoid interrupting it.
            }
        }
    }

    // -------------------------------------------------------------------------
    // Wallet open / create actions
    // -------------------------------------------------------------------------

    @FXML
    private void onOpenWallet() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open Wallet");
        fc.setInitialDirectory(Storage.getSparrowHome());
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Wallet Files", "*.json", "*.p", "*.mv.db"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File file = fc.showOpenDialog(AshigaruGui.get().getMainStage());
        if (file != null) {
            openWalletFile(file);
        }
    }

    public void setPendingSelectFile(File file) {
        this.pendingSelectFile = file;
    }

    @FXML
    private void onCreateWallet() {
        new WalletCreationFlow(AshigaruGui.get().getMainStage(), this).start();
    }

    @FXML
    private void onDeleteWallet() {
        WalletListItem selected = walletSelector.getSelectionModel().getSelectedItem();
        if (selected != null) deleteWallet(selected);
    }

    void deleteWallet(String walletId) {
        WalletListItem item = walletItems.stream()
                .filter(i -> i.walletId().equals(walletId))
                .findFirst().orElse(null);
        if (item != null) deleteWallet(item);
    }

    void deleteWallet(WalletListItem item) {
        WalletForm form = AshigaruGui.get().getWalletForms().get(item.walletId());
        if (form == null) return;

        boolean encrypted = false;
        try { encrypted = form.getStorage().isEncrypted(); } catch (IOException ignored) {}

        if (encrypted && item.walletFile() != null) {
            Dialog<String> pwDialog = buildPasswordDialog(item.displayName());
            pwDialog.setHeaderText("Enter wallet password to confirm permanent deletion");
            Optional<String> pwResult = pwDialog.showAndWait();
            if (pwResult.isEmpty() || pwResult.get() == null) return;

            Storage verifyStor = new Storage(item.walletFile());
            Storage.LoadWalletService verifySvc = new Storage.LoadWalletService(verifyStor, new SecureString(pwResult.get()));
            verifySvc.setOnSucceeded(e -> { verifySvc.getValue().clear(); Platform.runLater(() -> doDelete(item, form)); });
            verifySvc.setOnFailed(e -> {
                Throwable ex = verifySvc.getException();
                if (ex instanceof InvalidPasswordException) {
                    showError("Wrong Password", "Incorrect password — wallet not deleted.");
                } else {
                    showError("Verification Failed", "Could not verify password: " + ex.getMessage());
                }
            });
            verifySvc.start();
        } else {
            // Unencrypted wallet — verify BIP39 passphrase before deleting.
            Dialog<String> ppDialog = buildPassphraseDialog(item.displayName());
            Optional<String> ppResult = ppDialog.showAndWait();
            if (ppResult.isEmpty() || ppResult.get() == null) return;
            if (!verifyWalletPassphrase(form.getWallet(), ppResult.get())) {
                showError("Incorrect Passphrase", "The passphrase did not match. Wallet not deleted.");
                return;
            }
            doDelete(item, form);
        }
    }

    private Dialog<String> buildPassphraseDialog(String walletName) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Delete Wallet");
        dialog.initOwner(AshigaruGui.get().getMainStage());
        WalletCreationFlow.styleWizardDialog(dialog, "DELETE WALLET", "Confirm deletion",
                "Enter your BIP39 passphrase to permanently delete \"" + walletName + "\".");

        ButtonType deleteBtn = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(deleteBtn, ButtonType.CANCEL);
        WalletCreationFlow.styleWizardButtons(dialog.getDialogPane());

        Label hint = new Label("Enter your BIP39 passphrase (leave empty if none):");
        PasswordField pf = new PasswordField();
        pf.setPromptText("Passphrase");
        VBox vbox = new VBox(6, hint, pf);
        dialog.getDialogPane().setContent(vbox);
        Platform.runLater(pf::requestFocus);
        dialog.setResultConverter(btn -> btn == deleteBtn ? pf.getText() : null);
        return dialog;
    }

    /**
     * Re-derives the account extended key from the stored mnemonic + entered passphrase,
     * then compares the resulting public key against the keystore's stored xpub.
     * Returns true if they match (or if the wallet has no verifiable seed, e.g. hardware wallet).
     */
    private boolean verifyWalletPassphrase(Wallet wallet, String enteredPassphrase) {
        for (Keystore keystore : wallet.getKeystores()) {
            DeterministicSeed seed = keystore.getSeed();
            if (seed == null || seed.isEncrypted() || seed.getMnemonicCode() == null) {
                continue; // hardware wallet / watch-only — skip
            }
            SecureString savedPassphrase = seed.getPassphrase();
            try {
                seed.setPassphrase(enteredPassphrase);
                ExtendedKey derivedXprv = keystore.getExtendedPrivateKey();
                byte[] derivedPub = derivedXprv.getKey().dropPrivateBytes().getPubKey();
                byte[] storedPub  = keystore.getExtendedPublicKey().getKey().getPubKey();
                if (!Arrays.equals(derivedPub, storedPub)) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            } finally {
                seed.setPassphrase(savedPassphrase);
            }
        }
        return true;
    }

    private void doDelete(WalletListItem item, WalletForm form) {
        Storage.DeleteWalletService svc = new Storage.DeleteWalletService(form.getStorage(), false);
        svc.setOnSucceeded(e -> {
            svc.cancel();
            AppServices.getWhirlpoolServices().closeWallet(item.walletId());
            AshigaruGui.removeWallet(item.walletId());
            refreshWalletList();
            showWelcome();
        });
        svc.setOnFailed(e -> {
            svc.cancel();
            showError("Delete Failed", svc.getException().getMessage());
        });
        svc.start();
    }

    @FXML
    private void onExportLabels() {
        if(currentWalletForm == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Labels");
        fc.setInitialFileName(currentWalletForm.getWallet().getDisplayName() + "-labels.jsonl");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("BIP329 Labels (*.jsonl)", "*.jsonl"));
        File file = fc.showSaveDialog(AshigaruGui.get().getMainStage());
        if(file == null) return;

        WalletForm form = currentWalletForm;
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try(FileOutputStream fos = new FileOutputStream(file)) {
                    new WalletLabels().exportWallet(form.getWallet(), fos, null);
                }
                return null;
            }
        };
        task.setOnSucceeded(e -> showInfo("Labels Exported", "Labels saved to " + file.getName()));
        task.setOnFailed(e -> showError("Export Failed", task.getException().getMessage()));
        new Thread(task, "labels-export").start();
    }

    @FXML
    private void onImportLabels() {
        if(currentWalletForm == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Import Labels");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("BIP329 Labels (*.jsonl)", "*.jsonl"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File file = fc.showOpenDialog(AshigaruGui.get().getMainStage());
        if(file == null) return;

        List<WalletForm> forms = new ArrayList<>();
        forms.add(currentWalletForm);
        forms.addAll(currentWalletForm.getNestedWalletForms());
        WalletLabels importer = new WalletLabels(forms);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try(FileInputStream fis = new FileInputStream(file)) {
                    importer.importWallet(fis, null);
                }
                return null;
            }
        };
        task.setOnSucceeded(e -> showInfo("Labels Imported", "Labels imported from " + file.getName()));
        task.setOnFailed(e -> showError("Import Failed", task.getException().getMessage()));
        new Thread(task, "labels-import").start();
    }

    private void showInfo(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.initOwner(AshigaruGui.get().getMainStage());
            AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());
            alert.show();
        });
    }

    @FXML
    private void onLockWallet() {
        if (currentWalletForm == null) return;
        File walletFile = currentWalletForm.getStorage().getWalletFile();
        unloadWalletForm(currentWalletForm);
        unloadedWalletFiles.add(walletFile);
        refreshWalletList();
        showWelcome();
    }

    /**
     * Unloads a wallet form (and its nested Premix/Postmix/Badbank forms) from the UI and registry,
     * returning to the locked state. Shared by the Lock button and the reopen-on-wrong-passphrase flow.
     */
    private void unloadWalletForm(WalletForm form) {
        // Remove nested wallet forms from the registry (master removal won't clean these)
        for (WalletForm nested : form.getNestedWalletForms()) {
            AshigaruGui.get().getWalletForms().remove(nested.getWalletId());
        }
        AshigaruGui.removeWallet(form.getWalletId());
        if (currentWalletForm == form) {
            currentWalletForm = null;
        }
    }

    @FXML
    private void onViewSeed() {
        if (currentWalletForm == null) return;
        Wallet wallet = currentWalletForm.getWallet();
        if (wallet.getKeystores().isEmpty() || !wallet.getKeystores().get(0).hasSeed()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("No Seed Available");
            alert.setHeaderText("No seed words available");
            alert.setContentText("This wallet does not have seed words stored (e.g. it may be a watch-only wallet).");
            alert.initOwner(AshigaruGui.get().getMainStage());
            AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());
            alert.showAndWait();
            return;
        }
        Wallet copy = wallet.copy();
        if (copy.isEncrypted()) {
            WalletPasswordDialog dlg = new WalletPasswordDialog(copy.getMasterName(), WalletPasswordDialog.PasswordRequirement.LOAD);
            dlg.initOwner(AshigaruGui.get().getMainStage());
            Optional<SecureString> password = dlg.showAndWait();
            if (password.isPresent()) {
                Storage.DecryptWalletService svc = new Storage.DecryptWalletService(copy, password.get());
                svc.setOnSucceeded(e -> showSeedDialog(svc.getValue().getKeystores().get(0)));
                svc.setOnFailed(e -> AppServices.showErrorDialog("Incorrect Password", svc.getException().getMessage()));
                svc.start();
            }
        } else {
            showSeedDialog(wallet.getKeystores().get(0));
        }
    }

    private void showSeedDialog(Keystore keystore) {
        SeedDisplayDialog dlg = new SeedDisplayDialog(keystore);
        dlg.initOwner(AshigaruGui.get().getMainStage());
        dlg.showAndWait();
    }

    // -------------------------------------------------------------------------
    // Guided tour
    // -------------------------------------------------------------------------

    /** Set true when the intro/shell tour has completed but no wallet was open yet, so the
     *  wallet-scoped steps should resume as soon as a wallet's account view appears. */
    private boolean tourAwaitingWallet;

    /**
     * Show the welcome tour on first launch only, then remember it has been shown.
     * Invoked from {@link AshigaruGui} after the main stage is displayed (the scene
     * must exist for the coach-mark popovers to anchor).
     */
    public void maybeShowFirstRunTour() {
        if (!Config.get().isTourShown()) {
            Config.get().setTourShown(true);
            startTour();
        }

        //After the tour, so a first-time user is not met with two dialogs at once
        maybeAskUpdateConsent();
    }

    /**
     * Ask once whether Ashigaru may check for updates. Consent starts unset rather than defaulting
     * on, because the check is a network request that reveals this install is running - a choice
     * worth making deliberately in a wallet that otherwise stays off clearnet.
     */
    private void maybeAskUpdateConsent() {
        Config config = Config.get();
        if (config.isUpdateCheckAnswered()) {
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Ashigaru can check GitHub once a day for new releases, and verify a download against the "
                        + "release signing key before you install it. This keeps you off search results, where "
                        + "forks of this project are easy to mistake for the real one.\n\n"
                        + "The check reveals only that someone asked for the version number, never anything "
                        + "about your wallets, and it goes through your proxy when one is configured.\n\n"
                        + "You can change this any time in Settings \u2192 Update.",
                ButtonType.NO, ButtonType.YES);
        alert.setTitle("Check for updates?");
        alert.setHeaderText("Check for updates?");
        alert.initOwner(AshigaruGui.get().getMainStage());
        AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());

        Optional<ButtonType> answer = alert.showAndWait();
        //No answer means the dialog was dismissed; leave it unset so it is asked again rather than
        //treating a dismissal as consent
        if (answer.isEmpty()) {
            return;
        }

        boolean enabled = answer.get() == ButtonType.YES;
        config.setUpdateCheckConsent(enabled);
        EventManager.get().post(new VersionCheckStatusEvent(enabled));
    }

    @Subscribe
    public void updateAvailable(UpdateAvailableEvent event) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Ashigaru " + event.getVersion() + " has been published. Ashigaru can download it and check "
                            + "it against the release signing key before you install it.",
                    ButtonType.CLOSE, ButtonType.OK);
            alert.setTitle("Update available");
            alert.setHeaderText("Ashigaru " + event.getVersion() + " is available");
            alert.initOwner(AshigaruGui.get().getMainStage());
            AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());

            alert.showAndWait().filter(button -> button == ButtonType.OK)
                    .ifPresent(button -> ensureSettingsTourView(PreferenceGroup.UPDATE));
        });
    }

    /**
     * Launch the tour from the Tools view: leave Tools first (restoring the wallet or welcome
     * view) so the coach-mark anchors are present, then run the tour.
     */
    public void startGuidedTour() {
        closeTool();
        startTour();
    }

    private void startTour() {
        AshigaruTourDialog dialog = new AshigaruTourDialog();
        Optional<Boolean> takeTour = dialog.showAndWait();
        if (takeTour.isEmpty() || !takeTour.get()) {
            return;
        }

        tourAwaitingWallet = false;
        if (currentWalletController != null) {
            // A wallet is already open — everything is on screen, so run it in one pass.
            List<TourManager.TourStep> allSteps = new ArrayList<>(shellTourSteps());
            allSteps.addAll(walletTourSteps());
            runTour(allSteps, null, this::ensureWalletTourView);
        } else {
            // No wallet yet — run the shell steps, then wait for the first wallet to continue.
            runTour(shellTourSteps(), () -> tourAwaitingWallet = true, this::ensureWalletTourView);
        }
    }

    private void runTour(List<TourManager.TourStep> steps, Runnable onComplete, Runnable onExit) {
        Stage stage = AshigaruGui.get().getMainStage();
        TourManager manager = new TourManager(stage, steps, onComplete, onExit);
        // Defer until the modal dialog has fully closed and layout has settled,
        // so the popovers anchor to correctly positioned nodes.
        Platform.runLater(manager::start);
    }

    /** Ensure the wallet/welcome view is showing (leave Settings if the tour opened it). Idempotent. */
    private void ensureWalletTourView() {
        if (contentPane.getUserData() instanceof PreferencesController) {
            closePreferences();
        }
    }

    /** Open the Settings screen (if not already there) and select the given group. */
    private void ensureSettingsTourView(PreferenceGroup group) {
        if (!(contentPane.getUserData() instanceof PreferencesController)) {
            onPreferences();
        }
        if (contentPane.getUserData() instanceof PreferencesController prefsController) {
            prefsController.selectGroup(group);
        }
    }

    /** Intro steps — always present, even with no wallet open (sidebar + status bar). */
    private List<TourManager.TourStep> shellTourSteps() {
        return List.of(
                new TourManager.TourStep("walletSelector", "Current wallet",
                        "Switch between your loaded wallets from this dropdown. It stays here at all times."),
                new TourManager.TourStep("openWalletBtn", "Open a wallet",
                        "Load an existing wallet file from disk."),
                new TourManager.TourStep("createWalletBtn", "New or restore",
                        "Create a brand-new wallet, or restore one from an existing seed phrase. The tour continues automatically once you open your first wallet."),
                new TourManager.TourStep("connectionLabel", "Connection & network",
                        "Your network status — click to connect or disconnect. Block height and the active network are shown to its left.")
        );
    }

    /**
     * Wallet-scoped steps, plus the Settings walk-through. Each wallet step re-establishes the
     * wallet view on entry, and the Settings steps open the Settings screen in place, so Back/Next
     * navigation stays correct across the Settings boundary.
     */
    private List<TourManager.TourStep> walletTourSteps() {
        return List.of(
                new TourManager.TourStep("accountButtonsBox", "Wallet accounts",
                        "Switch between this wallet's Deposit, Premix, Postmix and Badbank accounts here.",
                        this::ensureWalletTourView),
                new TourManager.TourStep("balanceLabel", "Your balance",
                        "Your confirmed balance for the selected account. Pending mempool balance and UTXO count sit alongside it.",
                        this::ensureWalletTourView),
                new TourManager.TourStep("receiveBtn", "Receive funds",
                        "Generate a fresh receive address and QR code to fund your wallet.",
                        this::ensureWalletTourView),
                new TourManager.TourStep("mixSelectedBtn", "Mixing",
                        "Select coins and begin collaborative CoinJoin mixing. Start Mix and Mix To drive your mixing from here.",
                        this::ensureWalletTourView),
                new TourManager.TourStep("generalPrefsBtn", "General settings",
                        "This is Settings. Under General you'll find your units, fee-rates source and appearance options.",
                        () -> ensureSettingsTourView(PreferenceGroup.GENERAL)),
                new TourManager.TourStep("serverPrefsBtn", "Server settings",
                        "Under Server you choose how Ashigaru connects to Bitcoin — your own node or a public Electrum server, over Tor.",
                        () -> ensureSettingsTourView(PreferenceGroup.SERVER)),
                new TourManager.TourStep("toolsBtn", "Replay anytime",
                        "That's the tour! You can replay it whenever you like from Tools → Guided Tour.",
                        this::ensureWalletTourView)
        );
    }

    @FXML
    private void onTools() {
        try {
            FXMLLoader loader = new FXMLLoader(AppServices.class.getResource("gui/tools.fxml"));
            Node toolsPanel = loader.load();

            Button backBtn = new Button("← Back");
            backBtn.setOnAction(e -> closeTool());
            backBtn.getStyleClass().add("prefs-back-btn");

            Label pageTitle = new Label("Tools");
            pageTitle.getStyleClass().add("prefs-page-title");

            HBox pageHeader = new HBox(16, backBtn, pageTitle);
            pageHeader.setAlignment(Pos.CENTER_LEFT);
            pageHeader.getStyleClass().add("prefs-page-header");
            pageHeader.setPadding(new Insets(8, 16, 8, 16));

            VBox.setVgrow(toolsPanel, Priority.ALWAYS);
            VBox wrapper = new VBox(pageHeader, toolsPanel);

            contentPane.setCenter(wrapper);
            contentPane.setUserData(loader.getController());
        } catch(IOException e) {
            log.error("Error opening tools", e);
            showError("Error", "Could not open tools: " + e.getMessage());
        }
    }

    /** Opens Tools on Swaps, after a Badbank coin was chosen to swap */
    public void openSwapTool() {
        onTools();
        if(contentPane.getUserData() instanceof ToolsController tools) {
            tools.showSwaps();
        }
    }

    private void closeTool() {
        contentPane.setUserData(null);
        WalletListItem selected = walletSelector.getSelectionModel().getSelectedItem();
        if(selected != null && selected.isLoaded()) {
            selectWallet(selected.walletId());
        } else {
            showWelcome();
        }
    }

    @FXML
    private void onPreferences() {
        walletSelector.getSelectionModel().clearSelection();
        try {
            FXMLLoader loader = new FXMLLoader(AppServices.class.getResource("preferences/preferences.fxml"));
            Node prefsPanel = loader.load();
            PreferencesController prefsController = loader.getController();
            prefsController.initializeView(Config.get());
            prefsController.reconnectOnClosingProperty().set(AppServices.isConnecting() || AppServices.isConnected());
            prefsController.selectGroup(PreferenceGroup.GENERAL);

            Button backBtn = new Button("← Back");
            backBtn.setOnAction(e -> closePreferences());
            backBtn.getStyleClass().add("prefs-back-btn");

            Label pageTitle = new Label("Settings");
            pageTitle.getStyleClass().add("prefs-page-title");

            HBox pageHeader = new HBox(16, backBtn, pageTitle);
            pageHeader.setAlignment(Pos.CENTER_LEFT);
            pageHeader.getStyleClass().add("prefs-page-header");
            pageHeader.setPadding(new Insets(8, 16, 8, 16));

            VBox.setVgrow(prefsPanel, Priority.ALWAYS);
            VBox wrapper = new VBox(pageHeader, prefsPanel);

            contentPane.setCenter(wrapper);
            contentPane.setUserData(prefsController);
        } catch (IOException e) {
            log.error("Error loading preferences panel", e);
            showError("Error", "Could not load preferences: " + e.getMessage());
        }
    }

    private void closePreferences() {
        maybeReconnectOnLeavingPrefs();
        if (pendingSelectFile != null) {
            // A wallet was created/restored while in prefs — refreshWalletList will consume
            // pendingSelectFile and auto-select the new wallet
            refreshWalletList();
        } else if (currentWalletForm != null) {
            // Return to whichever wallet was open before entering prefs
            String wid = currentWalletForm.getWalletId();
            refreshWalletList();
            walletItems.stream()
                    .filter(item -> item.isLoaded() && wid.equals(item.walletId()))
                    .findFirst()
                    .ifPresentOrElse(
                            item -> walletSelector.getSelectionModel().select(item),
                            this::showWelcome);
        } else {
            walletSelector.getSelectionModel().select(PLACEHOLDER);
        }
    }

    public void openWalletFile(File file) {
        Storage storage = new Storage(file);
        try {
            if (!storage.isEncrypted()) {
                Platform.runLater(() -> runLoadService(storage, null));
            } else {
                Dialog<String> pwDialog = buildPasswordDialog(storage.getWalletName(null));
                Optional<String> result = pwDialog.showAndWait();
                result.ifPresent(pw -> Platform.runLater(() -> runLoadService(storage, new SecureString(pw))));
            }
        } catch (IOException e) {
            log.error("Could not check if wallet is encrypted", e);
        }
    }

    /**
     * Called on startup for each recent wallet file — does NOT prompt for a password.
     * Unencrypted wallets load immediately; encrypted wallets appear in the dropdown
     * with a lock icon and are only decrypted when the user explicitly selects them.
     */
    public void addRecentWalletFile(File file) {
        unloadedWalletFiles.add(file);
        Platform.runLater(this::refreshWalletList);
    }

    /**
     * Called when the user selects a locked wallet from the dropdown.
     * Shows a passphrase dialog; on cancel resets selection to PLACEHOLDER.
     */
    private void unlockWallet(WalletListItem item) {
        Storage storage = new Storage(item.walletFile());
        try {
            if (!storage.isEncrypted()) {
                pendingSelectFile = item.walletFile();
                Platform.runLater(() -> runLoadService(storage, null));
                return;
            }
        } catch (IOException e) {
            log.warn("Could not check encryption status: " + item.walletFile(), e);
        }
        String walletName = item.displayName().replaceFirst("^\uD83D\uDD12\\s*", "");
        Dialog<String> pwDialog = buildPasswordDialog(walletName);
        Optional<String> result = pwDialog.showAndWait();
        if (result.isEmpty() || result.get() == null) {
            Platform.runLater(() -> walletSelector.getSelectionModel().select(PLACEHOLDER));
            return;
        }
        pendingSelectFile = item.walletFile();
        runLoadService(storage, new SecureString(result.get()));
    }

    private Dialog<String> buildPasswordDialog(String walletName) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Wallet Password");
        dialog.initOwner(AshigaruGui.get().getMainStage());
        WalletCreationFlow.styleWizardDialog(dialog, "OPEN WALLET", "Wallet password",
                "Enter the password for " + walletName + ".");

        ButtonType okBtn = new ButtonType("Unlock", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);
        WalletCreationFlow.styleWizardButtons(dialog.getDialogPane());

        PasswordField pf = new PasswordField();
        pf.setPromptText("Password");
        VBox vbox = new VBox(pf);
        vbox.setSpacing(8);
        dialog.getDialogPane().setContent(vbox);
        Platform.runLater(pf::requestFocus);
        dialog.setResultConverter(btn -> btn == okBtn ? pf.getText() : null);
        return dialog;
    }

    private void runLoadService(Storage storage, SecureString password) {
        Storage.LoadWalletService svc = password == null
                ? new Storage.LoadWalletService(storage)
                : new Storage.LoadWalletService(storage, password);

        svc.setOnSucceeded(e -> {
            WalletAndKey wak = svc.getValue();
            try {
                storage.restorePublicKeysFromSeed(wak.getWallet(), wak.getKey());
                if (!wak.getWallet().isValid()) {
                    // Distinguish "user cancelled the passphrase prompt" from "aux/child wallet opened standalone"
                    boolean passphraseCancelled = wak.getWallet().getKeystores().stream()
                            .anyMatch(ks -> ks.hasSeed() && ks.getSeed().getPassphrase() == null
                                    && ks.getSeed().needsPassphrase());
                    if (passphraseCancelled) {
                        log.info("Wallet not opened — BIP39 passphrase prompt was cancelled: {}",
                                storage.getWalletFile().getName());
                        showWizardError("WALLET NOT OPENED", "Passphrase required",
                                "The BIP39 passphrase is required to open this wallet. "
                                        + "No passphrase was entered, so the wallet was not opened.");
                    } else {
                        log.warn("Wallet file is not valid (likely a child/aux wallet opened standalone): {}",
                                storage.getWalletFile().getName());
                        showWizardError("CANNOT OPEN", "Not a standalone wallet",
                                "This file is not a complete wallet on its own. "
                                        + "If it's a Premix, Postmix or Badbank file, open the parent wallet instead and use the tabs.");
                    }
                    Platform.runLater(() -> walletSelector.getSelectionModel().select(PLACEHOLDER));
                    return;
                }
                AshigaruGui.addWallet(storage, wak.getWallet());
                for (Map.Entry<WalletAndKey, Storage> entry : wak.getChildWallets().entrySet()) {
                    Storage childStorage = entry.getValue();
                    WalletAndKey childWak = entry.getKey();
                    childStorage.restorePublicKeysFromSeed(childWak.getWallet(), childWak.getKey());
                    AshigaruGui.addWallet(childStorage, childWak.getWallet());
                }
            } catch (Exception ex) {
                log.error("Error opening wallet", ex);
                showError("Error Opening Wallet", ex.getMessage());
            } finally {
                wak.clear();
            }
        });
        svc.setOnFailed(e -> {
            Throwable ex = svc.getException();
            if (ex instanceof InvalidPasswordException) {
                Optional<ButtonType> retry = showError(
                        "Invalid Password", "The wallet password was incorrect. Try again?",
                        ButtonType.CANCEL, ButtonType.OK);
                if (retry.isPresent() && retry.get() == ButtonType.OK) {
                    Platform.runLater(() -> {
                        Dialog<String> d = buildPasswordDialog(storage.getWalletName(null));
                        d.showAndWait().ifPresent(pw -> runLoadService(storage, new SecureString(pw)));
                    });
                }
            } else if (ex instanceof StorageException) {
                showError("Error Opening Wallet", ex.getMessage());
            }
        });
        svc.start();
    }

    // -------------------------------------------------------------------------
    // Event subscriptions
    // -------------------------------------------------------------------------

    @Subscribe
    public void childWalletsAdded(ChildWalletsAddedEvent event) {
        if (!event.getChildWallets().isEmpty()) {
            for (Wallet childWallet : event.getChildWallets()) {
                AshigaruGui.addWallet(event.getStorage(), childWallet);
            }
        }
    }

    @Subscribe
    public void connectionEvent(ConnectionEvent event) {
        Platform.runLater(() -> {
            setConnState(ConnState.CONNECTED);
            Integer height = AppServices.getCurrentBlockHeight();
            if (height != null) {
                blockHeightLabel.setText("Block " + height);
            }
        });
    }

    @Subscribe
    public void disconnectionEvent(DisconnectionEvent event) {
        Platform.runLater(() -> setConnState(ConnState.DISCONNECTED));
    }

    @Subscribe
    public void connectionStartEvent(ConnectionStartEvent event) {
        Platform.runLater(() -> setConnState(ConnState.CONNECTING));
    }

    @Subscribe
    public void torBootStatusEvent(TorBootStatusEvent event) {
        Platform.runLater(() -> setConnState(ConnState.CONNECTING));
    }

    @Subscribe
    public void torReadyStatusEvent(TorReadyStatusEvent event) {
        Platform.runLater(() -> statusLabel.setText("Tor is running"));
    }

    @Subscribe
    public void torFailedStatusEvent(TorFailedStatusEvent event) {
        Platform.runLater(() -> {
            setConnState(ConnState.DISCONNECTED);
            statusLabel.setText("Tor failed to start — right-click the connection status to restart it");
        });
    }

    @Subscribe
    public void connectionFailedEvent(ConnectionFailedEvent event) {
        Platform.runLater(() -> setConnState(ConnState.DISCONNECTED));
    }

    @Subscribe
    public void newBlock(NewBlockEvent event) {
        Platform.runLater(() -> blockHeightLabel.setText("Block " + event.getHeight()));
    }

    @Subscribe
    public void statusEvent(StatusEvent event) {
        Platform.runLater(() -> statusLabel.setText(event.getStatus()));
    }

    @Subscribe
    public void walletHistoryStarted(WalletHistoryStartedEvent event) {
        if (event.getWallet().isMasterWallet()) {
            Platform.runLater(() -> statusLabel.setText("⟳  Syncing " + event.getWallet().getDisplayName() + "…"));
        }
    }

    @Subscribe
    public void walletHistoryFinished(WalletHistoryFinishedEvent event) {
        if (event.getWallet().isMasterWallet()) {
            Platform.runLater(() -> statusLabel.setText("Ready"));
        }
    }

    @Subscribe
    public void walletHistoryFailed(WalletHistoryFailedEvent event) {
        walletHistoryFinished(new WalletHistoryFinishedEvent(event.getWallet()));
    }

    /**
     * Posted by WalletForm when an all-history change on a passphrase wallet suggests a mistyped
     * passphrase and the user chose "Reopen Wallet". Unload the (wrong-passphrase) wallet and reopen
     * it so the passphrase is prompted again — the same lock/unlock plumbing the Lock button uses.
     */
    @Subscribe
    public void requestWalletOpen(RequestWalletOpenEvent event) {
        File file = event.getFile();
        if (file == null) return;
        Platform.runLater(() -> {
            WalletForm form = AshigaruGui.get().getWalletForms().values().stream()
                    .filter(f -> f.getWallet().isMasterWallet() && file.equals(f.getStorage().getWalletFile()))
                    .findFirst().orElse(null);
            if (form != null) {
                unloadWalletForm(form);
            }
            unloadedWalletFiles.add(file);
            refreshWalletList();
            openWalletFile(file);
        });
    }

    @Subscribe
    public void walletOpened(WalletOpenedEvent event) {
        if (event.getWallet().isMasterWallet()) {
            Platform.runLater(() -> {
                unloadedWalletFiles.remove(event.getStorage().getWalletFile());
                if (contentPane.getUserData() instanceof PreferencesController && pendingSelectFile != null) {
                    // Wallet created/restored while in preferences — leave prefs and go to the new wallet
                    closePreferences();
                } else {
                    refreshWalletList();
                }
            });
        }
    }

    public void refreshWalletList() {
        if (contentPane.getUserData() instanceof PreferencesController) return;
        WalletListItem currentSelection = walletSelector.getSelectionModel().getSelectedItem();

        walletItems.clear();

        // PLACEHOLDER is always first
        walletItems.add(PLACEHOLDER);

        // Collect the files of all loaded wallets so we can skip them in the unloaded set
        Set<File> loadedFiles = new HashSet<>();
        for (WalletForm form : AshigaruGui.get().getWalletForms().values()) {
            if (form.getWallet().isMasterWallet()) {
                String name = form.getWallet().getFullDisplayName();
                int dash = name.lastIndexOf(" - ");
                if (dash > 0) name = name.substring(0, dash);
                walletItems.add(new WalletListItem(form.getWalletId(), name, form.getStorage().getWalletFile()));
                loadedFiles.add(form.getStorage().getWalletFile());
            }
        }

        // Locked (unloaded) wallets — show with a lock prefix so the user knows they need unlocking
        for (File f : unloadedWalletFiles) {
            if (!loadedFiles.contains(f)) {
                String displayName = "\uD83D\uDD12 " + deriveWalletName(f);
                walletItems.add(new WalletListItem(null, displayName, f));
            }
        }

        // After a successful unlock, auto-select the just-loaded wallet
        if (pendingSelectFile != null) {
            File toSelect = pendingSelectFile;
            pendingSelectFile = null;
            Optional<WalletListItem> autoSelect = walletItems.stream()
                    .filter(item -> item.isLoaded() && toSelect.equals(item.walletFile()))
                    .findFirst();
            if (autoSelect.isPresent()) {
                walletSelector.getSelectionModel().select(autoSelect.get());
                return;
            }
            // Wallet not loaded (unlock failed?) — fall through to normal selection restoration
        }

        // Restore prior selection by walletId, or fall back to PLACEHOLDER
        if (currentSelection != null && currentSelection.isLoaded()) {
            walletItems.stream()
                    .filter(item -> item.isLoaded() && item.walletId().equals(currentSelection.walletId()))
                    .findFirst()
                    .ifPresentOrElse(
                            item -> walletSelector.getSelectionModel().select(item),
                            () -> walletSelector.getSelectionModel().select(PLACEHOLDER));
        } else {
            walletSelector.getSelectionModel().select(PLACEHOLDER);
        }
    }

    private Optional<ButtonType> showError(String title, String message, ButtonType... buttons) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message,
                buttons.length > 0 ? buttons : new ButtonType[]{ButtonType.OK});
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.initOwner(AshigaruGui.get().getMainStage());
        AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());
        return alert.showAndWait();
    }

    private void showWizardError(String eyebrow, String title, String message) {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle(title);
        dlg.initOwner(AshigaruGui.get().getMainStage());
        WalletCreationFlow.styleWizardDialog(dlg, eyebrow, title, message);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.OK);
        WalletCreationFlow.styleWizardButtons(dlg.getDialogPane());
        dlg.getDialogPane().setPrefWidth(460);
        AppServices.moveToActiveWindowScreen(dlg);
        dlg.setResultConverter(bt -> null);
        dlg.showAndWait();
    }

    private static String deriveWalletName(File file) {
        String name = file.getName();
        if (name.endsWith(".mv.db")) return name.substring(0, name.length() - 6);
        if (name.endsWith(".json"))  return name.substring(0, name.length() - 5);
        return name;
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    record WalletListItem(String walletId, String displayName, File walletFile) {
        /** True when this item represents a fully-loaded wallet. */
        boolean isLoaded() { return walletId != null; }
        /** True for the "Select a wallet…" sentinel row. */
        boolean isPlaceholder() { return walletId == null && walletFile == null; }
        @Override
        public String toString() { return displayName; }
    }

    private static class WalletListCell extends ListCell<WalletListItem> {
        @Override
        protected void updateItem(WalletListItem item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("wallet-selector-placeholder");
            if (empty || item == null) {
                setText(null);
            } else {
                setText(item.displayName());
                if (item.isPlaceholder()) {
                    getStyleClass().add("wallet-selector-placeholder");
                }
            }
        }
    }
}
