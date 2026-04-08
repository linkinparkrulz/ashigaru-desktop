package com.sparrowwallet.sparrow.gui;

import com.samourai.wallet.api.backend.MinerFeeTarget;
import com.samourai.whirlpool.client.tx0.Tx0Preview;
import com.samourai.whirlpool.client.tx0.Tx0Previews;
import com.samourai.whirlpool.client.wallet.beans.Tx0FeeTarget;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.wallet.MixConfig;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.event.WalletMasterMixConfigChangedEvent;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.wallet.Entry;
import com.sparrowwallet.sparrow.wallet.UtxoEntry;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.sparrowwallet.sparrow.whirlpool.Whirlpool;
import com.sparrowwallet.sparrow.whirlpool.dataSource.SparrowMinerFeeSupplier;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.ResourceBundle;

/**
 * Controller for the Tx0 (Transaction Zero) dialog.
 * Covers: SCODE input, pool selection, fee preview, broadcast.
 */
public class AshigaruTx0Controller implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(AshigaruTx0Controller.class);

    @FXML private TextField scodeField;
    @FXML private Label feeRateLabel;
    @FXML private ComboBox<DisplayPool> poolCombo;
    @FXML private Label poolFeeLabel;
    @FXML private Label poolFeeValue;
    @FXML private Label premixOutputsValue;
    @FXML private Label amountToWhirlpoolValue;
    @FXML private Label unmixedChangeValue;
    @FXML private Label minerFeeValue;
    @FXML private Label totalFeesValue;
    @FXML private ButtonType broadcastBtnType;
    private Button broadcastBtn;   // resolved in show() after setDialogPane()

    // Set by show() before the dialog is displayed
    private String walletId;
    private WalletForm walletForm;
    private List<UtxoEntry> utxoEntries;

    private Tx0Previews tx0Previews;
    private Pool selectedPool;           // result returned to caller
    private Stage dialogStage;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        poolCombo.setItems(FXCollections.observableArrayList());
        poolCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null && sel.pool() != null) {
                fetchTx0Preview(sel.pool());
            }
        });

        // Fee rate display (High priority only, matching TUI behaviour)
        int feeRate = SparrowMinerFeeSupplier.getFee(
                Integer.parseInt(Tx0FeeTarget.BLOCKS_2.getFeeTarget().getValue()));
        feeRateLabel.setText(Math.max(2, feeRate) + " sats/vB  (High priority)");

        // SCODE listener — upper-case and propagate immediately
        scodeField.textProperty().addListener((obs, old, nw) -> {
            if (!nw.equals(nw.toUpperCase(Locale.ROOT))) {
                scodeField.setText(nw.toUpperCase(Locale.ROOT));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Static factory — opens dialog modally and returns chosen Pool (or null)
    // -------------------------------------------------------------------------

    public static Pool show(String walletId, WalletForm walletForm, List<UtxoEntry> utxoEntries) throws Exception {
        FXMLLoader loader = new FXMLLoader(AshigaruTx0Controller.class.getResource("ashigaru-tx0.fxml"));
        DialogPane pane = loader.load();
        AshigaruTx0Controller ctrl = loader.getController();
        ctrl.walletId = walletId;
        ctrl.walletForm = walletForm;
        ctrl.utxoEntries = utxoEntries;

        // Pre-fill SCODE from wallet's MixConfig
        MixConfig mixConfig = walletForm.getWallet().getMasterMixConfig();
        if (mixConfig.getScode() != null) {
            ctrl.scodeField.setText(mixConfig.getScode());
        }

        Dialog<Pool> dialog = new Dialog<>();
        dialog.setTitle(walletForm.getWallet().getFullDisplayName() + " — Transaction Zero");
        dialog.setDialogPane(pane);
        // Resolve the actual Button node now that the DialogPane is wired up
        ctrl.broadcastBtn = (Button) pane.lookupButton(ctrl.broadcastBtnType);
        ctrl.broadcastBtn.setDisable(true);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(AshigaruGui.get().getMainStage());
        ctrl.dialogStage = (Stage) dialog.getDialogPane().getScene().getWindow();

        // Wire Broadcast / Cancel to dialog result
        dialog.setResultConverter(btn -> {
            if (btn != null && btn.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                return ctrl.selectedPool;
            }
            return null;
        });

        // Fetch pools after the dialog is shown
        Platform.runLater(() -> ctrl.fetchPools(false));

        return dialog.showAndWait().orElse(null);
    }

    // -------------------------------------------------------------------------
    // Pool fetching
    // -------------------------------------------------------------------------

    private void fetchPools(boolean refresh) {
        long totalValue = utxoEntries.stream().mapToLong(Entry::getValue).sum();
        Whirlpool wp = AppServices.getWhirlpoolServices().getWhirlpool(walletId);
        Whirlpool.PoolsService svc = new Whirlpool.PoolsService(wp, totalValue, refresh);

        svc.setOnSucceeded(e -> {
            List<Pool> pools = svc.getValue().stream().toList();
            if (pools.isEmpty()) {
                fetchAllPoolsForMinimum(refresh);
            } else {
                Platform.runLater(() -> {
                    poolCombo.setDisable(false);
                    poolCombo.setItems(FXCollections.observableArrayList(
                            pools.stream().map(DisplayPool::new).toList()));
                    poolCombo.getSelectionModel().selectFirst();
                });
            }
        });
        svc.setOnFailed(e -> {
            Throwable ex = e.getSource().getException();
            while (ex.getCause() != null) ex = ex.getCause();
            String msg = ex.getMessage();
            Platform.runLater(() -> {
                var result = AppServices.showWarningDialog(
                        "Error fetching pools", "Could not reach Whirlpool coordinator. Try again?\n" + msg,
                        ButtonType.CANCEL, ButtonType.OK);
                if (result.isPresent() && result.get() == ButtonType.OK) {
                    fetchPools(true);
                }
            });
        });
        svc.start();
    }

    private void fetchAllPoolsForMinimum(boolean refresh) {
        Whirlpool wp = AppServices.getWhirlpoolServices().getWhirlpool(walletId);
        Whirlpool.PoolsService allSvc = new Whirlpool.PoolsService(wp, null, refresh);
        allSvc.setOnSucceeded(e -> {
            OptionalLong minValue = allSvc.getValue()
                    .stream().mapToLong(p -> p.getPremixValueMin() + p.getFeeValue()).min();
            if (minValue.isPresent()) {
                UnitFormat fmt = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
                long min = minValue.getAsLong();
                String valueStr = Config.get().getBitcoinUnit() == BitcoinUnit.BTC
                        ? fmt.formatBtcValue(min) + " BTC"
                        : fmt.formatSatsValue(min) + " sats";
                Platform.runLater(() ->
                        AppServices.showErrorDialog("Insufficient UTXO Value",
                                "No pools available for selected UTXOs.\n" +
                                "Select UTXOs with a combined value over " + valueStr + "."));
            }
        });
        allSvc.start();
    }

    // -------------------------------------------------------------------------
    // Tx0 preview fetching
    // -------------------------------------------------------------------------

    private void fetchTx0Preview(Pool pool) {
        MixConfig mixConfig = walletForm.getWallet().getMasterMixConfig();
        if (mixConfig.getScode() == null) {
            mixConfig.setScode(scodeField.getText().trim().toUpperCase(Locale.ROOT));
        }

        // Persist SCODE changes
        String currentScode = scodeField.getText().trim().toUpperCase(Locale.ROOT);
        mixConfig.setScode(currentScode);
        EventManager.get().post(new WalletMasterMixConfigChangedEvent(walletForm.getWallet()));

        Whirlpool wp = AppServices.getWhirlpoolServices().getWhirlpool(walletId);

        if (tx0Previews != null
                && currentScode.equals(wp.getScode())
                && Tx0FeeTarget.BLOCKS_2 == wp.getTx0FeeTarget()) {
            Tx0Preview preview = tx0Previews.getTx0Preview(pool.getPoolId());
            Platform.runLater(() -> applyPreview(preview, pool));
            return;
        }

        tx0Previews = null;
        wp.setScode(currentScode);
        wp.setTx0FeeTarget(Tx0FeeTarget.BLOCKS_2);
        wp.setMixFeeTarget(Tx0FeeTarget.BLOCKS_2);

        setPreviewCalculating();

        Whirlpool.Tx0PreviewsService previewSvc = new Whirlpool.Tx0PreviewsService(wp, utxoEntries);
        previewSvc.setOnSucceeded(e -> {
            tx0Previews = previewSvc.getValue();
            Tx0Preview preview = tx0Previews.getTx0Preview(pool.getPoolId());
            Platform.runLater(() -> applyPreview(preview, pool));
        });
        previewSvc.setOnFailed(e -> {
            Throwable ex = e.getSource().getException();
            while (ex.getCause() != null) ex = ex.getCause();
            String msg = ex.getMessage();
            Platform.runLater(() ->
                    AppServices.showErrorDialog("Error fetching Tx0 preview", msg));
        });
        previewSvc.start();
    }

    private void setPreviewCalculating() {
        premixOutputsValue.setText("Calculating...");
        amountToWhirlpoolValue.setText("Calculating...");
        unmixedChangeValue.setText("Calculating...");
        minerFeeValue.setText("Calculating...");
        totalFeesValue.setText("Calculating...");
        broadcastBtn.setDisable(true);
        selectedPool = null;
    }

    private void applyPreview(Tx0Preview preview, Pool pool) {
        if (preview == null) {
            setPreviewCalculating();
            return;
        }

        UnitFormat fmt = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();

        long poolFeeAmt = preview.getTx0Data().getFeeValue();
        long minerFee = (preview.getNbPremix() * preview.getPremixMinerFee()) + preview.getTx0MinerFee();

        if (preview.getPool().getFeeValue() != poolFeeAmt) {
            poolFeeLabel.setText("Anti-Sybil fee (discounted)");
        } else {
            poolFeeLabel.setText("Anti-Sybil fee");
        }

        poolFeeValue.setText(fmt.formatBtcValue(poolFeeAmt) + " BTC");
        premixOutputsValue.setText(String.valueOf(preview.getNbPremix()));
        amountToWhirlpoolValue.setText(fmt.formatBtcValue((long) preview.getNbPremix() * pool.getDenomination()) + " BTC");
        unmixedChangeValue.setText(fmt.tableFormatBtcValue(preview.getChangeValue()) + " BTC");
        minerFeeValue.setText(fmt.tableFormatBtcValue(minerFee) + " BTC");
        totalFeesValue.setText(fmt.tableFormatBtcValue(minerFee + poolFeeAmt) + " BTC");

        selectedPool = pool;
        broadcastBtn.setDisable(false);
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Inner display wrapper
    // -------------------------------------------------------------------------

    record DisplayPool(Pool pool) {
        @Override
        public String toString() {
            if (pool == null) return "Fetching pools...";
            UnitFormat fmt = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
            return fmt.formatBtcValue(pool.getDenomination()) + " BTC";
        }
    }
}
