package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.samourai.whirlpool.client.mix.listener.MixFailReason;
import com.samourai.whirlpool.client.mix.listener.MixStep;
import com.samourai.whirlpool.client.wallet.beans.MixProgress;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.swap.SwapService;
import com.sparrowwallet.sparrow.wallet.*;
import com.sparrowwallet.sparrow.whirlpool.Whirlpool;
import com.sparrowwallet.sparrow.whirlpool.WhirlpoolException;
import com.sparrowwallet.sparrow.whirlpool.WhirlpoolServices;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class AshigaruWalletController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(AshigaruWalletController.class);

    @FXML private Label accountNameLabel;
    @FXML private Button refreshBtn;
    @FXML private Button receiveBtn;
    @FXML private Button swapBtn;
    @FXML private Button receiveCta;
    @FXML private TabPane accountTabs;
    @FXML private Label balanceLabel;
    @FXML private Label mempoolLabel;
    @FXML private Label utxoCountLabel;
    @FXML private HBox badbankInfoBar;
    @FXML private Label badbankInfoLabel;
    @FXML private HBox coordinatorWarningBar;
    @FXML private HBox syncingBar;
    @FXML private ToggleButton utxoViewBtn;
    @FXML private ToggleButton txnViewBtn;
    @FXML private ListView<UtxoRow> utxoTable;
    @FXML private ListView<TxnRow> txnTable;
    @FXML private HBox mixButtonBox;
    @FXML private Button startMixBtn;
    @FXML private Button mixToBtn;
    @FXML private Button selectAllUtxosBtn;
    @FXML private Button mixSelectedBtn;

    private WalletForm currentWalletForm;   // master wallet form
    private WalletForm activeAccountForm;   // currently shown account form
    private boolean activeAccountSyncing;   // true while the active account's history is refreshing

    private final ObservableList<UtxoRow> utxoRows = FXCollections.observableArrayList();
    private final ObservableList<TxnRow> txnRows   = FXCollections.observableArrayList();

    // Whether the active account is a Whirlpool mix wallet (drives mix-status display in cells)
    private final SimpleBooleanProperty showMixInfo = new SimpleBooleanProperty(false);

    // Tracks when each UTXO first entered the CONNECTING mix step, to detect a coordinator
    // that has gone unreachable (the client loops re-emitting CONNECTING while it's down).
    private final Map<BlockTransactionHashIndex, Long> connectingSince = new HashMap<>();
    private static final long COORDINATOR_WARN_MS = 90_000;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // UTXO list — responsive card view (one card per UTXO, never scrolls sideways)
        utxoTable.setItems(utxoRows);
        utxoTable.setCellFactory(lv -> new AshigaruUtxoCell(showMixInfo));

        // Transaction list — responsive card view
        txnTable.setItems(txnRows);
        txnTable.setCellFactory(lv -> new AshigaruTxnCell());

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

    // Tracks whichever Whirlpool engine's start/stop/mixing state the mix button currently
    // reflects, so its listener can be moved when the active wallet/account changes instead of
    // leaving the button's text wired to a stale or no-longer-active engine.
    private Whirlpool boundWhirlpool;
    private final ChangeListener<Boolean> whirlpoolStateListener = (obs, old, neu) -> {
        if (activeAccountForm != null) {
            configureMixButtons(activeAccountForm.getWallet());
        }
    };

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
        // Reset coordinator-reachability tracking when switching accounts; the next mix
        // event for the newly active account repopulates it if mixing is still stuck.
        clearCoordinatorWarning();
        // A freshly selected account has no in-flight sync of its own; hide the banner until
        // a WalletHistoryStartedEvent for this account arrives.
        activeAccountSyncing = false;
        setSyncingBarVisible(false);
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

        // Receive button and empty-state CTA — only for Deposit (master wallet).
        // Premix/Postmix/Badbank wallets have no extendedPublicKey and will NPE if receive is attempted.
        boolean isDeposit = wallet.isMasterWallet() || wallet.getStandardAccountType() == null
                || wallet.getStandardAccountType() == StandardAccount.ACCOUNT_0;
        receiveBtn.setVisible(isDeposit);
        receiveBtn.setManaged(isDeposit);
        receiveCta.setVisible(isDeposit);
        receiveCta.setManaged(isDeposit);
        selectAllUtxosBtn.setVisible(isDeposit && utxoTable.isVisible());
        selectAllUtxosBtn.setManaged(isDeposit && utxoTable.isVisible());

        // Badbank info bar
        boolean isBadbank = wallet.getStandardAccountType() == StandardAccount.WHIRLPOOL_BADBANK;
        badbankInfoBar.setVisible(isBadbank);
        badbankInfoBar.setManaged(isBadbank);
        boolean canSwap = isBadbank && SwapService.get().isEnabled();
        swapBtn.setVisible(canSwap);
        swapBtn.setManaged(canSwap);

        // Buttons
        bindWhirlpoolState(wallet);
        configureMixButtons(wallet);
    }

    // Rebinds the mix button's start/stop/mixing listener to the given wallet's Whirlpool
    // engine, so button text recovers on its own after a start/stop transition (including one
    // that produces no eligible-to-mix UTXOs, or a failed start) instead of staying stuck on
    // "Starting..."/"Stopping..." until some unrelated event happens to call configureMixButtons.
    private void bindWhirlpoolState(Wallet wallet) {
        rebindWhirlpoolState(AppServices.getWhirlpoolServices().getWhirlpool(wallet));
    }

    private void rebindWhirlpoolState(Whirlpool wp) {
        if (wp == boundWhirlpool) {
            return;
        }
        if (boundWhirlpool != null) {
            boundWhirlpool.startingProperty().removeListener(whirlpoolStateListener);
            boundWhirlpool.stoppingProperty().removeListener(whirlpoolStateListener);
            boundWhirlpool.mixingProperty().removeListener(whirlpoolStateListener);
        }
        boundWhirlpool = wp;
        if (boundWhirlpool != null) {
            boundWhirlpool.startingProperty().addListener(whirlpoolStateListener);
            boundWhirlpool.stoppingProperty().addListener(whirlpoolStateListener);
            boundWhirlpool.mixingProperty().addListener(whirlpoolStateListener);
        }
    }

    /**
     * Detaches from whichever Whirlpool engine's state this controller is currently listening
     * to. The engine itself is deliberately left running (locking/switching away from a wallet
     * doesn't stop background remixing) — this only prevents the listener from keeping this
     * discarded controller instance reachable. Call when the controller is being torn down
     * (e.g. AshigaruMainController.showWelcome() on Lock/Delete).
     */
    public void unbindWhirlpoolState() {
        rebindWhirlpoolState(null);
    }

    private void refreshUtxoTable(WalletUtxosEntry utxosEntry, boolean isMixWallet) {
        showMixInfo.set(isMixWallet);

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
            String value   = fmt.formatBtcValue(hashIndex.getValue()) + " BTC";

            UtxoRow row = new UtxoRow(date, output, address, label, value, utxoEntry);
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

        // Map each BIP47 child wallet's label-base -> sender payment code, so we can
        // resolve the full payment code behind a "From <abbrev/PayNym>" transaction label.
        Map<String, PaymentCode> senderCodes = buildSenderPaymentCodes(activeAccountForm.getWallet());

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

            PaymentCode paymentCode = null;
            if (label.startsWith("From ")) {
                paymentCode = senderCodes.get(label.substring("From ".length()).trim());
            }

            txnRows.add(new TxnRow(date, txid, label, amount, txEntry, paymentCode));
        }
    }

    /**
     * Builds a lookup from each BIP47 child wallet's label-base to its sender payment code.
     * The label-base mirrors how the "From ..." transaction label is generated in
     * {@code WalletNode}: the child wallet label minus its trailing " &lt;ScriptType&gt;" suffix.
     */
    private Map<String, PaymentCode> buildSenderPaymentCodes(Wallet wallet) {
        Map<String, PaymentCode> codes = new HashMap<>();
        Wallet master = wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
        if (master == null) return codes;
        for (Wallet child : master.getChildWallets()) {
            if (!child.isBip47() || child.getLabel() == null || child.getKeystores().isEmpty()) continue;
            PaymentCode externalCode = child.getKeystores().get(0).getExternalPaymentCode();
            if (externalCode == null) continue;
            String suffix = " " + child.getScriptType().getName();
            String base = child.getLabel().endsWith(suffix)
                    ? child.getLabel().substring(0, child.getLabel().length() - suffix.length())
                    : child.getLabel();
            codes.put(base, externalCode);
        }
        return codes;
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

    private void updateSelectAllButton() {
        if (activeAccountForm == null || selectAllUtxosBtn == null) return;

        Wallet wallet = activeAccountForm.getWallet();
        boolean isDeposit = wallet.isMasterWallet() || wallet.getStandardAccountType() == null
                || wallet.getStandardAccountType() == StandardAccount.ACCOUNT_0;
        boolean show = isDeposit && utxoTable.isVisible();
        selectAllUtxosBtn.setVisible(show);
        selectAllUtxosBtn.setManaged(show);
        boolean anySelected = utxoRows.stream().anyMatch(r -> r.selected.get());
        selectAllUtxosBtn.setDisable(utxoRows.isEmpty());
        selectAllUtxosBtn.setText(anySelected ? "Clear Selection" : "Select All UTXOs");
    }

    private void updateActionButtons() {
        boolean utxosVisible = utxoTable.isVisible();

        if (!utxosVisible) {
            mixSelectedBtn.setVisible(false);
        } else if (mixSelectedBtn.isManaged()) {
            mixSelectedBtn.setVisible(true);
            updateMixSelectedButton();
        }
        updateSelectAllButton();
    }

    // -------------------------------------------------------------------------
    // Button actions
    // -------------------------------------------------------------------------

    @FXML
    private void onReceiveCta() {
        onReceive();
    }

    @FXML
    private void onSwap() {
        if(currentWalletForm != null) {
            SwapController.show(currentWalletForm, swapBtn.getScene().getWindow());
        }
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
    private void onSelectAllUtxos() {
        boolean selectAll = utxoRows.stream().noneMatch(r -> r.selected.get());
        utxoRows.forEach(r -> r.selected.set(selectAll));
        utxoTable.refresh();
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

        // Don't let a click silently no-op while the wallet/Whirlpool isn't ready yet.
        if (!AppServices.isConnected()) {
            AppServices.showAlertDialog("Not connected yet",
                    "Ashigaru isn't connected to a server yet. Mixing will be available once the "
                            + "connection is established — please try again in a moment.",
                    Alert.AlertType.INFORMATION);
            return;
        }
        if (activeAccountSyncing) {
            AppServices.showAlertDialog("Still syncing",
                    "Your wallet is still syncing to the latest block. Please wait until syncing "
                            + "finishes, then try mixing again.",
                    Alert.AlertType.INFORMATION);
            return;
        }

        Wallet activeWallet = activeAccountForm.getWallet();
        if (activeWallet.getStandardAccountType() == StandardAccount.WHIRLPOOL_POSTMIX) {
            Whirlpool wp = AppServices.getWhirlpoolServices().getWhirlpool(activeAccountForm.getMasterWalletId());
            if (wp == null) {
                AppServices.showErrorDialog("Mix error", "Whirlpool service not available for this wallet");
                return;
            }
            if (!wp.isStarted()) {
                AppServices.showAlertDialog("Mixing is starting up",
                        "The mixing service is still starting up for this wallet. Please try again "
                                + "in a moment.",
                        Alert.AlertType.INFORMATION);
                return;
            }
            int queued = 0;
            for (UtxoEntry entry : selectedEntries) {
                try {
                    wp.mix(entry.getHashIndex());
                    queued++;
                } catch (WhirlpoolException e) {
                    log.error("Could not queue remix for " + entry.getHashIndex(), e);
                }
            }
            log.info("Queued " + queued + " of " + selectedEntries.size() + " Postmix UTXO(s) for remix");
            if (queued == 0) {
                AppServices.showErrorDialog("Could not queue remix",
                        "None of the selected UTXOs could be queued for remixing. See the log file "
                                + "(Help menu) for details.");
            } else {
                AppServices.showSuccessDialog("Remix queued",
                        "Queued " + queued + " UTXO" + (queued == 1 ? "" : "s") + " for remix. They'll "
                                + "mix as coordinator rounds become available.");
            }
            return;
        }

        try {
            // The Tx0 dialog now signs + broadcasts (with its in-dialog animation) and
            // ensures the Premix account first; on success it returns the Tx0 txid.
            Sha256Hash txid = AshigaruTx0Controller.show(
                    activeAccountForm.getMasterWalletId(), activeAccountForm, selectedEntries);
            if (txid != null && accountTabs != null) {
                accountTabs.getTabs().stream()
                        .filter(t -> "Premix".equals(t.getText()))
                        .findFirst()
                        .ifPresent(t -> accountTabs.getSelectionModel().select(t));
            }
        } catch (Exception e) {
            log.error("Error in Mix Selected", e);
            AppServices.showErrorDialog("Error", e.getMessage());
        }
    }

    @FXML
    private void onDelete() {
        if (currentWalletForm == null) return;
        AshigaruGui.get().getMainController().deleteWallet(currentWalletForm.getWalletId());
    }

    @FXML
    private void onRefresh() {
        refreshForm(currentWalletForm);
        if (activeAccountForm != null && activeAccountForm != currentWalletForm) {
            refreshForm(activeAccountForm);
        }
    }

    private void refreshForm(WalletForm walletForm) {
        if (walletForm == null) return;
        Wallet wallet = walletForm.getWallet();
        Wallet pastWallet = wallet.copy();
        wallet.clearHistory();
        AppServices.clearTransactionHistoryCache(wallet);
        EventManager.get().post(new WalletHistoryClearedEvent(wallet, pastWallet, walletForm.getWalletId()));
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
            activeAccountSyncing = true;
            Platform.runLater(() -> {
                refreshBtn.setDisable(true);
                refreshBtn.setText("⟳  Syncing…");
                setSyncingBarVisible(true);
                // Disable mixing while syncing so a click can't be silently swallowed.
                mixSelectedBtn.setDisable(true);
                startMixBtn.setDisable(true);
            });
        }
    }

    @Subscribe
    public void walletHistoryFinished(WalletHistoryFinishedEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            activeAccountSyncing = false;
            Platform.runLater(() -> {
                refreshBtn.setDisable(false);
                refreshBtn.setText("⟳  Refresh");
                setSyncingBarVisible(false);
                startMixBtn.setDisable(false);
                // Re-enable Mix Selected based on the current selection.
                mixSelectedBtn.setDisable(utxoRows.stream().noneMatch(r -> r.selected.get()));
                // Always refresh the view when a sync finishes — ensures UTXOs are shown
                // even when the history was already current (no WalletHistoryChangedEvent fired).
                activeAccountForm.getWalletUtxosEntry().updateUtxos();
                refreshAccountView();
            });
        }
    }

    private void setSyncingBarVisible(boolean visible) {
        if (syncingBar == null) return;
        syncingBar.setVisible(visible);
        syncingBar.setManaged(visible);
    }

    @Subscribe
    public void walletHistoryFailed(WalletHistoryFailedEvent event) {
        walletHistoryFinished(new WalletHistoryFinishedEvent(event.getWallet()));
    }

    @Subscribe
    public void whirlpoolMix(WhirlpoolMixEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            Platform.runLater(() -> {
                utxoRows.stream()
                        .filter(row -> row.utxoEntry.getHashIndex().equals(event.getUtxo()))
                        .findFirst()
                        .ifPresent(row -> {
                            if(event.getNextUtxo() != null) {
                                row.utxoEntry.setNextMixUtxo(event.getNextUtxo());
                            } else if(event.getMixFailReason() != null
                                    && event.getMixFailReason() != MixFailReason.CANCEL
                                    && event.getMixFailReason() != MixFailReason.STOP) {
                                row.utxoEntry.setMixFailReason(event.getMixFailReason(), event.getMixError());
                            } else {
                                row.utxoEntry.setMixProgress(event.getMixProgress());
                            }
                            utxoTable.refresh();
                        });
                trackCoordinatorReachability(event);
            });
        }
    }

    @Subscribe
    public void mixingChanged(WhirlpoolMixEvent event) {
        if (activeAccountForm != null && event.getWallet().equals(activeAccountForm.getWallet())) {
            Platform.runLater(() -> configureMixButtons(activeAccountForm.getWallet()));
        }
    }

    /**
     * Detects when mixing can no longer reach the Whirlpool coordinator. While the coordinator
     * is down the client loops re-emitting the CONNECTING step, so a UTXO that stays in
     * CONNECTING beyond {@link #COORDINATOR_WARN_MS} is treated as unreachable and the warning
     * bar is shown. Because coordinator reachability is global (a single connection), any forward
     * progress or completed round clears tracking for ALL UTXOs so the bar drops as soon as the
     * coordinator is reachable again; a failure only clears the UTXO that failed.
     * Runs on the FX thread (called from within {@code whirlpoolMix}'s Platform.runLater).
     */
    private void trackCoordinatorReachability(WhirlpoolMixEvent event) {
        BlockTransactionHashIndex utxo = event.getUtxo();
        MixProgress mixProgress = event.getMixProgress();
        if (mixProgress != null && mixProgress.getMixStep() == MixStep.CONNECTING) {
            // Still trying to reach the coordinator for this UTXO; track how long it's been stuck.
            connectingSince.putIfAbsent(utxo, System.currentTimeMillis());
        } else if (event.getMixFailReason() != null) {
            // A failure doesn't prove the coordinator is reachable; just stop tracking this UTXO.
            connectingSince.remove(utxo);
        } else {
            // Any forward progress (CONNECTED, REGISTERED_INPUT, ...) or a completed round (nextUtxo)
            // proves the coordinator is reachable again — clear ALL stuck-tracking so the banner drops
            // immediately, including UTXOs that went idle while it was down.
            connectingSince.clear();
        }
        refreshCoordinatorWarning();
    }

    private void refreshCoordinatorWarning() {
        long now = System.currentTimeMillis();
        boolean unreachable = connectingSince.values().stream()
                .anyMatch(since -> now - since > COORDINATOR_WARN_MS);
        coordinatorWarningBar.setVisible(unreachable);
        coordinatorWarningBar.setManaged(unreachable);
    }

    private void clearCoordinatorWarning() {
        connectingSince.clear();
        coordinatorWarningBar.setVisible(false);
        coordinatorWarningBar.setManaged(false);
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

    static CopyButton makeCopyButton(String textToCopy) {
        CopyButton btn = new CopyButton();
        btn.setOnAction(e -> btn.copy(textToCopy));
        return btn;
    }

    static String getMixStage(UtxoEntry.MixStatus mixStatus) {
        if (mixStatus == null) {
            return "";
        }

        MixProgress mixProgress = mixStatus.getMixProgress();
        if (mixProgress != null && mixProgress.getMixStep() != null) {
            MixStep step = mixProgress.getMixStep();
            return formatMixStep(step) + " (" + step.getProgressPercent() + "%)";
        }

        MixFailReason failReason = mixStatus.getMixFailReason();
        if (failReason != null && failReason != MixFailReason.CANCEL && failReason != MixFailReason.STOP) {
            return "Failed - " + failReason.getMessage();
        }

        if (mixStatus.getNextMixUtxo() != null) {
            return "Queued for next mix";
        }

        return "";
    }

    private static String formatMixStep(MixStep step) {
        return Arrays.stream(step.name().split("_"))
                .map(part -> part.substring(0, 1) + part.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    public static class UtxoRow {
        final String date, output, address, label, value;
        public final UtxoEntry utxoEntry;
        final BooleanProperty selected = new SimpleBooleanProperty(false);
        UtxoRow(String date, String output, String address,
                String label, String value, UtxoEntry utxoEntry) {
            this.date = date; this.output = output; this.address = address;
            this.label = label; this.value = value;
            this.utxoEntry = utxoEntry;
        }
    }
    record TxnRow(String date, String txid, String label, String amount, TransactionEntry txnEntry, PaymentCode paymentCode) {}
}
