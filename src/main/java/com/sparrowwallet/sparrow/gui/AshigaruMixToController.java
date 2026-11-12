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
    @FXML private PipelineStrip mixToStrip;

    private void updateStrip() {
        DisplayWallet sel = mixToWalletCombo.getSelectionModel().getSelectedItem();
        Integer mixes = minMixesSpinner.getValue();
        boolean none = sel == null || sel == DisplayWallet.NONE;
        mixToStrip.showMixTo(mixes == null ? Whirlpool.DEFAULT_MIXTO_MIN_MIXES : mixes, none ? null : sel.toString());
    }
    @FXML private Label indexRangeLabel;
    @FXML private Button applyBtn;
    @FXML private Button cancelBtn;

    private WalletForm walletForm;
    private MixConfig workingConfig;   // copy of the real config; applied on OK

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

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(walletForm.getWallet().getFullDisplayName() + " — Mix To");
        dialog.setDialogPane(pane);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(AshigaruGui.get().getMainStage());
        // This pane has no ButtonTypes (Apply/Cancel are themed buttons in the content),
        // so a dialog result is never produced. Apply is therefore performed directly in
        // onApply() rather than via a result converter that would never fire.
        dialog.setResultConverter(btn -> null);

        dialog.showAndWait();
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
                        // Exclude BIP47/PayNym contact accounts — they're counterparty receive
                        // chains named by their payment code (e.g. "…- PM8T…"), not spendable
                        // mix destinations. (Matches how the account tabs skip payment codes.)
                        && !w.isBip47()
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
                // The configured mix-to wallet isn't open, so it can't be resolved. Show None
                // and enable Apply straight away so the user can clear the stale target
                // (mix-to wallets must be open to avoid address reuse).
                mixToWalletCombo.getSelectionModel().select(DisplayWallet.NONE);
                workingConfig.setMixToWalletName(null);
                workingConfig.setMixToWalletFile(null);
                applyBtn.setDisable(false);
            }
        } else {
            mixToWalletCombo.getSelectionModel().select(DisplayWallet.NONE);
        }

        int initMixes = workingConfig.getMinMixes() != null
                ? workingConfig.getMinMixes() : Whirlpool.DEFAULT_MIXTO_MIN_MIXES;
        minMixesSpinner.getValueFactory().setValue(initMixes);

        // Listeners
        updateStrip();
        mixToWalletCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> updateStrip());
        minMixesSpinner.valueProperty().addListener((obs, old, nw) -> updateStrip());
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
        commitMinMixes();
        applyConfig();
        applyBtn.getScene().getWindow().hide();
    }

    @FXML
    private void onCancel() {
        cancelBtn.getScene().getWindow().hide();
    }

    /**
     * Commits a value typed into the (editable) min-mixes spinner but not yet entered,
     * so it is captured when Apply is clicked. Setting the factory value fires the
     * value listener that writes it through to {@link #workingConfig}.
     */
    private void commitMinMixes() {
        if (minMixesSpinner == null || !minMixesSpinner.isEditable()) {
            return;
        }
        String text = minMixesSpinner.getEditor().getText();
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            int val = Math.max(1, Math.min(10000, Integer.parseInt(text.trim())));
            minMixesSpinner.getValueFactory().setValue(val);
        } catch (NumberFormatException e) {
            // keep the last committed value
        }
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
