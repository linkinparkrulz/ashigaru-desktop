package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.InvalidPasswordException;
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
import com.sparrowwallet.sparrow.io.Storage;
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
    @FXML private ListView<WalletListItem> walletListView;
    @FXML private BorderPane contentPane;
    @FXML private Label statusLabel;
    @FXML private StackPane welcomePane;

    private final ObservableList<WalletListItem> walletItems = FXCollections.observableArrayList();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        walletListView.setItems(walletItems);
        walletListView.setCellFactory(lv -> new WalletListCell(this));
        walletListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                showWalletPanel(selected.walletId());
            }
        });
        // Also handle re-clicks on the already-selected wallet (no selection change fires)
        walletListView.setOnMouseClicked(e -> {
            WalletListItem sel = walletListView.getSelectionModel().getSelectedItem();
            if (sel != null) showWalletPanel(sel.walletId());
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
    }

    private void showWalletPanel(String walletId) {
        maybeReconnectOnLeavingPrefs();
        try {
            WalletForm walletForm = AshigaruGui.get().getWalletForms().get(walletId);
            if (walletForm == null) return;

            FXMLLoader loader = new FXMLLoader(getClass().getResource("ashigaru-wallet.fxml"));
            Node walletPanel = loader.load();
            AshigaruWalletController controller = loader.getController();
            controller.setWalletForm(walletForm);
            contentPane.setCenter(walletPanel);
        } catch (Exception e) {
            log.error("Error loading wallet panel", e);
            AppServices.showErrorDialog("Error", "Could not load wallet view: " + e.getMessage());
        }
    }

    public void refreshWalletList() {
        String currentSelection = walletListView.getSelectionModel().getSelectedItem() != null
                ? walletListView.getSelectionModel().getSelectedItem().walletId() : null;

        walletItems.clear();
        for (WalletForm form : AshigaruGui.get().getWalletForms().values()) {
            if (form.getWallet().isMasterWallet()) {
                walletItems.add(new WalletListItem(form.getWalletId(), form.getWallet().getFullDisplayName()));
            }
        }

        if (currentSelection != null) {
            walletItems.stream()
                    .filter(item -> item.walletId().equals(currentSelection))
                    .findFirst()
                    .ifPresent(item -> walletListView.getSelectionModel().select(item));
        } else if (!walletItems.isEmpty()) {
            walletListView.getSelectionModel().selectFirst();
        }
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
            svc.setOnSucceeded(e -> Platform.runLater(() -> {
                AshigaruGui.removeWallet(item.walletId());
                refreshWalletList();
                showWelcome();
            }));
            svc.setOnFailed(e -> Platform.runLater(() ->
                    AppServices.showErrorDialog("Delete Failed",
                            svc.getException().getMessage())));
            svc.start();
        });
    }

    @FXML
    private void onPreferences() {
        try {
            FXMLLoader loader = new FXMLLoader(AppServices.class.getResource("preferences/preferences.fxml"));
            Node prefsPanel = loader.load();
            PreferencesController prefsController = loader.getController();
            prefsController.initializeView(Config.get());
            prefsController.reconnectOnClosingProperty().set(AppServices.isConnecting() || AppServices.isConnected());
            contentPane.setCenter(prefsPanel);
            contentPane.setUserData(prefsController);
            // Auto-navigate to General so preferences never opens blank
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
                // Password dialog
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
    public void connectionEvent(ConnectionEvent event) {
        Platform.runLater(() -> {
            updateConnectionLabel(true);
            // Update block height immediately on connection if available
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
    public void walletOpened(WalletOpenedEvent event) {
        if (event.getWallet().isMasterWallet()) {
            Platform.runLater(this::refreshWalletList);
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
        private final AshigaruMainController controller;

        WalletListCell(AshigaruMainController controller) {
            this.controller = controller;
        }

        @Override
        protected void updateItem(WalletListItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setContextMenu(null);
            } else {
                setText(item.displayName());
                MenuItem deleteItem = new MenuItem("Delete Wallet...");
                deleteItem.setOnAction(e -> controller.deleteWallet(item));
                setContextMenu(new ContextMenu(deleteItem));
            }
        }
    }
}
