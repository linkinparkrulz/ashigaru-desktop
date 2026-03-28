package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.samourai.whirlpool.client.wallet.beans.MixProgress;
import com.samourai.whirlpool.client.wallet.beans.Tx0FeeTarget;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.*;
import com.sparrowwallet.sparrow.whirlpool.Whirlpool;
import com.sparrowwallet.sparrow.whirlpool.WhirlpoolServices;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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
    @FXML private Button receiveBtn;
    @FXML private TabPane accountTabs;
    @FXML private Label balanceLabel;
    @FXML private Label mempoolLabel;
    @FXML private Label utxoCountLabel;
    @FXML private HBox badbankInfoBar;
    @FXML private ToggleButton utxoViewBtn;
    @FXML private ToggleButton txnViewBtn;
    @FXML private TableView<UtxoRow> utxoTable;
    @FXML private TableColumn<UtxoRow, String> colDate;
    @FXML private TableColumn<UtxoRow, String> colOutput;
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
        utxoTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        colDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().date()));
        colOutput.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().output()));
        colMixes.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().mixes()));
        colValue.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().value()));

        utxoTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) ->
                updateMixSelectedButton());

        // Transaction table
        txnTable.setItems(txnRows);
        colTxnDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().date()));
        colTxnId.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().txid()));
        colTxnLabel.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().label()));
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

            String date = hashIndex.getDate() != null ? df.format(hashIndex.getDate()) : "Unconfirmed";
            String output = abbreviate(hashIndex.getHash().toString()) + ":" + hashIndex.getIndex();
            String mixes = isMixWallet && utxoEntry.getMixStatus() != null
                    ? String.valueOf(utxoEntry.getMixStatus().getMixesDone()) : "-";
            String value = fmt.formatBtcValue(hashIndex.getValue()) + " BTC";

            utxoRows.add(new UtxoRow(date, output, mixes, value, utxoEntry));
        }
    }

    private void refreshTransactionTable() {
        if (activeAccountForm == null) return;
        txnRows.clear();

        WalletTransactionsEntry txnEntry = activeAccountForm.getWalletTransactionsEntry();
        if (txnEntry == null || txnEntry.getChildren() == null) return;

        DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        UnitFormat fmt = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();

        List<Entry> entries = new ArrayList<>(txnEntry.getChildren());
        // Most recent first
        Collections.reverse(entries);

        for (Entry entry : entries) {
            if (!(entry instanceof TransactionEntry txEntry)) continue;
            BlockTransaction blockTx = txEntry.getBlockTransaction();

            String date = blockTx.getDate() != null ? df.format(blockTx.getDate()) : "Unconfirmed";
            String txid = abbreviate(blockTx.getHashAsString());
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
                mixSelectedBtn.setDisable(true);
                updateMixToButton();
            }
        } else if (WhirlpoolServices.canWalletMix(wallet)) {
            // Deposit / Badbank — show Mix Selected to initiate Tx0
            mixButtonBox.setVisible(true);
            mixSelectedBtn.setVisible(true);
            mixSelectedBtn.setText("Mix Selected UTXOs");
            mixSelectedBtn.setDisable(true);
        }
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
        mixSelectedBtn.setDisable(utxoTable.getSelectionModel().getSelectedItems().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Button actions
    // -------------------------------------------------------------------------

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
        List<UtxoEntry> selectedEntries = utxoTable.getSelectionModel().getSelectedItems().stream()
                .map(UtxoRow::utxoEntry)
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
                // Auto-switch to Premix tab so user can see equalized UTXOs
                accountTabs.getTabs().stream()
                        .filter(t -> "Premix".equals(t.getText()))
                        .findFirst()
                        .ifPresent(t -> accountTabs.getSelectionModel().select(t));
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
                refreshAccountView();
            });
        }
    }

    @Subscribe
    public void whirlpoolMix(WhirlpoolMixEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            Platform.runLater(() -> {
                // Refresh the mixes column for the affected UTXO
                utxoRows.stream()
                        .filter(row -> row.utxoEntry().getHashIndex().equals(event.getUtxo()))
                        .findFirst()
                        .ifPresent(row -> {
                            if (event.getMixProgress() != null) {
                                row.utxoEntry().setMixProgress(event.getMixProgress());
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

    private static String abbreviate(String s) {
        if (s.length() <= 12) return s;
        return s.substring(0, 6) + "..." + s.substring(s.length() - 4);
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    record UtxoRow(String date, String output, String mixes, String value, UtxoEntry utxoEntry) {}
    record TxnRow(String date, String txid, String label, String amount, TransactionEntry txnEntry) {}
}
