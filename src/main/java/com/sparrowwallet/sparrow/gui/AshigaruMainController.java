package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.InvalidPasswordException;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.SeedDisplayDialog;
import com.sparrowwallet.sparrow.control.WalletPasswordDialog;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.preferences.PreferencesController;
import com.sparrowwallet.sparrow.preferences.PreferenceGroup;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.io.StorageException;
import com.sparrowwallet.sparrow.io.WalletAndKey;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;

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
        updateConnectionLabel(AppServices.isConnected());
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
        maybeReconnectOnLeavingPrefs();
        contentPane.setCenter(welcomePane);
        // walletSelector stays visible in the sidebar at all times
        accountButtonsBox.setVisible(false);
        accountButtonsBox.setManaged(false);
        deleteWalletBtn.setVisible(false);
        deleteWalletBtn.setManaged(false);
        viewSeedBtn.setVisible(false);
        viewSeedBtn.setManaged(false);
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

        boolean encrypted = false;
        try { encrypted = walletForm.getStorage().isEncrypted(); } catch (IOException ignored) {}
        lockWalletBtn.setVisible(encrypted);
        lockWalletBtn.setManaged(encrypted);

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
                currentWalletController = null;
            }

            // Get the appropriate account form based on selected account
            WalletForm activeForm = getAccountForm(currentWalletForm, selectedAccount);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("ashigaru-wallet.fxml"));
            Node walletPanel = loader.load();
            currentWalletController = loader.getController();
            currentWalletController.setWalletForm(activeForm, currentWalletForm);
            contentPane.setCenter(walletPanel);
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

    private void updateConnectionLabel(boolean connected) {
        if (connected) {
            connectionLabel.setText("● Connected");
            connectionLabel.getStyleClass().removeAll("disconnected");
            connectionLabel.getStyleClass().add("connected");
        } else {
            connectionLabel.setText("○ Disconnected");
            connectionLabel.getStyleClass().removeAll("connected");
            connectionLabel.getStyleClass().add("disconnected");
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
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Delete Wallet");
            confirm.setHeaderText("Delete \"" + item.displayName() + "\"?");
            confirm.setContentText("This will permanently delete the wallet file. This cannot be undone.");
            confirm.initOwner(AshigaruGui.get().getMainStage());
            confirm.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) return;
                doDelete(item, form);
            });
        }
    }

    private void doDelete(WalletListItem item, WalletForm form) {
        Storage.DeleteWalletService svc = new Storage.DeleteWalletService(form.getStorage(), false);
        svc.setOnSucceeded(e -> {
            svc.cancel();
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
    private void onLockWallet() {
        if (currentWalletForm == null) return;
        File walletFile = currentWalletForm.getStorage().getWalletFile();
        // Remove nested wallet forms from the registry (master removal won't clean these)
        for (WalletForm nested : currentWalletForm.getNestedWalletForms()) {
            AshigaruGui.get().getWalletForms().remove(nested.getWalletId());
        }
        AshigaruGui.removeWallet(currentWalletForm.getWalletId());
        currentWalletForm = null;
        unloadedWalletFiles.add(walletFile);
        refreshWalletList();
        showWelcome();
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

            Label pageTitle = new Label("Preferences");
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
        if (currentWalletForm != null) {
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
        try {
            Storage storage = new Storage(file);
            if (!storage.isEncrypted()) {
                Platform.runLater(() -> runLoadService(storage, null));
                return;
            }
        } catch (IOException e) {
            log.warn("Could not determine encryption status, treating as encrypted: " + file, e);
        }
        // Either confirmed encrypted, or couldn't tell — show as locked in dropdown
        unloadedWalletFiles.add(file);
        Platform.runLater(this::refreshWalletList);
    }

    /**
     * Called when the user selects a locked wallet from the dropdown.
     * Shows a passphrase dialog; on cancel resets selection to PLACEHOLDER.
     */
    private void unlockWallet(WalletListItem item) {
        Storage storage = new Storage(item.walletFile());
        String walletName = item.displayName().replaceFirst("^\uD83D\uDD12\\s*", "");
        Dialog<String> pwDialog = buildPasswordDialog(walletName);
        Optional<String> result = pwDialog.showAndWait();
        if (result.isEmpty() || result.get() == null) {
            // Cancelled — reset to placeholder without triggering another prompt
            Platform.runLater(() -> walletSelector.getSelectionModel().select(PLACEHOLDER));
            return;
        }
        pendingSelectFile = item.walletFile();
        runLoadService(storage, new SecureString(result.get()));
    }

    private Dialog<String> buildPasswordDialog(String walletName) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Wallet Password");
        dialog.setHeaderText("Enter password for: " + walletName);
        dialog.initOwner(AshigaruGui.get().getMainStage());

        ButtonType okBtn = new ButtonType("Unlock", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

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
                    throw new IllegalStateException("Wallet file is not valid.");
                }
                AshigaruGui.addWallet(storage, wak.getWallet());
                for (Map.Entry<WalletAndKey, Storage> entry : wak.getChildWallets().entrySet()) {
                    AshigaruGui.addWallet(entry.getValue(), entry.getKey().getWallet());
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
            updateConnectionLabel(true);
            Integer height = AppServices.getCurrentBlockHeight();
            if (height != null) {
                blockHeightLabel.setText("Block " + height);
            }
        });
    }

    @Subscribe
    public void disconnectionEvent(DisconnectionEvent event) {
        Platform.runLater(() -> updateConnectionLabel(false));
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
            Platform.runLater(() -> statusLabel.setText("Syncing " + event.getWallet().getDisplayName() + "…"));
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

    @Subscribe
    public void walletOpened(WalletOpenedEvent event) {
        if (event.getWallet().isMasterWallet()) {
            Platform.runLater(() -> {
                unloadedWalletFiles.remove(event.getStorage().getWalletFile());
                refreshWalletList();
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
        return alert.showAndWait();
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
