package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.samourai.whirlpool.client.wallet.beans.MixProgress;
import com.samourai.whirlpool.client.wallet.beans.Tx0FeeTarget;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.Theme;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.*;
import com.sparrowwallet.sparrow.whirlpool.Whirlpool;
import com.sparrowwallet.sparrow.whirlpool.WhirlpoolServices;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class AshigaruWalletController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(AshigaruWalletController.class);

    @FXML private Label walletNameLabel;
    @FXML private Label accountNameLabel;
    @FXML private Button refreshBtn;
    @FXML private Button receiveBtn;
    @FXML private Button receiveCta;
    @FXML private TabPane accountTabs;
    @FXML private Label balanceLabel;
    @FXML private Label mempoolLabel;
    @FXML private Label utxoCountLabel;
    @FXML private HBox badbankInfoBar;
    @FXML private Label badbankInfoLabel;
    @FXML private ToggleButton utxoViewBtn;
    @FXML private ToggleButton txnViewBtn;
    @FXML private TableView<UtxoRow> utxoTable;
    @FXML private TableColumn<UtxoRow, Boolean> colCheck;
    @FXML private TableColumn<UtxoRow, String> colDate;
    @FXML private TableColumn<UtxoRow, String> colOutput;
    @FXML private TableColumn<UtxoRow, String> colAddress;
    @FXML private TableColumn<UtxoRow, String> colLabel;
    @FXML private TableColumn<UtxoRow, String> colMixes;
    @FXML private TableColumn<UtxoRow, String> colValue;
    @FXML private TableView<TxnRow> txnTable;
    @FXML private TableColumn<TxnRow, String> colTxnDate;
    @FXML private TableColumn<TxnRow, String> colTxnId;
    @FXML private TableColumn<TxnRow, String> colTxnLabel;
    @FXML private TableColumn<TxnRow, String> colTxnAmount;
    @FXML private HBox mixButtonBox;
    @FXML private Button startMixBtn;
    @FXML private Button mixToBtn;
    @FXML private Button mixSelectedBtn;
    @FXML private VBox accountPanel;

    private WalletForm currentWalletForm;   // master wallet form
    private WalletForm activeAccountForm;   // currently shown account form

    private final ObservableList<UtxoRow> utxoRows = FXCollections.observableArrayList();
    private final ObservableList<TxnRow> txnRows   = FXCollections.observableArrayList();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // UTXO table
        utxoTable.setItems(utxoRows);
        utxoTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        colCheck.setCellValueFactory(d -> d.getValue().selected);
        colCheck.setCellFactory(CheckBoxTableCell.forTableColumn(colCheck));

        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().date));
        colOutput.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().output));
        colOutput.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isEmpty()) {
                    setText(null); setGraphic(null);
                } else {
                    Label lbl = new Label(item);
                    lbl.setMaxWidth(Double.MAX_VALUE);
                    HBox.setHgrow(lbl, javafx.scene.layout.Priority.ALWAYS);
                    HBox box = new HBox(4, lbl, makeCopyButton(item));
                    box.setAlignment(Pos.CENTER_LEFT);
                    setText(null); setGraphic(box);
                }
            }
        });
        colAddress.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().address));
        colMixes.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().mixes));
        colValue.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().value));

        colAddress.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isEmpty()) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label lbl = new Label(item);
                    lbl.setMaxWidth(Double.MAX_VALUE);
                    HBox.setHgrow(lbl, javafx.scene.layout.Priority.ALWAYS);
                    HBox box = new HBox(4, lbl, makeCopyButton(item));
                    box.setAlignment(Pos.CENTER_LEFT);
                    setText(null);
                    setGraphic(box);
                }
            }
        });

        colLabel.setCellValueFactory(d -> d.getValue().utxoEntry.labelProperty());
        colLabel.setCellFactory(TextFieldTableCell.forTableColumn());
        colLabel.setEditable(true);
        colLabel.setOnEditCommit(event ->
                event.getRowValue().utxoEntry.labelProperty().set(event.getNewValue()));
        utxoTable.setEditable(true);

        txnTable.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<TxnRow>) c -> updateActionButtons());

        // Transaction table
        txnTable.setItems(txnRows);
        txnTable.setEditable(true);
        colTxnDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().date()));
        colTxnId.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().txid()));
        colTxnId.setCellFactory(col -> new TableCell<TxnRow, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null); setText(null); return;
                }
                Label lbl = new Label(item);
                lbl.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(lbl, javafx.scene.layout.Priority.ALWAYS);
                HBox hbox = new HBox(4, lbl, makeCopyButton(item));
                hbox.setAlignment(Pos.CENTER_LEFT);
                setGraphic(hbox); setText(null);
            }
        });
        colTxnLabel.setCellValueFactory(d -> d.getValue().txnEntry().labelProperty());
        colTxnLabel.setEditable(true);
        colTxnLabel.setOnEditCommit(event -> {
            TxnRow row = event.getRowValue();
            if (row != null) {
                row.txnEntry().labelProperty().set(
                        event.getNewValue() != null ? event.getNewValue() : "");
            }
        });
        colTxnLabel.setCellFactory(col -> new TableCell<TxnRow, String>() {
            private final TextField textField = new TextField();
            {
                textField.setOnAction(e -> commitEdit(textField.getText()));
                textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                    if (wasFocused && !isFocused && isEditing()) commitEdit(textField.getText());
                });
                textField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancelEdit(); });
                setOnMouseClicked(e -> { if (!isEmpty()) getTableView().edit(getIndex(), getTableColumn()); });
            }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setText(null); setGraphic(null); }
                else if (isEditing()) { setText(null); setGraphic(textField); }
                else { setText(item != null ? item : ""); setGraphic(null); }
            }
            @Override public void startEdit() {
                super.startEdit();
                textField.setText(getItem() != null ? getItem() : "");
                setText(null); setGraphic(textField);
                textField.requestFocus(); textField.selectAll();
            }
            @Override public void cancelEdit() {
                super.cancelEdit();
                setText(getItem() != null ? getItem() : ""); setGraphic(null);
            }
            @Override public void commitEdit(String newValue) {
                super.commitEdit(newValue);
                setText(newValue != null ? newValue : ""); setGraphic(null);
            }
        });
        colTxnAmount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().amount()));

        // View toggle group
        ToggleGroup viewGroup = new ToggleGroup();
        utxoViewBtn.setToggleGroup(viewGroup);
        txnViewBtn.setToggleGroup(viewGroup);
        // Prevent deselecting both
        viewGroup.selectedToggleProperty().addListener((obs, old, neu) -> {
            if (neu == null) old.setSelected(true);
        });

        EventManager.get().register(this);
    }

    private ChangeListener<Tab> tabSelectionListener;

    // -------------------------------------------------------------------------
    // Public API: called by AshigaruMainController after FXML load
    // -------------------------------------------------------------------------

    public void setWalletForm(WalletForm form) {
        setWalletForm(form, form);
    }

    public void setWalletForm(WalletForm activeForm, WalletForm masterForm) {
        this.currentWalletForm = masterForm;
        this.activeAccountForm = activeForm;
        // Wallet name label removed from UI - account is now shown in sidebar
        // walletNameLabel.setText(masterForm.getWallet().getFullDisplayName());

        refreshAccountView();
    }

    // -------------------------------------------------------------------------
    // Tab building
    // -------------------------------------------------------------------------

    private void buildAccountTabs(WalletForm masterForm) {
        if (accountTabs == null) return; // account switching is handled by the main sidebar
        // Remember which tab was active
        Tab previouslySelected = accountTabs.getSelectionModel().getSelectedItem();
        String previousTabText = previouslySelected != null ? previouslySelected.getText() : null;

        // Remove old tab selection listener before clearing
        if (tabSelectionListener != null) {
            accountTabs.getSelectionModel().selectedItemProperty().removeListener(tabSelectionListener);
        }
        accountTabs.getTabs().clear();

        // Master wallet = Deposit account
        Tab depositTab = buildAccountTab("Deposit", masterForm);
        accountTabs.getTabs().add(depositTab);

        // Child wallets (Premix, Postmix, Badbank only — skip payment codes and other accounts)
        for (WalletForm nestedForm : masterForm.getNestedWalletForms()) {
            Wallet wallet = nestedForm.getWallet();
            StandardAccount accountType = wallet.getStandardAccountType();
            if (accountType == null) continue;
            String tabName = switch (accountType) {
                case WHIRLPOOL_PREMIX  -> "Premix";
                case WHIRLPOOL_POSTMIX -> "Postmix";
                case WHIRLPOOL_BADBANK -> "Badbank";
                default -> null;  // skip all other child accounts (payment codes, etc.)
            };
            if (tabName == null) continue;
            accountTabs.getTabs().add(buildAccountTab(tabName, nestedForm));
        }

        // Restore previously selected tab, or default to first
        Tab toSelect = accountTabs.getTabs().stream()
                .filter(t -> t.getText().equals(previousTabText))
                .findFirst()
                .orElse(accountTabs.getTabs().isEmpty() ? null : accountTabs.getTabs().get(0));
        if (toSelect != null) {
            accountTabs.getSelectionModel().select(toSelect);
            if (toSelect.getUserData() instanceof WalletForm form) {
                activateAccountForm(form);
            }
        }

        tabSelectionListener = (obs, old, tab) -> {
            if (tab != null && tab.getUserData() instanceof WalletForm form) {
                activateAccountForm(form);
            }
        };
        accountTabs.getSelectionModel().selectedItemProperty().addListener(tabSelectionListener);
    }

    private Tab buildAccountTab(String name, WalletForm form) {
        Tab tab = new Tab(name);
        tab.setClosable(false);
        tab.setUserData(form);
        return tab;
    }

    private void activateAccountForm(WalletForm form) {
        activeAccountForm = form;
        refreshAccountView();
    }

    // -------------------------------------------------------------------------
    // Account view refresh
    // -------------------------------------------------------------------------

    private void refreshAccountView() {
        if (activeAccountForm == null) return;
        Wallet wallet = activeAccountForm.getWallet();
        WalletUtxosEntry utxosEntry = activeAccountForm.getWalletUtxosEntry();

        // Balances
        UnitFormat fmt = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
        balanceLabel.setText(fmt.formatBtcValue(utxosEntry.getBalance()) + " BTC");
        mempoolLabel.setText(fmt.formatBtcValue(utxosEntry.getMempoolBalance()) + " BTC");
        utxoCountLabel.setText(String.valueOf(
                utxosEntry.getChildren() != null ? utxosEntry.getChildren().size() : 0));

        // UTXO table
        refreshUtxoTable(utxosEntry, wallet.isWhirlpoolMixWallet());

        // Transaction table (only refresh if visible)
        if (txnTable.isVisible()) {
            refreshTransactionTable();
        }

        // Account name label
        StandardAccount acctType = wallet.getStandardAccountType();
        String acctName = (acctType == null || wallet.isMasterWallet()) ? "Deposit"
                : switch (acctType) {
                    case WHIRLPOOL_PREMIX  -> "Premix";
                    case WHIRLPOOL_POSTMIX -> "Postmix";
                    case WHIRLPOOL_BADBANK -> "Badbank";
                    default -> wallet.getFullDisplayName();
                };
        accountNameLabel.setText(acctName);

        // Receive button — only for Deposit (master wallet)
        boolean isDeposit = wallet.isMasterWallet() || wallet.getStandardAccountType() == null
                || wallet.getStandardAccountType() == StandardAccount.ACCOUNT_0;
        receiveBtn.setVisible(isDeposit);
        receiveBtn.setManaged(isDeposit);

        // Badbank info bar
        boolean isBadbank = wallet.getStandardAccountType() == StandardAccount.WHIRLPOOL_BADBANK;
        badbankInfoBar.setVisible(isBadbank);
        badbankInfoBar.setManaged(isBadbank);

        // Buttons
        configureMixButtons(wallet);
    }

    private void refreshUtxoTable(WalletUtxosEntry utxosEntry, boolean isMixWallet) {
        colMixes.setVisible(isMixWallet);

        utxoRows.clear();
        if (utxosEntry.getChildren() == null) return;

        List<Entry> sorted = new ArrayList<>(utxosEntry.getChildren());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        UnitFormat fmt = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();

        for (Entry entry : sorted) {
            if (!(entry instanceof UtxoEntry utxoEntry)) continue;
            BlockTransactionHashIndex hashIndex = utxoEntry.getHashIndex();

            String date    = hashIndex.getDate() != null ? df.format(hashIndex.getDate()) : "Unconfirmed";
            String output  = hashIndex.getHash().toString() + ":" + hashIndex.getIndex();
            String address;
            try {
                Address addr = utxoEntry.getAddress();
                address = addr != null ? addr.toString() : "";
            } catch(Exception e) {
                address = "";
            }
            String label   = utxoEntry.getLabel() != null ? utxoEntry.getLabel() : "";
            String mixes   = isMixWallet && utxoEntry.getMixStatus() != null
                    ? String.valueOf(utxoEntry.getMixStatus().getMixesDone()) : "-";
            String value   = fmt.formatBtcValue(hashIndex.getValue()) + " BTC";

            UtxoRow row = new UtxoRow(date, output, address, mixes, label, value, utxoEntry);
            row.selected.addListener((obs, o, n) -> updateActionButtons());
            utxoRows.add(row);
        }
    }

    private void refreshTransactionTable() {
        if (activeAccountForm == null) return;
        txnRows.clear();

        WalletTransactionsEntry txnEntry = activeAccountForm.getWalletTransactionsEntry();
        if (txnEntry == null) return;
        txnEntry.updateTransactions();
        if (txnEntry.getChildren() == null) return;

        DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        UnitFormat fmt = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();

        List<Entry> entries = new ArrayList<>(txnEntry.getChildren());
        // Most recent first
        Collections.reverse(entries);

        for (Entry entry : entries) {
            if (!(entry instanceof TransactionEntry txEntry)) continue;
            BlockTransaction blockTx = txEntry.getBlockTransaction();

            String date = blockTx.getDate() != null ? df.format(blockTx.getDate()) : "Unconfirmed";
            String txid = blockTx.getHashAsString();
            String label = blockTx.getLabel() != null ? blockTx.getLabel() : "";
            long net = txEntry.getValue();
            String amount = (net >= 0 ? "+" : "") + fmt.formatBtcValue(net) + " BTC";

            txnRows.add(new TxnRow(date, txid, label, amount, txEntry));
        }
    }

    private void configureMixButtons(Wallet wallet) {
        // Hide all by default
        startMixBtn.setVisible(false);
        mixToBtn.setVisible(false);
        mixSelectedBtn.setVisible(false);
        mixSelectedBtn.setManaged(false);
        mixButtonBox.setVisible(false);

        if (wallet.isWhirlpoolMixWallet()) {
            // Premix / Postmix — show Start/Stop Mixing
            mixButtonBox.setVisible(true);
            startMixBtn.setVisible(true);

            boolean isMixing = isMixing(wallet);
            startMixBtn.setText(isMixing ? "Stop Mixing" : "Start Mixing");
            startMixBtn.setDisable(!AppServices.onlineProperty().get());

            // Postmix also shows Mix To and Mix Selected
            if (wallet.getStandardAccountType() == StandardAccount.WHIRLPOOL_POSTMIX) {
                mixToBtn.setVisible(true);
                mixSelectedBtn.setVisible(true);
                mixSelectedBtn.setManaged(true);
                mixSelectedBtn.setDisable(true);
                updateMixToButton();
            }
        } else if (WhirlpoolServices.canWalletMix(wallet)) {
            // Deposit / Badbank — show Mix Selected to initiate Tx0
            mixSelectedBtn.setVisible(true);
            mixSelectedBtn.setManaged(true);
            mixSelectedBtn.setText("Mix Selected UTXOs");
            mixSelectedBtn.setDisable(true);
        }
        updateActionButtons();
    }

    private boolean isMixing(Wallet wallet) {
        Whirlpool wp = AppServices.getWhirlpoolServices().getWhirlpool(wallet);
        return wp != null && wp.isMixing();
    }

    private void updateMixToButton() {
        if (activeAccountForm == null) return;
        MixConfig mixConfig = activeAccountForm.getWallet().getMasterMixConfig();
        if (mixConfig != null && mixConfig.getMixToWalletName() != null) {
            try {
                String mixToId = AppServices.getWhirlpoolServices()
                        .getWhirlpoolMixToWalletId(mixConfig);
                String name = AppServices.get().getWallet(mixToId).getFullDisplayName();
                mixToBtn.setText("Mixing to " + name);
            } catch (NoSuchElementException e) {
                mixToBtn.setText("⚠ Mix-to wallet not open");
            }
        } else {
            mixToBtn.setText("Mix To...");
        }
    }

    private void updateMixSelectedButton() {
        mixSelectedBtn.setDisable(utxoRows.stream().noneMatch(r -> r.selected.get()));
    }

    private void updateActionButtons() {
        boolean utxosVisible = utxoTable.isVisible();

        if (!utxosVisible) {
            mixSelectedBtn.setVisible(false);
        } else if (mixSelectedBtn.isManaged()) {
            mixSelectedBtn.setVisible(true);
            updateMixSelectedButton();
        }
    }

    // -------------------------------------------------------------------------
    // Button actions
    // -------------------------------------------------------------------------

    @FXML
    private void onReceiveCta() {
        onReceive();
    }

    @FXML
    private void onReceive() {
        if (activeAccountForm == null) return;
        try {
            AshigaruReceiveController.show(activeAccountForm);
        } catch (Exception e) {
            log.error("Error opening Receive dialog", e);
        }
    }

    @FXML
    private void onViewToggle() {
        boolean showUtxos = utxoViewBtn.isSelected();
        utxoTable.setVisible(showUtxos);
        utxoTable.setManaged(showUtxos);
        txnTable.setVisible(!showUtxos);
        txnTable.setManaged(!showUtxos);
        if (!showUtxos) {
            refreshTransactionTable();
        }
        updateActionButtons();
    }

    @FXML
    private void onStartMix() {
        if (activeAccountForm == null) return;
        Wallet wallet = activeAccountForm.getWallet();
        if (isMixing(wallet)) {
            stopMixing(wallet);
        } else {
            startMixing(wallet);
        }
    }

    private void startMixing(Wallet wallet) {
        startMixBtn.setDisable(true);
        startMixBtn.setText("Starting...");
        Platform.runLater(() -> {
            wallet.getMasterMixConfig().setMixOnStartup(Boolean.TRUE);
            EventManager.get().post(new WalletMasterMixConfigChangedEvent(wallet));

            Whirlpool wp = AppServices.getWhirlpoolServices().getWhirlpool(wallet);
            if (wp != null && !wp.isStarted() && AppServices.isConnected()) {
                AppServices.getWhirlpoolServices().startWhirlpool(wallet, wp, true);
            }
        });
    }

    private void stopMixing(Wallet wallet) {
        startMixBtn.setText("Stopping...");
        Platform.runLater(() -> {
            wallet.getMasterMixConfig().setMixOnStartup(Boolean.FALSE);
            EventManager.get().post(new WalletMasterMixConfigChangedEvent(wallet));

            Whirlpool wp = AppServices.getWhirlpoolServices().getWhirlpool(wallet);
            if (wp != null) {
                if (wp.isStarted()) {
                    AppServices.getWhirlpoolServices().stopWhirlpool(wp, true);
                } else {
                    wp.shutdown();
                }
            }
        });
    }

    @FXML
    private void onMixTo() {
        if (activeAccountForm == null) return;
        try {
            AshigaruMixToController.show(activeAccountForm);
        } catch (Exception e) {
            log.error("Error opening Mix To dialog", e);
        }
    }

    @FXML
    private void onMixSelected() {
        if (activeAccountForm == null) return;
        List<UtxoEntry> selectedEntries = utxoRows.stream()
                .filter(r -> r.selected.get())
                .map(r -> r.utxoEntry)
                .collect(Collectors.toList());
        if (selectedEntries.isEmpty()) return;

        try {
            Pool pool = AshigaruTx0Controller.show(
                    activeAccountForm.getMasterWalletId(), activeAccountForm, selectedEntries);
            if (pool != null) {
                Wallet wallet = activeAccountForm.getWallet();
                if (wallet.isMasterWallet() && !wallet.isWhirlpoolMasterWallet()) {
                    addAccountIfNeeded(wallet, StandardAccount.WHIRLPOOL_PREMIX,
                            () -> broadcastPremix(pool, selectedEntries));
                } else {
                    Platform.runLater(() -> broadcastPremix(pool, selectedEntries));
                }
            }
        } catch (Exception e) {
            log.error("Error in Mix Selected", e);
            AppServices.showErrorDialog("Error", e.getMessage());
        }
    }

    private void addAccountIfNeeded(Wallet wallet, StandardAccount account, Runnable after) {
        // Trigger account addition via event, then run after
        Platform.runLater(() -> {
            EventManager.get().post(new WalletAddAccountEvent(wallet, account));
            Platform.runLater(after);
        });
    }

    private void broadcastPremix(Pool pool, List<UtxoEntry> entries) {
        String masterId = activeAccountForm.getMasterWalletId();
        Whirlpool wp = AppServices.getWhirlpoolServices().getWhirlpool(masterId);
        List<BlockTransactionHashIndex> utxos = entries.stream()
                .map(HashIndexEntry::getHashIndex)
                .collect(Collectors.toList());

        Whirlpool.Tx0BroadcastService svc = new Whirlpool.Tx0BroadcastService(wp, pool, utxos);
        svc.setOnSucceeded(e -> {
            Sha256Hash txid = svc.getValue();
            Platform.runLater(() -> {
                AppServices.showSuccessDialog("Broadcast Successful",
                        "Transaction Zero ID:\n" + txid.toString());
                // Auto-switch to Premix tab so user can see equalized UTXOs (if tab view is active)
                if (accountTabs != null) {
                    accountTabs.getTabs().stream()
                            .filter(t -> "Premix".equals(t.getText()))
                            .findFirst()
                            .ifPresent(t -> accountTabs.getSelectionModel().select(t));
                }
            });
        });
        svc.setOnFailed(e -> {
            Throwable ex = e.getSource().getException();
            while (ex.getCause() != null) ex = ex.getCause();
            String msg = ex.getMessage();
            Platform.runLater(() ->
                    AppServices.showErrorDialog("Error broadcasting Transaction Zero", msg));
        });
        svc.start();
    }

    @FXML
    private void onDelete() {
        if (currentWalletForm == null) return;
        AshigaruGui.get().getMainController().deleteWallet(currentWalletForm.getWalletId());
    }

    @FXML
    private void onRefresh() {
        if (activeAccountForm != null) {
            activeAccountForm.refreshHistory(AppServices.getCurrentBlockHeight());
        }
    }

    // -------------------------------------------------------------------------
    // Event subscriptions
    // -------------------------------------------------------------------------

    @Subscribe
    public void walletNodesChanged(WalletNodesChangedEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            Platform.runLater(this::refreshAccountView);
        }
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            Platform.runLater(() -> {
                activeAccountForm.getWalletUtxosEntry().updateUtxos();
                activeAccountForm.getWalletTransactionsEntry().updateTransactions();
                refreshAccountView();
            });
        }
    }

    @Subscribe
    public void walletHistoryStarted(WalletHistoryStartedEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            Platform.runLater(() -> {
                refreshBtn.setDisable(true);
                refreshBtn.setText("⟳  Syncing…");
            });
        }
    }

    @Subscribe
    public void walletHistoryFinished(WalletHistoryFinishedEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            Platform.runLater(() -> {
                refreshBtn.setDisable(false);
                refreshBtn.setText("⟳  Refresh");
            });
        }
    }

    @Subscribe
    public void walletHistoryFailed(WalletHistoryFailedEvent event) {
        walletHistoryFinished(new WalletHistoryFinishedEvent(event.getWallet()));
    }

    @Subscribe
    public void whirlpoolMix(WhirlpoolMixEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            Platform.runLater(() -> {
                // Refresh the mixes column for the affected UTXO
                utxoRows.stream()
                        .filter(row -> row.utxoEntry.getHashIndex().equals(event.getUtxo()))
                        .findFirst()
                        .ifPresent(row -> {
                            if (event.getMixProgress() != null) {
                                row.utxoEntry.setMixProgress(event.getMixProgress());
                            }
                            // Force table refresh
                            utxoTable.refresh();
                        });
            });
        }
    }

    @Subscribe
    public void mixingChanged(WhirlpoolMixEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            Platform.runLater(() -> configureMixButtons(activeAccountForm.getWallet()));
        }
    }

    @Subscribe
    public void openWallets(OpenWalletsEvent event) {
        Platform.runLater(this::updateMixToButton);
    }

    @Subscribe
    public void walletOpened(WalletOpenedEvent event) {
        Wallet opened = event.getWallet();
        // Rebuild tabs when a nested wallet (BIP47 or Whirlpool child) is opened for the
        // currently displayed master wallet
        if (!opened.isNested() && !opened.isWhirlpoolChildWallet()) return;
        if (currentWalletForm == null) return;
        if (!opened.getMasterWallet().equals(currentWalletForm.getWallet())) return;
        Platform.runLater(() -> buildAccountTabs(currentWalletForm));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Button makeCopyButton(String textToCopy) {
        Button btn = new Button("⎘");
        btn.getStyleClass().add("copy-icon-btn");
        btn.setPrefSize(28, 28);
        btn.setStyle("-fx-text-fill: #A0A0A0;");
        btn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(textToCopy);
            Clipboard.getSystemClipboard().setContent(content);
            btn.setText("✓");
            btn.setStyle("-fx-text-fill: #4CAF50;");
            PauseTransition pause = new PauseTransition(Duration.seconds(1.5));
            pause.setOnFinished(ev -> { btn.setText("⎘"); btn.setStyle("-fx-text-fill: #A0A0A0;"); });
            pause.play();
        });
        return btn;
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    static class UtxoRow {
        final String date, output, address, mixes, label, value;
        final UtxoEntry utxoEntry;
        final BooleanProperty selected = new SimpleBooleanProperty(false);
        UtxoRow(String date, String output, String address, String mixes,
                String label, String value, UtxoEntry utxoEntry) {
            this.date = date; this.output = output; this.address = address;
            this.mixes = mixes; this.label = label; this.value = value;
            this.utxoEntry = utxoEntry;
        }
    }
    record TxnRow(String date, String txid, String label, String amount, TransactionEntry txnEntry) {}
}
