package com.sparrowwallet.sparrow.gui;

import com.samourai.whirlpool.client.wallet.beans.IndexRange;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.MixConfig;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletMasterMixConfigChangedEvent;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.sparrowwallet.sparrow.whirlpool.Whirlpool;
import com.sparrowwallet.sparrow.whirlpool.WhirlpoolServices;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.ResourceBundle;

/**
 * Controller for the "Mix To..." configuration dialog.
 * Lets the user pick a destination wallet and minimum mix count.
 */
public class AshigaruMixToController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(AshigaruMixToController.class);

    @FXML private ComboBox<DisplayWallet> mixToWalletCombo;
    @FXML private Spinner<Integer> minMixesSpinner;
    @FXML private Label indexRangeLabel;
    @FXML private Button applyBtn;
    @FXML private Button cancelBtn;

    private WalletForm walletForm;
    private MixConfig workingConfig;   // copy of the real config; applied on OK
    private boolean confirmed = false;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        minMixesSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10000, Whirlpool.DEFAULT_MIXTO_MIN_MIXES));
        minMixesSpinner.setEditable(true);
        indexRangeLabel.setText("Full");
        applyBtn.setDisable(true);
    }

    // -------------------------------------------------------------------------
    // Static factory — opens dialog modally
    // -------------------------------------------------------------------------

    public static void show(WalletForm walletForm) throws Exception {
        FXMLLoader loader = new FXMLLoader(AshigaruMixToController.class.getResource("ashigaru-mixto.fxml"));
        DialogPane pane = loader.load();
        AshigaruMixToController ctrl = loader.getController();
        ctrl.walletForm = walletForm;
        ctrl.populate();

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(walletForm.getWallet().getFullDisplayName() + " — Mix To");
        dialog.setDialogPane(pane);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(AshigaruGui.get().getMainStage());
        dialog.setResultConverter(btn -> ctrl.confirmed);

        dialog.showAndWait().ifPresent(applied -> {
            if (applied) ctrl.applyConfig();
        });
    }

    // -------------------------------------------------------------------------
    // Population
    // -------------------------------------------------------------------------

    private void populate() {
        Wallet wallet = walletForm.getWallet();
        workingConfig = wallet.getMasterMixConfig().copy();

        // Build wallet list
        List<DisplayWallet> items = new ArrayList<>();
        items.add(DisplayWallet.NONE);
        AppServices.get().getOpenWallets().keySet().stream()
                .filter(w -> w.isValid()
                        && (w.getScriptType() == ScriptType.P2WPKH || w.getScriptType() == ScriptType.P2WSH)
                        && w != wallet && w != wallet.getMasterWallet()
                        && (w.getStandardAccountType() == null
                            || !List.of(StandardAccount.WHIRLPOOL_PREMIX, StandardAccount.WHIRLPOOL_BADBANK)
                                    .contains(w.getStandardAccountType())))
                .map(DisplayWallet::new)
                .forEach(items::add);

        mixToWalletCombo.setItems(FXCollections.observableArrayList(items));

        // Set current selection
        if (workingConfig.getMixToWalletName() != null) {
            try {
                String mixToId = AppServices.getWhirlpoolServices().getWhirlpoolMixToWalletId(workingConfig);
                Wallet mixToWallet = AppServices.get().getWallet(mixToId);
                mixToWalletCombo.getSelectionModel().select(new DisplayWallet(mixToWallet));
            } catch (NoSuchElementException e) {
                mixToWalletCombo.getSelectionModel().select(DisplayWallet.NONE);
            }
        } else {
            mixToWalletCombo.getSelectionModel().select(DisplayWallet.NONE);
        }

        int initMixes = workingConfig.getMinMixes() != null
                ? workingConfig.getMinMixes() : Whirlpool.DEFAULT_MIXTO_MIN_MIXES;
        minMixesSpinner.getValueFactory().setValue(initMixes);

        // Listeners
        mixToWalletCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel == null) return;
            if (sel == DisplayWallet.NONE) {
                workingConfig.setMixToWalletName(null);
                workingConfig.setMixToWalletFile(null);
            } else {
                workingConfig.setMixToWalletName(sel.wallet().getName());
                workingConfig.setMixToWalletFile(
                        AppServices.get().getOpenWallets().get(sel.wallet()).getWalletFile());
            }
            applyBtn.setDisable(false);
        });

        minMixesSpinner.valueProperty().addListener((obs, old, nw) -> {
            workingConfig.setMinMixes(nw);
            applyBtn.setDisable(false);
        });
    }

    // -------------------------------------------------------------------------
    // Apply config — mirrors UtxosDialog.showMixToDialog()
    // -------------------------------------------------------------------------

    private void applyConfig() {
        MixConfig realConfig = walletForm.getWallet().getMasterMixConfig();
        realConfig.setMixToWalletName(workingConfig.getMixToWalletName());
        realConfig.setMixToWalletFile(workingConfig.getMixToWalletFile());
        realConfig.setMinMixes(workingConfig.getMinMixes());
        realConfig.setIndexRange(IndexRange.FULL.toString());

        Platform.runLater(() -> {
            EventManager.get().post(new WalletMasterMixConfigChangedEvent(walletForm.getWallet()));

            Whirlpool wp = AppServices.getWhirlpoolServices().getWhirlpool(walletForm.getWallet());
            if (wp == null) return;

            wp.setPostmixIndexRange(realConfig.getIndexRange());
            try {
                String mixToId = AppServices.getWhirlpoolServices().getWhirlpoolMixToWalletId(realConfig);
                wp.setMixToWallet(mixToId, realConfig.getMinMixes());
            } catch (NoSuchElementException e) {
                realConfig.setMixToWalletName(null);
                realConfig.setMixToWalletFile(null);
                EventManager.get().post(new WalletMasterMixConfigChangedEvent(walletForm.getWallet()));
                wp.setMixToWallet(null, null);
            }

            if (wp.isStarted()) {
                AppServices.getWhirlpoolServices().stopWhirlpool(wp, false);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    @FXML
    private void onApply() {
        confirmed = true;
        // Bubbles through dialog.setResultConverter → applyConfig() is called in show()
        applyBtn.getScene().getWindow().hide();
    }

    @FXML
    private void onCancel() {
        confirmed = false;
        cancelBtn.getScene().getWindow().hide();
    }

    // -------------------------------------------------------------------------
    // Inner display wrapper
    // -------------------------------------------------------------------------

    record DisplayWallet(Wallet wallet) {
        static final DisplayWallet NONE = new DisplayWallet(null);

        @Override
        public String toString() {
            return wallet == null ? "None" : wallet.getFullDisplayName();
        }
    }
}
