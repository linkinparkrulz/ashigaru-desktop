package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.MixConfig;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.whirlpool.Whirlpool;
import javafx.css.PseudoClass;
import com.sparrowwallet.sparrow.wallet.UtxoEntry;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Card-style list cell for a single UTXO on the Ashigaru wallet dashboard.
 * Replaces the old wide multi-column table: value + mix status stay visible at any
 * window width, with address / outpoint / date on secondary lines. Preserves the
 * existing selection semantics (the checkbox binds to {@code UtxoRow.selected}) and
 * inline label editing (writes through to {@code utxoEntry.labelProperty()}).
 */
public class AshigaruUtxoCell extends ListCell<AshigaruWalletController.UtxoRow> {
    // Static PNG rotated only while actively mixing avoids decoding a 1.1 MB animated GIF into RGBA frames in RAM.
    private static final Image WHIRLPOOL_ICON =
            new Image("/image/Ashigaru_Whirlpool_Logo_White.png", 18, 18, true, true);

    private final ObservableValue<Boolean> showMixInfo;

    private final CheckBox checkBox = new CheckBox();
    private final Label valueLabel = new Label();
    private final Label badgeText = new Label();
    private final ImageView badgeIcon = new ImageView(WHIRLPOOL_ICON);
    private final javafx.animation.RotateTransition badgeSpin = new javafx.animation.RotateTransition(Duration.seconds(2.5), badgeIcon);
    private final HBox badgeBox = new HBox(6, badgeIcon, badgeText);
    //The coin's mix count as dots: the number the wallet already tracks, drawn so a list of Postmix
    //coins reads at a glance. The dots turn green once the coin reaches the wallet's own minimum.
    private static final int PIPS = 4;
    private static final PseudoClass FILLED = PseudoClass.getPseudoClass("filled");
    private static final PseudoClass PRIVATE = PseudoClass.getPseudoClass("private");
    private final HBox pipBox = new HBox(4);
    private final Tooltip pipTip = new Tooltip();
    private final TextField labelField = new TextField();
    private final Label addressLabel = new Label();
    private final CopyButton addressCopyBtn = new CopyButton();
    private final Label metaLabel = new Label();
    private final CopyButton outputCopyBtn = new CopyButton();
    private final HBox addressLine = new HBox(6, addressLabel, addressCopyBtn);
    private final HBox metaLine = new HBox(6, metaLabel, outputCopyBtn);
    private final HBox root = new HBox(12);

    private BooleanProperty boundSelected;

    public AshigaruUtxoCell(ObservableValue<Boolean> showMixInfo) {
        this.showMixInfo = showMixInfo;

        badgeSpin.setByAngle(360);
        badgeSpin.setInterpolator(javafx.animation.Interpolator.LINEAR);
        badgeSpin.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if(newScene == null) {
                badgeSpin.stop();
            }
        });

        valueLabel.getStyleClass().add("card-value");
        badgeText.getStyleClass().add("mix-badge-text");
        badgeBox.getStyleClass().add("mix-badge");
        badgeBox.setAlignment(Pos.CENTER_RIGHT);
        pipBox.getStyleClass().add("mix-pips");
        pipBox.setAlignment(Pos.CENTER);
        pipTip.setShowDelay(Duration.millis(150));
        pipTip.setShowDuration(Duration.INDEFINITE);
        pipTip.setWrapText(true);
        pipTip.setMaxWidth(320);
        Tooltip.install(pipBox, pipTip);
        for(int i = 0; i < PIPS; i++) {
            Region pip = new Region();
            pip.getStyleClass().add("mix-pip");
            pipBox.getChildren().add(pip);
        }

        labelField.getStyleClass().add("card-label-field");
        labelField.setPromptText("Add label…");
        labelField.setOnAction(e -> commitLabel());
        labelField.focusedProperty().addListener((o, was, is) -> { if (was && !is) commitLabel(); });

        addressLabel.getStyleClass().add("card-secondary");
        addressLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(addressLabel, Priority.ALWAYS);
        metaLabel.getStyleClass().add("card-faint");

        addressLine.setAlignment(Pos.CENTER_LEFT);
        metaLine.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topLine = new HBox(10, valueLabel, spacer, pipBox, badgeBox);
        topLine.setAlignment(Pos.CENTER_LEFT);

        VBox center = new VBox(4, topLine, labelField, addressLine, metaLine);
        center.setFillWidth(true);
        HBox.setHgrow(center, Priority.ALWAYS);

        VBox checkWrap = new VBox(checkBox);
        checkWrap.setAlignment(Pos.TOP_LEFT);

        root.setAlignment(Pos.TOP_LEFT);
        root.getChildren().addAll(checkWrap, center);
        root.setMaxWidth(Double.MAX_VALUE);

        // Re-render the badge when the active account's mix-relevance changes
        showMixInfo.addListener((o, a, b) -> updateBadge());
    }

    private void commitLabel() {
        AshigaruWalletController.UtxoRow row = getItem();
        if (row != null && row.utxoEntry != null) {
            row.utxoEntry.labelProperty().set(labelField.getText() == null ? "" : labelField.getText());
        }
    }

    @Override
    protected void updateItem(AshigaruWalletController.UtxoRow row, boolean empty) {
        super.updateItem(row, empty);

        if (boundSelected != null) {
            checkBox.selectedProperty().unbindBidirectional(boundSelected);
            boundSelected = null;
        }

        if (empty || row == null) {
            badgeSpin.stop();
            setText(null);
            setGraphic(null);
            return;
        }

        checkBox.selectedProperty().bindBidirectional(row.selected);
        boundSelected = row.selected;

        valueLabel.setText(row.value);

        String lbl = row.utxoEntry != null && row.utxoEntry.getLabel() != null
                ? row.utxoEntry.getLabel() : row.label;
        if (!labelField.isFocused()) {
            labelField.setText(lbl != null ? lbl : "");
        }

        boolean hasAddress = row.address != null && !row.address.isEmpty();
        addressLabel.setText(hasAddress ? row.address : "(no address)");
        addressCopyBtn.setVisible(hasAddress);
        addressCopyBtn.setManaged(hasAddress);
        if (hasAddress) {
            addressCopyBtn.setCopyText(row.address, "Copy address");
        }

        metaLabel.setText(row.date + "   ·   " + shorten(row.output));
        boolean hasOutput = row.output != null && !row.output.isEmpty();
        outputCopyBtn.setVisible(hasOutput);
        outputCopyBtn.setManaged(hasOutput);
        if (hasOutput) {
            outputCopyBtn.setCopyText(row.output, "Copy output (TxID:index)");
        }

        updateBadge();
        setText(null);
        setGraphic(root);
    }

    private void updateBadge() {
        AshigaruWalletController.UtxoRow row = getItem();
        boolean show = Boolean.TRUE.equals(showMixInfo.getValue());
        pipBox.setVisible(false);
        pipBox.setManaged(false);
        if (row == null || !show || row.utxoEntry == null) {
            badgeSpin.stop();
            badgeBox.setVisible(false);
            badgeBox.setManaged(false);
            return;
        }
        badgeBox.setVisible(true);
        badgeBox.setManaged(true);

        //Change left in Postmix after a spend is not a mixed coin. The wallet's default reports
        //"1 mix" for anything in Postmix, which is wrong for it: show what it is instead.
        Wallet account = row.utxoEntry.getWallet();
        if (account.getStandardAccountType() == StandardAccount.WHIRLPOOL_POSTMIX
                && !WalletPrivacy.isMixedCoin(account, row.utxoEntry.getNode())) {
            badgeSpin.stop();
            badgeBox.getStyleClass().removeAll("active", "done");
            badgeBox.getStyleClass().add("change");
            badgeIcon.setVisible(false);
            badgeIcon.setManaged(false);
            badgeText.setText("Change \u00b7 not mixed");
            return;
        }
        badgeBox.getStyleClass().remove("change");

        UtxoEntry.MixStatus mixStatus = row.utxoEntry.getMixStatus();
        boolean mixing = mixStatus != null
                && (mixStatus.getMixProgress() != null || mixStatus.getNextMixUtxo() != null);

        badgeBox.getStyleClass().removeAll("active", "done");
        if (mixStatus != null) {
            int done = mixStatus.getMixesDone();
            boolean isPrivate = done >= minMixes(row);
            for(int i = 0; i < PIPS; i++) {
                pipBox.getChildren().get(i).pseudoClassStateChanged(FILLED, i < done);
            }
            pipBox.pseudoClassStateChanged(PRIVATE, isPrivate);
            pipTip.setText(pipExplainer(done, minMixes(row)));
            pipBox.setVisible(true);
            pipBox.setManaged(true);
        }
        if (mixing) {
            badgeIcon.setVisible(true);
            badgeIcon.setManaged(true);
            if(badgeSpin.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                badgeSpin.play();
            }
            String stage = AshigaruWalletController.getMixStage(mixStatus);
            badgeText.setText(stage.isEmpty() ? "Mixing" : stage);
            badgeBox.getStyleClass().add("active");
        } else {
            badgeSpin.stop();
            badgeIcon.setVisible(false);
            badgeIcon.setManaged(false);
            if (mixStatus != null) {
                int mixes = mixStatus.getMixesDone();
                badgeText.setText(mixes + (mixes == 1 ? " mix" : " mixes"));
                badgeBox.getStyleClass().add("done");
            } else {
                badgeText.setText("Unmixed");
            }
        }
    }

    /**
     * What the dots mean, on hover. Each dot is one completed mix; the coin's own count is said first, then
     * the whole scale, so a reader who has never seen the Whirlpool tab can still read the row.
     */
    static String pipExplainer(int done, int minMixes) {
        String own = done == 0 ? "This coin has not been mixed yet."
                : "This coin has completed " + done + (done == 1 ? " mix." : " mixes.");
        String reached = done >= minMixes ? " It has reached your minimum of " + minMixes + ", so its dots are green."
                : " Your minimum is " + minMixes + "; the dots turn green when a coin gets there.";
        return own + reached + "\n\n"
                + "Each dot is one completed mix:\n"
                + "\u25CB\u25CB\u25CB\u25CB  none yet, waiting in Premix\n"
                + "\u25CF\u25CB\u25CB\u25CB  1 mix\n"
                + "\u25CF\u25CF\u25CB\u25CB  2 mixes\n"
                + "\u25CF\u25CF\u25CF\u25CB  3 mixes\n"
                + "\u25CF\u25CF\u25CF\u25CF  4 or more\n\n"
                + "More mixes means more coins it could be mistaken for. Remixing is free while a coin stays in Postmix.";
    }

    /** The wallet's own "private enough" threshold: the Mix To minimum, or Whirlpool's default. */
    private static int minMixes(AshigaruWalletController.UtxoRow row) {
        try {
            Wallet wallet = row.utxoEntry.getWallet();
            Wallet master = wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
            MixConfig mixConfig = master.getMixConfig();
            if(mixConfig != null && mixConfig.getMinMixes() != null) {
                return mixConfig.getMinMixes();
            }
        } catch(Exception e) {
            //fall through to the default
        }
        return Whirlpool.DEFAULT_MIXTO_MIN_MIXES;
    }

    private static String shorten(String outpoint) {
        if (outpoint == null) {
            return "";
        }
        int colon = outpoint.lastIndexOf(':');
        String hash = colon > 0 ? outpoint.substring(0, colon) : outpoint;
        String idx = colon > 0 ? outpoint.substring(colon) : "";
        if (hash.length() > 16) {
            hash = hash.substring(0, 8) + "…" + hash.substring(hash.length() - 4);
        }
        return hash + idx;
    }
}
