package com.sparrowwallet.sparrow.gui;

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
    private static final Image WHIRLPOOL_GIF =
            new Image("/image/Ashigaru_Whirlpool_Logo_GIF_White_On_Transparent.gif", 18, 18, true, true);

    private final ObservableValue<Boolean> showMixInfo;

    private final CheckBox checkBox = new CheckBox();
    private final Label valueLabel = new Label();
    private final Label badgeText = new Label();
    private final ImageView badgeIcon = new ImageView(WHIRLPOOL_GIF);
    private final HBox badgeBox = new HBox(6, badgeIcon, badgeText);
    //The coin's mix count as dots: the number the wallet already tracks, drawn so a list of Postmix
    //coins reads at a glance. The dots turn green once the coin reaches the wallet's own minimum.
    private static final int PIPS = 4;
    private static final PseudoClass FILLED = PseudoClass.getPseudoClass("filled");
    private static final PseudoClass PRIVATE = PseudoClass.getPseudoClass("private");
    private final HBox pipBox = new HBox(4);
    private final TextField labelField = new TextField();
    private final Label addressLabel = new Label();
    private final Label metaLabel = new Label();
    private final HBox addressLine = new HBox(6);
    private final HBox metaLine = new HBox(6);
    private final HBox root = new HBox(12);

    private BooleanProperty boundSelected;

    public AshigaruUtxoCell(ObservableValue<Boolean> showMixInfo) {
        this.showMixInfo = showMixInfo;

        valueLabel.getStyleClass().add("card-value");
        badgeText.getStyleClass().add("mix-badge-text");
        badgeBox.getStyleClass().add("mix-badge");
        badgeBox.setAlignment(Pos.CENTER_RIGHT);
        pipBox.getStyleClass().add("mix-pips");
        pipBox.setAlignment(Pos.CENTER);
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

        addressLine.getChildren().setAll(addressLabel);
        boolean hasAddress = row.address != null && !row.address.isEmpty();
        addressLabel.setText(hasAddress ? row.address : "(no address)");
        if (hasAddress) {
            addressLine.getChildren().add(AshigaruWalletController.makeCopyButton(row.address));
        }

        metaLabel.setText(row.date + "   ·   " + shorten(row.output));
        metaLine.getChildren().setAll(metaLabel);
        if (row.output != null && !row.output.isEmpty()) {
            metaLine.getChildren().add(AshigaruWalletController.makeCopyButton(row.output));
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
            badgeBox.setVisible(false);
            badgeBox.setManaged(false);
            return;
        }
        badgeBox.setVisible(true);
        badgeBox.setManaged(true);

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
            pipBox.setVisible(true);
            pipBox.setManaged(true);
        }
        if (mixing) {
            badgeIcon.setVisible(true);
            badgeIcon.setManaged(true);
            String stage = AshigaruWalletController.getMixStage(mixStatus);
            badgeText.setText(stage.isEmpty() ? "Mixing" : stage);
            badgeBox.getStyleClass().add("active");
        } else {
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
