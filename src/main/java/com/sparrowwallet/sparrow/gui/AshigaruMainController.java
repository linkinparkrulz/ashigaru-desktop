package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.InvalidPasswordException;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
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
import javafx.scene.layout.BorderPane;
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
    @FXML private ToggleGroup accountToggleGroup;
    @FXML private ToggleButton depositBtn;
    @FXML private ToggleButton premixBtn;
    @FXML private ToggleButton postmixBtn;
    @FXML private ToggleButton badbankBtn;

    private final ObservableList<WalletListItem> walletItems = FXCollections.observableArrayList();
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
            if (selected != null) {
                selectWallet(selected.walletId());
            }
        });

        // Account toggle group - prevent deselecting all
        accountToggleGroup.selectedToggleProperty().addListener((obs, old, neu) -> {
            if (neu == null && old != null) {
                old.setSelected(true);
            }
        });

        showWelcome();
        EventManager.get().register(this);
        updateNetworkLabel();
        updateConnectionLabel(AppServices.isConnected());
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    private void maybeReconnectOnLeavingPrefs() {
        if (contentPane.getUserData() instanceof PreferencesController prefsCtrl) {
            if (prefsCtrl.isReconnectOnClosing() && !(AppServices.isConnecting() || AppServices.isConnected())) {
                EventManager.get().post(new RequestConnectEvent());
            }
            contentPane.setUserData(null);
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
            AppServices.showErrorDialog("Error", "Could not load wallet view: " + e.getMessage());
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

    @FXML
    private void onCreateWallet() {
        new WalletCreationFlow(AshigaruGui.get().getMainStage()).start();
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

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Wallet");
        confirm.setHeaderText("Delete \"" + item.displayName() + "\"?");
        confirm.setContentText("This will permanently delete the wallet file. This cannot be undone.");
        confirm.initOwner(AshigaruGui.get().getMainStage());
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.OK) return;
            Storage.DeleteWalletService svc = new Storage.DeleteWalletService(form.getStorage(), false);
            svc.setOnSucceeded(e -> {
                svc.cancel();
                AshigaruGui.removeWallet(item.walletId());
                refreshWalletList();
                showWelcome();
            });
            svc.setOnFailed(e -> {
                svc.cancel();
                AppServices.showErrorDialog("Delete Failed", svc.getException().getMessage());
            });
            svc.start();
        });
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
            contentPane.setCenter(prefsPanel);
            contentPane.setUserData(prefsController);
            prefsController.selectGroup(PreferenceGroup.GENERAL);
        } catch (IOException e) {
            log.error("Error loading preferences panel", e);
            AppServices.showErrorDialog("Error", "Could not load preferences: " + e.getMessage());
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
                AppServices.showErrorDialog("Error Opening Wallet", ex.getMessage());
            } finally {
                wak.clear();
            }
        });
        svc.setOnFailed(e -> {
            Throwable ex = svc.getException();
            if (ex instanceof InvalidPasswordException) {
                Optional<ButtonType> retry = AppServices.showErrorDialog(
                        "Invalid Password", "The wallet password was incorrect. Try again?",
                        ButtonType.CANCEL, ButtonType.OK);
                if (retry.isPresent() && retry.get() == ButtonType.OK) {
                    Platform.runLater(() -> {
                        Dialog<String> d = buildPasswordDialog(storage.getWalletName(null));
                        d.showAndWait().ifPresent(pw -> runLoadService(storage, new SecureString(pw)));
                    });
                }
            } else if (ex instanceof StorageException) {
                AppServices.showErrorDialog("Error Opening Wallet", ex.getMessage());
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
            Platform.runLater(this::refreshWalletList);
        }
    }

    public void refreshWalletList() {
        String currentSelection = walletSelector.getSelectionModel().getSelectedItem() != null
                ? walletSelector.getSelectionModel().getSelectedItem().walletId() : null;

        walletItems.clear();
        for (WalletForm form : AshigaruGui.get().getWalletForms().values()) {
            if (form.getWallet().isMasterWallet()) {
                String name = form.getWallet().getFullDisplayName();
                int dash = name.lastIndexOf(" - ");
                if (dash > 0) name = name.substring(0, dash);
                walletItems.add(new WalletListItem(form.getWalletId(), name));
            }
        }

        // Don't auto-navigate when preferences is open
        if (contentPane.getUserData() instanceof PreferencesController) {
            return;
        }

        if (currentSelection != null) {
            walletItems.stream()
                    .filter(item -> item.walletId().equals(currentSelection))
                    .findFirst()
                    .ifPresent(item -> walletSelector.getSelectionModel().select(item));
        } else if (!walletItems.isEmpty()) {
            walletSelector.getSelectionModel().selectFirst();
        }
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    record WalletListItem(String walletId, String displayName) {
        @Override
        public String toString() { return displayName; }
    }

    private static class WalletListCell extends ListCell<WalletListItem> {
        @Override
        protected void updateItem(WalletListItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
            } else {
                setText(item.displayName());
            }
        }
    }
}
